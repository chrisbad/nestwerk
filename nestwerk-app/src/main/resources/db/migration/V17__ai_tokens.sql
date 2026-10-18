-- Persönliche Zugangstoken für KI-Assistenten (MCP-Server unter /mcp)
CREATE TABLE ai_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(100)             NOT NULL,
    token_hash   VARCHAR(64)              NOT NULL UNIQUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_on   DATE,
    revoked_at   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ai_tokens_user_idx ON ai_tokens (user_id);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('AI_ACCESS', 'Verwaltung', 'KI-Zugang nutzen',
        'Eigene Zugangstoken anlegen, mit denen ein KI-Assistent Nestwerk im eigenen Namen bedient', 60);
