-- School data in the schema of V29 (school and teachers as free text). MigrationUpgradeTest migrates it to the
-- latest version and checks that school and teachers became contacts.

INSERT INTO users (id, username, password_hash, enabled, email)
VALUES (1001, 'mama', '{noop}x', TRUE, NULL);
INSERT INTO user_roles (user_id, role_id)
SELECT 1001, id FROM roles WHERE name = 'Administrator';

INSERT INTO family_members (id, name, color, user_id)
VALUES (2001, 'Lena', '#ff9f1c', NULL),
       (2002, 'Tom', '#123456', NULL);

INSERT INTO school_profile (id, member_id, state)
VALUES (4001, 2001, 'BY'),
       (4002, 2002, 'BY');

INSERT INTO school_year (id, profile_id, label, school, class_name, starts_on, ends_on, active)
VALUES (4101, 4001, '2026/27', 'Grundschule am Park', '3a', '2026-08-01', '2027-07-31', TRUE),
       (4102, 4002, '2026/27', 'Grundschule am Park', '1b', '2026-08-01', '2027-07-31', TRUE);

INSERT INTO school_subject (id, year_id, name, color, teacher)
VALUES (4201, 4101, 'Mathe', '#4d74ff', 'Frau Weber'),
       (4202, 4102, 'Mathe', '#4d74ff', ' Frau Weber '),
       (4203, 4101, 'Kunst', '#000000', NULL);

INSERT INTO school_lesson (id, year_id, subject_id, weekday, starts_at, ends_at, room, week_kind)
VALUES (4251, 4101, 4201, 1, '08:00', '08:45', 'R12', 'EVERY');

INSERT INTO school_exam (id, year_id, subject_id, kind, title, day, study_days)
VALUES (4301, 4101, 4201, 'EXAM', 'Brüche', '2026-10-20', 3);
