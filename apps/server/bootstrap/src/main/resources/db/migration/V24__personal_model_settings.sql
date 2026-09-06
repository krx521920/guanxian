-- Personal secrets are encrypted by the application; never store plaintext API keys.
CREATE TABLE personal_model_setting (
    user_id UUID PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE,
    provider VARCHAR(24) NOT NULL CHECK (provider IN ('DOUBAO', 'DEEPSEEK', 'KIMI', 'QWEN')),
    model VARCHAR(160) NOT NULL,
    encrypted_key TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
