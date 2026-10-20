-- Additive only. Roll back by disabling atoms.project-execution; retain task/event evidence.
CREATE TABLE execution_builds (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    source_version_id uuid NOT NULL,
    tree_sha256 varchar(64) NOT NULL CHECK (tree_sha256 ~ '^[a-f0-9]{64}$'),
    idempotency_key_sha256 varchar(64) NOT NULL CHECK (idempotency_key_sha256 ~ '^[a-f0-9]{64}$'),
    request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK (status IN
        ('QUEUED','PREPARING','INSTALLING','CHECKING','PACKAGING','SUCCEEDED',
         'FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION')),
    queue_capacity integer NOT NULL DEFAULT 8 CHECK (queue_capacity BETWEEN 1 AND 8),
    worker_capacity integer NOT NULL DEFAULT 1 CHECK (worker_capacity BETWEEN 1 AND 4),
    queue_slot integer CHECK (queue_slot BETWEEN 1 AND queue_capacity),
    worker_slot integer CHECK (worker_slot BETWEEN 1 AND worker_capacity),
    task_timeout_seconds integer NOT NULL DEFAULT 1800 CHECK (task_timeout_seconds BETWEEN 1 AND 1800),
    queue_timeout_seconds integer NOT NULL DEFAULT 300 CHECK (queue_timeout_seconds BETWEEN 1 AND 300),
    cancel_requested boolean NOT NULL DEFAULT false,
    execution_pending boolean NOT NULL DEFAULT false,
    error_code varchar(64) CHECK (error_code ~ '^EXECUTION_[A-Z_]+$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    last_event_seq integer NOT NULL DEFAULT 0 CHECK (last_event_seq BETWEEN 0 AND 1000),
    event_bytes integer NOT NULL DEFAULT 0 CHECK (event_bytes BETWEEN 0 AND 65536),
    UNIQUE (project_id,idempotency_key_sha256),
    FOREIGN KEY (source_version_id,project_id) REFERENCES source_versions(id,project_id),
    CHECK ((status IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')) = (finished_at IS NOT NULL)),
    CHECK (CASE WHEN finished_at IS NOT NULL THEN queue_slot IS NULL AND worker_slot IS NULL AND NOT execution_pending
                WHEN status='QUEUED' THEN queue_slot IS NOT NULL AND worker_slot IS NULL AND NOT execution_pending
                WHEN status='NEEDS_RECONCILIATION' THEN (queue_slot IS NULL) <> (worker_slot IS NULL)
                ELSE queue_slot IS NULL AND worker_slot IS NOT NULL END),
    CHECK (status <> 'SUCCEEDED' OR (NOT cancel_requested AND error_code IS NULL))
);
-- Partial uniqueness is enforced by PG, independent of a JVM-local lock.
-- https://www.postgresql.org/docs/17/indexes-partial.html
CREATE UNIQUE INDEX execution_build_active_project ON execution_builds(project_id) WHERE finished_at IS NULL;
CREATE UNIQUE INDEX execution_build_queue_slot ON execution_builds(queue_slot) WHERE queue_slot IS NOT NULL;
CREATE UNIQUE INDEX execution_build_worker_slot ON execution_builds(worker_slot) WHERE worker_slot IS NOT NULL;
CREATE INDEX execution_build_project_order ON execution_builds(project_id,created_at DESC,id DESC);

CREATE FUNCTION guard_execution_build() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Execution evidence is retained' USING ERRCODE='55000';
    ELSIF TG_OP='INSERT' THEN
        IF NEW.status<>'QUEUED' OR NEW.cancel_requested OR NEW.execution_pending
            OR NEW.last_event_seq<>0 OR NEW.event_bytes<>0 OR NEW.error_code IS NOT NULL
            OR NOT EXISTS (SELECT 1 FROM source_versions WHERE id=NEW.source_version_id
                AND project_id=NEW.project_id AND tree_sha256=NEW.tree_sha256) THEN
            RAISE EXCEPTION 'Invalid execution intent' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.project_id,NEW.owner_id,NEW.source_version_id,NEW.tree_sha256,
           NEW.idempotency_key_sha256,NEW.request_sha256,NEW.created_at,NEW.queue_capacity,
           NEW.worker_capacity,NEW.task_timeout_seconds,NEW.queue_timeout_seconds)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.owner_id,OLD.source_version_id,OLD.tree_sha256,
           OLD.idempotency_key_sha256,OLD.request_sha256,OLD.created_at,OLD.queue_capacity,
           OLD.worker_capacity,OLD.task_timeout_seconds,OLD.queue_timeout_seconds)
        OR (OLD.cancel_requested AND NOT NEW.cancel_requested)
        OR NEW.last_event_seq<OLD.last_event_seq OR NEW.event_bytes<OLD.event_bytes
        OR (OLD.finished_at IS NOT NULL AND ROW(NEW.status,NEW.cancel_requested,NEW.error_code,NEW.finished_at)
            IS DISTINCT FROM ROW(OLD.status,OLD.cancel_requested,OLD.error_code,OLD.finished_at))
        OR (OLD.execution_pending AND NEW.finished_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Immutable execution binding or unsettled process' USING ERRCODE='55000';
    END IF;
    IF NEW.status<>OLD.status AND NOT (
        (OLD.status='QUEUED' AND NEW.status IN ('PREPARING','FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION'))
        OR (OLD.status='PREPARING' AND NEW.status='INSTALLING')
        OR (OLD.status='INSTALLING' AND NEW.status='CHECKING')
        OR (OLD.status='CHECKING' AND NEW.status='PACKAGING')
        OR (OLD.status='PACKAGING' AND NEW.status='SUCCEEDED')
        OR (OLD.status IN ('PREPARING','INSTALLING','CHECKING','PACKAGING')
            AND NEW.status IN ('FAILED','CANCELLED','TIMED_OUT','NEEDS_RECONCILIATION'))
        OR (OLD.status='NEEDS_RECONCILIATION' AND NOT OLD.execution_pending AND NEW.status IN ('FAILED','CANCELLED','TIMED_OUT'))
    ) THEN
        RAISE EXCEPTION 'Invalid execution transition' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_build_guard BEFORE INSERT OR UPDATE OR DELETE ON execution_builds
    FOR EACH ROW EXECUTE FUNCTION guard_execution_build();

CREATE TABLE execution_build_events (
    build_id uuid NOT NULL REFERENCES execution_builds(id),
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 1000),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object' AND octet_length(payload::text)<=1024),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (build_id,seq)
);
CREATE FUNCTION reject_execution_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Execution events are immutable' USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER execution_event_immutable BEFORE UPDATE OR DELETE ON execution_build_events
    FOR EACH ROW EXECUTE FUNCTION reject_execution_event_mutation();

-- Stage events and their budget update share the task transaction: no silent event loss.
-- https://www.postgresql.org/docs/17/trigger-definition.html
CREATE FUNCTION record_execution_stage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    event_payload jsonb;
    event_seq integer;
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.status,NEW.cancel_requested,NEW.error_code)
        IS NOT DISTINCT FROM ROW(OLD.status,OLD.cancel_requested,OLD.error_code) THEN RETURN NEW; END IF;
    event_payload := jsonb_build_object('status',NEW.status,'cancelRequested',NEW.cancel_requested,'code',NEW.error_code);
    UPDATE execution_builds SET last_event_seq=last_event_seq+1,
        event_bytes=event_bytes+octet_length(event_payload::text)
        WHERE id=NEW.id RETURNING last_event_seq INTO event_seq;
    INSERT INTO execution_build_events(build_id,seq,payload) VALUES (NEW.id,event_seq,event_payload);
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_build_stage AFTER INSERT OR UPDATE OF status,cancel_requested,error_code ON execution_builds
    FOR EACH ROW EXECUTE FUNCTION record_execution_stage();
