CREATE TABLE preview_reports (
    id UUID PRIMARY KEY,
    ordinal BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    project_id UUID NOT NULL REFERENCES projects(id),
    version_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PASSED', 'FAILED')),
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (version_id, project_id) REFERENCES versions(id, project_id)
);
CREATE INDEX preview_reports_latest ON preview_reports(version_id, ordinal DESC);
