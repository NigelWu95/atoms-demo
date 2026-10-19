-- Initialization metadata only. Business migrations belong to subsequent source versions.
CREATE TABLE generated_app.application_metadata (
    name varchar(64) PRIMARY KEY,
    value varchar(255) NOT NULL
);
INSERT INTO generated_app.application_metadata(name,value) VALUES ('template','full-project-v1');
