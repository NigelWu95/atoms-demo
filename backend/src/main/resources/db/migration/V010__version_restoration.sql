ALTER TABLE versions ADD COLUMN restored_from_id UUID;
ALTER TABLE versions ADD CONSTRAINT versions_restored_from_fk
    FOREIGN KEY (restored_from_id, project_id) REFERENCES versions(id, project_id);
