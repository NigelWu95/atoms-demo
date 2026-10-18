-- Additive task audit; disabling Agent must not discard unfinished edit/execution fences.
ALTER TABLE source_edit_leases ADD CONSTRAINT source_edit_task_identity
    UNIQUE(id,project_id,owner_id,task_id,base_version_id,base_revision);
ALTER TABLE source_operations ADD CONSTRAINT source_operation_project_identity UNIQUE(id,project_id);

CREATE TABLE agent_tasks (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    edit_lease_id uuid NOT NULL UNIQUE,
    base_version_id uuid NOT NULL,
    base_revision bigint NOT NULL CHECK(base_revision BETWEEN 1 AND 9007199254740991),
    idempotency_sha256 varchar(64) NOT NULL CHECK(idempotency_sha256 ~ '^[a-f0-9]{64}$'),
    request_sha256 varchar(64) NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
    prompt text CHECK(prompt IS NULL OR char_length(prompt) BETWEEN 1 AND 8000),
    status varchar(32) NOT NULL DEFAULT 'QUEUED' CHECK(status IN
        ('QUEUED','PREPARING','RUNNING','VALIDATING','ACCEPTING','SOURCE_SAVED','NO_CHANGES','FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION')),
    cancel_requested boolean NOT NULL DEFAULT false,
    execution_id uuid UNIQUE,
    source_operation_id uuid UNIQUE,
    result_version_id uuid,
    live_reserved boolean NOT NULL DEFAULT false,
    error_code varchar(96) CHECK(error_code ~ '^(AGENT|SOURCE)_[A-Z_]+$'),
    max_tokens integer NOT NULL CHECK(max_tokens BETWEEN 1 AND 128000),
    max_turns integer NOT NULL CHECK(max_turns BETWEEN 1 AND 24),
    task_timeout_seconds integer NOT NULL CHECK(task_timeout_seconds BETWEEN 1 AND 1800),
    queue_timeout_seconds integer NOT NULL CHECK(queue_timeout_seconds BETWEEN 1 AND 300),
    entry_point varchar(32) NOT NULL DEFAULT 'internal_service' CHECK(entry_point IN ('internal_service','workbench')),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    last_event_seq integer NOT NULL DEFAULT 0 CHECK(last_event_seq BETWEEN 0 AND 4096),
    event_bytes integer NOT NULL DEFAULT 0 CHECK(event_bytes BETWEEN 0 AND 262144),
    history_expired boolean NOT NULL DEFAULT false,
    UNIQUE(project_id,idempotency_sha256),
    FOREIGN KEY(edit_lease_id,project_id,owner_id,id,base_version_id,base_revision)
        REFERENCES source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision),
    FOREIGN KEY(source_operation_id,project_id) REFERENCES source_operations(id,project_id),
    FOREIGN KEY(result_version_id,project_id) REFERENCES source_versions(id,project_id),
    CHECK((status IN ('SOURCE_SAVED','NO_CHANGES','FAILED','CANCELLED','TIMED_OUT')) = (finished_at IS NOT NULL)),
    CHECK((status='SOURCE_SAVED') = (result_version_id IS NOT NULL)),
    CHECK(NOT live_reserved OR execution_id IS NOT NULL),
    CHECK(status<>'SOURCE_SAVED' OR source_operation_id IS NOT NULL)
);
CREATE UNIQUE INDEX agent_active_project ON agent_tasks(project_id)
    WHERE status NOT IN ('SOURCE_SAVED','NO_CHANGES','FAILED','CANCELLED','TIMED_OUT');
