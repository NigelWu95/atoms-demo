CREATE TABLE user_accounts (
    user_id uuid PRIMARY KEY REFERENCES guest_users(id),
    display_name varchar(80) NOT NULL DEFAULT '邮箱创作者',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE email_identities (
    email varchar(254) PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES user_accounts(user_id),
    verified_at timestamptz NOT NULL DEFAULT now()
);
-- Tombstones outlive the maximum session lifetime; concurrent saves cannot restore access.
CREATE TABLE revoked_auth_sessions (
    session_hash char(64) PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
CREATE INDEX revoked_auth_sessions_expiry ON revoked_auth_sessions(expires_at);
