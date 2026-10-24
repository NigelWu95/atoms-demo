-- Shared, transactional occupancy for builds and dependency preparations.
-- Roll back by disabling execution; retain task audit and this projection. Do not clean old evidence.
CREATE TABLE execution_task_slots (
    task_id uuid PRIMARY KEY,
    task_kind varchar(16) NOT NULL CHECK(task_kind IN ('BUILD','PREPARATION')),
    project_id uuid NOT NULL UNIQUE REFERENCES projects(id),
    queue_slot integer UNIQUE CHECK(queue_slot BETWEEN 1 AND 8),
    worker_slot integer UNIQUE CHECK(worker_slot BETWEEN 1 AND 4),
    CHECK((queue_slot IS NULL) <> (worker_slot IS NULL))
);

-- Existing uncertain tasks retain their occupied slots. Migration never settles them.
INSERT INTO execution_task_slots(task_id,task_kind,project_id,queue_slot,worker_slot)
    SELECT id,'BUILD',project_id,queue_slot,worker_slot FROM execution_builds WHERE finished_at IS NULL;

CREATE FUNCTION guard_execution_task_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Occupancy is a projection, not another independently writable state machine.
    -- This protects against accidental direct DML, not a hostile database owner.
    IF pg_trigger_depth()<2 THEN
        RAISE EXCEPTION 'Execution slots must follow task state' USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_task_slot_guard BEFORE INSERT OR UPDATE OR DELETE ON execution_task_slots
    FOR EACH ROW EXECUTE FUNCTION guard_execution_task_slot();

CREATE FUNCTION sync_execution_task_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.finished_at IS NOT NULL THEN
        DELETE FROM execution_task_slots WHERE task_id=NEW.id AND task_kind=TG_ARGV[0];
    ELSIF TG_OP='INSERT' THEN
        INSERT INTO execution_task_slots(task_id,task_kind,project_id,queue_slot,worker_slot)
            VALUES(NEW.id,TG_ARGV[0],NEW.project_id,NEW.queue_slot,NEW.worker_slot);
    ELSE
        UPDATE execution_task_slots SET queue_slot=NEW.queue_slot,worker_slot=NEW.worker_slot
            WHERE task_id=NEW.id AND task_kind=TG_ARGV[0] AND project_id=NEW.project_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Execution occupancy is missing' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_build_slots AFTER INSERT OR UPDATE OF queue_slot,worker_slot,finished_at ON execution_builds
    FOR EACH ROW EXECUTE FUNCTION sync_execution_task_slot('BUILD');
