-- Additive current-authority storage. Identity/sponsorship and lifecycle administration
-- remain operator-owned; later F04/O04 adapters must use the policy transaction boundary.
ALTER TABLE principal ADD COLUMN authority_eligibility jsonb NOT NULL DEFAULT '{"version":1,"value":{"Local":{}}}'::jsonb;
-- statement
ALTER TABLE dataset ADD COLUMN authority_lifecycle text NOT NULL DEFAULT 'Available'
 CHECK(authority_lifecycle IN ('Available','Quarantined','Withdrawn','Unavailable'));
-- statement
ALTER TABLE release ADD COLUMN authority_lifecycle text NOT NULL DEFAULT 'Available'
 CHECK(authority_lifecycle IN ('Available','Quarantined','Withdrawn','Unavailable'));
-- statement
ALTER TABLE access_grant DROP CONSTRAINT access_grant_actions_check,
 DROP CONSTRAINT access_grant_check, DROP CONSTRAINT access_grant_basis_check,
 ADD CONSTRAINT access_grant_actions_check CHECK(cardinality(actions)>0 AND
 actions <@ ARRAY['Discover','ReadMetadata','ReadContent','ReadEvidence','Propose','Accept']::text[]),
 ADD CONSTRAINT access_grant_fixed_actions CHECK(release_id IS NULL OR
 actions <@ ARRAY['Discover','ReadMetadata','ReadContent','ReadEvidence']::text[]),
 ADD CONSTRAINT access_grant_basis_check CHECK(basis IN ('Independent','Derived')),
 ADD COLUMN basis_payload jsonb NOT NULL DEFAULT '{"version":1,"value":{"Independent":{}}}'::jsonb,
 ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
-- statement
-- The old schema has no grant creation clock. Use its first audit event if present;
-- otherwise retain the migration clock. Do not alter historical action/scope/revocation.
UPDATE access_grant g SET created_at=COALESCE((SELECT min(recorded_at) FROM policy_audit a
 WHERE a.grant_id=g.id AND a.action='Granted'),created_at);
-- statement
-- The runtime may append grant events, never operator organization/credential events.
-- Schema owners retain the explicit operator path; historical rows remain immutable.
ALTER TABLE organization_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_event_read ON organization_event FOR SELECT USING (true);
CREATE POLICY policy_event_runtime_grant ON organization_event FOR INSERT
 WITH CHECK (payload->>'version'='policy-local-v2' AND payload->>'kind' IN ('GrantIssued','GrantRevoked')
   AND request IS NULL AND durability='LocalOnly');
