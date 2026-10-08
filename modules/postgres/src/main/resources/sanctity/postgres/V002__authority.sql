CREATE TABLE principal (
  id uuid PRIMARY KEY
);
INSERT INTO principal(id) SELECT DISTINCT curator FROM dataset ON CONFLICT DO NOTHING;
-- statement
CREATE TABLE access_grant (
  id uuid PRIMARY KEY,
  dataset_id uuid NOT NULL REFERENCES dataset(id),
  recipient uuid NOT NULL REFERENCES principal(id),
  release_id uuid,
  actions text[] NOT NULL CHECK (cardinality(actions)>0 AND actions <@ ARRAY['ReadMetadata','ReadContent','Propose','Accept']::text[]),
  revoked_at timestamptz,
  FOREIGN KEY(dataset_id,release_id) REFERENCES release(dataset_id,id),
  CHECK(release_id IS NULL OR actions <@ ARRAY['ReadMetadata','ReadContent']::text[])
);
CREATE INDEX access_grant_active ON access_grant(dataset_id,recipient) WHERE revoked_at IS NULL;
-- statement
CREATE TABLE policy_audit (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dataset_id uuid NOT NULL REFERENCES dataset(id),
  actor uuid NOT NULL,
  grant_id uuid NOT NULL REFERENCES access_grant(id),
  action text NOT NULL CHECK(action IN ('Granted','Revoked')),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
