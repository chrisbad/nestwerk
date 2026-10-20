-- Schule: Schule und Lehrkräfte als Kontakte, Stundenraster mit Pausen, Termine mit Uhrzeit, Ort und Teilnehmenden

-- Ersteller für automatisch angelegte Kontakte: ältester Administrator, sonst ältester Benutzer
CREATE TEMPORARY TABLE school_migration_owner AS
SELECT COALESCE((SELECT min(ur.user_id)
                 FROM user_roles ur
                          JOIN roles r ON r.id = ur.role_id
                 WHERE r.all_permissions),
                (SELECT min(id) FROM users)) AS user_id;

-- Schule als Kontakt
ALTER TABLE school_year ADD COLUMN school_contact_id BIGINT REFERENCES contacts (id) ON DELETE SET NULL;

INSERT INTO contacts (name, category, created_by)
SELECT DISTINCT y.school, 'Schule', o.user_id
FROM school_year y
         CROSS JOIN school_migration_owner o
WHERE o.user_id IS NOT NULL;

UPDATE school_year y
SET school_contact_id = (SELECT min(c.id) FROM contacts c WHERE c.category = 'Schule' AND c.name = y.school);

ALTER TABLE school_year DROP COLUMN school;

-- Lehrkräfte als Kontakte, mehrere je Fach
CREATE TABLE school_subject_teacher (
    subject_id BIGINT NOT NULL REFERENCES school_subject (id) ON DELETE CASCADE,
    contact_id BIGINT NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    PRIMARY KEY (subject_id, contact_id)
);

INSERT INTO contacts (name, category, created_by)
SELECT DISTINCT trim(s.teacher), 'Lehrkraft', o.user_id
FROM school_subject s
         CROSS JOIN school_migration_owner o
WHERE s.teacher IS NOT NULL AND trim(s.teacher) <> '' AND o.user_id IS NOT NULL;

INSERT INTO school_subject_teacher (subject_id, contact_id)
SELECT s.id, (SELECT min(c.id) FROM contacts c WHERE c.category = 'Lehrkraft' AND c.name = trim(s.teacher))
FROM school_subject s
WHERE s.teacher IS NOT NULL AND trim(s.teacher) <> '';

ALTER TABLE school_subject DROP COLUMN teacher;

DROP TABLE school_migration_owner;

-- Stundenraster einer Schule mit Stunden und Pausen
CREATE TABLE school_period (
    id                BIGSERIAL PRIMARY KEY,
    school_contact_id BIGINT     NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    kind              VARCHAR(6) NOT NULL,
    number            SMALLINT,
    label             VARCHAR(40),
    starts_at         TIME       NOT NULL,
    ends_at           TIME       NOT NULL,
    CHECK (ends_at > starts_at),
    CHECK ((kind = 'LESSON') = (number IS NOT NULL))
);
CREATE INDEX school_period_school_idx ON school_period (school_contact_id, starts_at);
CREATE UNIQUE INDEX school_period_number_idx ON school_period (school_contact_id, number);

-- Stunden: Stunde von–bis aus dem Raster, sonst freie Zeit
ALTER TABLE school_lesson ADD COLUMN period_from SMALLINT;
ALTER TABLE school_lesson ADD COLUMN period_to SMALLINT;
ALTER TABLE school_lesson ADD CONSTRAINT school_lesson_period_check
    CHECK ((period_from IS NULL) = (period_to IS NULL) AND (period_to IS NULL OR period_to >= period_from));

-- Termine mit Uhrzeit, Ort und Teilnehmenden
ALTER TABLE school_exam ADD COLUMN starts_at TIME;
ALTER TABLE school_exam ADD COLUMN ends_at TIME;
ALTER TABLE school_exam ADD COLUMN location VARCHAR(150);

CREATE TABLE school_exam_member (
    exam_id   BIGINT NOT NULL REFERENCES school_exam (id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    PRIMARY KEY (exam_id, member_id)
);

INSERT INTO school_exam_member (exam_id, member_id)
SELECT e.id, p.member_id
FROM school_exam e
         JOIN school_year y ON y.id = e.year_id
         JOIN school_profile p ON p.id = y.profile_id;
