package sanctity.processing

import bids4s.NiftiHeaderFacts
import sanctity.kernel.*

/** Coded voxel-centre coordinates, in millimetres. No preferred transform or registration is inferred. */
final case class CodedTransform(code: Int, rows: Vector[Vector[Double]]):
  def point(p: Vector[Double]): Vector[Double] = rows.map(r => r.take(3).zip(p).map(_ * _).sum + r(3))

final case class QualifiedGeometry(dimensions: Vector[Long], qform: Option[CodedTransform], sform: Option[CodedTransform]):
  def facts: Map[String, MetadataValue] =
    def form(name: String, value: Option[CodedTransform]): Map[String, MetadataValue] =
      Map(name + "Code" -> MetadataValue.Number(value.fold(0)(_.code))) ++ value.map { t =>
        name + "Millimetres" -> MetadataValue.Array(t.rows.map(r => MetadataValue.Array(r.map(NiftiPayload.number))))
      }
    Map("qualified" -> MetadataValue.Flag(true),
      "cornerToleranceMm" -> NiftiPayload.number(NiftiGeometry.CornerToleranceMm)) ++ form("qform", qform) ++ form("sform", sform)

object NiftiGeometry:
  val Rule = "sanctity.nifti-geometry.v1"
  val FamilyRule = "sanctity.echo-geometry.v1"
  val CornerToleranceMm = 0.001
  private val UnitScale = Map(1 -> 1000.0, 2 -> 1.0, 3 -> 0.001)

  private def determinant(r: Vector[Vector[Double]]): Double =
    r(0)(0) * (r(1)(1) * r(2)(2) - r(1)(2) * r(2)(1)) -
      r(0)(1) * (r(1)(0) * r(2)(2) - r(1)(2) * r(2)(0)) +
      r(0)(2) * (r(1)(0) * r(2)(1) - r(1)(1) * r(2)(0))

  private def normalizedDeterminant(rows: Vector[Vector[Double]]): Double =
    val lengths = Vector.tabulate(3)(j => math.hypot(math.hypot(rows(0)(j), rows(1)(j)), rows(2)(j)))
    determinant(rows.map(r => r.take(3).zip(lengths).map(_ / _)))

  private def corners(dimensions: Vector[Long]): Vector[Vector[Double]] =
    (for x <- Vector(0.0, dimensions(0).toDouble - 1); y <- Vector(0.0, dimensions(1).toDouble - 1)
      z <- Vector(0.0, dimensions(2).toDouble - 1) yield Vector(x, y, z)).distinct

  /** Known codes 0–5; at least one coded form; finite, nonsingular forms with consistent handedness.
    * qfac=0 follows NIfTI's +1 fallback. Inactive transform fields are not interpreted.
    */
  def inspect(h: NiftiHeaderFacts): Either[Problem, QualifiedGeometry] =
    def fail(message: String) = Left(Problem.Unsupported(message))
    val spacing = h.pixelDimensions.slice(1, 4)
    if h.dimensions.size < 3 || !UnitScale.contains(h.unitsCode & 7) then
      fail("coded geometry requires three spatial dimensions and known metre/mm/micron units")
    else if spacing.exists(n => !n.isFinite || n <= 0) then fail("spatial spacing must be finite and positive")
    else if !Vector(h.qformCode, h.sformCode).forall(n => n >= 0 && n <= 5) then fail("unsupported qform/sform coordinate code")
    else if h.qformCode == 0 && h.sformCode == 0 then fail("at least one coded qform or sform is required")
    else
      val scale = UnitScale(h.unitsCode & 7)
      def validate(code: Int, rows: Vector[Vector[Double]]): Either[Problem, CodedTransform] =
        val mm = rows.map(_.map(_ * scale))
        val det = normalizedDeterminant(mm)
        if mm.flatten.exists(n => !n.isFinite || math.abs(n) > 1e12) ||
            !det.isFinite || math.abs(det) < 1e-10 then fail("coded transform must be finite and nonsingular within geometry bounds")
        else
          val form = CodedTransform(code, mm)
          if corners(h.dimensions).flatMap(form.point).exists(n => !n.isFinite || math.abs(n) > 1e12) then
            fail("voxel-centre coordinates exceed the 1e12 mm bound")
          else Right(form)
      val q: Either[Problem, Option[CodedTransform]] =
        if h.qformCode == 0 then Right(None)
        else if h.quaternion.exists(n => !n.isFinite) || h.qoffset.exists(n => !n.isFinite) ||
            !Set(-1.0, 0.0, 1.0)(h.pixelDimensions.head) then fail("coded qform has nonfinite components or invalid qfac")
        else
          val norm = h.quaternion.map(n => n * n).sum
          val epsilon = 3 * (if h.version == 1 then math.ulp(1.0f).toDouble else math.ulp(1.0))
          if !norm.isFinite || norm > 1 + epsilon then fail("quaternion squared norm exceeds one beyond header precision")
          else
            val quaternion = if norm > 1 then h.quaternion.map(_ / math.sqrt(norm)) else h.quaternion
            val b = quaternion(0)
            val c = quaternion(1)
            val d = quaternion(2)
            val a = math.sqrt(math.max(0, 1 - norm))
            val rotation = Vector(Vector(a*a+b*b-c*c-d*d, 2*(b*c-a*d), 2*(b*d+a*c)),
              Vector(2*(b*c+a*d), a*a+c*c-b*b-d*d, 2*(c*d-a*b)),
              Vector(2*(b*d-a*c), 2*(c*d+a*b), a*a+d*d-c*c-b*b))
            val sizes = spacing.updated(2, spacing(2) * (if h.pixelDimensions.head < 0 then -1 else 1))
            validate(h.qformCode, rotation.zip(h.qoffset).map((r, offset) => r.zip(sizes).map(_ * _) :+ offset)).map(Some(_))
      val s = if h.sformCode == 0 then Right(None) else validate(h.sformCode, h.sformRows).map(Some(_))
      for
        qform <- q
        sform <- s
        _ <- if qform.zip(sform).exists((q, s) => math.signum(normalizedDeterminant(q.rows)) != math.signum(normalizedDeterminant(s.rows))) then
          fail("coded qform and sform disagree on handedness") else Right(())
      yield QualifiedGeometry(h.dimensions.take(3), qform, sform)

  /** Compare like-coded forms separately at all eight voxel-centre corners (absolute mm tolerance). */
  def agreement(members: Vector[QualifiedGeometry]): Either[Problem, Double] =
    if members.size < 2 then Left(Problem.Unsupported("geometry agreement requires a recorded echo family"))
    else
      val first = members.head
      if members.exists(g => g.dimensions != first.dimensions || g.qform.map(_.code) != first.qform.map(_.code) ||
          g.sform.map(_.code) != first.sform.map(_.code)) then
        Left(Problem.Unsupported("echoes require the same spatial dimensions and separately coded qform/sform systems"))
      else
        val distances = members.combinations(2).toVector.flatMap { pair =>
          (pair(0).qform.zip(pair(1).qform).toVector ++ pair(0).sform.zip(pair(1).sform)).flatMap { (a, b) =>
            corners(first.dimensions).map(p => math.sqrt(a.point(p).zip(b.point(p)).map((x, y) => (x-y)*(x-y)).sum))
          }
        }
        val maximum = distances.maxOption.getOrElse(0.0)
        Either.cond(maximum.isFinite && maximum <= CornerToleranceMm, maximum,
          Problem.Unsupported(s"echo coded transforms disagree at voxel-centre corners: $maximum mm exceeds $CornerToleranceMm mm"))

  def families(interpretation: Interpretation): Vector[Vector[RelPath]] =
    interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == "bids.multi-echo-bold")
      .map(r => (r.to :+ r.from).distinct.sortBy(_.value)).distinct.sortBy(_.head.value)
