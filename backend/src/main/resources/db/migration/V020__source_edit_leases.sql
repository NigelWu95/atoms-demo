-- Additive: disable new callers to roll back; retain leases and stop evidence, never drop active fences.
ALTER TABLE source_versions ADD CONSTRAINT source_version_revision_identity UNIQUE (id,project_id,version_no);
CREATE TABLE source_edit_leases (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project_sources(project_id),
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    task_id uuid NOT NULL UNIQUE,
    base_version_id uuid NOT NULL,
    base_revision bigint NOT NULL CHECK (base_revision > 0),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','NEEDS_RECONCILIATION','RELEASED')),
    execution_id uuid UNIQUE,
    execution_pending boolean NOT NULL DEFAULT false,
    stopped_sha256 varchar(64) CHECK (stopped_sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    UNIQUE (id,project_id),
    FOREIGN KEY (base_version_id,project_id,base_revision) REFERENCES source_versions(id,project_id,version_no),
    CHECK ((status='RELEASED') = (released_at IS NOT NULL)),
    CHECK (NOT execution_pending OR (execution_id IS NOT NULL AND status<>'RELEASED' AND stopped_sha256 IS NULL)),
    CHECK ((execution_id IS NULL AND NOT execution_pending AND stopped_sha256 IS NULL)
        OR (execution_id IS NOT NULL AND (execution_pending OR stopped_sha256 IS NOT NULL)))
);
CREATE UNIQUE INDEX source_edit_active_project ON source_edit_leases(project_id) WHERE status<>'RELEASED';
ALTER TABLE source_operations ADD COLUMN edit_lease_id uuid;
ALTER TABLE source_operations ADD CONSTRAINT source_operation_edit_lease_fk
    FOREIGN KEY (edit_lease_id,project_id) REFERENCES source_edit_leases(id,project_id);
CREATE UNIQUE INDEX source_operation_edit_lease_once ON source_operations(edit_lease_id) WHERE edit_lease_id IS NOT NULL;

CREATE FUNCTION enforce_source_edit_lease_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'ACTIVE' OR NEW.execution_id IS NOT NULL OR NEW.execution_pending OR NEW.stopped_sha256 IS NOT NULL THEN
            RAISE EXCEPTION 'Source edit must start without execution evidence' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Source edit evidence cannot be deleted' USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.id,NEW.project_id,NEW.owner_id,NEW.task_id,NEW.base_version_id,NEW.base_revision,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.project_id,OLD.owner_id,OLD.task_id,OLD.base_version_id,OLD.base_revision,OLD.created_at)
        OR (OLD.execution_id IS NOT NULL AND NEW.execution_id IS DISTINCT FROM OLD.execution_id)
        OR (OLD.execution_id IS NOT NULL AND NOT OLD.execution_pending AND NEW.execution_pending)
        OR (OLD.stopped_sha256 IS NOT NULL AND NEW.stopped_sha256 IS DISTINCT FROM OLD.stopped_sha256)
        OR (OLD.status='RELEASED' AND NEW IS DISTINCT FROM OLD)
        OR (OLD.status='NEEDS_RECONCILIATION' AND NEW.status='ACTIVE')
        OR (NEW.status='RELEASED' AND EXISTS (SELECT 1 FROM source_operations
            WHERE edit_lease_id=OLD.id AND (status NOT IN ('COMMITTED','FAILED','CANCELLED') OR execution_pending))) THEN
        RAISE EXCEPTION 'Invalid source edit lease transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_edit_lease_transition BEFORE INSERT OR UPDATE OR DELETE ON source_edit_leases
    FOR EACH ROW EXECUTE FUNCTION enforce_source_edit_lease_transition();

CREATE FUNCTION enforce_source_edit_operation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lease source_edit_leases%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.edit_lease_id IS DISTINCT FROM OLD.edit_lease_id THEN
            RAISE EXCEPTION 'Source edit operation binding is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.edit_lease_id IS NOT NULL THEN
        SELECT * INTO lease FROM source_edit_leases WHERE id=NEW.edit_lease_id;
        IF NOT FOUND OR lease.project_id<>NEW.project_id OR lease.owner_id<>NEW.owner_id
            OR lease.base_version_id IS DISTINCT FROM NEW.expected_version_id OR lease.base_revision<>NEW.expected_revision
            OR NEW.kind<>'ACCEPT' OR lease.status<>'ACTIVE' OR lease.execution_pending OR lease.stopped_sha256 IS NULL THEN
            RAISE EXCEPTION 'Source edit is not stopped for publication' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_edit_operation_binding BEFORE INSERT OR UPDATE ON source_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_source_edit_operation_binding();
