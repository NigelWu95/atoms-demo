CREATE TABLE project_drafts (
    project_id uuid PRIMARY KEY REFERENCES projects(id),
    prompt text NOT NULL CHECK (char_length(prompt) <= 8000),
    revision bigint NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    updated_at timestamptz NOT NULL DEFAULT now()
);
