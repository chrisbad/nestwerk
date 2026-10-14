-- Rechtekatalog in der Datenbank; der Code kennt nur noch die Schlüssel.
CREATE TABLE permissions (
    key         VARCHAR(100) PRIMARY KEY,
    module      VARCHAR(100) NOT NULL,
    label       VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    sort_order  INT          NOT NULL,
    system      BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('USER_MANAGE', 'Verwaltung', 'Benutzer verwalten',
        'Konten anlegen, Rollen zuweisen, Passwörter zurücksetzen', 10),
       ('ACCESS_ROLE_MANAGE', 'Verwaltung', 'Rollen und Rechte verwalten',
        'Rollen, Rechte und Zugriffsregeln bearbeiten', 20),
       ('MEMBER_MANAGE', 'Verwaltung', 'Familienmitglieder verwalten',
        'Mitglieder anlegen, Farben und Konten zuordnen', 30),
       ('CALENDAR_VIEW', 'Kalender', 'Kalender ansehen', NULL, 110),
       ('CALENDAR_EVENT_CREATE', 'Kalender', 'Termine anlegen', NULL, 120),
       ('CALENDAR_EVENT_EDIT_OWN', 'Kalender', 'Eigene Termine bearbeiten', NULL, 130),
       ('CALENDAR_EVENT_EDIT_ALL', 'Kalender', 'Alle Termine bearbeiten', NULL, 140),
       ('CALENDAR_EVENT_DELETE_OWN', 'Kalender', 'Eigene Termine löschen', NULL, 150),
       ('CALENDAR_EVENT_DELETE_ALL', 'Kalender', 'Alle Termine löschen', NULL, 160),
       ('CALENDAR_PRIVATE_CREATE', 'Kalender', 'Private Termine anlegen', NULL, 170);

-- Altdaten mit unbekannten Rechten entfernen, danach Fremdschlüssel erzwingen
DELETE FROM role_permissions WHERE permission NOT IN (SELECT key FROM permissions);

ALTER TABLE role_permissions
    ADD CONSTRAINT role_permissions_permission_fk
        FOREIGN KEY (permission) REFERENCES permissions (key) ON DELETE CASCADE;
