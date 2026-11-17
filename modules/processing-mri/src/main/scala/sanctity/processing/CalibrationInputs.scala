package sanctity.processing

import cats.syntax.all.*
import sanctity.bids.SbrefBindings
import sanctity.kernel.*

/** Deliberately narrow recipe policy, separate from BIDS conformance and distortion correction. */
object CalibrationInputs:
  val ReferenceGeometry = "sanctity.reference-geometry.v1"
  val FieldmapGeometry = "sanctity.epi-calibration-geometry.v1"
  private val fieldmapKinds = Set("bids.b0-field-source", "bids.intended-for")

  def references(i: Interpretation, p: RelPath): Vector[RelPath] =
    i.relationships.toVector.flatMap(_.relationships).filter(r => r.from == p && r.kind == SbrefBindings.Kind)
      .flatMap(_.to).distinct.sortBy(_.value)

  def fieldmaps(i: Interpretation, p: RelPath): Vector[Vector[RelPath]] =
    val declarations = i.relationships.toVector.flatMap(_.relationships).filter(r => r.from == p && fieldmapKinds(r.kind))
      .map(r => r.kind -> i.relationshipClosure(r.to).map(_.artifacts).getOrElse(r.to).distinct.sortBy(_.value))
    val named = declarations.collect { case ("bids.b0-field-source", group) => group }
    // IntendedFor can redundantly name members of an explicit B0 group. Separate
    // B0 identifiers remain distinct choices, even when their source sets overlap.
    // Independent singleton IntendedFor declarations do not establish a pair.
    val unnamed = declarations.collect { case ("bids.intended-for", group)
        if !named.exists(g => group.toSet.subsetOf(g.toSet)) => group }.distinct
    (named ++ unnamed).sortBy(_.map(_.value).mkString("\n"))

  def geometryGroups(i: Interpretation, p: RelPath): Vector[(String, Vector[RelPath])] =
    Vector(ReferenceGeometry -> references(i, p), FieldmapGeometry -> fieldmaps(i, p).flatten.distinct.sorted(Ordering.by[RelPath, String](_.value)))
      .filter(_._2.nonEmpty).map((rule, members) => rule -> (p +: members.filterNot(_ == p)))

  def inputs(m: Manifest, i: Interpretation, members: Vector[RelPath]): Vector[CheckInput] =
    val files = m.files.map(f => f.path -> f).toMap
    val docs = members.flatMap(p => i.byPath.get(p).toVector.flatMap(_.sources)).distinct.sortBy(_.value)
    (members.zipWithIndex.flatMap((p, n) => files.get(p).map(f => CheckInput(s"image-$n", p, f.blob))) ++
      docs.zipWithIndex.flatMap((p, n) => files.get(p).map(f => CheckInput(s"metadata-$n", p, f.blob)))).sortBy(_.role)

  def geometry(m: Manifest, i: Interpretation, observed: Map[RelPath, Either[Problem, PayloadObservation]]): Either[Problem, Vector[ContentCheck]] =
    i.facts.filter(_.suffix == "bold").traverse { f =>
      geometryGroups(i, f.path).traverse { (rule, members) =>
        val qualified = members.traverse(p => observed.getOrElse(p, Left(Problem.Unsupported("missing calibration payload")))
          .flatMap(o => NiftiGeometry.inspect(o.header))).flatMap(NiftiGeometry.agreement)
        ContentCheck.from(rule, f.path, inputs(m, i, members),
          Map("qualified" -> MetadataValue.Flag(qualified.isRight), "cornerToleranceMm" -> NiftiPayload.number(NiftiGeometry.CornerToleranceMm)) ++
            qualified.toOption.map(n => "maximumCornerDistanceMm" -> NiftiPayload.number(n)),
          qualified.left.toOption.map(e => Finding("CalibrationGeometry", Severity.Warning, Some(f.path),
            "Same-grid calibration policy: " + e.message)).toVector)
      }
    }.map(_.flatten)

  def requirements(m: Manifest, i: Interpretation, a: Assessment, roots: Vector[RelPath]): Vector[InputRequirement] =
    val facts = i.facts.map(f => f.path -> f).toMap
    val files = m.files.map(f => f.path -> f).toMap
    def number(p: RelPath, field: String): Option[BigDecimal] = i.byPath.get(p).flatMap(_.fields.get(field)).flatMap(_.value match
      case MetadataValue.Number(n) if n > 0 => Some(n)
      case _ => None)
    def direction(p: RelPath): Option[String] = i.byPath.get(p).flatMap(_.fields.get("PhaseEncodingDirection")).flatMap(_.value match
      case MetadataValue.Text(s) if Set("i", "i-", "j", "j-", "k", "k-")(s) => Some(s)
      case _ => None)
    roots.flatMap { p =>
      val ref = references(i, p)
      val ambiguousReference = a.findings.exists(f => f.code == "SbrefAmbiguousOrIncomplete" &&
        f.path.flatMap(facts.get).exists(b => facts.get(p).exists(t => SbrefBindings.key(b) == SbrefBindings.key(t))))
      val groups = fieldmaps(i, p)
      val epi = groups.flatten.distinct.sortBy(_.value)
      val companions = (ref ++ epi).distinct
      def req(code: String, ok: Boolean, failure: RequirementStatus, paths: Vector[RelPath], message: String) =
        InputRequirement(code, Some(p), if ok then RequirementStatus.Satisfied else failure, paths.distinct.sortBy(_.value), message)
      val sameSession = facts.get(p).exists(b => companions.forall(q => facts.get(q).exists(f =>
        f.participant == b.participant && f.session == b.session)))
      val pair = groups.size == 1 && epi.size == 2 && epi.forall(q => facts.get(q).exists(_.suffix == "epi"))
      val family = NiftiGeometry.families(i).find(_.contains(p)).getOrElse(Vector(p))
      val coherent = family.forall(q => fieldmaps(i, q) == groups)
      val directions = epi.flatMap(direction)
      val opposite = pair && directions.size == 2 && directions(0).head == directions(1).head && directions(0) != directions(1) &&
        direction(p).exists(d => d.head == directions.head.head)
      val values = Vector(
        req("reference", ref.size == 1 && !ambiguousReference, if ambiguousReference || ref.size > 1 then RequirementStatus.Ambiguous else RequirementStatus.Missing, ref,
          "Require one recorded functional SBRef per BOLD echo; supply an exact shared reference or complete indexed references"),
        req("calibration-group", pair, if groups.isEmpty then RequirementStatus.Missing else if groups.size > 1 then RequirementStatus.Ambiguous else RequirementStatus.Unsupported, epi,
          "Require one explicitly declared group of two EPI fieldmaps; independent singleton declarations do not establish a pair"),
        req("calibration-scope", sameSession, RequirementStatus.Unsupported, companions,
          "Reference and EPI pair must belong to the same participant and session"),
        req("calibration-family", coherent, RequirementStatus.Ambiguous, family,
          "Every BOLD echo in an acquisition must declare the same calibration group"),
        req("phase-encoding", opposite && ref.size == 1 && direction(ref.head).nonEmpty && direction(ref.head) == direction(p),
          RequirementStatus.Disallowed, p +: companions, "Author i/i-, j/j- or k/k-: EPI pair opposes on the BOLD axis; SBRef matches BOLD direction"),
        req("readout-time", (p +: companions).forall(q => number(q, "TotalReadoutTime").nonEmpty), RequirementStatus.Missing,
          p +: companions, "Each BOLD, SBRef and EPI source needs an authored positive TotalReadoutTime in seconds; values may differ"))
      val payload = companions.flatMap { q => Vector(NiftiPayload.Rule -> "complete", NiftiGeometry.Rule -> "qualified").map { (rule, flag) =>
        val bound = files.get(q).exists(f => a.checks.count(c => c.target == q && c.rule == rule && c.findings.isEmpty &&
          c.facts.get(flag).contains(MetadataValue.Flag(true)) && c.inputs == Vector(CheckInput("image", q, f.blob))) == 1)
        req("calibration-" + flag, bound, RequirementStatus.Missing, Vector(q), "Require exact complete companion payload and coded geometry evidence from payload profile v3")
      }}
      val geometry = geometryGroups(i, p).map { (rule, members) =>
        val expected = inputs(m, i, members)
        val bound = a.checks.count(c => c.target == p && c.rule == rule && c.inputs == expected && c.findings.isEmpty &&
          c.facts.get("qualified").contains(MetadataValue.Flag(true))) == 1
        req(rule, bound, RequirementStatus.Disallowed, members,
          "Require matching spatial dimensions, separately coded coordinate systems and all-pair corner agreement within 0.001 mm; this does not establish registration")
      }
      values ++ payload ++ geometry
    }
