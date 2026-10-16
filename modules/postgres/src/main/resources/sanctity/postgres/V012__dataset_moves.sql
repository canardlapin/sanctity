-- F02.3: governed dataset move requests and atomic stewardship transfer. A move request
-- freezes its destination, policy and the generations its access-impact preview used;
-- every required steward approves the same request before an atomic commit.
CREATE TABLE organization_move (
 payload jsonb NOT NULL CHECK(jsonb_exists(payload,'version') AND payload->>'version'='1' AND jsonb_typeof(payload->'value')='object'),
 id uuid GENERATED ALWAYS AS ((payload #>> '{value,id}')::uuid) STORED NOT NULL,
 generation bigint GENERATED ALWAYS AS ((payload #>> '{value,generation}')::bigint) STORED NOT NULL,
 dataset_id uuid GENERATED ALWAYS AS ((payload #>> '{value,dataset}')::uuid) STORED NOT NULL,
 source_project uuid GENERATED ALWAYS AS ((payload #>> '{value,source}')::uuid) STORED NOT NULL,
 destination_project uuid GENERATED ALWAYS AS ((payload #>> '{value,destination}')::uuid) STORED NOT NULL,
 status text GENERATED ALWAYS AS ((payload #>> '{value,status}')) STORED NOT NULL,
 PRIMARY KEY(id),
 CHECK(generation>=0),
 CHECK(source_project<>destination_project),
 FOREIGN KEY(dataset_id) REFERENCES organization_placement(dataset_id),
 FOREIGN KEY(source_project) REFERENCES organization_project(id),
 FOREIGN KEY(destination_project) REFERENCES organization_project(id),
 CHECK(status IN ('Open','Committed','Withdrawn'))
);
-- statement
CREATE UNIQUE INDEX organization_move_open ON organization_move(dataset_id) WHERE status='Open';
-- statement
DROP POLICY policy_event_runtime_governed ON organization_event;
-- statement
CREATE POLICY policy_event_runtime_governed ON organization_event FOR INSERT
 WITH CHECK (payload->>'version'='policy-local-v2'
   AND payload->>'kind' IN ('RenameLab','ArchiveLab','CreateProject','RenameProject','ArchiveProject',
     'AddMember','EndMember','AddParticipant','EndParticipant','PlaceNewDataset','AddSteward','EndSteward','SetInheritance',
     'TransferSteward','RequestMove','ApproveMove','CommitMove','WithdrawMove')
   AND request IS NOT NULL AND (durability='LocalOnly' OR
      (durability='Pending' AND prepare_certificate IS NOT NULL)));
