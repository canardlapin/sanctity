CREATE TABLE scientific_artifact (
 interpretation_id text NOT NULL REFERENCES interpretation(id),
 path text NOT NULL,
 participant text NOT NULL,
 session text,
 suffix text NOT NULL,
 extension text NOT NULL,
 PRIMARY KEY(interpretation_id,path),
 FOREIGN KEY(interpretation_id,path) REFERENCES metadata_artifact(interpretation_id,path)
);
CREATE INDEX scientific_subject_session ON scientific_artifact(interpretation_id,participant,session,suffix);
-- statement
CREATE TABLE scientific_entity (
 interpretation_id text NOT NULL, path text NOT NULL, name text NOT NULL, value text NOT NULL,
 PRIMARY KEY(interpretation_id,path,name),
 FOREIGN KEY(interpretation_id,path) REFERENCES scientific_artifact(interpretation_id,path)
);
CREATE INDEX scientific_entity_lookup ON scientific_entity(interpretation_id,name,value,path);
-- statement
CREATE TABLE scientific_value (
 interpretation_id text NOT NULL, path text NOT NULL, name text NOT NULL, text_value text, number_value numeric,
 PRIMARY KEY(interpretation_id,path,name),
 FOREIGN KEY(interpretation_id,path) REFERENCES metadata_artifact(interpretation_id,path)
);
CREATE INDEX scientific_number_lookup ON scientific_value(interpretation_id,name,number_value,path);
-- statement
CREATE TABLE participant_value (
 interpretation_id text NOT NULL REFERENCES interpretation(id), participant text NOT NULL, name text NOT NULL,
 lexical_value text NOT NULL, number_value numeric,
 PRIMARY KEY(interpretation_id,participant,name)
);
-- statement
CREATE TABLE frozen_selection (
 id text NOT NULL, owner uuid NOT NULL REFERENCES principal(id), payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(id,owner)
);
