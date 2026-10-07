CREATE TABLE versions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    version_no BIGINT NOT NULL CHECK (version_no > 0),
    source_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    based_on_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, version_no),
    UNIQUE (id, project_id),
    FOREIGN KEY (based_on_id, project_id) REFERENCES versions(id, project_id)
);

ALTER TABLE projects ADD COLUMN current_version_id UUID;
ALTER TABLE projects ADD CONSTRAINT projects_current_version_fk
    FOREIGN KEY (current_version_id, id) REFERENCES versions(id, project_id);

-- A restore creates a new row. Editing/deleting history is not a supported operation.
CREATE FUNCTION reject_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Artifact versions are immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER versions_immutable BEFORE UPDATE OR DELETE ON versions
    FOR EACH ROW EXECUTE FUNCTION reject_version_mutation();
