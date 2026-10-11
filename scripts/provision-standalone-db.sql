-- Fixed identifiers/markers and a generated SCRAM verifier only; no application SQL.
-- https://www.postgresql.org/docs/17/app-psql.html (ON_ERROR_STOP, gexec)
SET statement_timeout = '10s';
SET lock_timeout = '3s';
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
DO $$ BEGIN
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
     OR current_user <> '@@ADMIN@@' OR current_database() <> 'postgres' THEN
    RAISE EXCEPTION 'Explicit local administrator connection required';
  END IF;
  IF NOT pg_try_advisory_lock(1748392051) THEN
    RAISE EXCEPTION 'Another standalone provisioner is active';
  END IF;
  IF EXISTS (SELECT FROM pg_roles r WHERE rolname = '@@DATABASE@@'
      AND (shobj_description(r.oid, 'pg_authid') IS DISTINCT FROM '@@MARKER@@'
        OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolcanlogin
        OR EXISTS (SELECT FROM pg_auth_members WHERE member = r.oid OR roleid = r.oid))) THEN
    RAISE EXCEPTION 'Existing role is unowned or unsafe';
  END IF;
  IF EXISTS (SELECT FROM pg_database WHERE datname = '@@DATABASE@@'
      AND (shobj_description(oid, 'pg_database') IS DISTINCT FROM '@@MARKER@@'
        OR pg_get_userbyid(datdba) <> current_user)) THEN
    RAISE EXCEPTION 'Existing database is unowned';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '@@DATABASE@@') THEN
    CREATE ROLE "@@DATABASE@@" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '@@VERIFIER@@';
    COMMENT ON ROLE "@@DATABASE@@" IS '@@MARKER@@';
    ALTER ROLE "@@DATABASE@@" SET search_path = pg_catalog, standalone_app;
  END IF;
END $$;
-- CREATE DATABASE cannot run inside DO/transaction. Missing objects only.
SELECT 'CREATE DATABASE "@@DATABASE@@" OWNER "@@ADMIN@@" TEMPLATE template0'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '@@DATABASE@@')
\gexec
COMMENT ON DATABASE "@@DATABASE@@" IS '@@MARKER@@';
REVOKE ALL ON DATABASE "@@DATABASE@@" FROM PUBLIC;
REVOKE ALL ON DATABASE "@@DATABASE@@" FROM "@@DATABASE@@";
GRANT CONNECT ON DATABASE "@@DATABASE@@" TO "@@DATABASE@@";
\connect @@DATABASE@@
SET statement_timeout = '10s';
SET lock_timeout = '3s';
DO $$ BEGIN
  IF EXISTS (SELECT FROM pg_namespace WHERE nspname = 'standalone_app'
      AND pg_get_userbyid(nspowner) <> '@@DATABASE@@') THEN
    RAISE EXCEPTION 'Existing schema is unowned';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'standalone_app') THEN
    CREATE SCHEMA standalone_app AUTHORIZATION "@@DATABASE@@";
  END IF;
END $$;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM "@@DATABASE@@";
REVOKE ALL ON SCHEMA standalone_app FROM PUBLIC;
SELECT 'STANDALONE_READY';
