CREATE TABLE projects (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    name varchar(80) NOT NULL CHECK (char_length(name) > 0),
    project_revision bigint NOT NULL DEFAULT 0 CHECK (project_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX projects_owner_created_idx ON projects(owner_id, created_at DESC, id DESC);
