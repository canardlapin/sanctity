CREATE TABLE api_credential (
  id uuid PRIMARY KEY,
  principal_id uuid NOT NULL REFERENCES principal(id),
  token_digest text NOT NULL UNIQUE CHECK(token_digest ~ '^[0-9a-f]{64}$'),
  label text NOT NULL CHECK(length(label) BETWEEN 1 AND 100),
  issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz NOT NULL CHECK(expires_at > issued_at),
  revoked_at timestamptz
);
-- statement
CREATE TABLE credential_audit (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  credential_id uuid NOT NULL REFERENCES api_credential(id),
  action text NOT NULL CHECK(action IN ('Issued','Revoked','RecoveryInvalidated')),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
