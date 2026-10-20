-- Schule: Fächer, Stundenplan mit A/B-Wochen, Ausfall/Vertretung und die daraus erzeugten Blocker
ALTER TABLE school_year ADD COLUMN week_a_start DATE;

CREATE TABLE school_subject (
    id      BIGSERIAL PRIMARY KEY,
    year_id BIGINT       NOT NULL REFERENCES school_year (id) ON DELETE CASCADE,
    name    VARCHAR(60)  NOT NULL,
    color   VARCHAR(7)   NOT NULL,
    teacher VARCHAR(100)
);
CREATE UNIQUE INDEX school_subject_name_idx ON school_subject (year_id, lower(name));

CREATE TABLE school_lesson (
    id         BIGSERIAL PRIMARY KEY,
    year_id    BIGINT      NOT NULL REFERENCES school_year (id) ON DELETE CASCADE,
    subject_id BIGINT      NOT NULL REFERENCES school_subject (id) ON DELETE CASCADE,
    weekday    SMALLINT    NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    starts_at  TIME        NOT NULL,
    ends_at    TIME        NOT NULL,
    room       VARCHAR(30),
    week_kind  VARCHAR(5)  NOT NULL DEFAULT 'EVERY',
    CHECK (ends_at > starts_at)
);
CREATE INDEX school_lesson_year_idx ON school_lesson (year_id, weekday);

CREATE TABLE school_lesson_change (
    id        BIGSERIAL PRIMARY KEY,
    lesson_id BIGINT       NOT NULL REFERENCES school_lesson (id) ON DELETE CASCADE,
    day       DATE         NOT NULL,
    kind      VARCHAR(12)  NOT NULL,
    note      VARCHAR(200),
    UNIQUE (lesson_id, day)
);

-- vom Stundenplan verwaltete Blocker; werden bei jeder Änderung neu erzeugt
CREATE TABLE school_blocker (
    blocker_id BIGINT PRIMARY KEY REFERENCES blockers (id) ON DELETE CASCADE,
    year_id    BIGINT NOT NULL REFERENCES school_year (id) ON DELETE CASCADE
);
CREATE INDEX school_blocker_year_idx ON school_blocker (year_id);
