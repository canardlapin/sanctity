package sanctity.app

import bids4s.BidsPath
import munit.FunSuite
import sanctity.bids.{BidsScopes, RepositoryBidsContext}
import sanctity.kernel.*
import java.util.UUID
import scala.io.Source

/** The datatype rules against the names that occur in the public BIDS example datasets. */
final class BidsExamplesNamesSuite extends FunSuite:
  private val described = Set("eeg", "ieeg", "meg", "nirs", "pet", "perf", "motion")
  private val paths: Vector[String] =
    val source = Source.fromInputStream(getClass.getResourceAsStream("/bids-examples/described-paths.txt"), "UTF-8")
    try source.getLines().filterNot(l => l.startsWith("#") || l.isBlank).toVector finally source.close()

  private def manifest(files: Vector[String]): Manifest =
    val directories = files.flatMap(f => f.split('/').dropRight(1).inits.filter(_.nonEmpty).map(_.mkString("/"))).distinct
    val entries = directories.map(d => Entry.Directory(RelPath.from(d).toOption.get)) ++ files.zipWithIndex.map { (f, i) =>
      val path = RelPath.from(f).toOption.get
      Entry.File(path, Id[ArtifactTag](new UUID(2, i.toLong)), BlobRef.from(Digest.bytes(f.getBytes("UTF-8")), 0).toOption.get)
    }
    Manifest.from(entries).fold(p => fail(p.message), identity)

  test("every example name in a described data type is accepted, or kept inside a recording directory") {
    val datasets = paths.groupBy(_.takeWhile(_ != '/')).view.mapValues(_.map(p => p.dropWhile(_ != '/').drop(1))).toMap
    val results = datasets.toVector.sortBy(_._1).map { (name, files) =>
      val partition = BidsScopes.partition(manifest(files))
      val read = partition.interpreted.filter(f => BidsScopes.datatypeFolder(f.path).exists(described))
      val refused = read.filter(f => RepositoryBidsContext.Repository.parsePath(BidsPath(f.path.value)).isLeft).map(f => s"$name/${f.path.value}")
      // Everything this listing holds is in a described folder, so the only files kept aside are recording members.
      assert(partition.preserved.forall(_.reason == BidsScopes.Reason.RecordingDirectory), name)
      (read.size - refused.size, partition.preserved.size, refused)
    }
    // A stray R history file in one dataset is not a BIDS name, and is rightly refused.
    assertEquals(results.flatMap(_._3), Vector("xeeg_hed_score/sub-eegArtifactTUH/ses-eeg01/eeg/.Rapp.history"))
    assertEquals(datasets.size, 39)
    assertEquals(results.map(_._1).sum + results.map(_._2).sum + 1, paths.size)
    assert(results.map(_._1).sum > 3000, results.map(_._1).sum.toString)
  }

  test("recordings stored as directories are the only content kept aside") {
    val kept = paths.filter(p => BidsScopes.partition(manifest(Vector(p.dropWhile(_ != '/').drop(1)))).preserved.nonEmpty)
    val containers = kept.map(_.split('/').dropRight(1).last.replaceAll("^.*\\.", ".")).distinct.sorted
    assertEquals(containers, Vector(".ds", ".segd"))
  }
