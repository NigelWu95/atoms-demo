CREATE TABLE runs (
    id uuid PRIMARY KEY,
    ordinal bigserial UNIQUE NOT NULL,
    project_id uuid NOT NULL REFERENCES projects(id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    prompt text NOT NULL CHECK (char_length(prompt) BETWEEN 1 AND 8000),
    intent text NOT NULL CHECK (intent IN ('CREATE', 'MODIFY')),
    base_version_id uuid,
    expected_project_revision bigint NOT NULL CHECK (expected_project_revision >= 0),
    status text NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    stage text NOT NULL DEFAULT 'QUEUED',
    result_version_id uuid,
    error_code text,
    error_message text,
    last_event_seq bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(project_id, id),
    FOREIGN KEY (project_id, base_version_id) REFERENCES versions(project_id, id),
    FOREIGN KEY (project_id, result_version_id) REFERENCES versions(project_id, id)
);
CREATE UNIQUE INDEX runs_one_active ON runs(project_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX runs_project_history ON runs(project_id, ordinal DESC);
ALTER TABLE projects ADD COLUMN active_run_id uuid;
ALTER TABLE projects ADD CONSTRAINT projects_active_run_fk FOREIGN KEY (id, active_run_id) REFERENCES runs(project_id, id);

CREATE TABLE messages (
    id uuid PRIMARY KEY,
    ordinal bigserial UNIQUE NOT NULL,
    project_id uuid NOT NULL REFERENCES projects(id),
    run_id uuid NOT NULL,
    role text NOT NULL CHECK (role IN ('USER', 'PLANNER', 'BUILDER', 'SYSTEM')),
    content text NOT NULL CHECK (octet_length(content) <= 32768),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, run_id) REFERENCES runs(project_id, id)
);
CREATE INDEX messages_project_history ON messages(project_id, ordinal DESC);

CREATE TABLE run_events (
    run_id uuid NOT NULL REFERENCES runs(id),
    seq bigint NOT NULL,
    type text NOT NULL,
    data_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);
