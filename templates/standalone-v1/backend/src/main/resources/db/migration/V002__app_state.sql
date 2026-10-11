-- JSON (not jsonb) preserves the bounded document, including JSON-escaped U+0000.
-- No field-level query/index is needed for the single-document state contract.
CREATE TABLE app_state (
    guest_id uuid PRIMARY KEY REFERENCES guest_users(id) ON DELETE CASCADE,
    value json NOT NULL CHECK (json_typeof(value) = 'object' AND octet_length(value::text) <= 262144),
    revision bigint NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    state_schema_version integer NOT NULL CHECK (state_schema_version = 1),
    updated_at timestamptz NOT NULL DEFAULT now()
);
