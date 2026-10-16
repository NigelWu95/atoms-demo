ALTER TABLE projects ADD COLUMN archived boolean NOT NULL DEFAULT false;
CREATE INDEX projects_owner_archive_updated ON projects(owner_id, archived, updated_at DESC, id DESC);
