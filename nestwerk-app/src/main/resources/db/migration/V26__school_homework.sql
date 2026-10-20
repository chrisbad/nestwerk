-- Schule: Hausaufgaben sind ToDos; diese Tabelle ordnet sie Schuljahr und Fach zu
CREATE TABLE school_homework (
    todo_id    BIGINT PRIMARY KEY REFERENCES todos (id) ON DELETE CASCADE,
    year_id    BIGINT NOT NULL REFERENCES school_year (id) ON DELETE CASCADE,
    subject_id BIGINT REFERENCES school_subject (id) ON DELETE SET NULL
);
CREATE INDEX school_homework_year_idx ON school_homework (year_id);
