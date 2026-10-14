-- Regelbasiertes ACL: Rolle darf Aktion auf Objekttyp bei Beziehung.
CREATE TABLE acl_object_types (
    key        VARCHAR(50) PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL
);

CREATE TABLE acl_actions (
    object_type VARCHAR(50)  NOT NULL REFERENCES acl_object_types (key),
    key         VARCHAR(50)  NOT NULL,
    label       VARCHAR(100) NOT NULL,
    sort_order  INT          NOT NULL,
    PRIMARY KEY (object_type, key)
);

CREATE TABLE acl_relations (
    key        VARCHAR(50) PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL
);

-- Gültige Kombinationen (Zellen der Matrix)
CREATE TABLE acl_cells (
    object_type VARCHAR(50) NOT NULL,
    action      VARCHAR(50) NOT NULL,
    relation    VARCHAR(50) NOT NULL REFERENCES acl_relations (key),
    PRIMARY KEY (object_type, action, relation),
    FOREIGN KEY (object_type, action) REFERENCES acl_actions (object_type, key)
);

CREATE TABLE acl_rules (
    role_id     BIGINT      NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    object_type VARCHAR(50) NOT NULL,
    action      VARCHAR(50) NOT NULL,
    relation    VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, object_type, action, relation),
    FOREIGN KEY (object_type, action, relation) REFERENCES acl_cells (object_type, action, relation)
);

CREATE TABLE acl_presets (
    key        VARCHAR(50) PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL
);

CREATE TABLE acl_preset_rules (
    preset_key  VARCHAR(50) NOT NULL REFERENCES acl_presets (key) ON DELETE CASCADE,
    object_type VARCHAR(50) NOT NULL,
    action      VARCHAR(50) NOT NULL,
    relation    VARCHAR(50) NOT NULL,
    PRIMARY KEY (preset_key, object_type, action, relation),
    FOREIGN KEY (object_type, action, relation) REFERENCES acl_cells (object_type, action, relation)
);

INSERT INTO acl_object_types (key, label, sort_order)
VALUES ('TODO', 'ToDos', 10),
       ('CONTACT', 'Kontakte', 20);

INSERT INTO acl_relations (key, label, sort_order)
VALUES ('ANY', 'Alle', 10),
       ('RESPONSIBLE', 'Verantwortlich', 20),
       ('ASSIGNED', 'Zuständig', 30),
       ('WATCHING', 'Beobachtend', 40),
       ('CREATOR', 'Ersteller', 50);

INSERT INTO acl_actions (object_type, key, label, sort_order)
VALUES ('TODO', 'VIEW', 'Ansehen', 10),
       ('TODO', 'CREATE', 'Anlegen', 20),
       ('TODO', 'CREATE_PRIVATE', 'Privat anlegen', 30),
       ('TODO', 'EDIT', 'Bearbeiten', 40),
       ('TODO', 'DELETE', 'Löschen', 50),
       ('TODO', 'CHANGE_STATUS', 'Status ändern und abhaken', 60),
       ('TODO', 'ASSIGN', 'Zuständige ändern', 70),
       ('TODO', 'CHANGE_RESPONSIBLE', 'Verantwortlichen ändern', 80),
       ('TODO', 'WATCH', 'Selbst beobachten', 90),
       ('TODO', 'MANAGE_WATCHERS', 'Beobachter verwalten', 100),
       ('TODO', 'COMMENT', 'Kommentieren', 110),
       ('TODO', 'ATTACH', 'Anhänge hochladen', 120),
       ('TODO', 'LINK', 'Verknüpfen', 130),
       ('TODO', 'NOTIFY_OVERRIDE', 'Benachrichtigungen überschreiben', 140),
       ('CONTACT', 'VIEW', 'Ansehen', 10),
       ('CONTACT', 'CREATE', 'Anlegen', 20),
       ('CONTACT', 'EDIT', 'Bearbeiten', 30),
       ('CONTACT', 'DELETE', 'Löschen', 40),
       ('CONTACT', 'ATTACH', 'Anhänge hochladen', 50);

-- Anlegen hat noch keine Beziehung, daher nur "Alle"; Kontakte kennen nur "Alle" und "Ersteller".
INSERT INTO acl_cells (object_type, action, relation)
SELECT a.object_type, a.key, r.key
FROM acl_actions a
         CROSS JOIN acl_relations r
