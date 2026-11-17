package sanctity.application

import sanctity.kernel.*

/** The accepted base and the exact paths the curator has agreed to replace. */
final case class ImportPlan(base: Option[ReleaseId] = None, replace: Set[RelPath] = Set.empty):
  def validate(previous: Option[Manifest], paths: Vector[(RelPath, Boolean)]): Either[Problem, Unit] =
    val old = previous.fold(Map.empty[RelPath, Entry])(_.byPath)
    val collisions = paths.collect { case (path, true) if old.get(path).exists(_.isInstanceOf[Entry.File]) => path }.toSet
    val wrongKind = paths.exists((path, file) => old.get(path).exists(_.isInstanceOf[Entry.File] != file))
    val combined = old.keySet ++ paths.map(_._1)
    val directories = old.collect { case (path, _: Entry.Directory) => path }.toSet ++ paths.collect { case (path, false) => path }
    for
      _ <- Either.cond(base.isDefined == previous.isDefined, (), Problem.Conflict("import base is missing"))
      _ <- Either.cond(combined.size <= 10000 && paths.size <= 10000, (), Problem.LimitExceeded("combined inventory exceeds 10000 entries"))
      _ <- Either.cond(paths.map(_._1).distinct.size == paths.size && combined.map(_.collisionKey).size == combined.size &&
        combined.flatMap(_.parent).subsetOf(directories), (), Problem.InvalidManifest("duplicate, colliding or missing parent path"))
      _ <- Either.cond(!wrongKind && collisions == replace, (), Problem.Conflict("explicit replacements must match the colliding files"))
    yield ()

  def manifest(previous: Option[Manifest], entries: Vector[Entry]): Either[Problem, Manifest] =
    validate(previous, entries.map(e => e.path -> e.isInstanceOf[Entry.File])).flatMap { _ =>
      val old = previous.fold(Map.empty[RelPath, Entry])(_.byPath)
      val additions = entries.map {
        case f: Entry.File => f.path -> old.get(f.path).collect { case existing: Entry.File => f.copy(artifact = existing.artifact) }.getOrElse(f)
        case d => d.path -> d
      }
      Manifest.from((old ++ additions).values.toVector)
    }
