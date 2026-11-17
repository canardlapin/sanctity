ALTER TABLE import_operation
  ADD COLUMN base_release uuid,
  ADD FOREIGN KEY (base_release, dataset_id) REFERENCES release(id, dataset_id),
  ADD COLUMN replacements jsonb NOT NULL DEFAULT '{"version":1,"value":[]}'::jsonb;
