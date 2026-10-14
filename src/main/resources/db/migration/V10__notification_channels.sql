-- Zustellkanäle E-Mail und Push
ALTER TABLE users ADD COLUMN email VARCHAR(200);

CREATE TABLE notification_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    notification_id BIGINT                   NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    channel         VARCHAR(10)              NOT NULL CHECK (channel IN ('EMAIL', 'PUSH')),
    status          VARCHAR(10)              NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts        INT                      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at         TIMESTAMP WITH TIME ZONE,
    last_error      TEXT,
    UNIQUE (notification_id, channel)
);
CREATE INDEX notification_deliveries_due_idx ON notification_deliveries (status, next_attempt_at);

-- Einstellungen: global (scope_id 0), je Benutzer, je ToDo; type 'ALL' gilt für alle Anlässe
CREATE TABLE notification_preferences (
    id       BIGSERIAL PRIMARY KEY,
    scope    VARCHAR(10) NOT NULL CHECK (scope IN ('GLOBAL', 'USER', 'TODO')),
    scope_id BIGINT      NOT NULL,
    type     VARCHAR(40) NOT NULL,
    channel  VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'PUSH')),
    enabled  BOOLEAN     NOT NULL,
    UNIQUE (scope, scope_id, type, channel)
);

CREATE TABLE push_subscriptions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint   VARCHAR(1000)            NOT NULL UNIQUE,
    p256dh     VARCHAR(200)             NOT NULL,
    auth       VARCHAR(100)             NOT NULL,
    user_agent VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
