-- Separate lock-only intent. Source contents stay in Git; only immutable identities and digests are persisted.
-- Disable execution to roll back; do not delete evidence or release unknown source edits.
CREATE TABLE dependency_preparations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    edit_lease_id uuid NOT NULL UNIQUE,
    base_version_id uuid NOT NULL,
    base_revision bigint NOT NULL CHECK(base_revision BETWEEN 1 AND 9007199254740991),
    tree_sha256 varchar(64) NOT NULL CHECK(tree_sha256 ~ '^[a-f0-9]{64}$'),
    manifest_sha256 varchar(64) NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
    base_lock_sha256 varchar(64) CHECK(base_lock_sha256 ~ '^[a-f0-9]{64}$'),
    policy_sha256 varchar(64) NOT NULL CHECK(policy_sha256 ~ '^[a-f0-9]{64}$'),
    idempotency_key_sha256 varchar(64) NOT NULL CHECK(idempotency_key_sha256 ~ '^[a-f0-9]{64}$'),
    request_sha256 varchar(64) NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
    status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK(status IN
        ('QUEUED','PREPARING','RESOLVING','ACCEPTING','SOURCE_SAVED','NO_CHANGES',
         'FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION')),
    queue_capacity integer NOT NULL CHECK(queue_capacity BETWEEN 1 AND 8),
    worker_capacity integer NOT NULL CHECK(worker_capacity BETWEEN 1 AND 4),
    queue_slot integer CHECK(queue_slot BETWEEN 1 AND queue_capacity),
    worker_slot integer CHECK(worker_slot BETWEEN 1 AND worker_capacity),
    task_timeout_seconds integer NOT NULL CHECK(task_timeout_seconds BETWEEN 1 AND 1800),
    queue_timeout_seconds integer NOT NULL CHECK(queue_timeout_seconds BETWEEN 1 AND 300),
    cancel_requested boolean NOT NULL DEFAULT false,
    execution_pending boolean NOT NULL DEFAULT false,
    source_operation_id uuid UNIQUE,
    result_version_id uuid,
    error_code varchar(64) CHECK(error_code ~ '^(EXECUTION|SOURCE)_[A-Z_]+$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE(project_id,idempotency_key_sha256),
    FOREIGN KEY(edit_lease_id,project_id,owner_id,id,base_version_id,base_revision)
        REFERENCES source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision),
    FOREIGN KEY(base_version_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY(source_operation_id,project_id) REFERENCES source_operations(id,project_id),
    FOREIGN KEY(result_version_id,project_id) REFERENCES source_versions(id,project_id),
    CHECK((status IN ('SOURCE_SAVED','NO_CHANGES','FAILED','CANCELLED','TIMED_OUT')) = (finished_at IS NOT NULL)),
    CHECK((status='SOURCE_SAVED') = (result_version_id IS NOT NULL)),
    CHECK(status<>'SOURCE_SAVED' OR source_operation_id IS NOT NULL),
    CHECK(status NOT IN ('SOURCE_SAVED','NO_CHANGES') OR error_code IS NULL),
    CHECK(CASE WHEN finished_at IS NOT NULL THEN queue_slot IS NULL AND worker_slot IS NULL AND NOT execution_pending
               WHEN status='QUEUED' THEN queue_slot IS NOT NULL AND worker_slot IS NULL AND NOT execution_pending
               WHEN status='NEEDS_RECONCILIATION' THEN (queue_slot IS NULL) <> (worker_slot IS NULL)
               ELSE queue_slot IS NULL AND worker_slot IS NOT NULL END)
);
CREATE INDEX dependency_preparation_project_order ON dependency_preparations(project_id,created_at DESC,id DESC);

