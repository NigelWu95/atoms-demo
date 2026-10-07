CREATE TABLE idempotency_records (
    actor_id uuid NOT NULL REFERENCES guest_users(id),
    method varchar(10) NOT NULL,
    resource varchar(255) NOT NULL,
    intent_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    response_status integer,
    response_body text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, method, resource, intent_key),
    CHECK ((response_status IS NULL) = (response_body IS NULL))
);
