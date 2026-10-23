-- A planned chore can be made longer or shorter for its own date; without a value the chore's medium effort applies
ALTER TABLE chore_tasks ADD COLUMN planned_minutes INTEGER;
