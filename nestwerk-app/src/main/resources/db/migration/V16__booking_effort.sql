-- Aufwand und Zeitpunkt werden beim Buchen gewählt: je Hausarbeit drei Zeitaufwände statt einer festen Dauer
ALTER TABLE chores ADD COLUMN minutes_easy INT;
ALTER TABLE chores ADD COLUMN minutes_medium INT;
ALTER TABLE chores ADD COLUMN minutes_hard INT;

UPDATE chores SET minutes_easy = minutes, minutes_medium = minutes, minutes_hard = minutes;

ALTER TABLE chores ALTER COLUMN minutes_easy SET NOT NULL;
ALTER TABLE chores ALTER COLUMN minutes_medium SET NOT NULL;
ALTER TABLE chores ALTER COLUMN minutes_hard SET NOT NULL;
ALTER TABLE chores ADD CONSTRAINT chores_minutes_easy_check CHECK (minutes_easy BETWEEN 1 AND 600);
ALTER TABLE chores ADD CONSTRAINT chores_minutes_medium_check CHECK (minutes_medium BETWEEN 1 AND 600);
ALTER TABLE chores ADD CONSTRAINT chores_minutes_hard_check CHECK (minutes_hard BETWEEN 1 AND 600);

ALTER TABLE chores DROP COLUMN minutes;
ALTER TABLE chores DROP COLUMN effort;

-- Gebuchte Stufe und Zeitpunkt der Buchung; completed_at ist der Zeitpunkt der Durchführung
ALTER TABLE chore_tasks ADD COLUMN effort VARCHAR(10);
ALTER TABLE chore_tasks ADD COLUMN booked_at TIMESTAMP WITH TIME ZONE;

UPDATE chore_tasks t SET booked_at = t.completed_at,
                         effort = (SELECT c.effort FROM chore_credits c WHERE c.task_id = t.id LIMIT 1)
WHERE t.status = 'DONE';
