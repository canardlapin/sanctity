package sanctity.kernel

class SemanticContextSuite extends munit.FunSuite:
  private def right[A](e: Either[Problem, A]): A = e.fold(p => fail(p.message), identity)
  private val registry = Vector("registry-v1", "first", "second")
  private val rules = Map("metadata" -> "v1", "query" -> "v1")
  private def context(name: String = "pilot", version: Option[String] = Some("1.10.0"),
      coverage: String = "partial", tokens: Vector[String] = registry, versions: Map[String, String] = rules): SemanticContext =
    right(SemanticContext.from(name, version, coverage, tokens, versions))

  test("identity includes rules, coverage, target version and ordered descriptor with canonical map ordering") {
    val initial = context()
    val variants = Vector(context(name = "other"), context(version = None), context(coverage = "other"),
      context(tokens = registry.reverse), context(versions = rules.updated("metadata", "v2")))
    variants.foreach(c => assertNotEquals(c.id, initial.id))
    assertEquals(context(versions = rules.toVector.reverse.toMap).id, initial.id)
    assertEquals(context(versions = rules.updated("query", "v2")).registryId, initial.registryId)
    assertNotEquals(context(tokens = Vector("ab", "c")).registryId, context(tokens = Vector("a", "bc")).registryId)
  }

  test("empty, invalid and oversized semantic descriptors fail checked construction") {
    assert(SemanticContext.from("p", None, "partial", Vector.empty, rules).isLeft)
    assert(SemanticContext.from("p", Some("latest"), "partial", registry, rules).isLeft)
    assert(SemanticContext.from("p", None, "partial", registry, Map.empty).isLeft)
    assert(SemanticContext.from("p", None, "partial", Vector.fill(300)("a" * 4096), rules).isLeft)
  }

  test("unchanged interpreted facts still bind context and authored BIDS version separately") {
    val manifest = right(Manifest.from(Vector.empty))
    val profile = SemanticProfile("test", "bids", "repository", "partial", Some(context()))
    val first = right(Interpretation.from(manifest.id, profile, Vector.empty, declaredBidsVersion = Some("1.0.0")))
    val contextChange = right(Interpretation.from(manifest.id, profile.copy(context = Some(context(versions = rules.updated("metadata", "v2")))),
      Vector.empty, declaredBidsVersion = Some("1.0.0")))
    val declarationChange = right(Interpretation.from(manifest.id, profile, Vector.empty, declaredBidsVersion = Some("1.1.0")))
    assertNotEquals(first.id, contextChange.id)
    assertNotEquals(first.id, declarationChange.id)
    assertEquals(first.content, contextChange.content)
    assertEquals(first.declaredBidsVersion, Some("1.0.0"))
    assertEquals(first.profile.context.flatMap(_.specificationVersion), Some("1.10.0"))
  }
