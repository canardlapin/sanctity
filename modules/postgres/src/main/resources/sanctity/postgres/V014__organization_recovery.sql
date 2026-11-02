-- Operator-only durable recovery sessions. Runtime identities can read diagnostics
-- but cannot create, advance, release or remove a recovery session.
CREATE TABLE organization_recovery_session (
 operation uuid PRIMARY KEY,
 backup_digest text NOT NULL CHECK(backup_digest ~ '^[0-9a-f]{64}$'),
 operator_id uuid NOT NULL,
 recovery_identity text NOT NULL CHECK(recovery_identity ~ '^[0-9a-f]{64}$'),
 writer_identity text NOT NULL CHECK(writer_identity ~ '^[0-9a-f]{64}$'),
 phase text NOT NULL CHECK(phase IN ('Restored','Verified','Applied','Released','Opened')),
 plan jsonb NOT NULL,
 verification jsonb,
 release_command jsonb,
 release_certificate jsonb,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 opened_at timestamptz,
 CHECK((phase='Opened') = (opened_at IS NOT NULL))
);
-- statement
-- Policy for scientific targets absent from an older backup is retained as a
-- restrictive tombstone, not fabricated scientific releases or dropped history.
CREATE TABLE organization_recovery_tombstone (
 kind text NOT NULL CHECK(kind IN ('dataset','release')),
 target uuid NOT NULL,
 policy jsonb NOT NULL,
 recovery_operation uuid NOT NULL REFERENCES organization_recovery_session(operation),
 PRIMARY KEY(kind,target)
);
-- statement
CREATE FUNCTION reject_recovered_identity_reuse() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE retired boolean;
BEGIN
 EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.organization_recovery_tombstone WHERE kind=$1 AND target=$2)',TG_TABLE_SCHEMA)
 INTO retired USING TG_TABLE_NAME,NEW.id;
 IF retired THEN RAISE EXCEPTION 'scientific identity is retired by recovery' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END
$$;
-- statement
CREATE TRIGGER dataset_recovery_identity BEFORE INSERT ON dataset
 FOR EACH ROW EXECUTE FUNCTION reject_recovered_identity_reuse();
-- statement
CREATE TRIGGER release_recovery_identity BEFORE INSERT ON release
 FOR EACH ROW EXECUTE FUNCTION reject_recovered_identity_reuse();
