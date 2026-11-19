-- What an accepted release says about itself, taken from its own descriptor (SEE2).
-- A projection of the release: written in the transaction that accepts it, and rebuildable from retained bytes.
CREATE TABLE release_catalog (
  release_id uuid PRIMARY KEY,
  dataset_id uuid NOT NULL,
  record jsonb NOT NULL,
  FOREIGN KEY (release_id, dataset_id) REFERENCES release(id, dataset_id)
)
-- statement
CREATE INDEX release_catalog_dataset ON release_catalog(dataset_id)
