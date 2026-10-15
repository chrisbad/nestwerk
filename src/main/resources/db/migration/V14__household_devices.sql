-- Wand-Tablets für den Putzplan und PIN je Familienmitglied
CREATE TABLE household_devices (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(100)             NOT NULL,
    token_hash         VARCHAR(64) UNIQUE,
    pairing_hash       VARCHAR(64),
    pairing_expires_at TIMESTAMP WITH TIME ZONE,
    created_by         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMP WITH TIME ZONE,
    revoked_at         TIMESTAMP WITH TIME ZONE
);
CREATE INDEX household_devices_pairing_idx ON household_devices (pairing_hash);

ALTER TABLE family_members ADD COLUMN pin_hash VARCHAR(100);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('HOUSEHOLD_DEVICE_MANAGE', 'Verwaltung', 'Wand-Tablets koppeln',
        'Tablets für den Putzplan koppeln und wieder abmelden', 50);
