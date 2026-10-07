-- Administrator-only, after creating atoms_app and atoms_demo (see README).
-- Does not reset passwords, drop data, or apply test fixtures.
\set ON_ERROR_STOP on
\connect atoms_demo
GRANT CONNECT ON DATABASE atoms_demo TO atoms_app;
CREATE SCHEMA IF NOT EXISTS atoms AUTHORIZATION atoms_app;
-- Refuse an incompatible existing schema instead of silently changing ownership.
DO $$ BEGIN
  IF (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'atoms') <> 'atoms_app' THEN
    RAISE EXCEPTION 'Existing atoms schema is not owned by atoms_app; inspect before proceeding';
  END IF;
END $$;
