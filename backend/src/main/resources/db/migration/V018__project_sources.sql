-- Additive rollout. Roll code back by disabling atoms.source; retain all evidence and old tables.
CREATE TABLE project_sources (
    project_id uuid PRIMARY KEY REFERENCES projects(id),
    format varchar(24) NOT NULL DEFAULT 'full-project-v1' CHECK (format='full-project-v1'),
    current_source_version_id uuid,
    source_revision bigint NOT NULL DEFAULT 0 CHECK (source_revision >= 0),
    status varchar(24) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','NEEDS_RECONCILIATION')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((current_source_version_id IS NULL) = (source_revision = 0))
);
CREATE TABLE source_versions (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project_sources(project_id),
    version_no bigint NOT NULL CHECK (version_no > 0),
    commit_id varchar(40) NOT NULL CHECK (commit_id ~ '^[a-f0-9]{40}$'),
    tree_sha256 varchar(64) NOT NULL CHECK (tree_sha256 ~ '^[a-f0-9]{64}$'),
    file_count integer NOT NULL CHECK (file_count > 0),
    tree_bytes bigint NOT NULL CHECK (tree_bytes >= 0),
    based_on_id uuid,
    restored_from_id uuid,
    legacy_version_id uuid,
    template_id varchar(64),
    template_digest varchar(64) CHECK (template_digest ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, version_no),
    UNIQUE (id, project_id),
    FOREIGN KEY (based_on_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY (restored_from_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY (legacy_version_id,project_id) REFERENCES versions(id,project_id),
    CHECK ((template_id IS NULL) = (template_digest IS NULL)),
    CHECK (based_on_id IS DISTINCT FROM id AND restored_from_id IS DISTINCT FROM id)
);
ALTER TABLE project_sources ADD CONSTRAINT project_sources_current_version_fk
    FOREIGN KEY (current_source_version_id,project_id) REFERENCES source_versions(id,project_id);
CREATE FUNCTION reject_source_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Source versions are immutable' USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER source_versions_immutable BEFORE UPDATE OR DELETE ON source_versions
    FOR EACH ROW EXECUTE FUNCTION reject_source_version_mutation();

CREATE TABLE source_operations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project_sources(project_id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('INITIALIZE','IMPORT','ACCEPT','RESTORE')),
    idempotency_sha256 varchar(64) NOT NULL CHECK (idempotency_sha256 ~ '^[a-f0-9]{64}$'),
    request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    expected_version_id uuid,
    expected_revision bigint NOT NULL CHECK (expected_revision >= 0),
    candidate_version_id uuid NOT NULL UNIQUE,
    target_version_id uuid,
    legacy_version_id uuid,
    template_id varchar(64),
    template_digest varchar(64) CHECK (template_digest ~ '^[a-f0-9]{64}$'),
    candidate_sha256 varchar(64) CHECK (candidate_sha256 ~ '^[a-f0-9]{64}$'),
    status varchar(24) NOT NULL DEFAULT 'PREPARING'
        CHECK (status IN ('PREPARING','CANDIDATE_READY','COMMITTED','FAILED','CANCELLED','NEEDS_RECONCILIATION')),
    cancel_requested boolean NOT NULL DEFAULT false,
    result_version_id uuid,
    error_code varchar(64) CHECK (error_code ~ '^SOURCE_[A-Z_]+$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (project_id,idempotency_sha256),
    FOREIGN KEY (expected_version_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY (target_version_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY (legacy_version_id,project_id) REFERENCES versions(id,project_id),
    FOREIGN KEY (result_version_id,project_id) REFERENCES source_versions(id,project_id),
    CHECK ((expected_version_id IS NULL) = (expected_revision = 0)),
    CHECK ((template_id IS NULL) = (template_digest IS NULL)),
    CHECK ((status='COMMITTED') = (result_version_id IS NOT NULL)),
    CHECK (result_version_id IS NULL OR result_version_id=candidate_version_id),
    CHECK (status NOT IN ('CANDIDATE_READY','COMMITTED') OR candidate_sha256 IS NOT NULL),
    CHECK ((status IN ('COMMITTED','FAILED','CANCELLED')) = (finished_at IS NOT NULL)),
    CHECK (status <> 'COMMITTED' OR (error_code IS NULL AND NOT cancel_requested))
);
CREATE INDEX source_operations_pending ON source_operations(created_at,id)
    WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION');
CREATE INDEX source_versions_project_order ON source_versions(project_id,version_no DESC);

CREATE FUNCTION enforce_source_operation_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.project_id,NEW.owner_id,NEW.kind,NEW.idempotency_sha256,NEW.request_sha256,
           NEW.expected_version_id,NEW.expected_revision,NEW.candidate_version_id,NEW.target_version_id,
           NEW.legacy_version_id,NEW.template_id,NEW.template_digest,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.owner_id,OLD.kind,OLD.idempotency_sha256,OLD.request_sha256,
           OLD.expected_version_id,OLD.expected_revision,OLD.candidate_version_id,OLD.target_version_id,
           OLD.legacy_version_id,OLD.template_id,OLD.template_digest,OLD.created_at)
        OR (OLD.candidate_sha256 IS NOT NULL AND NEW.candidate_sha256 IS DISTINCT FROM OLD.candidate_sha256)
        OR (OLD.cancel_requested AND NOT NEW.cancel_requested)
        OR (OLD.status IN ('COMMITTED','FAILED','CANCELLED') AND NEW IS DISTINCT FROM OLD)
        OR (NEW.status <> OLD.status AND NOT (
            (OLD.status='PREPARING' AND NEW.status IN ('CANDIDATE_READY','FAILED','CANCELLED','NEEDS_RECONCILIATION')) OR
            (OLD.status='CANDIDATE_READY' AND NEW.status IN ('COMMITTED','FAILED','CANCELLED','NEEDS_RECONCILIATION')) OR
            (OLD.status='NEEDS_RECONCILIATION' AND NEW.status IN ('COMMITTED','FAILED','CANCELLED')))) THEN
        RAISE EXCEPTION 'Invalid source operation transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_operations_transition BEFORE UPDATE ON source_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_source_operation_transition();
