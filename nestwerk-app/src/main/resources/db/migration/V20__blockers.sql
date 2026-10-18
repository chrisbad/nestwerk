-- Blocker: wiederkehrende Arbeits-, Schlaf-, Ruhe- und Freizeiten; Ruhe-Ziele; Hausarbeiten mit Uhrzeit
CREATE TABLE blockers (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(100)             NOT NULL,
    kind          VARCHAR(10)              NOT NULL,
    start_time    TIME                     NOT NULL,
    end_time      TIME                     NOT NULL,
    weekdays      SMALLINT                 NOT NULL,
    valid_from    DATE                     NOT NULL,
    valid_until   DATE,
    strength      VARCHAR(10)              NOT NULL,
    visibility    VARCHAR(10)              NOT NULL,
    quiet         BOOLEAN                  NOT NULL DEFAULT FALSE,
    holidays_off  BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version       BIGINT                   NOT NULL DEFAULT 0
);

CREATE TABLE blocker_members (
    blocker_id BIGINT NOT NULL REFERENCES blockers (id) ON DELETE CASCADE,
    member_id  BIGINT NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    PRIMARY KEY (blocker_id, member_id)
);
CREATE INDEX blocker_members_member_idx ON blocker_members (member_id);

-- Personen, die trotz des Blockers einplanen dürfen
CREATE TABLE blocker_planners (
    blocker_id BIGINT NOT NULL REFERENCES blockers (id) ON DELETE CASCADE,
    member_id  BIGINT NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    PRIMARY KEY (blocker_id, member_id)
);

-- einzelne Tage, an denen der Blocker entfällt (Urlaub, Gleittag)
CREATE TABLE blocker_skips (
    blocker_id BIGINT NOT NULL REFERENCES blockers (id) ON DELETE CASCADE,
    day        DATE   NOT NULL,
    PRIMARY KEY (blocker_id, day)
);

CREATE TABLE rest_goals (
    id           BIGSERIAL PRIMARY KEY,
    member_id    BIGINT       NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    kind         VARCHAR(10)  NOT NULL,
    window_start TIME,
    window_end   TIME,
    target       INTEGER      NOT NULL,
    label        VARCHAR(100) NOT NULL
);

ALTER TABLE chores ADD COLUMN window_start TIME;
ALTER TABLE chores ADD COLUMN window_end TIME;
ALTER TABLE chore_tasks ADD COLUMN planned_at TIME;
