-- Additive audit streams. Retain old rows and do not invent events for pre-migration history.
-- Disable execution callers to roll back; do not drop event evidence or mutate prior migrations.
CREATE TABLE dependency_preparation_events (
    task_id uuid NOT NULL REFERENCES dependency_preparations(id),
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 1000),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object' AND octet_length(payload::text)<=1024),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (task_id,seq)
);
CREATE TABLE execution_run_events (
    task_id uuid NOT NULL REFERENCES execution_runs(id),
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 1000),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object' AND octet_length(payload::text)<=1024),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (task_id,seq)
);

-- The parent lock serializes writers, including direct event inserts. No mutable counter is added
-- to a terminal preparation/run row. Each sum scans at most 1000 entries through the primary key.
CREATE FUNCTION guard_execution_event_budget() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE task uuid; locked uuid; entries integer; last_seq integer; bytes bigint;
BEGIN
    task := (to_jsonb(NEW)->>TG_ARGV[1])::uuid;
    EXECUTE format('SELECT id FROM %I.%I WHERE id=$1 FOR UPDATE', TG_TABLE_SCHEMA,TG_ARGV[0]) INTO locked USING task;
    IF locked IS NULL THEN RAISE EXCEPTION 'Event task missing' USING ERRCODE='23503'; END IF;
    EXECUTE format('SELECT count(*),coalesce(max(seq),0),coalesce(sum(octet_length(payload::text)),0) FROM %I.%I WHERE %I=$1',
        TG_TABLE_SCHEMA,TG_TABLE_NAME,TG_ARGV[1]) INTO entries,last_seq,bytes USING task;
    IF entries<>last_seq OR NEW.seq<>last_seq+1 OR entries>=1000 OR bytes+octet_length(NEW.payload::text)>65536 THEN
        RAISE EXCEPTION 'Execution event budget or sequence invalid' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_build_event_budget BEFORE INSERT ON execution_build_events
    FOR EACH ROW EXECUTE FUNCTION guard_execution_event_budget('execution_builds','build_id');
CREATE TRIGGER dependency_preparation_event_budget BEFORE INSERT ON dependency_preparation_events
    FOR EACH ROW EXECUTE FUNCTION guard_execution_event_budget('dependency_preparations','task_id');
CREATE TRIGGER execution_run_event_budget BEFORE INSERT ON execution_run_events
    FOR EACH ROW EXECUTE FUNCTION guard_execution_event_budget('execution_runs','task_id');
CREATE TRIGGER dependency_preparation_event_immutable BEFORE UPDATE OR DELETE ON dependency_preparation_events
    FOR EACH ROW EXECUTE FUNCTION reject_execution_event_mutation();
CREATE TRIGGER execution_run_event_immutable BEFORE UPDATE OR DELETE ON execution_run_events
    FOR EACH ROW EXECUTE FUNCTION reject_execution_event_mutation();

-- Trigger and parent update share one transaction: a quota failure cannot silently lose an event.
-- https://www.postgresql.org/docs/17/trigger-definition.html
CREATE FUNCTION record_execution_lifecycle_stage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_row jsonb; previous_row jsonb; payload jsonb; previous_payload jsonb;
BEGIN
    current_row := to_jsonb(NEW);
    payload := jsonb_strip_nulls(jsonb_build_object('status',current_row->'status','code',current_row->'error_code',
        'cancelRequested',current_row->'cancel_requested','stopRequested',current_row->'stop_requested',
        'executionPending',current_row->'execution_pending','executionId',current_row->'execution_id'));
    IF TG_OP='UPDATE' THEN
        previous_row := to_jsonb(OLD);
        previous_payload := jsonb_strip_nulls(jsonb_build_object('status',previous_row->'status','code',previous_row->'error_code',
            'cancelRequested',previous_row->'cancel_requested','stopRequested',previous_row->'stop_requested',
            'executionPending',previous_row->'execution_pending','executionId',previous_row->'execution_id'));
        IF payload=previous_payload THEN RETURN NEW; END IF;
    END IF;
    EXECUTE format('INSERT INTO %I.%I(task_id,seq,payload) SELECT $1,coalesce(max(seq),0)+1,$2 FROM %I.%I WHERE task_id=$1',
        TG_TABLE_SCHEMA,TG_ARGV[0],TG_TABLE_SCHEMA,TG_ARGV[0]) USING NEW.id,payload;
    RETURN NEW;
END;
$$;
CREATE TRIGGER dependency_preparation_stage AFTER INSERT OR UPDATE OF status,error_code,cancel_requested,execution_pending ON dependency_preparations
    FOR EACH ROW EXECUTE FUNCTION record_execution_lifecycle_stage('dependency_preparation_events');
CREATE TRIGGER execution_run_stage AFTER INSERT OR UPDATE OF status,error_code,stop_requested,execution_pending,execution_id ON execution_runs
    FOR EACH ROW EXECUTE FUNCTION record_execution_lifecycle_stage('execution_run_events');
