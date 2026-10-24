-- Permanent project ownership. Stopping an application never frees its database or port.
-- Unique + bounded slots enforce the four-project ceiling across all JVMs.
-- https://www.postgresql.org/docs/17/ddl-constraints.html
CREATE TABLE execution_database_bindings (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL UNIQUE REFERENCES projects(id),
    binding_slot smallint NOT NULL UNIQUE CHECK (binding_slot BETWEEN 1 AND 4),
    database_port integer NOT NULL CHECK (database_port BETWEEN 1 AND 65535),
    database_name varchar(63) NOT NULL UNIQUE CHECK (database_name ~ '^[a-z][a-z0-9_]{0,62}$'
        AND database_name NOT IN ('postgres','template0','template1','atoms_demo','atoms_demo_test','atoms_test') AND database_name NOT LIKE 'pg\_%'),
    role_name varchar(63) NOT NULL UNIQUE CHECK (role_name ~ '^[a-z][a-z0-9_]{0,62}$'
        AND role_name NOT IN ('postgres','atoms_demo','atoms_demo_test','atoms_test') AND role_name NOT LIKE 'pg\_%'),
    application_port integer NOT NULL UNIQUE CHECK (application_port BETWEEN 1024 AND 65535 AND application_port<>database_port),
    database_oid bigint NOT NULL CHECK (database_oid BETWEEN 1 AND 4294967295),
    role_oid bigint NOT NULL CHECK (role_oid BETWEEN 1 AND 4294967295),
    identity_sha256 varchar(64) NOT NULL CHECK (identity_sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id,project_id),
    UNIQUE (database_port,database_oid),
    UNIQUE (database_port,role_oid)
);

CREATE FUNCTION guard_execution_database_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Runtime database ownership is permanent' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER execution_database_binding_immutable BEFORE UPDATE OR DELETE ON execution_database_bindings
    FOR EACH ROW EXECUTE FUNCTION guard_execution_database_binding();
