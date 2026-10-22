-- Household: a chore can be restricted to one booking per day
ALTER TABLE chores ADD COLUMN once_per_day BOOLEAN NOT NULL DEFAULT FALSE;
