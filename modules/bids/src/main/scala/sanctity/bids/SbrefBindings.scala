package sanctity.bids

import bids4s.{DwiIssue, NiftiHeaderFacts}
import cats.syntax.all.*
import sanctity.kernel.*

/** Repository policy: exact functional acquisition keys, never scan proximity or entity inheritance. */
object SbrefBindings:
  val Version = "sanctity-functional-sbref-v1"
  val Rule = "sanctity.sbref-content.v1"
  val Kind = "bids.bold-sbref"
  private val excluded = Set("part", "chunk", "recording", "sample", "flip", "inv", "mt")
  def normalized(entities: Map[String, String]): Map[String, String] = entities.map { (k, v) =>
    val stripped = v.dropWhile(_ == '0')
    k -> (if Set("run", "echo")(k) then (if stripped.isEmpty then "0" else stripped) else v)
  }
  def key(f: ArtifactFacts): (Option[RelPath], Map[String, String]) = f.path.parent -> (normalized(f.entities) - "echo")
  def functional(f: ArtifactFacts): Boolean = Set("nii", "nii.gz")(f.extension) &&
    f.path.parent.exists(_.fileName == "func") && !f.entities.keySet.exists(excluded)

  def resolve(facts: Vector[ArtifactFacts]): (Vector[ScientificRelationship], Vector[Finding]) =
    val findings = Vector.newBuilder[Finding]
    val refs = facts.filter(f => f.suffix == "sbref" && functional(f)).groupBy(key)
    val links = facts.filter(f => f.suffix == "bold" && functional(f)).groupBy(key).toVector.sortBy(_._2.head.path.value).flatMap { (_, images) =>
      val members = images.sortBy(_.path.value)
      val applicable = refs.getOrElse(key(members.head), Vector.empty).sortBy(_.path.value)
      val shared = applicable.filterNot(_.entities.contains("echo"))
      val indexed = applicable.filter(_.entities.contains("echo"))
      val echoes = members.map(f => normalized(f.entities).get("echo"))
      val refEchoes = indexed.map(f => normalized(f.entities).get("echo"))
      val sharedValid = shared.size == 1 && indexed.isEmpty
      val indexedValid = shared.isEmpty && indexed.nonEmpty && echoes.forall(_.nonEmpty) &&
        echoes.distinct.size == echoes.size && refEchoes.distinct.size == refEchoes.size && refEchoes.toSet == echoes.toSet
      if applicable.isEmpty then Vector.empty
      else if !sharedValid && !indexedValid then
        findings += Finding("SbrefAmbiguousOrIncomplete", Severity.Error, Some(members.head.path),
          "Use exactly one unindexed SBRef or one SBRef per echo for the exact functional acquisition; candidates: " + applicable.map(_.path.value).mkString(", "))
        Vector.empty
      else members.map { f =>
        val ref = if sharedValid then shared.head else indexed.find(r => normalized(r.entities).get("echo") == normalized(f.entities).get("echo")).get
        ScientificRelationship(f.path, Kind, Vector(ref.path), None, Vector(RelationshipEvidence.CompanionSelection(
          f.path, ref.path, Version, None, normalized(ref.entities), Vector(ref.path))))
      }
    }
    links -> findings.result()

  def assess(manifest: Manifest, interpretation: Interpretation,
      headers: Map[RelPath, Either[DwiIssue, NiftiHeaderFacts]]): Either[Problem, Vector[ContentCheck]] =
    val files = manifest.files.map(f => f.path -> f).toMap
    val links = interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == Kind)
    interpretation.facts.filter(f => f.suffix == "sbref" && functional(f)).traverse { f =>
      val consumers = links.filter(_.to.contains(f.path)).map(_.from).distinct.sorted(Ordering.by[RelPath, String](_.value))
      val paths = (consumers :+ f.path).distinct
      val metadata = paths.flatMap(p => interpretation.byPath(p).sources).distinct.sortBy(_.value)
      val fields = interpretation.byPath(f.path).fields
      val findings = Vector.newBuilder[Finding]
      def check(ok: Boolean, code: String, message: String): Unit =
        if !ok then findings += Finding(code, Severity.Error, Some(f.path), message)
      Vector("EchoTime", "TotalReadoutTime").foreach { field =>
        check(fields.get(field).forall(_.value match
          case MetadataValue.Number(n) => n > 0
          case _ => false), "SbrefMetadata", s"When authored, $field must be a positive number in seconds")
      }
      check(fields.get("PhaseEncodingDirection").forall(_.value match
        case MetadataValue.Text(s) => Set("i", "i-", "j", "j-", "k", "k-")(s)
        case _ => false), "SbrefMetadata", "When authored, PhaseEncodingDirection must use the BIDS voxel-axis vocabulary")
      check(headers.get(f.path).flatMap(_.toOption).exists(h => h.dimensions.size == 3 ||
        (h.dimensions.size == 4 && h.dimensions(3) == 1)), "SbrefDimensions", "Functional reference policy requires a 3D image or a singleton fourth dimension")
      consumers.foreach { p =>
        val target = interpretation.byPath(p).fields
        check(fields.get("TaskName").forall(v => target.get("TaskName").exists(_.value == v.value)),
          "SbrefTask", "When authored, SBRef TaskName must match its BOLD acquisition")
        if f.entities.contains("echo") then
          check(fields.get("EchoTime").exists(_.value match
            case MetadataValue.Number(n) => n > 0 && target.get("EchoTime").exists(_.value match
              case MetadataValue.Number(t) => (n - t).abs <= BigDecimal("0.000000001")
              case _ => false)
            case _ => false), "SbrefEchoTime", "Indexed SBRef needs positive EchoTime agreeing with its BOLD echo within 1e-9 s")
      }
      if paths.size + metadata.size > 100 then Left(Problem.LimitExceeded("SBRef evidence exceeds 100 image/metadata inputs"))
      else ContentCheck.from(Rule, f.path,
        paths.zipWithIndex.map((p, n) => CheckInput(s"image-$n", p, files(p).blob)) ++
          metadata.zipWithIndex.map((p, n) => CheckInput(s"metadata-$n", p, files(p).blob)),
        Map("singleVolume" -> MetadataValue.Flag(headers.get(f.path).flatMap(_.toOption).exists(h => h.dimensions.size == 3 || (h.dimensions.size == 4 && h.dimensions(3) == 1))),
          "referenceMetadata" -> MetadataValue.Record(fields.filter((k, _) => Set("TaskName", "EchoTime", "RepetitionTime", "PhaseEncodingDirection", "TotalReadoutTime")(k))
            .map((k, v) => k -> MetadataValue.Record(Map("value" -> v.value, "origin" -> MetadataValue.Text(v.origin.value))))),
          "consumers" -> MetadataValue.Array(consumers.map(p => MetadataValue.Text(p.value)))), findings.result())
    }