WHERE (a.object_type = 'TODO' AND (a.key NOT IN ('CREATE', 'CREATE_PRIVATE') OR r.key = 'ANY'))
   OR (a.object_type = 'CONTACT' AND r.key IN ('ANY', 'CREATOR') AND (a.key <> 'CREATE' OR r.key = 'ANY'));

INSERT INTO acl_presets (key, label, sort_order)
VALUES ('KIND', 'Kind', 10),
       ('ELTERNTEIL', 'Elternteil', 20),
       ('GAST', 'Gast (nur lesen)', 30);

INSERT INTO acl_preset_rules (preset_key, object_type, action, relation)
VALUES ('KIND', 'TODO', 'VIEW', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'VIEW', 'ASSIGNED'),
       ('KIND', 'TODO', 'VIEW', 'WATCHING'),
       ('KIND', 'TODO', 'CREATE', 'ANY'),
       ('KIND', 'TODO', 'EDIT', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'CHANGE_STATUS', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'CHANGE_STATUS', 'ASSIGNED'),
       ('KIND', 'TODO', 'WATCH', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'WATCH', 'ASSIGNED'),
       ('KIND', 'TODO', 'COMMENT', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'COMMENT', 'ASSIGNED'),
       ('KIND', 'TODO', 'COMMENT', 'WATCHING'),

       ('ELTERNTEIL', 'TODO', 'VIEW', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'VIEW', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'VIEW', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'VIEW', 'WATCHING'),
       ('ELTERNTEIL', 'TODO', 'CREATE', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'CREATE_PRIVATE', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'EDIT', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'EDIT', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'DELETE', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'CHANGE_STATUS', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'CHANGE_STATUS', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'CHANGE_STATUS', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'ASSIGN', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'ASSIGN', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'WATCH', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'WATCH', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'WATCH', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'MANAGE_WATCHERS', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'WATCHING'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'WATCHING'),
       ('ELTERNTEIL', 'TODO', 'LINK', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'LINK', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'CONTACT', 'VIEW', 'ANY'),
       ('ELTERNTEIL', 'CONTACT', 'CREATE', 'ANY'),
       ('ELTERNTEIL', 'CONTACT', 'EDIT', 'ANY'),
       ('ELTERNTEIL', 'CONTACT', 'ATTACH', 'ANY'),

       ('GAST', 'TODO', 'VIEW', 'ANY'),
       ('GAST', 'CONTACT', 'VIEW', 'ANY');

-- Startbelegung der Rolle "Benutzer". Private ToDos sind nur über die beteiligten Beziehungen
-- erreichbar, daher stehen VIEW/COMMENT/ATTACH/WATCH auch für diese Beziehungen.
INSERT INTO acl_rules (role_id, object_type, action, relation)
SELECT r.id, v.object_type, v.action, v.relation
FROM roles r
         CROSS JOIN (VALUES ('TODO', 'VIEW', 'ANY'),
                            ('TODO', 'VIEW', 'RESPONSIBLE'),
                            ('TODO', 'VIEW', 'ASSIGNED'),
                            ('TODO', 'VIEW', 'WATCHING'),
                            ('TODO', 'CREATE', 'ANY'),
                            ('TODO', 'CREATE_PRIVATE', 'ANY'),
                            ('TODO', 'EDIT', 'RESPONSIBLE'),
                            ('TODO', 'DELETE', 'RESPONSIBLE'),
                            ('TODO', 'LINK', 'RESPONSIBLE'),
                            ('TODO', 'ASSIGN', 'RESPONSIBLE'),
                            ('TODO', 'CHANGE_STATUS', 'RESPONSIBLE'),
                            ('TODO', 'CHANGE_STATUS', 'ASSIGNED'),
                            ('TODO', 'COMMENT', 'ANY'),
                            ('TODO', 'COMMENT', 'RESPONSIBLE'),
                            ('TODO', 'COMMENT', 'ASSIGNED'),
                            ('TODO', 'COMMENT', 'WATCHING'),
                            ('TODO', 'ATTACH', 'ANY'),
                            ('TODO', 'ATTACH', 'RESPONSIBLE'),
                            ('TODO', 'ATTACH', 'ASSIGNED'),
                            ('TODO', 'ATTACH', 'WATCHING'),
                            ('TODO', 'WATCH', 'ANY'),
                            ('TODO', 'WATCH', 'RESPONSIBLE'),
                            ('TODO', 'WATCH', 'ASSIGNED'),
                            ('TODO', 'WATCH', 'WATCHING'),
                            ('CONTACT', 'VIEW', 'ANY')) AS v (object_type, action, relation)
WHERE r.name = 'Benutzer';
