-- Explicit acceptance of observed database state; never an automatic rollback or a reconstructed stop capability.
ALTER TABLE execution_runs ADD CONSTRAINT execution_run_project_identity UNIQUE (id,project_id);
CREATE TABLE execution_run_reconciliations (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL UNIQUE,
    project_id uuid NOT NULL REFERENCES projects(id),
    approved_by uuid NOT NULL REFERENCES guest_users(id),
    execution_id uuid NOT NULL,
    build_id uuid NOT NULL,
    source_version_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    tree_sha256 varchar(64) NOT NULL CHECK (tree_sha256 ~ '^[a-f0-9]{64}$'),
    jar_sha256 varchar(64) NOT NULL CHECK (jar_sha256 ~ '^[a-f0-9]{64}$'),
    manifest_sha256 varchar(64) NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    binding_identity_sha256 varchar(64) NOT NULL CHECK (binding_identity_sha256 ~ '^[a-f0-9]{64}$'),
    stopped_sha256 varchar(64) NOT NULL CHECK (stopped_sha256 ~ '^[a-f0-9]{64}$'),
    history_sha256 varchar(64) NOT NULL CHECK (history_sha256 ~ '^[a-f0-9]{64}$'),
    key_sha256 varchar(64) NOT NULL CHECK (key_sha256 ~ '^[a-f0-9]{64}$'),
    confirmation_sha256 varchar(64) NOT NULL CHECK (confirmation_sha256 ~ '^[a-f0-9]{64}$'),
    previous_error varchar(64) NOT NULL CHECK (previous_error ~ '^RUN_[A-Z_]+$'),
    action varchar(32) NOT NULL DEFAULT 'ACCEPT_DATABASE_STATE' CHECK (action='ACCEPT_DATABASE_STATE'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id,project_id) REFERENCES execution_runs(id,project_id),
    FOREIGN KEY (build_id,project_id) REFERENCES execution_builds(id,project_id),
    FOREIGN KEY (source_version_id,project_id) REFERENCES source_versions(id,project_id),
    FOREIGN KEY (binding_id,project_id) REFERENCES execution_database_bindings(id,project_id),
    UNIQUE (project_id,key_sha256)
);

CREATE FUNCTION guard_execution_run_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original execution_runs%ROWTYPE;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Run reconciliation evidence is immutable' USING ERRCODE='55000';
    END IF;
    PERFORM id FROM projects WHERE id=NEW.project_id AND owner_id=NEW.approved_by AND NOT archived FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Reconciliation owner invalid' USING ERRCODE='55000'; END IF;
    SELECT * INTO original FROM execution_runs WHERE id=NEW.run_id AND project_id=NEW.project_id FOR UPDATE;
    IF NOT FOUND OR original.status<>'NEEDS_RECONCILIATION' OR original.execution_pending
        OR original.execution_id IS NULL OR original.stopped_sha256 IS NULL
        OR original.ready_at IS NOT NULL OR original.finished_at IS NOT NULL
        OR ROW(NEW.approved_by,NEW.execution_id,NEW.build_id,NEW.source_version_id,NEW.binding_id,
            NEW.tree_sha256,NEW.jar_sha256,NEW.manifest_sha256,NEW.binding_identity_sha256,NEW.stopped_sha256,NEW.previous_error)
           IS DISTINCT FROM ROW(original.owner_id,original.execution_id,original.build_id,original.source_version_id,original.binding_id,
            original.tree_sha256,original.jar_sha256,original.manifest_sha256,original.binding_identity_sha256,original.stopped_sha256,original.error_code) THEN
        RAISE EXCEPTION 'Stopped failure identity does not match' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_run_reconciliation_guard BEFORE INSERT OR UPDATE OR DELETE ON execution_run_reconciliations
    FOR EACH ROW EXECUTE FUNCTION guard_execution_run_reconciliation();

-- An audit and its terminal transition must commit together. Existing run guards retain all immutable fields.
CREATE FUNCTION require_execution_run_reconciliation_finish() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT FROM execution_runs WHERE id=NEW.run_id AND project_id=NEW.project_id
        AND status='FAILED' AND finished_at IS NOT NULL AND NOT execution_pending
        AND execution_id=NEW.execution_id AND stopped_sha256=NEW.stopped_sha256 AND error_code=NEW.previous_error) THEN
        RAISE EXCEPTION 'Reconciliation transition incomplete' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER execution_run_reconciliation_finish AFTER INSERT ON execution_run_reconciliations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_execution_run_reconciliation_finish();
