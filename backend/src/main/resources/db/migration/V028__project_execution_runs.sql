-- Additive metadata. These rows do not authorize launching, migrating, or reconstructing a Process handle.
ALTER TABLE execution_builds ADD CONSTRAINT execution_build_project_identity UNIQUE (id,project_id);

CREATE TABLE execution_migration_reviews (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    binding_id uuid NOT NULL,
    build_id uuid NOT NULL,
    approved_by uuid NOT NULL REFERENCES guest_users(id),
    jar_sha256 varchar(64) NOT NULL CHECK (jar_sha256 ~ '^[a-f0-9]{64}$'),
    history_sha256 varchar(64) NOT NULL CHECK (history_sha256 ~ '^[a-f0-9]{64}$'),
    target_sha256 varchar(64) NOT NULL CHECK (target_sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (binding_id,project_id) REFERENCES execution_database_bindings(id,project_id),
    FOREIGN KEY (build_id,project_id) REFERENCES execution_builds(id,project_id),
    UNIQUE (id,project_id,build_id,binding_id),
    UNIQUE (project_id,binding_id,build_id,history_sha256,target_sha256)
);

CREATE FUNCTION guard_execution_migration_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Migration review evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF NOT EXISTS (SELECT FROM execution_builds b JOIN projects p ON p.id=b.project_id
        WHERE b.id=NEW.build_id AND b.project_id=NEW.project_id AND b.status='SUCCEEDED'
            AND b.jar_sha256=NEW.jar_sha256 AND p.owner_id=NEW.approved_by AND NOT p.archived) THEN
        RAISE EXCEPTION 'Migration review source does not match' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_migration_review_guard BEFORE INSERT OR UPDATE OR DELETE ON execution_migration_reviews
    FOR EACH ROW EXECUTE FUNCTION guard_execution_migration_review();

CREATE TABLE execution_runs (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    build_id uuid NOT NULL,
    source_version_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    migration_review_id uuid,
    tree_sha256 varchar(64) NOT NULL CHECK (tree_sha256 ~ '^[a-f0-9]{64}$'),
    jar_sha256 varchar(64) NOT NULL CHECK (jar_sha256 ~ '^[a-f0-9]{64}$'),
    manifest_sha256 varchar(64) NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    binding_identity_sha256 varchar(64) NOT NULL CHECK (binding_identity_sha256 ~ '^[a-f0-9]{64}$'),
    idempotency_key_sha256 varchar(64) NOT NULL CHECK (idempotency_key_sha256 ~ '^[a-f0-9]{64}$'),
    request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    status varchar(24) NOT NULL DEFAULT 'STARTING' CHECK (status IN
        ('STARTING','READY','STOPPING','STOPPED','FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION')),
    stop_requested boolean NOT NULL DEFAULT false,
    execution_id uuid UNIQUE,
    execution_pending boolean NOT NULL DEFAULT false,
    stopped_sha256 varchar(64) CHECK (stopped_sha256 ~ '^[a-f0-9]{64}$'),
    error_code varchar(64) CHECK (error_code ~ '^RUN_[A-Z_]+$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ready_at timestamptz,
    finished_at timestamptz,
    FOREIGN KEY (build_id,project_id) REFERENCES execution_builds(id,project_id),
    FOREIGN KEY (source_version_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY (binding_id,project_id) REFERENCES execution_database_bindings(id,project_id),
    FOREIGN KEY (migration_review_id,project_id,build_id,binding_id) REFERENCES execution_migration_reviews(id,project_id,build_id,binding_id),
    UNIQUE (project_id,idempotency_key_sha256),
    CHECK ((status IN ('STOPPED','FAILED','CANCELLED','TIMED_OUT')) = (finished_at IS NOT NULL)),
    CHECK (execution_pending = (execution_id IS NOT NULL AND stopped_sha256 IS NULL)),
    CHECK (stopped_sha256 IS NULL OR execution_id IS NOT NULL),
    CHECK (finished_at IS NULL OR NOT execution_pending),
    CHECK (status<>'READY' OR (execution_pending AND ready_at IS NOT NULL AND NOT stop_requested AND error_code IS NULL))
);
CREATE UNIQUE INDEX execution_run_active_project ON execution_runs(project_id) WHERE finished_at IS NULL;
CREATE INDEX execution_run_project_order ON execution_runs(project_id,created_at DESC,id DESC);

CREATE FUNCTION guard_execution_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Run evidence is retained' USING ERRCODE='55000';
    ELSIF TG_OP='INSERT' THEN
        IF NEW.status<>'STARTING' OR NEW.execution_id IS NOT NULL OR NEW.execution_pending OR NEW.stop_requested
            OR NEW.stopped_sha256 IS NOT NULL OR NEW.ready_at IS NOT NULL OR NEW.finished_at IS NOT NULL OR NEW.error_code IS NOT NULL
            OR NOT EXISTS (SELECT FROM execution_builds b JOIN projects p ON p.id=b.project_id
                JOIN execution_database_bindings d ON d.project_id=b.project_id
                WHERE b.id=NEW.build_id AND b.project_id=NEW.project_id AND b.status='SUCCEEDED'
                    AND b.source_version_id=NEW.source_version_id AND b.tree_sha256=NEW.tree_sha256
                    AND b.jar_sha256=NEW.jar_sha256 AND b.manifest_sha256=NEW.manifest_sha256
                    AND d.id=NEW.binding_id AND d.identity_sha256=NEW.binding_identity_sha256
                    AND p.owner_id=NEW.owner_id AND NOT p.archived) THEN
            RAISE EXCEPTION 'Invalid run identity' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.project_id,NEW.owner_id,NEW.build_id,NEW.source_version_id,NEW.binding_id,NEW.migration_review_id,
        NEW.tree_sha256,NEW.jar_sha256,NEW.manifest_sha256,NEW.binding_identity_sha256,NEW.idempotency_key_sha256,NEW.request_sha256,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.owner_id,OLD.build_id,OLD.source_version_id,OLD.binding_id,OLD.migration_review_id,
        OLD.tree_sha256,OLD.jar_sha256,OLD.manifest_sha256,OLD.binding_identity_sha256,OLD.idempotency_key_sha256,OLD.request_sha256,OLD.created_at)
        OR (OLD.finished_at IS NOT NULL AND NEW IS DISTINCT FROM OLD)
        OR (OLD.stop_requested AND NOT NEW.stop_requested)
        OR (OLD.execution_id IS NOT NULL AND NEW.execution_id IS DISTINCT FROM OLD.execution_id)
        OR (OLD.stopped_sha256 IS NOT NULL AND NEW.stopped_sha256 IS DISTINCT FROM OLD.stopped_sha256)
        OR (OLD.ready_at IS NOT NULL AND NEW.ready_at IS DISTINCT FROM OLD.ready_at)
        OR (OLD.execution_pending AND NEW.finished_at IS NOT NULL)
        OR (OLD.execution_id IS NOT NULL AND NOT OLD.execution_pending AND NEW.execution_pending)
        OR (OLD.execution_id IS NULL AND NEW.execution_id IS NOT NULL
            AND (OLD.status<>'STARTING' OR NEW.status<>'STARTING' OR NOT NEW.execution_pending OR OLD.stop_requested OR NEW.stop_requested))
        OR (OLD.ready_at IS NULL AND NEW.ready_at IS NOT NULL AND NOT (OLD.status='STARTING' AND NEW.status='READY')) THEN
        RAISE EXCEPTION 'Immutable run identity or unsettled process' USING ERRCODE='55000';
    END IF;
    IF NEW.status<>OLD.status AND NOT (
        (OLD.status='STARTING' AND NEW.status IN ('READY','STOPPING','FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION'))
        OR (OLD.status='READY' AND NEW.status IN ('STOPPING','FAILED','NEEDS_RECONCILIATION'))
        OR (OLD.status='STOPPING' AND NEW.status IN ('STOPPED','FAILED','NEEDS_RECONCILIATION'))
        OR (OLD.status='NEEDS_RECONCILIATION' AND NOT OLD.execution_pending AND NEW.status IN ('STOPPED','FAILED','CANCELLED','TIMED_OUT'))
    ) THEN
        RAISE EXCEPTION 'Invalid run transition' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_run_guard BEFORE INSERT OR UPDATE OR DELETE ON execution_runs
    FOR EACH ROW EXECUTE FUNCTION guard_execution_run();
