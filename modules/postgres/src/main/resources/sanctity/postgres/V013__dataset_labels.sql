-- F05.1: stewards curate a dataset's discovery label (ADR 002 Discover exposes a safe label, not metadata).
-- The label lives in the placement payload; only the governed event kind needs admitting.
DROP POLICY policy_event_runtime_governed ON organization_event;
-- statement
CREATE POLICY policy_event_runtime_governed ON organization_event FOR INSERT
 WITH CHECK (payload->>'version'='policy-local-v2'
   AND payload->>'kind' IN ('RenameLab','ArchiveLab','CreateProject','RenameProject','ArchiveProject',
     'AddMember','EndMember','AddParticipant','EndParticipant','PlaceNewDataset','AddSteward','EndSteward','SetInheritance',
     'TransferSteward','RequestMove','ApproveMove','CommitMove','WithdrawMove','RenameDataset')
   AND request IS NOT NULL AND (durability='LocalOnly' OR
      (durability='Pending' AND prepare_certificate IS NOT NULL)));
