-- Additive metadata; no file contents, paths or credentials. Keep V023 and historical evidence unchanged.
-- Rollback: disable project execution and roll back code; retain these columns and guards, never erase evidence.
ALTER TABLE execution_builds
    ADD COLUMN jar_bytes bigint,
    ADD COLUMN jar_sha256 varchar(64),
    ADD COLUMN manifest_sha256 varchar(64),
    ADD CONSTRAINT execution_artifact_binding CHECK (
        CASE WHEN status='SUCCEEDED' THEN
            jar_bytes IS NOT NULL AND jar_bytes BETWEEN 1 AND 268435456
            AND jar_sha256 IS NOT NULL AND jar_sha256 ~ '^[a-f0-9]{64}$'
            AND manifest_sha256 IS NOT NULL AND manifest_sha256 ~ '^[a-f0-9]{64}$'
        ELSE jar_bytes IS NULL AND jar_sha256 IS NULL AND manifest_sha256 IS NULL END
    );

-- Null checks are explicit: SQL CHECK accepts UNKNOWN as well as TRUE.
-- https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-CHECK-CONSTRAINTS
CREATE FUNCTION guard_execution_artifact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.finished_at IS NOT NULL AND
        ROW(NEW.jar_bytes,NEW.jar_sha256,NEW.manifest_sha256)
            IS DISTINCT FROM ROW(OLD.jar_bytes,OLD.jar_sha256,OLD.manifest_sha256) THEN
        RAISE EXCEPTION 'Execution artifact evidence is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_artifact_guard BEFORE UPDATE ON execution_builds
    FOR EACH ROW EXECUTE FUNCTION guard_execution_artifact();
