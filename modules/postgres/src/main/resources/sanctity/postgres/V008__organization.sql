ALTER TABLE principal ADD COLUMN label text NOT NULL DEFAULT 'Legacy principal',
 ADD COLUMN active boolean NOT NULL DEFAULT TRUE,
 ADD COLUMN generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 ADD COLUMN created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
 ADD CONSTRAINT principal_label_size CHECK(char_length(label) BETWEEN 1 AND 200);
-- statement
CREATE TABLE organization_lab (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 status text GENERATED ALWAYS AS ((payload #>> '{value,status}')) STORED NOT NULL,
 name text GENERATED ALWAYS AS ((payload #>> '{value,name}')) STORED NOT NULL,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 CHECK(status IN ('Active','Archived')),
 CHECK(char_length(name) BETWEEN 1 AND 200)
);
-- statement
CREATE TABLE organization_project (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 lab_id uuid GENERATED ALWAYS AS ((payload #>> '{value,lab}')::uuid) STORED NOT NULL,
 status text GENERATED ALWAYS AS ((payload #>> '{value,status}')) STORED NOT NULL,
 name text GENERATED ALWAYS AS ((payload #>> '{value,name}')) STORED NOT NULL,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 FOREIGN KEY(lab_id) REFERENCES organization_lab(id),
 CHECK(status IN ('Active','Archived')),
 CHECK(char_length(name) BETWEEN 1 AND 200)
);
-- statement
CREATE TABLE organization_membership (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 lab_id uuid GENERATED ALWAYS AS ((payload #>> '{value,lab}')::uuid) STORED NOT NULL,
 principal_id uuid GENERATED ALWAYS AS ((payload #>> '{value,principal}')::uuid) STORED NOT NULL,
 role text GENERATED ALWAYS AS ((payload #>> '{value,role}')) STORED NOT NULL,
 status text GENERATED ALWAYS AS ((payload #>> '{value,status}')) STORED NOT NULL,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 FOREIGN KEY(lab_id) REFERENCES organization_lab(id),
 FOREIGN KEY(principal_id) REFERENCES principal(id),
 UNIQUE(id,principal_id),
 CHECK(role IN ('Member','Admin')),
 CHECK(status IN ('Active','Ended'))
);
-- statement
CREATE TABLE organization_participation (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 project_id uuid GENERATED ALWAYS AS ((payload #>> '{value,project}')::uuid) STORED NOT NULL,
 principal_id uuid GENERATED ALWAYS AS ((payload #>> '{value,principal}')::uuid) STORED NOT NULL,
 role text GENERATED ALWAYS AS ((payload #>> '{value,role}')) STORED NOT NULL,
 status text GENERATED ALWAYS AS ((payload #>> '{value,status}')) STORED NOT NULL,
 basis_membership uuid GENERATED ALWAYS AS ((payload #>> '{value,basis,Membership,id}')::uuid) STORED,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 FOREIGN KEY(project_id) REFERENCES organization_project(id),
 FOREIGN KEY(principal_id) REFERENCES principal(id),
 FOREIGN KEY(basis_membership,principal_id) REFERENCES organization_membership(id,principal_id),
 CHECK(role IN ('Reader','Contributor','Steward')),
 CHECK(status IN ('Active','Ended')),
 CHECK((basis_membership IS NULL AND payload #> '{value,basis}' = '{"Independent":{}}'::jsonb) OR basis_membership IS NOT NULL)
);
-- statement
CREATE TABLE organization_placement (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 dataset_id uuid GENERATED ALWAYS AS ((payload #>> '{value,dataset}')::uuid) STORED NOT NULL,
 project_id uuid GENERATED ALWAYS AS ((payload #>> '{value,project}')::uuid) STORED NOT NULL,
 policy text GENERATED ALWAYS AS ((payload #>> '{value,policy}')) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 PRIMARY KEY(dataset_id),
 CHECK(generation>=0),
 FOREIGN KEY(dataset_id) REFERENCES dataset(id),
 FOREIGN KEY(project_id) REFERENCES organization_project(id),
 CHECK(policy IN ('ExplicitOnly','ProjectMembers'))
);
-- statement
CREATE TABLE organization_stewardship (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 dataset_id uuid GENERATED ALWAYS AS ((payload #>> '{value,dataset}')::uuid) STORED NOT NULL,
 principal_id uuid GENERATED ALWAYS AS ((payload #>> '{value,principal}')::uuid) STORED NOT NULL,
 status text GENERATED ALWAYS AS ((payload #>> '{value,status}')) STORED NOT NULL,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 FOREIGN KEY(dataset_id) REFERENCES organization_placement(dataset_id),
 FOREIGN KEY(principal_id) REFERENCES principal(id),
 CHECK(status IN ('Active','Ended'))
);
-- statement
CREATE TABLE organization_link (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 dataset_id uuid GENERATED ALWAYS AS ((payload #>> '{value,dataset}')::uuid) STORED NOT NULL,
 project_id uuid GENERATED ALWAYS AS ((payload #>> '{value,project}')::uuid) STORED NOT NULL,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 FOREIGN KEY(dataset_id) REFERENCES organization_placement(dataset_id),
 FOREIGN KEY(project_id) REFERENCES organization_project(id),
 UNIQUE(project_id,dataset_id)
);
-- statement
CREATE INDEX organization_project_lab ON organization_project(lab_id,status);
-- statement
CREATE INDEX organization_membership_current ON organization_membership(lab_id,principal_id,status);
-- statement
CREATE INDEX organization_participation_current ON organization_participation(project_id,principal_id,status);
-- statement
CREATE INDEX organization_participation_basis ON organization_participation(basis_membership);
-- statement
CREATE INDEX organization_placement_project ON organization_placement(project_id);
-- statement
CREATE UNIQUE INDEX organization_steward_current ON organization_stewardship(dataset_id,principal_id) WHERE status='Active';
-- statement
CREATE TABLE organization_policy (
 singleton boolean PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 initialized boolean NOT NULL DEFAULT FALSE,
 installation uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
 bootstrap_operator uuid REFERENCES principal(id),
 migration_digest text CHECK(migration_digest ~ '^[0-9a-f]{64}$'),
 migration_report jsonb,
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 writer_epoch bigint NOT NULL DEFAULT 0 CHECK(writer_epoch>=0),
 applied_hash text NOT NULL DEFAULT repeat('0',64) CHECK(applied_hash ~ '^[0-9a-f]{64}$'),
 durability_mode text NOT NULL DEFAULT 'local-engineering' CHECK(durability_mode='local-engineering'),
 CHECK(NOT initialized OR (bootstrap_operator IS NOT NULL AND migration_digest IS NOT NULL AND migration_report IS NOT NULL))
);
INSERT INTO organization_policy(singleton) VALUES(TRUE);
-- statement
CREATE TABLE organization_event (
 id uuid PRIMARY KEY,
 revision bigint NOT NULL UNIQUE CHECK(revision>=0),
 installation uuid NOT NULL REFERENCES organization_policy(installation),
 writer_epoch bigint NOT NULL CHECK(writer_epoch>=0),
 previous_hash text NOT NULL CHECK(previous_hash ~ '^[0-9a-f]{64}$'),
 payload_digest text NOT NULL CHECK(payload_digest ~ '^[0-9a-f]{64}$'),
 applied_hash text NOT NULL CHECK(applied_hash ~ '^[0-9a-f]{64}$'),
 actor uuid NOT NULL REFERENCES principal(id),
 scope text NOT NULL,
 request_key text NOT NULL CHECK(char_length(request_key) BETWEEN 1 AND 200),
 request jsonb,
 receipt jsonb NOT NULL,
 payload jsonb NOT NULL,
 recorded_at timestamptz NOT NULL,
 durability text NOT NULL DEFAULT 'LocalOnly' CHECK(durability='LocalOnly'),
 prepare_certificate jsonb CHECK(prepare_certificate IS NULL),
 commit_certificate jsonb CHECK(commit_certificate IS NULL),
 UNIQUE(actor,scope,request_key)
);
-- statement
ALTER TABLE access_grant
 ADD COLUMN issuer uuid REFERENCES principal(id),
 ADD COLUMN generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 ADD COLUMN expires_at timestamptz,
 ADD COLUMN basis text NOT NULL DEFAULT 'Independent' CHECK(basis='Independent');
-- statement
CREATE INDEX organization_grant_expiry ON access_grant(dataset_id,recipient,expires_at) WHERE revoked_at IS NULL;
