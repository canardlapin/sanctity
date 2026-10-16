-- F02.3: the runtime may record governed organization commands that the application has
-- authorized under the policy guard (lab admins, project stewards, dataset stewards).
-- Lab creation, principal disablement, genesis, credential and recovery events stay
-- operator-only: they are not listed here, and the runtime holds no lab INSERT or
-- principal UPDATE privilege. Grant events keep their separate request-free policy.
CREATE POLICY policy_event_runtime_governed ON organization_event FOR INSERT
 WITH CHECK (payload->>'version'='policy-local-v2'
   AND payload->>'kind' IN ('RenameLab','ArchiveLab','CreateProject','RenameProject','ArchiveProject',
     'AddMember','EndMember','AddParticipant','EndParticipant','PlaceNewDataset','AddSteward','EndSteward','SetInheritance')
   AND request IS NOT NULL AND (durability='LocalOnly' OR
      (durability='Pending' AND prepare_certificate IS NOT NULL)));
