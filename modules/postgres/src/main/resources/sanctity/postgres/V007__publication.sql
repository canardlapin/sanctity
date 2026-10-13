CREATE TABLE processing_publication (
  publisher uuid NOT NULL REFERENCES principal(id),
  request_key text NOT NULL,
  intent_id text NOT NULL UNIQUE,
  run_id uuid NOT NULL REFERENCES processing_run(id),
  draft_id uuid NOT NULL UNIQUE REFERENCES draft(id),
  PRIMARY KEY (publisher, request_key)
);
