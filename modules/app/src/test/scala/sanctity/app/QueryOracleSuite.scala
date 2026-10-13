package sanctity.app

import munit.FunSuite
import sanctity.kernel.*

final class QueryOracleSuite extends FunSuite:
  test("seeded source corpus and oracle are deterministic and preserve adversarial witnesses") {
    Vector(7L, 17L, 29L).foreach { seed =>
      val corpus = QueryOracle.generate(seed)
      assertEquals(corpus, QueryOracle.generate(seed))
      assertEquals(QueryOracle.queries(seed), QueryOracle.queries(seed))
      val same = ScientificQuery(ParticipantPredicate.HasSession(Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Suffix("T1w"))))
      val subjects = corpus.expected(same).map(_.subject).toSet
      assert(!subjects("01")) // Its images exist in different sessions.
      assert(!subjects("03") && !subjects("04")) // Shared session labels cannot cross subjects.
      assert(subjects("1") && subjects("02")) // Lexical subject and the session-less group remain separate.
      val age = corpus.expected(ScientificQuery(ParticipantPredicate.FieldAtLeast("age", BigDecimal(18)))).map(_.subject).toSet
      assert(age("1") && age("03"))
      assert(!age("01") && !age("02") && !age("04"))
      assertEquals(corpus.expected(ScientificQuery(artifacts = ArtifactPredicate.MetadataEquals("EchoTime", "0.01"))), Vector.empty)
      QueryOracle.queries(seed).foreach(c => assert(c.query.validate.isRight, s"seed=$seed case=${c.name}"))
    }
  }
