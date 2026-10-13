CREATE TABLE scientific_observation (
  interpretation_id text NOT NULL REFERENCES interpretation(id),
  ordinal integer NOT NULL,
  source_key text NOT NULL,
  metric_name text NOT NULL,
  metric_version text NOT NULL,
  unit text,
  value numeric NOT NULL,
  payload jsonb NOT NULL,
  PRIMARY KEY (interpretation_id, ordinal)
);
-- statement
CREATE INDEX observation_lookup ON scientific_observation(source_key, metric_name, metric_version, value);
-- statement
CREATE TABLE processing_run (
  id uuid PRIMARY KEY,
  destination uuid NOT NULL REFERENCES dataset(id),
  owner uuid NOT NULL REFERENCES principal(id),
  request_key text NOT NULL,
  payload jsonb NOT NULL,
  UNIQUE(destination, owner, request_key)
);
-- statement
CREATE TABLE derivative_submission (
  destination uuid NOT NULL REFERENCES dataset(id),
  invocation uuid NOT NULL,
  draft_id uuid NOT NULL REFERENCES draft(id),
  PRIMARY KEY(destination, invocation)
);
