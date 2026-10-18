-- A platform crash does not prove its Git child has stopped. Retain this fence until that execution is settled.
ALTER TABLE source_operations ADD COLUMN execution_id uuid;
ALTER TABLE source_operations ADD COLUMN execution_pending boolean NOT NULL DEFAULT false;
ALTER TABLE source_operations ADD CONSTRAINT source_execution_identity CHECK (NOT execution_pending OR execution_id IS NOT NULL);
ALTER TABLE source_operations ADD CONSTRAINT source_execution_state
    CHECK (NOT execution_pending OR status IN ('PREPARING','NEEDS_RECONCILIATION'));
CREATE UNIQUE INDEX source_execution_project_slot ON source_operations(project_id) WHERE execution_pending;
CREATE FUNCTION enforce_source_execution_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.execution_id IS NOT NULL AND NEW.execution_id IS DISTINCT FROM OLD.execution_id)
        OR (NEW.execution_pending AND NOT OLD.execution_pending AND OLD.execution_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Source execution cannot be replaced or restarted' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_operations_execution_fence BEFORE UPDATE ON source_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_source_execution_fence();
