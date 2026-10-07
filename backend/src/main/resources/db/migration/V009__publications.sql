CREATE TABLE publications (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    slug VARCHAR(32) NOT NULL UNIQUE CHECK (slug ~ '^[0-9a-f]{32}$'),
    version_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PUBLISHED', 'UNPUBLISHED')),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (version_id, project_id) REFERENCES versions(id, project_id)
);
