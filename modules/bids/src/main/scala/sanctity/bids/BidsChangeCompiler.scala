package sanctity.bids

import bids4s.{BidsContext, BidsTable, BidsUri, CheckedTsv}
import cats.syntax.all.*
import io.circe.{Json, JsonObject, Printer}
import io.circe.jawn.JawnParser
import sanctity.application.{ReferenceRepair, ScientificChangeCompiler}
import sanctity.kernel.*

/** Bounded raw MRI edit profile. No inferred companion renames or automatic
  * removal of B0 group declarations. Unsupported reference vocabularies fail closed.
  */
final class BidsChangeCompiler(bidsContext: BidsContext = BidsContext.Default) extends ScientificChangeCompiler:
  val identity: String = "sanctity-bids-file-edits-v4:" + BuildIdentity.bids4s + ":" + BuildIdentity.sanctity + ":" + BidsInterpreter.describe(bidsContext).id.value
  private val parser = new JawnParser(maxValueSize = Some(4 * 1024 * 1024), allowDuplicateKeys = false)
  private val printer = Printer.spaces2.copy(sortKeys = true)
  private val unsupportedReferences = Set("Sources", "RawSources", "AssociatedEmptyRoom", "M0Scan", "DatasetLinks")
  private val suffixes = Set("T1w", "T2w", "bold", "dwi", "epi", "events", "phasediff", "phase1", "phase2", "magnitude1", "magnitude2")
  private val extensions = Set("nii", "nii.gz", "json", "bval", "bvec", "tsv")

  private def require(ok: Boolean, detail: String): Either[Problem, Unit] =
    Either.cond(ok, (), Problem.Unsupported(detail))

  private def context(source: Release, edits: Vector[FileEdit]): Either[Problem, Map[RelPath, Option[RelPath]]] =
    for
      _ <- require(source.interpretation.profile.context.contains(BidsInterpreter.describe(bidsContext)),
        "source interpretation context differs from the compiler; explicitly reinterpret before transforming")
      _ <- FileEdits.applyTo(source.manifest, edits)
      _ <- require(source.interpretation.relationships.exists(_.version == BidsInterpreter.RelationshipVersion) &&
        source.interpretation.artifacts.forall(_.datasetRoot.isEmpty) &&
        !source.manifest.files.exists(f => f.path.fileName == "dataset_description.json" && f.path.parent.nonEmpty),
        "file edits require the current single raw dataset relationship profile")
      _ <- source.interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == "bids.multi-echo-bold").traverse_ { r =>
        val members = (r.to :+ r.from).toSet
        val affected = edits.filter(e => members(e.source))
        val destinations = affected.flatMap(_.destination)
        val names = destinations.flatMap(p => bidsContext.parseGeneric(p.fileName).toOption)
        val keys = names.map(n => n.entities.values.map { (k, v) =>
          val normalized = if k.short == "run" then v.dropWhile(_ == '0') else v
          k.short -> (if normalized.isEmpty then "0" else normalized)
        }.toMap - "echo")
        val coherent = destinations.isEmpty || (names.size == members.size &&
          names.forall(_.entities.get(bids4s.EntityKey.Echo).nonEmpty) && keys.distinct.size == 1)
        require(affected.isEmpty || (affected.map(_.source).toSet == members &&
          affected.map(_.destination.isEmpty).distinct.size == 1 && coherent),
          "echo family images must be moved as one indexed family or removed together; metadata can be corrected independently")
      }
      _ <- edits.traverse_ { edit =>
        def name(path: RelPath) = bidsContext.parseGeneric(path.fileName).leftMap(e => Problem.Unsupported(e.message))
        if edit.source.value.startsWith("stimuli/") then
          edit.destination.traverse_(to => require(to.value.startsWith("stimuli/") &&
            bids4s.BidsStimulusResolver.path(to.value.stripPrefix("stimuli/")).exists(_.value == to.value),
            "stimulus moves must remain safe local paths beneath stimuli/"))
        else for
          from <- name(edit.source)
          _ <- require(suffixes(from.kind) && extensions(from.extension) &&
            !edit.source.value.split('/').exists(Set("derivatives", "sourcedata", "stimuli", "code", "phenotype")),
            "edit profile supports MRI images, sidecars, events and gradients; summary tables and dataset structure need another profile")
          _ <- edit.destination.traverse_ { to =>
            for
              target <- name(to)
              _ <- require(target.kind == from.kind && target.extension == from.extension,
                "renaming may change entities but cannot change scientific suffix or format")
              _ <- require(!to.value.split('/').exists(Set("derivatives", "sourcedata", "stimuli", "code", "phenotype")), "unsupported destination scope")
              _ <- if Set("nii", "nii.gz")(from.extension) then
                require(to.parent == edit.source.parent &&
                  Vector("sub", "ses").forall(k => from.entities.values.find(_._1.short == k).map(_._2) == target.entities.values.find(_._1.short == k).map(_._2)),
                  "image moves preserve participant/session placement; identity relabeling requires another profile")
                else Right(())
            yield ()
          }
        yield ()
      }
    yield edits.map(e => e.source -> e.destination).toMap

  def documents(source: Release, edits: Vector[FileEdit]): Either[Problem, Vector[RelPath]] =
    context(source, edits).map { mapping =>
      val events = source.interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == "bids.task-events").flatMap(_.to).toSet
      source.manifest.files.filter(f => mapping.getOrElse(f.path, Some(f.path)).nonEmpty && !f.path.value.startsWith("stimuli/") &&
        (f.path.fileName.endsWith(".json") || f.path.fileName.endsWith("_scans.tsv") ||
          (mapping.keys.exists(_.value.startsWith("stimuli/")) && events(f.path))))
        .map(_.path).sortBy(_.value)
    }

  def repairs(source: Release, edits: Vector[FileEdit], documents: Map[RelPath, String]): Either[Problem, Vector[ReferenceRepair]] =
    for
      mapping <- context(source, edits)
      paths <- this.documents(source, edits)
      _ <- require(paths.forall(documents.contains), "exact source JSON and scans documents are required")
      result <- paths.traverse { path =>
        val input = source.manifest.byPath(path).asInstanceOf[Entry.File]
        val output = mapping.getOrElse(path, Some(path)).get
        val text = documents(path)
        for
          _ <- require(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4 * 1024 * 1024,
            "source document exceeds 4 MiB")
          rewritten <- if path.fileName.endsWith(".json") then json(source, path, output, text, mapping)
            else if path.fileName.endsWith("_scans.tsv") then scans(source, path, text, mapping)
            else stimuli(source, text, mapping)
        yield rewritten.map { value =>
          ReferenceRepair(input, output, value, if path.fileName.endsWith(".json") then "bids.intended-for.v1"
            else if path.fileName.endsWith("_scans.tsv") then "bids.scans-filename.v1" else "bids.stim-file.v1")
        }
      }
    yield result.flatten

  private def stimuli(source: Release, text: String, mapping: Map[RelPath, Option[RelPath]]): Either[Problem, Option[String]] =
    for
      table <- CheckedTsv.parse(text).leftMap(e => Problem.Unsupported(e.message)).map(_.table)
      column = table.columns.indexOf("stim_file")
      rows <- table.rows.traverse { row =>
        if column < 0 || row(column).isEmpty then Right(row)
        else for
          path <- bids4s.BidsStimulusResolver.path(row(column).get).leftMap(Problem.Unsupported.apply)
          original <- RelPath.from(path.value)
          _ <- require(source.manifest.byPath.get(original).exists(_.isInstanceOf[Entry.File]), "referenced stimulus is absent")
          next <- mapping.getOrElse(original, Some(original)).toRight(Problem.Unsupported("cannot remove a stimulus still referenced by retained events"))
        yield row.updated(column, Some(next.value.stripPrefix("stimuli/")))
      }
      revised <- BidsTable.fromRows(table.columns, rows).leftMap(e => Problem.Unsupported(e.message))
    yield Option.when(rows != table.rows)(CheckedTsv.render(revised))

  private def json(source: Release, path: RelPath, output: RelPath, text: String,
      mapping: Map[RelPath, Option[RelPath]]): Either[Problem, Option[String]] =
    for
      value <- parser.parse(text).leftMap(e => Problem.Unsupported(e.message))
      _ <- require(bounded(value), s"JSON nesting exceeds 128: ${path.value}")
      obj <- value.asObject.toRight(Problem.Unsupported(s"expected a JSON object: ${path.value}"))
      _ <- require(!obj.keys.exists(unsupportedReferences), s"unsupported reference vocabulary in ${path.value}")
      result <- obj("IntendedFor") match
        case None => Right(None)
        case Some(field) =>
          val values = field.asString.map(Vector(_)).orElse(field.asArray.flatMap(v => v.traverse(_.asString)))
          for
            refs <- values.filter(_.nonEmpty).toRight(Problem.Unsupported(s"invalid IntendedFor in ${path.value}"))
            targets <- refs.traverse(reference(source, path, _))
            _ <- require(targets.distinct.size == targets.size, s"duplicate IntendedFor targets in ${path.value}")
            next = targets.flatMap(p => mapping.getOrElse(p, Some(p)))
            // Moved legacy documents must not acquire a new participant-relative meaning.
            changed = next != targets || (path != output && refs.exists(!_.startsWith("bids::")))
          yield Option.when(changed) {
            val rewritten: JsonObject = if next.isEmpty then obj.remove("IntendedFor")
              else obj.add("IntendedFor", if field.isString && next.size == 1 then Json.fromString("bids::" + next.head.value)
                else Json.fromValues(next.map(p => Json.fromString("bids::" + p.value))))
            printer.print(Json.fromJsonObject(rewritten)) + "\n"
          }
    yield result

  private def reference(source: Release, document: RelPath, text: String): Either[Problem, RelPath] =
    val path = if text.startsWith("bids:") then
      for
        uri <- BidsUri.parse(text).leftMap(e => Problem.Unsupported(e.message))
        _ <- require(uri.datasetName.isCurrent, "external IntendedFor references require another edit profile")
        relative <- RelPath.from(uri.relativePath.value)
        _ <- require(text == "bids::" + relative.value, "IntendedFor must be a canonical current-dataset URI")
      yield relative
    else
      val localSubject = document.value.split('/').headOption.filter(_.startsWith("sub-"))
      val consumers = source.interpretation.artifacts.filter(_.sources.contains(document)).flatMap { m =>
        m.path.value.split('/').headOption.filter(_.startsWith("sub-"))
      }.distinct
      val subject = localSubject.orElse(Option.when(consumers.size == 1)(consumers.head))
      for
        part <- subject.toRight(Problem.Unsupported(s"legacy IntendedFor has ambiguous participant scope: ${document.value}"))
        relative <- RelPath.from(text)
        full <- RelPath.from(part + "/" + relative.value)
      yield full
    path.flatMap(p => require(source.manifest.byPath.get(p).exists(_.isInstanceOf[Entry.File]),
      s"IntendedFor target is absent: ${p.value}").as(p))

  private def scans(source: Release, path: RelPath, text: String,
      mapping: Map[RelPath, Option[RelPath]]): Either[Problem, Option[String]] =
    val prefix = path.parent.fold("")(_.value + "/")
    for
      parent <- path.parent.toRight(Problem.Unsupported("scans table must be inside a participant/session"))
      _ <- require(parent.value.matches("sub-[A-Za-z0-9]+(/ses-[A-Za-z0-9]+)?") &&
        path.fileName == parent.value.replace('/', '_') + "_scans.tsv", "unsupported scans table placement")
      table <- CheckedTsv.parse(text).leftMap(e => Problem.Unsupported(s"${path.value}: ${e.message}")).map(_.table)
      _ <- require(table.columns.headOption.contains("filename") && table.rows.forall(_.head.nonEmpty), "scans table requires filename as its first column")
      targets <- table.rows.traverse(r => RelPath.from(prefix + r.head.get))
      _ <- require(targets.distinct.size == targets.size && targets.forall(p => source.manifest.byPath.get(p).exists(_.isInstanceOf[Entry.File])),
        "scans filenames must be unique existing source files")
      next <- table.rows.zip(targets).traverse { (row, target) =>
        mapping.getOrElse(target, Some(target)).traverse { destination =>
          require(destination.value.startsWith(prefix), "moving a recording across scans-table scope requires another edit profile")
            .as(row.updated(0, Some(destination.value.stripPrefix(prefix))))
        }
      }.map(_.flatten)
      projected <- BidsTable.fromRows(table.columns, next).leftMap(e => Problem.Unsupported(e.message))
    yield Option.when(next != table.rows)(CheckedTsv.render(projected))

  private def bounded(value: Json, depth: Int = 0): Boolean =
    depth <= 128 && value.asArray.forall(_.forall(bounded(_, depth + 1))) &&
      value.asObject.forall(_.values.forall(bounded(_, depth + 1)))
