-- Schule: Profil je Familienmitglied und Schuljahre
CREATE TABLE school_profile (
    id         BIGSERIAL PRIMARY KEY,
    member_id  BIGINT                   NOT NULL UNIQUE REFERENCES family_members (id) ON DELETE CASCADE,
    state      VARCHAR(2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE school_year (
    id         BIGSERIAL PRIMARY KEY,
    profile_id BIGINT       NOT NULL REFERENCES school_profile (id) ON DELETE CASCADE,
    label      VARCHAR(30)  NOT NULL,
    school     VARCHAR(150) NOT NULL,
    class_name VARCHAR(30)  NOT NULL,
    starts_on  DATE         NOT NULL,
    ends_on    DATE         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT FALSE,
    CHECK (ends_on > starts_on)
);
-- je Profil höchstens ein aktives Schuljahr
CREATE UNIQUE INDEX school_year_active_idx ON school_year (profile_id) WHERE active;

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('SCHOOL_VIEW', 'Schule', 'Schule ansehen', 'Stundenplan, Hausaufgaben, Klassenarbeiten und Ferien sehen', 310),
       ('SCHOOL_EDIT', 'Schule', 'Schule pflegen', 'Schuljahre, Fächer, Stundenplan, Klassenarbeiten und Elternbriefe bearbeiten', 320),
       ('SCHOOL_HOMEWORK', 'Schule', 'Hausaufgaben', 'Hausaufgaben anlegen und erledigen', 330),
       ('SCHOOL_GRADES', 'Schule', 'Noten aller Kinder', 'Noten aller Kinder sehen und pflegen', 340),
       ('SCHOOL_GRADES_OWN', 'Schule', 'Eigene Noten', 'Nur die eigenen Noten sehen', 350),
       ('SCHOOL_SETTINGS', 'Schule', 'Schule einstellen', 'Schulprofile, Bundesland und Ferienabruf', 360);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
         CROSS JOIN (VALUES ('SCHOOL_VIEW'),
                            ('SCHOOL_HOMEWORK'),
                            ('SCHOOL_GRADES_OWN')) AS p (permission)
WHERE r.name = 'Benutzer' AND r.system_role;
