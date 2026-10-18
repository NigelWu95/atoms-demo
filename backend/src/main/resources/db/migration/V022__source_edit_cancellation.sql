-- Cancellation is a durable publication barrier, not evidence that the editing process stopped.
ALTER TABLE source_edit_leases ADD COLUMN cancel_requested boolean NOT NULL DEFAULT false;
CREATE FUNCTION enforce_source_edit_cancellation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP='INSERT' AND NEW.cancel_requested)
        OR (TG_OP='UPDATE' AND OLD.cancel_requested AND NOT NEW.cancel_requested) THEN
        RAISE EXCEPTION 'Source edit cancellation is monotonic' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_edit_cancellation BEFORE INSERT OR UPDATE ON source_edit_leases
    FOR EACH ROW EXECUTE FUNCTION enforce_source_edit_cancellation();
