-- Business schema owned by this application. Test transactions roll back this DDL and their rows.
CREATE TABLE generated_app.todos (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title varchar(160) NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 160),
    completed boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0)
);
