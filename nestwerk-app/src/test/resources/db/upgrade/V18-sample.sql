-- Bestandsdaten im Schema von V18 (Stand vor „Updates aus der App“). MigrationUpgradeTest lädt sie und migriert
-- danach auf den neuesten Stand. Bei neuen Releases einen weiteren Snapshot anlegen statt diesen zu ändern.

INSERT INTO users (id, username, password_hash, enabled, email)
VALUES (1001, 'mama', '{noop}x', TRUE, 'mama@example.org'),
       (1002, 'papa', '{noop}x', TRUE, NULL);
INSERT INTO user_roles (user_id, role_id)
SELECT 1001, id FROM roles WHERE name = 'Administrator';
INSERT INTO user_roles (user_id, role_id)
SELECT 1002, id FROM roles WHERE name = 'Benutzer';

INSERT INTO family_members (id, name, color, user_id)
VALUES (2001, 'Mama', '#8b6cff', 1001),
       (2002, 'Lio', '#ff9f1c', NULL);

INSERT INTO calendar_events (id, uid, title, description, location, starts_at, ends_at, all_day, timezone, rrule,
                             private_event, created_by, absence)
VALUES (3001, 'evt-3001@nestwerk', 'Elternabend', 'Mit Umlauten äöü', 'Schule', '2026-10-20 17:00:00+00',
        '2026-10-20 19:00:00+00', FALSE, 'Europe/Berlin', NULL, FALSE, 1001, FALSE),
       (3002, 'evt-3002@nestwerk', 'Schwimmen', NULL, NULL, '2026-10-06 15:00:00+00', '2026-10-06 16:00:00+00',
        FALSE, 'Europe/Berlin', 'FREQ=WEEKLY;BYDAY=TU', FALSE, 1002, FALSE),
       (3003, 'evt-3003@nestwerk', 'Urlaub', NULL, NULL, '2026-11-02 00:00:00+00', '2026-11-07 00:00:00+00', TRUE,
        'Europe/Berlin', NULL, TRUE, 1001, TRUE);
INSERT INTO calendar_event_members (event_id, member_id)
VALUES (3001, 2001), (3002, 2002), (3003, 2001);
INSERT INTO calendar_event_exceptions (id, event_id, recurrence_id, cancelled)
VALUES (3101, 3002, '2026-10-13 15:00:00+00', TRUE);

INSERT INTO contacts (id, name, category, phone, email, city, note, created_by)
VALUES (4001, 'Dr. Sommer', 'Arzt', '0123 456', 'praxis@example.org', 'Berlin', 'Kinderarzt', 1001);

INSERT INTO todos (id, title, description, status, priority, due_on, responsible_user_id, created_by,
                   completion_mode, private_todo, rrule)
VALUES (5001, 'Keller aufräumen', 'Regal **zuerst**', 'OPEN', 'HIGH', '2026-10-25', 1001, 1001, 'ANY', FALSE,
        NULL),
       (5002, 'Müll rausbringen', NULL, 'DONE', 'NORMAL', NULL, 1002, 1001, 'ANY', FALSE, 'FREQ=WEEKLY');
INSERT INTO todos (id, title, status, responsible_user_id, created_by, parent_id)
VALUES (5003, 'Regal abbauen', 'OPEN', 1001, 1001, 5001);
INSERT INTO todo_tags (todo_id, tag) VALUES (5001, 'Haus'), (5002, 'Haus');
INSERT INTO todo_watchers (todo_id, user_id) VALUES (5001, 1002);
INSERT INTO todo_assignees (id, todo_id, member_id) VALUES (5101, 5001, 2001);
INSERT INTO todo_comments (id, todo_id, author_id, body) VALUES (5201, 5001, 1002, 'Mach ich am Samstag');
INSERT INTO todo_history (id, todo_id, actor_id, field, old_value, new_value)
VALUES (5301, 5001, 1001, 'priority', 'NORMAL', 'HIGH');
INSERT INTO todo_completions (id, todo_id, member_id, completed_by) VALUES (5401, 5002, 2002, 1002);
INSERT INTO todo_links (id, source_id, target_id, type, created_by) VALUES (5501, 5003, 5001, 'BLOCKS', 1001);
INSERT INTO todo_event_links (todo_id, event_id) VALUES (5001, 3001);

INSERT INTO chores (id, title, area, kind, rrule, starts_on, due_time, distribution, created_by, minutes_easy,
                    minutes_medium, minutes_hard)
VALUES (6001, 'Bad putzen', 'Bad', 'RECURRING', 'FREQ=WEEKLY;BYDAY=SA', '2026-10-03', '18:00', 'ROTATION', 1001, 15,
        25, 40);
INSERT INTO chore_members (chore_id, position, member_id) VALUES (6001, 0, 2001), (6001, 1, 2002);
INSERT INTO chore_tasks (id, chore_id, slot_key, due_on, due_time, assignee_member_id, status)
VALUES (6101, 6001, '2026-10-17', '2026-10-17', '18:00', 2002, 'DONE'),
       (6102, 6001, '2026-10-24', '2026-10-24', '18:00', 2001, 'OPEN');
INSERT INTO chore_credits (id, task_id, member_id, minutes, effort) VALUES (6201, 6101, 2002, 25, 'MEDIUM');
INSERT INTO rewards (id, title, cost_minutes) VALUES (6301, 'Kinoabend', 120);
INSERT INTO reward_redemptions (id, reward_id, member_id, cost_minutes, status, requested_by, requested_at)
VALUES (6401, 6301, 2002, 120, 'REQUESTED', 1002, '2026-10-16 10:00:00+00');

INSERT INTO notifications (id, user_id, type, source_type, source_id, due_key, title, body, link)
VALUES (7001, 1001, 'TODO_ASSIGNED', 'TODO', 5001, 'x', 'Neu zugewiesen', 'Keller aufräumen', '/todos/5001');
INSERT INTO app_settings (key, value) VALUES ('reminders.hour', '7')
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
INSERT INTO backup_runs (id, kind, status, started_at, finished_at, file_name, size_bytes, message, started_by)
VALUES (8001, 'SCHEDULED', 'SUCCESS', '2026-10-16 02:30:00+00', '2026-10-16 02:30:05+00',
        'nestwerk-20261016-023000.zip', 12345, 'ok', NULL);
