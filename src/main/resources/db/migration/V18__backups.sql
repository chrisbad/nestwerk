-- Sicherungen: Verlauf der Läufe und Recht zum Verwalten
CREATE TABLE backup_runs (
    id          BIGSERIAL PRIMARY KEY,
    kind        VARCHAR(20)              NOT NULL,
    status      VARCHAR(20)              NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    file_name   VARCHAR(200),
    size_bytes  BIGINT,
    message     VARCHAR(2000),
    started_by  BIGINT REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX backup_runs_started_idx ON backup_runs (started_at DESC);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('BACKUP_MANAGE', 'Verwaltung', 'Sicherungen verwalten',
        'Sicherungen anlegen, herunterladen, einstellen und Daten wiederherstellen', 70);

-- Zugangsdaten der Sicherungsziele (z. B. ein privater SSH-Schlüssel) sind verschlüsselt länger als 2000 Zeichen
ALTER TABLE app_settings ALTER COLUMN value TYPE TEXT;
