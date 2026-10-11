package sanctity.kernel

/** Portable evidence of the selected semantics, independent of authored BIDSVersion.
  * Ordered descriptor tokens preserve registry precedence. Rule maps are canonicalized.
  */
final class SemanticContext private (
    val name: String, val specificationVersion: Option[String], val coverage: String,
    val registryTokens: Vector[String], val rules: Map[String, String]
):
  val registryId: Digest = Digest.framed("sanctity.registry.v1", registryTokens)
  val tokens: Vector[String] = Vector(name, specificationVersion.getOrElse(""), coverage,
    registryTokens.size.toString) ++ registryTokens ++ Vector(rules.size.toString) ++
    rules.toVector.sortBy(_._1).flatMap((k, v) => Vector(k, v))
  val id: Digest = Digest.framed("sanctity.semantic-context.v1", tokens)
  override def equals(other: Any): Boolean = other match
    case c: SemanticContext => tokens == c.tokens
    case _ => false
  override def hashCode(): Int = tokens.hashCode()

object SemanticContext:
  def from(name: String, specificationVersion: Option[String], coverage: String,
      registryTokens: Vector[String], rules: Map[String, String]): Either[Problem, SemanticContext] =
    def label(s: String): Boolean = s.nonEmpty && s.length <= 200 && !s.exists(_.isControl)
    val valid = label(name) && coverage.nonEmpty && coverage.length <= 4096 &&
      specificationVersion.forall(v => v.length <= 32 && v.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) &&
      registryTokens.nonEmpty && registryTokens.size <= 100000 && registryTokens.forall(_.length <= 4096) &&
      registryTokens.foldLeft(0L)(_ + _.length) <= 1024 * 1024 && rules.nonEmpty && rules.size <= 128 &&
      rules.forall((k, v) => label(k) && label(v))
    Either.cond(valid, new SemanticContext(name, specificationVersion, coverage, registryTokens, rules),
      Problem.InvalidManifest("invalid or oversized semantic context"))
