-- Permanent project-to-installation binding: stopping never gives another project its database.
CREATE TABLE runtime_installations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL UNIQUE REFERENCES projects(id),
    config_fingerprint varchar(64) NOT NULL CHECK (config_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, project_id)
);
ALTER TABLE engineering_builds ADD CONSTRAINT engineering_builds_project_unique UNIQUE (id, project_id);
CREATE TABLE app_instances (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    build_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('QUEUED','STARTING','RUNNING','STOPPING','STOPPED','FAILED','INTERRUPTED')),
    execution_pending boolean NOT NULL DEFAULT false,
    error_code varchar(48),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    FOREIGN KEY(project_id,owner_id) REFERENCES projects(id,owner_id),
    FOREIGN KEY(build_id,project_id) REFERENCES engineering_builds(id,project_id),
    FOREIGN KEY(installation_id,project_id) REFERENCES runtime_installations(id,project_id),
    CHECK ((status IN ('QUEUED','STARTING','RUNNING','STOPPING'))=(finished_at IS NULL)),
    CHECK (status NOT IN ('STARTING','RUNNING','STOPPING') OR execution_pending),
    CHECK (status NOT IN ('STOPPED','FAILED') OR NOT execution_pending)
);
CREATE UNIQUE INDEX app_instances_active_project ON app_instances(project_id)
    WHERE execution_pending OR status IN ('QUEUED','STARTING','RUNNING','STOPPING');
CREATE INDEX app_instances_history ON app_instances(project_id,created_at DESC,id DESC);
