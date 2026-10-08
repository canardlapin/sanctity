CREATE TABLE import_operation (
 id uuid PRIMARY KEY,
 dataset_id uuid NOT NULL REFERENCES dataset(id),
 owner uuid NOT NULL REFERENCES principal(id),
 idempotency_key text NOT NULL,
 source text NOT NULL,
 inventory jsonb NOT NULL,
 candidate_id uuid UNIQUE REFERENCES candidate(id),
 receipt jsonb,
 lease_token uuid,
 lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(dataset_id,owner,idempotency_key),
 CHECK((lease_token IS NULL) = (lease_until IS NULL))
);
-- statement
CREATE TABLE acquired_file (
 operation_id uuid NOT NULL REFERENCES import_operation(id),
 path text NOT NULL,
 payload jsonb NOT NULL,
 PRIMARY KEY(operation_id,path)
);
-- statement
CREATE TABLE outbox_delivery (
 sequence bigint NOT NULL REFERENCES outbox(sequence),
 target text NOT NULL,
 lease_token uuid,
 lease_until timestamptz,
 delivered_at timestamptz,
 attempts integer NOT NULL DEFAULT 0,
 PRIMARY KEY(sequence,target),
 CHECK((lease_token IS NULL) = (lease_until IS NULL))
);
-- statement
CREATE TABLE repository_state (
 singleton boolean PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 recovery_required boolean NOT NULL DEFAULT FALSE
);
INSERT INTO repository_state(singleton) VALUES(TRUE);
-- statement
CREATE TABLE recovery_audit (
 sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 policy_digest text NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
