CREATE TABLE app_states (
    project_id UUID NOT NULL REFERENCES projects(id),
    environment VARCHAR(16) NOT NULL CHECK (environment IN ('PREVIEW', 'LIVE')),
    actor_id UUID NOT NULL REFERENCES guest_users(id),
    value_json TEXT NOT NULL CHECK (octet_length(value_json) <= 262144),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    state_schema_version INTEGER NOT NULL CHECK (state_schema_version = 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, environment, actor_id)
);
