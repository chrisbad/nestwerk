-- Schule: Klassenarbeiten und Termine sowie Schulferien je Bundesland
CREATE TABLE school_exam (
    id         BIGSERIAL PRIMARY KEY,
    year_id    BIGINT       NOT NULL REFERENCES school_year (id) ON DELETE CASCADE,
    subject_id BIGINT REFERENCES school_subject (id) ON DELETE SET NULL,
    kind       VARCHAR(15)  NOT NULL,
    title      VARCHAR(150) NOT NULL,
    day        DATE         NOT NULL,
    study_days INT          NOT NULL DEFAULT 0 CHECK (study_days BETWEEN 0 AND 60),
    event_id   BIGINT REFERENCES calendar_events (id) ON DELETE SET NULL
);
CREATE INDEX school_exam_year_idx ON school_exam (year_id, day);

CREATE TABLE school_holiday (
    id          BIGSERIAL PRIMARY KEY,
    state       VARCHAR(2)   NOT NULL,
    name        VARCHAR(100) NOT NULL,
    starts_on   DATE         NOT NULL,
    ends_on     DATE         NOT NULL,
    source      VARCHAR(10)  NOT NULL,
    external_id VARCHAR(64),
    CHECK (ends_on >= starts_on)
);
CREATE INDEX school_holiday_state_idx ON school_holiday (state, starts_on);