CREATE FUNCTION guard_dependency_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE edit source_edit_leases%ROWTYPE; operation source_operations%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Dependency preparation evidence is retained' USING ERRCODE='55000';
    END IF;
    SELECT * INTO edit FROM source_edit_leases WHERE id=NEW.edit_lease_id;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'QUEUED' OR NEW.cancel_requested OR NEW.execution_pending OR NEW.error_code IS NOT NULL
            OR NEW.source_operation_id IS NOT NULL OR edit.status IS DISTINCT FROM 'ACTIVE'
            OR edit.execution_id IS NOT NULL OR edit.cancel_requested
            OR NOT EXISTS(SELECT 1 FROM source_versions WHERE id=NEW.base_version_id
                AND project_id=NEW.project_id AND tree_sha256=NEW.tree_sha256) THEN
            RAISE EXCEPTION 'Invalid preparation intent' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.project_id,NEW.owner_id,NEW.edit_lease_id,NEW.base_version_id,NEW.base_revision,
           NEW.tree_sha256,NEW.manifest_sha256,NEW.base_lock_sha256,NEW.policy_sha256,
           NEW.idempotency_key_sha256,NEW.request_sha256,NEW.created_at,NEW.queue_capacity,
           NEW.worker_capacity,NEW.task_timeout_seconds,NEW.queue_timeout_seconds)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.owner_id,OLD.edit_lease_id,OLD.base_version_id,OLD.base_revision,
           OLD.tree_sha256,OLD.manifest_sha256,OLD.base_lock_sha256,OLD.policy_sha256,
           OLD.idempotency_key_sha256,OLD.request_sha256,OLD.created_at,OLD.queue_capacity,
           OLD.worker_capacity,OLD.task_timeout_seconds,OLD.queue_timeout_seconds)
        OR (OLD.cancel_requested AND NOT NEW.cancel_requested)
        OR (OLD.source_operation_id IS NOT NULL AND NEW.source_operation_id IS DISTINCT FROM OLD.source_operation_id)
        OR (OLD.finished_at IS NOT NULL AND NEW IS DISTINCT FROM OLD)
        OR (NEW.finished_at IS NOT NULL AND (OLD.execution_pending OR edit.execution_pending OR edit.status<>'RELEASED'))
        OR (NEW.execution_pending AND NOT edit.execution_pending) THEN
        RAISE EXCEPTION 'Immutable preparation binding or unsettled edit' USING ERRCODE='55000';
    END IF;
    IF NEW.status<>OLD.status AND NOT (
        (OLD.status='QUEUED' AND NEW.status IN ('PREPARING','FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION'))
        OR (OLD.status='PREPARING' AND NEW.status='RESOLVING')
        OR (OLD.status='RESOLVING' AND NEW.status='ACCEPTING')
        OR (OLD.status='ACCEPTING' AND NEW.status IN ('SOURCE_SAVED','NO_CHANGES'))
        OR (OLD.status IN ('PREPARING','RESOLVING','ACCEPTING') AND NEW.status IN ('FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION'))
        OR (OLD.status='NEEDS_RECONCILIATION' AND NOT OLD.execution_pending AND NEW.status IN ('SOURCE_SAVED','NO_CHANGES','FAILED','CANCELLED','TIMED_OUT'))
    ) THEN
        RAISE EXCEPTION 'Invalid preparation transition' USING ERRCODE='55000';
    END IF;
    IF NEW.source_operation_id IS NOT NULL THEN
        SELECT * INTO operation FROM source_operations WHERE id=NEW.source_operation_id;
        IF operation.edit_lease_id IS DISTINCT FROM NEW.edit_lease_id THEN
            RAISE EXCEPTION 'Preparation source operation does not match its edit' USING ERRCODE='55000';
        END IF;
    END IF;
    IF NEW.status='SOURCE_SAVED' AND (operation.status IS DISTINCT FROM 'COMMITTED'
        OR operation.result_version_id IS DISTINCT FROM NEW.result_version_id) THEN
        RAISE EXCEPTION 'Preparation result must match committed source' USING ERRCODE='55000';
    END IF;
    IF NEW.status='NO_CHANGES' AND (edit.stopped_sha256 IS NULL OR NEW.source_operation_id IS NOT NULL) THEN
        RAISE EXCEPTION 'No-change preparation requires stopped execution without publication' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER dependency_preparation_guard BEFORE INSERT OR UPDATE OR DELETE ON dependency_preparations
    FOR EACH ROW EXECUTE FUNCTION guard_dependency_preparation();
CREATE TRIGGER dependency_preparation_slots AFTER INSERT OR UPDATE OF queue_slot,worker_slot,finished_at ON dependency_preparations
    FOR EACH ROW EXECUTE FUNCTION sync_execution_task_slot('PREPARATION');
