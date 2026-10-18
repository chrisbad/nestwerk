-- Haushalt: Hausarbeiten (Vorlagen), konkrete Aufgaben, Gutschriften; Abwesenheit im Kalender
CREATE TABLE chores (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(150)             NOT NULL,
    area             VARCHAR(100),
    description      VARCHAR(2000),
    minutes          INT                      NOT NULL CHECK (minutes BETWEEN 1 AND 600),
    effort           VARCHAR(10)              NOT NULL,
    kind             VARCHAR(10)              NOT NULL,
    rrule            VARCHAR(500),
    starts_on        DATE,
    due_time         TIME,
    interval_days    INT CHECK (interval_days BETWEEN 1 AND 365),
    distribution     VARCHAR(10)              NOT NULL,
    active           BOOLEAN                  NOT NULL DEFAULT TRUE,
    ical_url         VARCHAR(500),
    ical_filter      VARCHAR(100),
    ical_days_before INT                      NOT NULL DEFAULT 0,
    ical_time        TIME,
    ical_synced_at   TIMESTAMP WITH TIME ZONE,
    created_by       BIGINT                   NOT NULL REFERENCES users (id),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version          BIGINT                   NOT NULL DEFAULT 0
);

-- Kandidaten in Reihenfolge (für reihum)
CREATE TABLE chore_members (
    chore_id  BIGINT NOT NULL REFERENCES chores (id) ON DELETE CASCADE,
    position  INT    NOT NULL,
    member_id BIGINT NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    PRIMARY KEY (chore_id, position)
);

-- Feste Termine (von Hand oder aus iCal)
CREATE TABLE chore_dates (
    id           BIGSERIAL PRIMARY KEY,
    chore_id     BIGINT      NOT NULL REFERENCES chores (id) ON DELETE CASCADE,
    due_on       DATE        NOT NULL,
    due_time     TIME,
    source       VARCHAR(10) NOT NULL,
    external_uid VARCHAR(255)
);
CREATE INDEX chore_dates_chore_idx ON chore_dates (chore_id, due_on);

CREATE TABLE chore_tasks (
    id                 BIGSERIAL PRIMARY KEY,
    chore_id           BIGINT                   NOT NULL REFERENCES chores (id) ON DELETE CASCADE,
    slot_key           VARCHAR(40)              NOT NULL,
    due_on             DATE                     NOT NULL,
    due_time           TIME,
    assignee_member_id BIGINT REFERENCES family_members (id) ON DELETE SET NULL,
    status             VARCHAR(10)              NOT NULL DEFAULT 'OPEN',
    completed_at       TIMESTAMP WITH TIME ZONE,
    completed_by_user  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    notified_due       BOOLEAN                  NOT NULL DEFAULT FALSE,
    version            BIGINT                   NOT NULL DEFAULT 0,
    UNIQUE (chore_id, slot_key)
);
CREATE INDEX chore_tasks_due_idx ON chore_tasks (due_on, status);

CREATE TABLE chore_credits (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT                   NOT NULL REFERENCES chore_tasks (id) ON DELETE CASCADE,
    member_id         BIGINT                   NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    minutes           INT                      NOT NULL,
    effort            VARCHAR(10)              NOT NULL,
    corrected_minutes INT CHECK (corrected_minutes BETWEEN 0 AND 600),
    correction_reason VARCHAR(200),
    corrected_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    corrected_at      TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (task_id, member_id)
);
CREATE INDEX chore_credits_member_idx ON chore_credits (member_id, created_at);

ALTER TABLE calendar_events ADD COLUMN absence BOOLEAN NOT NULL DEFAULT FALSE;

-- ACL: Objekttyp Hausarbeiten
INSERT INTO acl_object_types (key, label, sort_order)
VALUES ('CHORE', 'Hausarbeiten', 30);

INSERT INTO acl_actions (object_type, key, label, sort_order)
VALUES ('CHORE', 'VIEW', 'Putzplan ansehen', 10),
       ('CHORE', 'CREATE', 'Anlegen', 20),
       ('CHORE', 'EDIT', 'Bearbeiten', 30),
       ('CHORE', 'DELETE', 'Löschen und deaktivieren', 40),
       ('CHORE', 'COMPLETE', 'Abhaken', 50),
       ('CHORE', 'ASSIGN', 'Umverteilen', 60),
       ('CHORE', 'CORRECT', 'Gutschriften korrigieren', 70),
       ('CHORE', 'STATS', 'Auswertung aller Mitglieder', 80);

INSERT INTO acl_cells (object_type, action, relation)
VALUES ('CHORE', 'VIEW', 'ANY'),
       ('CHORE', 'VIEW', 'ASSIGNED'),
       ('CHORE', 'CREATE', 'ANY'),
       ('CHORE', 'EDIT', 'ANY'),
       ('CHORE', 'EDIT', 'CREATOR'),
       ('CHORE', 'DELETE', 'ANY'),
       ('CHORE', 'DELETE', 'CREATOR'),
       ('CHORE', 'COMPLETE', 'ANY'),
       ('CHORE', 'COMPLETE', 'ASSIGNED'),
       ('CHORE', 'ASSIGN', 'ANY'),
       ('CHORE', 'ASSIGN', 'ASSIGNED'),
       ('CHORE', 'CORRECT', 'ANY'),
       ('CHORE', 'STATS', 'ANY');

INSERT INTO acl_preset_rules (preset_key, object_type, action, relation)
SELECT 'ELTERNTEIL', object_type, action, relation
FROM acl_cells
WHERE object_type = 'CHORE';

INSERT INTO acl_preset_rules (preset_key, object_type, action, relation)
VALUES ('KIND', 'CHORE', 'VIEW', 'ANY'),
       ('KIND', 'CHORE', 'VIEW', 'ASSIGNED'),
       ('KIND', 'CHORE', 'COMPLETE', 'ASSIGNED'),
       ('KIND', 'CHORE', 'ASSIGN', 'ASSIGNED'),
       ('GAST', 'CHORE', 'VIEW', 'ANY');

INSERT INTO acl_rules (role_id, object_type, action, relation)
SELECT r.id, v.object_type, v.action, v.relation
FROM roles r
         CROSS JOIN (VALUES ('CHORE', 'VIEW', 'ANY'),
                            ('CHORE', 'VIEW', 'ASSIGNED'),
                            ('CHORE', 'COMPLETE', 'ANY'),
                            ('CHORE', 'COMPLETE', 'ASSIGNED'),
                            ('CHORE', 'ASSIGN', 'ASSIGNED')) AS v (object_type, action, relation)
WHERE r.name = 'Benutzer';
