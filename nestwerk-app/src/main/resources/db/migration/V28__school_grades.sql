-- Schule: Noten (in Zehnteln: 17 = 1,7 = 2+) und Zeugnisnoten je Halbjahr
CREATE TABLE school_grade (
    id         BIGSERIAL PRIMARY KEY,
    year_id    BIGINT      NOT NULL REFERENCES school_year (id) ON DELETE CASCADE,
    subject_id BIGINT      NOT NULL REFERENCES school_subject (id) ON DELETE CASCADE,
    term       SMALLINT    NOT NULL CHECK (term IN (1, 2)),
    tenths     SMALLINT    NOT NULL CHECK (tenths BETWEEN 10 AND 60),
    weight     SMALLINT    NOT NULL DEFAULT 100 CHECK (weight BETWEEN 25 AND 400),
    kind       VARCHAR(10) NOT NULL,
    day        DATE        NOT NULL,
    note       VARCHAR(200)
);
CREATE INDEX school_grade_year_idx ON school_grade (year_id, term);

CREATE TABLE school_report_grade (
    id         BIGSERIAL PRIMARY KEY,
    subject_id BIGINT   NOT NULL REFERENCES school_subject (id) ON DELETE CASCADE,
    term       SMALLINT NOT NULL CHECK (term IN (1, 2)),
    tenths     SMALLINT NOT NULL CHECK (tenths BETWEEN 10 AND 60),
    UNIQUE (subject_id, term)
);