CREATE INDEX agent_queue_order ON agent_tasks(created_at,id) WHERE status='QUEUED';
CREATE INDEX agent_project_history ON agent_tasks(project_id,created_at DESC,id DESC);
CREATE TABLE agent_live_budget (
    id boolean PRIMARY KEY DEFAULT true CHECK(id),
    used bigint NOT NULL DEFAULT 0 CHECK(used>=0)
);
INSERT INTO agent_live_budget(id) VALUES(true);
CREATE TABLE agent_events (
    task_id uuid NOT NULL REFERENCES agent_tasks(id),
    seq integer NOT NULL CHECK(seq BETWEEN 1 AND 4096),
    kind varchar(32) NOT NULL CHECK(kind IN ('STAGE','RESULT','ERROR','USAGE','CHANGES')),
    payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=16384),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(task_id,seq)
);

CREATE FUNCTION enforce_agent_task_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE edit source_edit_leases%ROWTYPE; operation source_operations%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Agent task audit cannot be deleted' USING ERRCODE='55000';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'QUEUED' OR NEW.execution_id IS NOT NULL OR NEW.live_reserved
            OR NEW.source_operation_id IS NOT NULL OR NEW.prompt IS NULL THEN
            RAISE EXCEPTION 'Agent task must begin queued' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.project_id,NEW.owner_id,NEW.edit_lease_id,NEW.base_version_id,NEW.base_revision,
            NEW.idempotency_sha256,NEW.request_sha256,NEW.created_at,NEW.max_tokens,NEW.max_turns,
            NEW.task_timeout_seconds,NEW.queue_timeout_seconds,NEW.entry_point)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.owner_id,OLD.edit_lease_id,OLD.base_version_id,OLD.base_revision,
            OLD.idempotency_sha256,OLD.request_sha256,OLD.created_at,OLD.max_tokens,OLD.max_turns,
            OLD.task_timeout_seconds,OLD.queue_timeout_seconds,OLD.entry_point)
        OR (OLD.execution_id IS NOT NULL AND NEW.execution_id IS DISTINCT FROM OLD.execution_id)
        OR (OLD.source_operation_id IS NOT NULL AND NEW.source_operation_id IS DISTINCT FROM OLD.source_operation_id)
        OR (OLD.live_reserved AND NOT NEW.live_reserved)
        OR (OLD.cancel_requested AND NOT NEW.cancel_requested)
        OR (NEW.prompt IS DISTINCT FROM OLD.prompt AND (NEW.prompt IS NOT NULL OR OLD.finished_at IS NULL))
        OR (OLD.finished_at IS NOT NULL AND ROW(NEW.status,NEW.finished_at,NEW.result_version_id,NEW.error_code)
            IS DISTINCT FROM ROW(OLD.status,OLD.finished_at,OLD.result_version_id,OLD.error_code)) THEN
        RAISE EXCEPTION 'Agent task identity and results are immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO edit FROM source_edit_leases WHERE id=NEW.edit_lease_id;
    IF NEW.execution_id IS NOT NULL AND NEW.execution_id IS DISTINCT FROM edit.execution_id THEN
        RAISE EXCEPTION 'Agent execution must match edit intent' USING ERRCODE='23514';
    END IF;
    IF NEW.finished_at IS NOT NULL AND edit.execution_pending THEN
        RAISE EXCEPTION 'Agent execution has not stopped' USING ERRCODE='23514';
    END IF;
    IF NEW.source_operation_id IS NOT NULL THEN
        SELECT * INTO operation FROM source_operations WHERE id=NEW.source_operation_id;
        IF operation.edit_lease_id IS DISTINCT FROM NEW.edit_lease_id THEN
            RAISE EXCEPTION 'Agent source operation must match its edit' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.status='SOURCE_SAVED' AND (operation.status IS DISTINCT FROM 'COMMITTED'
            OR operation.result_version_id IS DISTINCT FROM NEW.result_version_id) THEN
        RAISE EXCEPTION 'Agent result must match committed source' USING ERRCODE='23514';
    END IF;
    IF NEW.status='NO_CHANGES' AND (edit.stopped_sha256 IS NULL OR NEW.source_operation_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Agent no-change result requires stopped execution without publication' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_task_transition BEFORE INSERT OR UPDATE OR DELETE ON agent_tasks
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_task_transition();
