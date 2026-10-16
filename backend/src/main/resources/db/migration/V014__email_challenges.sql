CREATE TABLE email_auth_challenges (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES guest_users(id),
    session_hash char(64) NOT NULL,
    email varchar(254) NOT NULL,
    code_hash char(64) NOT NULL,
    status varchar(12) NOT NULL CHECK (status IN ('PENDING','SENT','FAILED','CONSUMED','REPLACED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX email_auth_challenges_session ON email_auth_challenges(session_hash, status);
CREATE INDEX email_auth_challenges_expiry ON email_auth_challenges(expires_at);
CREATE TABLE email_auth_budgets (
    budget_key char(64) NOT NULL,
    window_start bigint NOT NULL,
    used integer NOT NULL CHECK (used > 0),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (budget_key, window_start)
);
CREATE INDEX email_auth_budgets_expiry ON email_auth_budgets(expires_at);
