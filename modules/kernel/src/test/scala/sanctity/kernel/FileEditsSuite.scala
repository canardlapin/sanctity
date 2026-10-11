package sanctity.kernel

import munit.FunSuite
import java.util.UUID

final class FileEditsSuite extends FunSuite:
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def file(s: String, n: Long): Entry.File = Entry.File(p(s), Id[ArtifactTag](new UUID(0, n)),
    BlobRef.from(Digest.bytes(Array(n.toByte)), 1).toOption.get)
  private val a = file("a.nii", 1)
  private val b = file("b.nii", 2)
  private val base = Manifest.from(Vector(a, b, Entry.Directory(p("empty")))).toOption.get

  test("simultaneous swaps preserve identity and are independent of operation order") {
    val edits = Vector(FileEdit.Move(a.path, b.path), FileEdit.Move(b.path, a.path))
    val next = FileEdits.applyTo(base, edits).toOption.get
    assertEquals(next.byPath(a.path), b.copy(path = a.path))
    assertEquals(next.byPath(b.path), a.copy(path = b.path))
    assertEquals(next.id, FileEdits.applyTo(base, edits.reverse).toOption.get.id)
    assertEquals(FileEdits.applyTo(next, edits).toOption.get.id, base.id)
  }

  test("collisions and ambiguous base-relative edits fail without partial mutation") {
    Vector(
      Vector(FileEdit.Move(a.path, b.path)),
      Vector(FileEdit.Move(a.path, p("B.nii"))),
      Vector(FileEdit.Move(a.path, p("same")), FileEdit.Move(b.path, p("same"))),
      Vector(FileEdit.Remove(a.path), FileEdit.Move(a.path, p("new"))),
      Vector(FileEdit.Remove(p("empty"))),
      Vector(FileEdit.Move(a.path, a.path)),
      Vector(FileEdit.Move(a.path, p("b.nii/child"))),
      Vector.empty
    ).foreach(edits => assert(FileEdits.applyTo(base, edits).isLeft))
    assertEquals(base.byPath(a.path), a)
  }

  test("parents are introduced without discarding empty directories; deletion can free a destination") {
    val next = FileEdits.applyTo(base, Vector(FileEdit.Move(a.path, p("new/path/a.nii")), FileEdit.Remove(b.path))).toOption.get
    assertEquals(next.byPath(p("new/path/a.nii")), a.copy(path = p("new/path/a.nii")))
    assertEquals(next.directories.toSet, Set(p("empty"), p("new"), p("new/path")))
    val replace = FileEdits.applyTo(base, Vector(FileEdit.Remove(b.path), FileEdit.Move(a.path, b.path))).toOption.get
    assertEquals(replace.byPath(b.path), a.copy(path = b.path))
  }
