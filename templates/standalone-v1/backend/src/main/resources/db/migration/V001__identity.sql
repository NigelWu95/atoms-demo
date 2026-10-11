-- Installation identity and initial tables commit together, under DatabaseInstallation's lock.
-- The sole placeholder is a Java-validated canonical UUID, never model/application text.
CREATE TABLE installation (
    singleton boolean PRIMARY KEY CHECK (singleton),
    instance_id uuid NOT NULL,
    format_version integer NOT NULL CHECK (format_version = 1),
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO installation (singleton, instance_id, format_version) VALUES (true, '${instanceId}'::uuid, 1);

CREATE TABLE guest_users (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Spring Session JDBC 3.5.7 PostgreSQL schema; Flyway alone owns its initialization.
-- https://github.com/spring-projects/spring-session/blob/3.5.7/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID char(36) PRIMARY KEY,
    SESSION_ID char(36) NOT NULL,
    CREATION_TIME bigint NOT NULL,
    LAST_ACCESS_TIME bigint NOT NULL,
    MAX_INACTIVE_INTERVAL integer NOT NULL,
    EXPIRY_TIME bigint NOT NULL,
    PRINCIPAL_NAME varchar(100)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID char(36) NOT NULL REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME varchar(200) NOT NULL,
    ATTRIBUTE_BYTES bytea NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);
