-- Chores and events can forbid that chores are planned over them or over each other
ALTER TABLE chores ADD COLUMN no_overlap BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE calendar_events ADD COLUMN no_overlap BOOLEAN NOT NULL DEFAULT FALSE;
