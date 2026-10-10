package sanctity.kernel

final case class CheckInput(role: String, path: RelPath, blob: BlobRef)

/** A completed, versioned content check over exact managed inputs. Facts are observations,
  * never edits to authored BIDS metadata. Findings participate directly in admission.
  */
final class ContentCheck private (
    val rule: String, val target: RelPath, val inputs: Vector[CheckInput],
    val facts: Map[String, MetadataValue], val findings: Vector[Finding]
):
  private[kernel] def tokens: Vector[String] =
    Vector(rule, target.value, inputs.size.toString) ++
      inputs.flatMap(i => Vector(i.role, i.path.value, i.blob.digest.value, i.blob.size.toString)) ++
      Vector(facts.size.toString) ++ facts.toVector.sortBy(_._1).flatMap((k, v) => Vector(k) ++ v.tokens) ++
      Vector(findings.size.toString) ++ findings.flatMap(f =>
        Vector(f.code, f.severity.toString, f.path.fold("")(_.value), f.message))

object ContentCheck:
  def from(rule: String, target: RelPath, inputs: Vector[CheckInput], facts: Map[String, MetadataValue],
      findings: Vector[Finding]): Either[Problem, ContentCheck] =
    val paths = inputs.map(_.path).toSet
    val valid = rule.nonEmpty && rule.length <= 200 && !rule.exists(_.isControl) &&
      inputs.nonEmpty && inputs.size <= 100 && inputs.map(_.role).distinct.size == inputs.size &&
      inputs.forall(i => i.role.matches("[a-z][a-z0-9.-]{0,99}")) && paths(target) &&
      facts.size <= 100 && facts.keys.forall(_.matches("[A-Za-z][A-Za-z0-9.-]{0,99}")) &&
      findings.size <= 1000 && findings.forall(_.path.forall(paths))
    Either.cond(valid, new ContentCheck(rule, target, inputs.sortBy(_.role), facts,
      findings.sortBy(f => (f.path.fold("")(_.value), f.code, f.message, f.severity.ordinal))),
      Problem.InvalidManifest("invalid content-check evidence"))
