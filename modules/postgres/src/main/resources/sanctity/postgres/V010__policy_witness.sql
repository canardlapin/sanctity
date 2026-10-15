-- Preserve every historical event and certificate column. New acknowledgements are
-- append-only and do not rewrite LocalOnly receipts from before enrollment.
ALTER TABLE organization_policy DROP CONSTRAINT organization_policy_durability_mode_check,
 ADD CONSTRAINT organization_policy_durability_mode_check
 CHECK(durability_mode IN ('local-engineering','enrolling','independent'));
-- statement
ALTER TABLE organization_event DROP CONSTRAINT organization_event_durability_check,
 DROP CONSTRAINT organization_event_prepare_certificate_check,
 ADD CONSTRAINT organization_event_durability_check CHECK(durability IN ('LocalOnly','Pending')),
 ADD CONSTRAINT organization_event_prepare_certificate_check
 CHECK((durability='LocalOnly' AND prepare_certificate IS NULL) OR
       (durability='Pending' AND prepare_certificate IS NOT NULL)),
 ADD CONSTRAINT organization_event_durability_identity UNIQUE(id,durability);
-- statement
CREATE TABLE policy_acknowledgement (
 event_id uuid PRIMARY KEY,
 event_durability text NOT NULL DEFAULT 'Pending' CHECK(event_durability='Pending'),
 certificate jsonb NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(event_id,event_durability) REFERENCES organization_event(id,durability)
);
-- statement
CREATE TABLE policy_enrollment (
 singleton boolean PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 command jsonb NOT NULL,
 certificate jsonb,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- statement
DROP POLICY policy_event_runtime_grant ON organization_event;
CREATE POLICY policy_event_runtime_grant ON organization_event FOR INSERT
 WITH CHECK (payload->>'version'='policy-local-v2' AND payload->>'kind' IN ('GrantIssued','GrantRevoked')
   AND request IS NULL AND (durability='LocalOnly' OR
      (durability='Pending' AND prepare_certificate IS NOT NULL)));
-- statement
-- Existing runtime SQL identities are retired during enrollment. Reusing their
-- credentials could admit an old binary that cannot enforce witness permits.
CREATE TABLE policy_retired_runtime (
 role_name text PRIMARY KEY
);
