-- Updates aus der App: Verlauf der Läufe und Recht zum Einspielen
CREATE TABLE update_runs (
    id           BIGSERIAL PRIMARY KEY,
    job_id       VARCHAR(40)              NOT NULL UNIQUE,
    from_version VARCHAR(60)              NOT NULL,
    to_version   VARCHAR(60)              NOT NULL,
    channel      VARCHAR(10)              NOT NULL,
    status       VARCHAR(20)              NOT NULL,
    step         VARCHAR(20),
    message      VARCHAR(2000),
    log          TEXT,
    backup_file  VARCHAR(200),
    requested_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX update_runs_requested_idx ON update_runs (requested_at DESC);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('UPDATE_MANAGE', 'Verwaltung', 'Updates verwalten', 'Neue Versionen prüfen und einspielen', 80);
