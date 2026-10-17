ALTER TABLE user_accounts ADD COLUMN auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0);
CREATE TABLE account_passwords (
    user_id uuid PRIMARY KEY REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    password_hash varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE email_auth_challenges ADD COLUMN purpose varchar(20) NOT NULL DEFAULT 'LOGIN'
    CHECK (purpose IN ('LOGIN', 'PASSWORD_SETUP'));
ALTER TABLE email_auth_challenges ADD COLUMN credential_version bigint;
ALTER TABLE email_auth_challenges ADD CONSTRAINT challenge_credential_version
    CHECK ((purpose='LOGIN' AND credential_version IS NULL) OR (purpose='PASSWORD_SETUP' AND credential_version >= 0 AND credential_version IS NOT NULL));
