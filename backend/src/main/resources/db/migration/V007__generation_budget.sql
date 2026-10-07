-- An absolute lifetime request ceiling is configured by the operator; restarting never resets it.
CREATE TABLE generation_budget (
    id integer PRIMARY KEY CHECK (id = 1),
    requests_reserved bigint NOT NULL DEFAULT 0 CHECK (requests_reserved >= 0)
);
INSERT INTO generation_budget(id) VALUES (1);
CREATE TABLE model_calls (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES runs(id),
    stage text NOT NULL,
    status text NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN', 'SUCCEEDED', 'FAILED')),
    prompt_tokens bigint,
    completion_tokens bigint,
    total_tokens bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);
