-- Calendar: event categories with a colour from a fixed palette
CREATE TABLE calendar_categories (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(60) NOT NULL,
    color VARCHAR(20) NOT NULL
);
CREATE UNIQUE INDEX calendar_categories_name_idx ON calendar_categories (lower(name));

INSERT INTO calendar_categories (name, color)
VALUES ('Arzt', 'RED'),
       ('Schule', 'BLUE'),
       ('Sport', 'GREEN'),
       ('Arbeit', 'SLATE'),
       ('Familie', 'ORANGE'),
       ('Geburtstag', 'PINK');

ALTER TABLE calendar_events
    ADD COLUMN category_id BIGINT REFERENCES calendar_categories (id) ON DELETE SET NULL;

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('CALENDAR_CATEGORY_MANAGE', 'Kalender', 'Terminkategorien verwalten',
        'Kategorien und ihre Farben anlegen, ändern und löschen', 180);

-- Household: a time set by hand in the calendar survives automatic re-planning
ALTER TABLE chore_tasks ADD COLUMN planned_manually BOOLEAN NOT NULL DEFAULT FALSE;
