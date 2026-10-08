CREATE TABLE dataset (
  id uuid PRIMARY KEY,
  custodian uuid NOT NULL,
  curator uuid NOT NULL,
  head uuid
)
-- statement
CREATE TABLE manifest (
  id text PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
  payload jsonb NOT NULL,
  entry_count integer NOT NULL CHECK (entry_count >= 0)
)
-- statement
CREATE TABLE blob (
  digest text PRIMARY KEY CHECK (digest ~ '^[0-9a-f]{64}$'),
  size bigint NOT NULL CHECK (size >= 0),
  UNIQUE (digest, size)
)
-- statement
CREATE TABLE manifest_entry (
  manifest_id text NOT NULL REFERENCES manifest(id),
  path text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('file', 'directory')),
  artifact uuid,
  digest text,
  size bigint,
  PRIMARY KEY (manifest_id, path),
  UNIQUE (manifest_id, artifact),
  FOREIGN KEY (digest, size) REFERENCES blob(digest, size),
  CHECK ((kind = 'directory' AND artifact IS NULL AND digest IS NULL AND size IS NULL)
      OR (kind = 'file' AND artifact IS NOT NULL AND digest IS NOT NULL AND size IS NOT NULL))
)
-- statement
CREATE TABLE interpretation (
  id text PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
  manifest_id text NOT NULL REFERENCES manifest(id),
  payload jsonb NOT NULL,
  artifact_count integer NOT NULL CHECK (artifact_count >= 0),
  field_count integer NOT NULL CHECK (field_count >= 0),
  UNIQUE (id, manifest_id)
)
-- statement
CREATE TABLE metadata_artifact (
  interpretation_id text NOT NULL REFERENCES interpretation(id),
  path text NOT NULL,
  dataset_root text,
  sources jsonb NOT NULL,
  PRIMARY KEY (interpretation_id, path)
)
-- statement
CREATE TABLE metadata_field (
  interpretation_id text NOT NULL,
  path text NOT NULL,
  name text NOT NULL,
  value jsonb NOT NULL,
  origin text NOT NULL,
  history jsonb NOT NULL,
  PRIMARY KEY (interpretation_id, path, name),
  FOREIGN KEY (interpretation_id, path) REFERENCES metadata_artifact(interpretation_id, path)
)
-- statement
CREATE INDEX metadata_field_lookup ON metadata_field (interpretation_id, name, path)
-- statement
CREATE TABLE assessment (
  id text PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
  manifest_id text NOT NULL,
  interpretation_id text NOT NULL,
  admissible boolean NOT NULL,
  payload jsonb NOT NULL,
  FOREIGN KEY (interpretation_id, manifest_id) REFERENCES interpretation(id, manifest_id),
  UNIQUE (id, interpretation_id, manifest_id)
)
-- statement
CREATE TABLE draft (
  id uuid PRIMARY KEY,
  dataset_id uuid NOT NULL REFERENCES dataset(id),
  base uuid,
  generation bigint NOT NULL CHECK (generation >= 0),
  manifest_id text NOT NULL REFERENCES manifest(id),
  activity jsonb NOT NULL,
  accepted_release uuid,
  UNIQUE (id, dataset_id)
)
-- statement
CREATE TABLE candidate (
  id uuid PRIMARY KEY,
  draft_id uuid NOT NULL,
  dataset_id uuid NOT NULL,
  base uuid,
  generation bigint NOT NULL CHECK (generation >= 0),
  manifest_id text NOT NULL,
  interpretation_id text NOT NULL,
  assessment_id text NOT NULL,
  activity jsonb NOT NULL,
  FOREIGN KEY (draft_id, dataset_id) REFERENCES draft(id, dataset_id),
  FOREIGN KEY (assessment_id, interpretation_id, manifest_id) REFERENCES assessment(id, interpretation_id, manifest_id),
  UNIQUE (id, dataset_id, draft_id),
  UNIQUE (id, dataset_id)
)
-- statement
CREATE TABLE release (
  id uuid PRIMARY KEY,
  dataset_id uuid NOT NULL REFERENCES dataset(id),
  parent uuid,
  draft_id uuid NOT NULL UNIQUE,
  candidate_id uuid NOT NULL UNIQUE,
  accepted_by uuid NOT NULL,
  accepted_at timestamptz NOT NULL,
  FOREIGN KEY (candidate_id, dataset_id, draft_id) REFERENCES candidate(id, dataset_id, draft_id),
  UNIQUE (dataset_id, id),
  UNIQUE (id, dataset_id, candidate_id),
  UNIQUE (id, draft_id)
)
-- statement
ALTER TABLE dataset ADD CONSTRAINT dataset_head_fk FOREIGN KEY (id, head) REFERENCES release(dataset_id, id)
-- statement
ALTER TABLE draft ADD CONSTRAINT draft_base_fk FOREIGN KEY (dataset_id, base) REFERENCES release(dataset_id, id)
-- statement
ALTER TABLE draft ADD CONSTRAINT draft_release_fk FOREIGN KEY (accepted_release, id) REFERENCES release(id, draft_id)
-- statement
ALTER TABLE candidate ADD CONSTRAINT candidate_base_fk FOREIGN KEY (dataset_id, base) REFERENCES release(dataset_id, id)
-- statement
ALTER TABLE release ADD CONSTRAINT release_parent_fk FOREIGN KEY (dataset_id, parent) REFERENCES release(dataset_id, id)
-- statement
CREATE TABLE acceptance_receipt (
  dataset_id uuid NOT NULL,
  principal uuid NOT NULL,
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
  candidate_id uuid NOT NULL,
  release_id uuid NOT NULL,
  PRIMARY KEY (dataset_id, principal, idempotency_key),
  FOREIGN KEY (release_id, dataset_id, candidate_id) REFERENCES release(id, dataset_id, candidate_id)
)
-- statement
CREATE TABLE audit (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dataset_id uuid NOT NULL,
  release_id uuid NOT NULL UNIQUE,
  actor uuid NOT NULL,
  action text NOT NULL CHECK (action = 'ReleaseAccepted'),
  recorded_at timestamptz NOT NULL,
  FOREIGN KEY (dataset_id, release_id) REFERENCES release(dataset_id, id)
)
-- statement
CREATE TABLE outbox (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dataset_id uuid NOT NULL,
  release_id uuid NOT NULL UNIQUE,
  event_type text NOT NULL CHECK (event_type = 'ReleaseAccepted'),
  payload jsonb NOT NULL,
  recorded_at timestamptz NOT NULL,
  delivered_at timestamptz,
  FOREIGN KEY (dataset_id, release_id) REFERENCES release(dataset_id, id)
)
-- statement
CREATE INDEX outbox_pending ON outbox (sequence) WHERE delivered_at IS NULL
