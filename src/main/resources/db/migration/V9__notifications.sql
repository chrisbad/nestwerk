-- Benachrichtigungen (modulunabhängig) und einfache Einstellungen
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(40)              NOT NULL,
    source_type VARCHAR(20)              NOT NULL,
    source_id   BIGINT                   NOT NULL,
    due_key     VARCHAR(40)              NOT NULL,
    title       VARCHAR(200)             NOT NULL,
    body        TEXT,
    link        VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    read_at     TIMESTAMP WITH TIME ZONE,
    UNIQUE (user_id, type, source_type, source_id, due_key)
);
CREATE INDEX notifications_user_unread_idx ON notifications (user_id, read_at, created_at DESC);

CREATE TABLE app_settings (
    key   VARCHAR(100) PRIMARY KEY,
    value VARCHAR(2000)
);

INSERT INTO app_settings (key, value)
VALUES ('reminders.hour', '8'),
       ('reminders.defaultLeadDays', '2');

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('NOTIFICATION_SETTINGS_MANAGE', 'Verwaltung', 'Benachrichtigungen einstellen',
        'Erinnerungszeit, Standard-Kanäle sowie E-Mail und Push einrichten', 40);
