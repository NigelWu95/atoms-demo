-- Successful responses only: a transaction either inserts a complete result or leaves no row.
-- Retained across restarts; no expiry shorter than the visitor's retry window.
CREATE TABLE idempotency_records (
    guest_id uuid NOT NULL REFERENCES guest_users(id) ON DELETE CASCADE,
    method varchar(8) NOT NULL CHECK (method = 'PUT'),
    resource varchar(32) NOT NULL CHECK (resource = '/api/state'),
    intent_key varchar(128) NOT NULL CHECK (intent_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    response_body json NOT NULL CHECK (json_typeof(response_body) = 'object' AND octet_length(response_body::text) <= 278528),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (guest_id, method, resource, intent_key)
);
