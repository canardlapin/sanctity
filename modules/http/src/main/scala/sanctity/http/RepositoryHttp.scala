package sanctity.http

import cats.data.Kleisli
import cats.effect.Async
import cats.effect.syntax.all.*
import scala.concurrent.duration.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIString
import sanctity.application.*
import sanctity.kernel.*

/** Public v1 workflow adapter. No arbitrary filesystem paths, blobs, manifests, or caller contexts are accepted. */
final class RepositoryHttp[F[_]](
    catalog: Catalog[F], repository: NativeRepository[F], authenticator: Authenticator[F],
    independentPolicy: Boolean = false, organization: Option[OrganizationService[F]] = None
)(using F: Async[F]) extends Http4sDsl[F]:
  import PublicJson as Out
  import RequestJson as In

  private def checked[A](value: F[Either[Problem, A]]): F[A] = value.flatMap(Checked.lift[F, A])
  private def lift[A](value: Either[Problem, A]): F[A] = Checked.lift[F, A](value)
  private def id[A](value: String): F[Id[A]] = lift(Id.parse[A](value))
  private def ref(d: String, r: String): F[ReleaseRef] = (id[DatasetTag](d), id[ReleaseTag](r)).mapN(ReleaseRef.apply)
  private def json(value: Json, status: Status = Status.Ok): F[Response[F]] =
    F.pure(Response[F](status).withEntity(value.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
  private def error(status: Status, code: String): F[Response[F]] = json(Json.obj("error" -> Json.fromString(code)), status)
  private def policyAcknowledged(response: Response[F]): Response[F] =
    response.putHeaders(Header.Raw(CIString("X-Sanctity-Policy-Durability"),if independentPolicy then "Durable" else "LocalOnly"))
  private def failure(p: Problem): F[Response[F]] = p match
    case Problem.Denied | _: Problem.NotFound => error(Status.NotFound, "not_found")
    case Problem.PolicyPending(operation) =>
      error(Status.ServiceUnavailable, "policy_pending:" + operation)
    case Problem.PolicyOutcomeUnknown(operation) =>
      error(Status.ServiceUnavailable, "policy_outcome_unknown:" + operation)
    case _: Problem.Conflict => error(Status.Conflict, "conflict")
    case _: Problem.Rejected => error(Status.UnprocessableEntity, "admission_rejected")
    case _: Problem.LimitExceeded => error(Status.PayloadTooLarge, "limit_exceeded")
    case _: Problem.Integrity | _: Problem.Operational => error(Status.InternalServerError, "repository_failure")
    case _ => error(Status.BadRequest, "invalid_request")

  private def org: F[OrganizationService[F]] =
    organization.fold(F.raiseError[OrganizationService[F]](Failure(Problem.NotFound("route", "organization"))))(F.pure)

  /** One governed organization mutation (ADR 001): Idempotency-Key and the body's `expected` generations are required
    * preconditions (428 when absent); the command is authorized and applied by the organization service.
    */
  private def govern(ctx: AccessContext, req: Request[F], fields: Set[String], status: Status = Status.Ok)(
      build: (Json, IdempotencyKey) => F[(OrganizationCommand, Json)]): F[Response[F]] =
    if req.headers.get(CIString("Idempotency-Key")).isEmpty then error(Status.PreconditionRequired, "precondition_required")
    else for
      service <- org
      key <- header(req, "Idempotency-Key").flatMap(v => lift(IdempotencyKey.from(v)))
      b <- body(req)
      res <- if b.hcursor.downField("expected").focus.isEmpty then error(Status.PreconditionRequired, "precondition_required") else for
        _ <- lift(In.fields(b, fields + "expected"))
        expected <- lift(In.expected(b))
        built <- build(b, key)
        receipt <- checked(service.execute(ctx, OrganizationMutation(key, expected, built._1)))
        out <- json(Out.organizationReceipt(receipt, built._2), status)
      yield out
    yield res
  /** Server-minted identities derive from the caller, the parent and the idempotency key, so a retry after a lost
    * response names the same record instead of creating another.
    */
  private def minted[A](kind: String, ctx: AccessContext, parent: String, key: IdempotencyKey): Id[A] =
    OrganizationMigration.id[A]("http-" + kind, ctx.principal.render, parent, key.value)
  private def created(pairs: (String, String)*): Json = Json.obj(pairs.map((k, v) => k -> Json.fromString(v))*)
  private def role[A](values: Array[A], name: String): Either[Problem, A] =
    values.find(_.toString.equalsIgnoreCase(name)).toRight(Problem.Unsupported("unknown role"))

  private def body(req: Request[F]): F[Json] =
    if req.contentType.forall(_.mediaType != MediaType.application.json) ||
        req.headers.headers.exists(h => h.name == CIString("Content-Encoding") && !h.value.equalsIgnoreCase("identity")) then
      F.raiseError(Failure(Problem.Unsupported("expected uncompressed application/json")))
    else req.body.take(In.maxBytes.toLong + 1).compile.to(Array).timeout(15.seconds).flatMap(b => lift(In.parse(b)))

  private def header(req: Request[F], name: String): F[String] =
    lift(req.headers.headers.filter(_.name == CIString(name)).map(_.value) match
      case values if values.size == 1 => Right(values.head)
      case _ => Left(Problem.Unsupported("one required header is missing or repeated")))
  private def parameter(req: Request[F], name: String): F[String] =
    lift(req.uri.multiParams.get(name).filter(_.size == 1).map(_.head).toRight(Problem.Unsupported("one query parameter required")))
  private def path(req: Request[F]): F[RelPath] = parameter(req, "path").flatMap(v => lift(RelPath.from(v)))
  private def pageNumber(req: Request[F], name: String, default: Int): F[Int] =
    req.uri.multiParams.get(name) match
      case None => F.pure(default)
      case Some(values) if values.size == 1 => lift(values.head.toIntOption.toRight(Problem.Unsupported("invalid page")))
      case _ => F.raiseError(Failure(Problem.Unsupported("invalid page")))

  private def dispatch(ctx: AccessContext, req: Request[F]): F[Response[F]] = req match
    case GET -> Root / "v1" / "me" =>
      json(Json.obj("principal" -> Json.fromString(ctx.principal.render)))
    case GET -> Root / "v1" / "datasets" / d =>
      id[DatasetTag](d).flatMap(i => checked(catalog.dataset(ctx, i))).flatMap { ds =>
        json(Json.obj("id" -> Json.fromString(ds.id.render), "head" -> ds.head.fold(Json.Null)(r => Json.fromString(r.render))))
      }
    case GET -> Root / "v1" / "datasets" / d / "releases" / r =>
      for
        at <- ref(d, r)
        rel <- checked(repository.release(ctx, at))
        // Curation history (why, what changed) is dataset-wide information: a fixed-release grant sees the release alone.
        // The parent's identity is shown only to readers of that parent; curator identities need Propose or ManageAccess.
        readsHistory <- catalog.check(ctx, at.dataset, Action.ReadMetadata).map(_.isRight)
        showParent <- rel.parent.fold(F.pure(false))(p => catalog.check(ctx, at.dataset, Action.ReadMetadata, Some(p)).map(_.isRight))
        curates <- if !readsHistory then F.pure(false) else
          catalog.check(ctx, at.dataset, Action.Propose).flatMap(r => if r.isRight then F.pure(true) else catalog.check(ctx, at.dataset, Action.ManageAccess).map(_.isRight))
        parent <- if readsHistory && showParent then rel.parent.traverse(p => checked(repository.release(ctx, ReleaseRef(at.dataset, p)))) else F.pure(None)
        res <- json(Out.release(rel, Option.when(readsHistory)(Out.provenance(rel, parent, curates)), showParent))
      yield res
    case GET -> Root / "v1" / "datasets" / d / "grants" =>
      id[DatasetTag](d).flatMap(i => checked(catalog.grants(ctx, i))).flatMap(gs => json(Out.grants(gs)))
    case GET -> Root / "v1" / "datasets" / d / "access" =>
      for
        dataset <- id[DatasetTag](d)
        subject <- parameter(req, "principal").flatMap(id[PrincipalTag])
        release <- req.uri.multiParams.get("release") match
          case None => F.pure(None)
          case Some(Seq(r)) => id[ReleaseTag](r).map(Some(_))
          case _ => F.raiseError[Option[ReleaseId]](Failure(Problem.Unsupported("one release parameter at most")))
        name <- parameter(req, "permission")
        permission <- lift(OrganizationAuthority.Permission.values.find(_.toString == name).toRight(Problem.Unsupported("unknown permission")))
        explained <- checked(catalog.explainAccess(ctx, subject, OrganizationAuthority.DataScope(dataset, release), permission))
        res <- json(Out.explanation(explained))
      yield res
    case GET -> Root / "v1" / "datasets" / d / "releases" / r / "metadata" =>
      (ref(d, r), path(req)).tupled.flatMap((at, p) => checked(repository.explain(ctx, at, p))).flatMap(v => json(Out.metadata(v)))
    case GET -> Root / "v1" / "datasets" / d / "releases" / r / "file" =>
      for
        at <- ref(d, r)
        p <- path(req)
        file <- checked(repository.readFile(ctx, at, p))
      yield Response[F](Status.Ok).withBodyStream(file.bytes).putHeaders(
        Header.Raw(CIString("Content-Type"), "application/octet-stream"),
        Header.Raw(CIString("Content-Disposition"), "attachment"),
        Header.Raw(CIString("X-Sanctity-SHA256"), file.digest.value),
        Header.Raw(CIString("X-Sanctity-Size"), file.size.toString))
    case POST -> Root / "v1" / "datasets" / d / "releases" / r / "corrections" =>
      for
        at <- ref(d, r)
        _ <- checked(catalog.check(ctx, at.dataset, Action.Propose))
        b <- body(req)
        _ <- lift(In.fields(b, Set("path", "text", "reason")))
        p <- lift(In.get[String](b, "path").flatMap(RelPath.from))
        text <- lift(In.get[String](b, "text"))
        reason <- lift(In.get[String](b, "reason"))
        _ <- lift(Either.cond(reason.length <= 2000, (), Problem.LimitExceeded("reason exceeds 2000 characters")))
        preview <- checked(if p.fileName.endsWith(".json") then repository.correctMetadata(ctx, at, p, text, reason)
          else repository.correctCompanion(ctx, at, p, text, reason))
        result <- json(Out.preview(preview), Status.Created)
      yield result
    case GET -> Root / "v1" / "candidates" / c =>
      id[CandidateTag](c).flatMap(i => checked(catalog.candidate(ctx, i))).flatMap(v => json(Out.candidate(v)))
    case POST -> Root / "v1" / "candidates" / c / "accept" =>
      for
        candidate <- id[CandidateTag](c)
        k <- header(req, "Idempotency-Key").flatMap(v => lift(IdempotencyKey.from(v)))
        receipt <- checked(repository.accept(ctx, candidate, k))
        result <- json(Out.receipt(receipt))
      yield result
    case POST -> Root / "v1" / "datasets" / d / "grants" =>
      for
        dataset <- id[DatasetTag](d)
        _ <- checked(catalog.check(ctx, dataset, Action.ManageAccess))
        b <- body(req)
        _ <- lift(In.fields(b, Set("recipient", "release", "actions")))
        recipient <- lift(In.id[PrincipalTag](b, "recipient"))
        release <- lift(In.get[Option[String]](b, "release").flatMap(_.traverse(Id.parse[ReleaseTag])))
        names <- lift(In.get[Vector[String]](b, "actions"))
        actions <- lift(names.traverse(n => Action.values.find(_.toString == n).toRight(Problem.Unsupported("unknown action"))))
        grant <- checked(catalog.grant(ctx, dataset, recipient, release, actions.toSet))
        result <- json(Out.grant(grant), Status.Created).map(policyAcknowledged)
      yield result
    case DELETE -> Root / "v1" / "datasets" / d / "grants" / g =>
      (id[DatasetTag](d), id[GrantTag](g)).tupled.flatMap((ds, grant) => checked(catalog.revoke(ctx, ds, grant)))
        .as(policyAcknowledged(Response[F](Status.NoContent)))
    case POST -> Root / "v1" / "selections" =>
      for
        b <- body(req)
        parsed <- lift(In.query(b))
        (refs, query) = parsed
        selected <- checked(catalog.select(ctx, refs, query))
        result <- json(Json.obj("id" -> Json.fromString(selected.id.value.value), "total" -> Json.fromInt(selected.hits.size),
          "releases" -> Json.arr(selected.releases.map(Out.ref)*)), Status.Created)
      yield result
    case GET -> Root / "v1" / "selections" / s =>
      for
        selection <- lift(Digest.from(s)).flatMap(d => checked(catalog.selection(ctx, SelectionId(d))))
        offset <- pageNumber(req, "offset", 0)
        limit <- pageNumber(req, "limit", 100)
        hits <- lift(selection.page(offset, limit))
        result <- json(Json.obj("id" -> Json.fromString(selection.id.value.value), "total" -> Json.fromInt(selection.hits.size),
          "offset" -> Json.fromInt(offset), "hits" -> Json.arr(hits.map(h => Json.obj("ref" -> Out.ref(h.at),
            "path" -> Json.fromString(h.path.value), "participant" -> Json.fromString(h.participant),
            "session" -> h.session.fold(Json.Null)(Json.fromString)))*)))
      yield result
    // ---------- organization (F02.3) ----------
    case GET -> Root / "v1" / "labs" / l =>
      for { service <- org; lab <- id[LabTag](l); v <- checked(service.lab(ctx, lab)); r <- json(Out.lab(v)) } yield r
    case GET -> Root / "v1" / "projects" / p =>
      for { service <- org; project <- id[ProjectTag](p); v <- checked(service.project(ctx, project)); r <- json(Out.project(v)) } yield r
    case GET -> Root / "v1" / "datasets" / d / "placement" =>
      for { service <- org; dataset <- id[DatasetTag](d); v <- checked(service.placement(ctx, dataset)); r <- json(Out.placement(v)) } yield r
    case GET -> Root / "v1" / "datasets" / d / "inheritance" =>
      for
        service <- org
        dataset <- id[DatasetTag](d)
        impact <- checked(service.inheritanceImpact(ctx, dataset))
        r <- json(Out.impact(impact).deepMerge(Json.obj("digest" -> Json.fromString(Out.impactDigest(impact)))))
      yield r
    case POST -> Root / "v1" / "projects" / p / "datasets" =>
      if req.headers.get(CIString("Idempotency-Key")).isEmpty then error(Status.PreconditionRequired, "precondition_required")
      else for
        service <- org
        project <- id[ProjectTag](p)
        key <- header(req, "Idempotency-Key").flatMap(v => lift(IdempotencyKey.from(v)))
        b <- body(req)
        res <- if b.hcursor.downField("expected").focus.isEmpty then error(Status.PreconditionRequired, "precondition_required") else for
          _ <- lift(In.fields(b, Set("custodian", "expected")))
          custodian <- lift(In.id[CustodianTag](b, "custodian"))
          expected <- lift(In.expected(b))
          generation <- lift(expected.get(OrganizationRef.Project(project)).filter(_ => expected.size == 1)
            .toRight(Problem.Conflict("required organization preconditions missing or extra")))
          made <- checked(service.createDataset(ctx, custodian, project, key, generation))
          out <- json(Out.organizationReceipt(made._2, created("dataset" -> made._1.id.render)), Status.Created)
        yield out
      yield res
    case POST -> Root / "v1" / "labs" / l / "rename" =>
      id[LabTag](l).flatMap(lab => govern(ctx, req, Set("name", "description"))((b, _) =>
        lift((In.name(b), In.description(b)).tupled).map((n, d) => OrganizationCommand.RenameLab(lab, n, d) -> Json.Null)))
    case POST -> Root / "v1" / "labs" / l / "archive" =>
      id[LabTag](l).flatMap(lab => govern(ctx, req, Set("archived"))((b, _) =>
        lift(In.get[Boolean](b, "archived")).map(a => OrganizationCommand.ArchiveLab(lab, a) -> Json.Null)))
    case POST -> Root / "v1" / "labs" / l / "projects" =>
      id[LabTag](l).flatMap(lab => govern(ctx, req, Set("name", "description", "steward"), Status.Created)((b, key) => lift(for
        n <- In.name(b)
        d <- In.description(b)
        steward <- In.get[Option[String]](b, "steward").flatMap(_.traverse(Id.parse[PrincipalTag]))
      yield
        val project = minted[ProjectTag]("project", ctx, lab.render, key)
        val participation = minted[ProjectParticipationTag]("project-steward", ctx, lab.render, key)
        OrganizationCommand.CreateProject(project, lab, steward.getOrElse(ctx.principal), participation, n, d) ->
          created("project" -> project.render, "participation" -> participation.render))))
    case POST -> Root / "v1" / "labs" / l / "members" =>
      id[LabTag](l).flatMap(lab => govern(ctx, req, Set("principal", "role", "expiresAt"), Status.Created)((b, key) => lift(for
        person <- In.id[PrincipalTag](b, "principal")
        r <- In.get[String](b, "role").flatMap(role(LabRole.values, _))
        expires <- In.expiry(b)
      yield
        val membership = minted[LabMembershipTag]("membership", ctx, lab.render, key)
        OrganizationCommand.AddMember(membership, lab, person, r, expires) -> created("membership" -> membership.render))))
    case POST -> Root / "v1" / "memberships" / m / "end" =>
      id[LabMembershipTag](m).flatMap(x => govern(ctx, req, Set.empty)((_, _) => F.pure(OrganizationCommand.EndMember(x) -> Json.Null)))
    case POST -> Root / "v1" / "projects" / p / "rename" =>
      id[ProjectTag](p).flatMap(project => govern(ctx, req, Set("name", "description"))((b, _) =>
        lift((In.name(b), In.description(b)).tupled).map((n, d) => OrganizationCommand.RenameProject(project, n, d) -> Json.Null)))
    case POST -> Root / "v1" / "projects" / p / "archive" =>
      id[ProjectTag](p).flatMap(project => govern(ctx, req, Set("archived"))((b, _) =>
        lift(In.get[Boolean](b, "archived")).map(a => OrganizationCommand.ArchiveProject(project, a) -> Json.Null)))
    case POST -> Root / "v1" / "projects" / p / "participants" =>
      id[ProjectTag](p).flatMap(project => govern(ctx, req, Set("principal", "role", "membership", "expiresAt"), Status.Created)((b, key) => lift(for
        person <- In.id[PrincipalTag](b, "principal")
        r <- In.get[String](b, "role").flatMap(role(OrganizationRole.values, _))
        membership <- In.get[Option[String]](b, "membership").flatMap(_.traverse(Id.parse[LabMembershipTag]))
        expires <- In.expiry(b)
      yield
        val participation = minted[ProjectParticipationTag]("participation", ctx, project.render, key)
        OrganizationCommand.AddParticipant(participation, project, person, r,
          membership.fold(ParticipationBasis.Independent)(ParticipationBasis.Membership(_)), expires) ->
          created("participation" -> participation.render))))
    case POST -> Root / "v1" / "participations" / x / "end" =>
      id[ProjectParticipationTag](x).flatMap(r => govern(ctx, req, Set.empty)((_, _) => F.pure(OrganizationCommand.EndParticipant(r) -> Json.Null)))
    case POST -> Root / "v1" / "datasets" / d / "stewards" =>
      id[DatasetTag](d).flatMap(dataset => govern(ctx, req, Set("principal"), Status.Created)((b, key) =>
        lift(In.id[PrincipalTag](b, "principal")).map { person =>
          val stewardship = minted[DatasetStewardshipTag]("stewardship", ctx, dataset.render, key)
          OrganizationCommand.AddSteward(stewardship, dataset, person) -> created("stewardship" -> stewardship.render)
        }))
    case POST -> Root / "v1" / "stewardships" / x / "end" =>
      id[DatasetStewardshipTag](x).flatMap(a => govern(ctx, req, Set.empty)((_, _) => F.pure(OrganizationCommand.EndSteward(a) -> Json.Null)))
    case POST -> Root / "v1" / "datasets" / d / "inheritance" =>
      // The steward confirms the preview they saw: its digest must match the impact recomputed now.
      id[DatasetTag](d).flatMap(dataset => govern(ctx, req, Set("policy", "previewDigest"))((b, _) => for
        service <- org
        policy <- lift(In.get[String](b, "policy").flatMap(n => PlacementPolicy.values.find(_.toString == n).toRight(Problem.Unsupported("unknown policy"))))
        digest <- lift(In.get[String](b, "previewDigest"))
        impact <- checked(service.inheritanceImpact(ctx, dataset))
        _ <- lift(Either.cond(Out.impactDigest(impact) == digest, (), Problem.Conflict("access-impact preview changed; review it again")))
      yield OrganizationCommand.SetInheritance(dataset, policy, impact) -> Json.Null))
    case _ => error(Status.NotFound, "not_found")

  private def authenticate(req: Request[F]): F[Either[Problem, AccessContext]] =
    val values = req.headers.headers.filter(_.name == CIString("Authorization")).map(_.value)
    // Only one header, never query parameters, cookies or caller-provided principal headers.
    values match
      case values if values.size == 1 && values.head.length <= 512 =>
        values.head.split(" ", -1).toList match
          case scheme :: token :: Nil if scheme.equalsIgnoreCase("Bearer") && token.nonEmpty => authenticator.authenticate(token)
          case _ => F.pure(Left(Problem.Denied))
      case _ => F.pure(Left(Problem.Denied))

  val app: HttpApp[F] = Kleisli { req =>
    val result = authenticate(req).flatMap {
      case Right(ctx) if ctx.credential.nonEmpty => dispatch(ctx, req)
      case Right(_) | Left(Problem.Denied) =>
        error(Status.Unauthorized, "unauthorized").map(_.putHeaders(Header.Raw(CIString("WWW-Authenticate"), "Bearer realm=\"sanctity\"")))
      case Left(_: Problem.Conflict) => error(Status.ServiceUnavailable, "repository_unavailable")
      case Left(_) => error(Status.ServiceUnavailable, "authentication_unavailable")
    }.handleErrorWith {
      case Failure(problem) => failure(problem)
      case _: java.util.concurrent.TimeoutException => error(Status.RequestTimeout, "request_timeout")
      case _ => error(Status.InternalServerError, "repository_failure")
    }
    result.map(_.putHeaders(Header.Raw(CIString("Cache-Control"), "no-store"),
      Header.Raw(CIString("X-Content-Type-Options"), "nosniff")))
  }
