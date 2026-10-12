-- Additive only: disabling the feature rolls code back without deleting evidence.
ALTER TABLE projects ADD CONSTRAINT projects_build_owner_unique UNIQUE (id, owner_id);
CREATE TABLE engineering_builds (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    version_id uuid NOT NULL,
    source_content_hash varchar(64) NOT NULL CHECK (source_content_hash ~ '^[a-f0-9]{64}$'),
    template_digest varchar(64) NOT NULL CHECK (template_digest ~ '^[a-f0-9]{64}$'),
    build_policy_version varchar(32) NOT NULL DEFAULT 'native-v1' CHECK (build_policy_version = 'native-v1'),
    workspace_key uuid NOT NULL CHECK (workspace_key = id),
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED','BUILDING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')),
    stage varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK (stage IN ('QUEUED','PREPARING','INSTALLING','CHECKING_FRONTEND','BUILDING_FRONTEND','CHECKING_BACKEND','VERIFYING','COMPLETE')),
    bundle_id varchar(64) CHECK (bundle_id ~ '^[a-f0-9]{64}$'),
    archive_sha256 varchar(64) CHECK (archive_sha256 ~ '^[a-f0-9]{64}$'),
    loaded_runtime_id varchar(64) CHECK (loaded_runtime_id ~ '^[a-f0-9]{64}$'),
    jar_sha256 varchar(64) CHECK (jar_sha256 ~ '^[a-f0-9]{64}$'),
    error_code varchar(40) CHECK (error_code IN ('BUILD_FAILED','BUILD_TIMEOUT','BUILD_INPUT_CHANGED','BUILD_INTERRUPTED','BUILD_CANCELLED','BUILD_CAPACITY_EXCEEDED','BUILD_ENVIRONMENT_NOT_READY','INVALID_ENGINEERING_BUNDLE','BUILD_ARTIFACT_UNAVAILABLE')),
    execution_id uuid,
    execution_pending boolean NOT NULL DEFAULT false,
    last_event_seq integer NOT NULL DEFAULT 0 CHECK (last_event_seq BETWEEN 0 AND 1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    FOREIGN KEY (project_id, owner_id) REFERENCES projects(id, owner_id),
    FOREIGN KEY (version_id, project_id) REFERENCES versions(id, project_id),
    CHECK ((status IN ('QUEUED','BUILDING','CANCELLING')) = (finished_at IS NULL)),
    CHECK (NOT execution_pending OR execution_id IS NOT NULL),
    CHECK (status NOT IN ('BUILDING','CANCELLING') OR (started_at IS NOT NULL AND execution_pending)),
    CHECK (status <> 'SUCCEEDED' OR (bundle_id IS NOT NULL AND archive_sha256 IS NOT NULL
        AND loaded_runtime_id IS NOT NULL AND jar_sha256 IS NOT NULL AND error_code IS NULL AND NOT execution_pending AND stage='COMPLETE')),
    CHECK ((bundle_id IS NULL) = (archive_sha256 IS NULL))
);
CREATE UNIQUE INDEX engineering_builds_active_project ON engineering_builds(project_id)
    WHERE status IN ('QUEUED','BUILDING','CANCELLING');
-- The execution fence survives interrupted status and platform restarts.
CREATE UNIQUE INDEX engineering_builds_execution_slot ON engineering_builds((true)) WHERE execution_pending;
CREATE INDEX engineering_builds_project_created ON engineering_builds(project_id, created_at DESC, id DESC);
CREATE TABLE engineering_build_events (
    build_id uuid NOT NULL REFERENCES engineering_builds(id),
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 1000),
    type varchar(24) NOT NULL CHECK (type IN ('build.queued','build.stage','build.cancelling','build.completed')),
    status varchar(16) NOT NULL,
    stage varchar(24) NOT NULL,
    error_code varchar(40),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (build_id, seq)
);
CREATE FUNCTION enforce_build_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.owner_id,NEW.project_id,NEW.version_id,NEW.source_content_hash,NEW.template_digest,NEW.build_policy_version,NEW.workspace_key,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.owner_id,OLD.project_id,OLD.version_id,OLD.source_content_hash,OLD.template_digest,OLD.build_policy_version,OLD.workspace_key,OLD.created_at)
        OR (OLD.bundle_id IS NOT NULL AND ROW(NEW.bundle_id,NEW.archive_sha256) IS DISTINCT FROM ROW(OLD.bundle_id,OLD.archive_sha256))
        OR (OLD.execution_id IS NOT NULL AND NEW.execution_id IS DISTINCT FROM OLD.execution_id)
        OR NEW.last_event_seq < OLD.last_event_seq
        OR (NEW.status <> OLD.status AND NOT (
            (OLD.status='QUEUED' AND NEW.status IN ('BUILDING','CANCELLED','INTERRUPTED')) OR
            (OLD.status='BUILDING' AND NEW.status IN ('CANCELLING','SUCCEEDED','FAILED','INTERRUPTED')) OR
            (OLD.status='CANCELLING' AND NEW.status IN ('CANCELLED','INTERRUPTED')))) THEN
        RAISE EXCEPTION 'Invalid immutable build transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER engineering_builds_transition BEFORE UPDATE ON engineering_builds
    FOR EACH ROW EXECUTE FUNCTION enforce_build_transition();
