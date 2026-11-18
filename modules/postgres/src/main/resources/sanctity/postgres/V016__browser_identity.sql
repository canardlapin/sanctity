CREATE TABLE identity_binding (
  id uuid PRIMARY KEY,
  issuer text NOT NULL,
  subject text NOT NULL,
  principal_id uuid NOT NULL REFERENCES principal(id),
  display_name text NOT NULL,
  UNIQUE (issuer, subject),
  UNIQUE (issuer, principal_id)
)
-- statement
CREATE TABLE browser_session (
  id uuid PRIMARY KEY,
  binding_id uuid NOT NULL REFERENCES identity_binding(id) ON DELETE CASCADE,
  token_digest text UNIQUE NOT NULL,
  expires_at timestamptz NOT NULL
)
-- statement
CREATE INDEX browser_session_expiry ON browser_session(expires_at)
