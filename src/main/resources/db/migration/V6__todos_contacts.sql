-- Freie Kontakte (Handwerker, Schule, Behörden …)
CREATE TABLE contacts (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150)             NOT NULL,
    category    VARCHAR(100),
    company     VARCHAR(150),
    phone       VARCHAR(50),
    email       VARCHAR(200),
    street      VARCHAR(200),
    postal_code VARCHAR(20),
    city        VARCHAR(100),
    url         VARCHAR(500),
    note        TEXT,
    created_by  BIGINT                   NOT NULL REFERENCES users (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version     INT                      NOT NULL DEFAULT 0
);

CREATE TABLE todos (
    id                  BIGSERIAL PRIMARY KEY,
    title               VARCHAR(200)             NOT NULL,
    description         TEXT,
    status              VARCHAR(20)              NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'DISCARDED')),
    priority            VARCHAR(10)              NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    available_from      DATE,
    due_on              DATE,
    follow_up_on        DATE,
    remind_days_before  INT CHECK (remind_days_before IS NULL OR remind_days_before BETWEEN 0 AND 365),
    completion_mode     VARCHAR(10)              NOT NULL DEFAULT 'ANY' CHECK (completion_mode IN ('ANY', 'EACH')),
    private_todo        BOOLEAN                  NOT NULL DEFAULT FALSE,
    responsible_user_id BIGINT                   NOT NULL REFERENCES users (id),
    created_by          BIGINT                   NOT NULL REFERENCES users (id),
    updated_by          BIGINT REFERENCES users (id),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at        TIMESTAMP WITH TIME ZONE,
    completed_by        BIGINT REFERENCES users (id),
    version             INT                      NOT NULL DEFAULT 0,
    CHECK (available_from IS NULL OR due_on IS NULL OR available_from <= due_on)
);
CREATE INDEX todos_responsible_idx ON todos (responsible_user_id);
CREATE INDEX todos_due_idx ON todos (due_on);

CREATE TABLE todo_tags (
    todo_id BIGINT      NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    tag     VARCHAR(40) NOT NULL,
    PRIMARY KEY (todo_id, tag)
);
CREATE INDEX todo_tags_tag_idx ON todo_tags (tag);

-- Zuständige: genau eines von Mitglied, Rolle oder Kontakt
CREATE TABLE todo_assignees (
    id         BIGSERIAL PRIMARY KEY,
    todo_id    BIGINT NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    member_id  BIGINT REFERENCES family_members (id) ON DELETE CASCADE,
    role_id    BIGINT REFERENCES roles (id) ON DELETE CASCADE,
    contact_id BIGINT REFERENCES contacts (id) ON DELETE CASCADE,
    CHECK (num_nonnulls(member_id, role_id, contact_id) = 1)
);
CREATE INDEX todo_assignees_todo_idx ON todo_assignees (todo_id);
CREATE UNIQUE INDEX todo_assignees_member_uq ON todo_assignees (todo_id, member_id) WHERE member_id IS NOT NULL;
CREATE UNIQUE INDEX todo_assignees_role_uq ON todo_assignees (todo_id, role_id) WHERE role_id IS NOT NULL;
CREATE UNIQUE INDEX todo_assignees_contact_uq ON todo_assignees (todo_id, contact_id) WHERE contact_id IS NOT NULL;

-- Abhakpflichtige Stellen bei "jeder einzeln"
CREATE TABLE todo_completions (
    id           BIGSERIAL PRIMARY KEY,
    todo_id      BIGINT NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    member_id    BIGINT REFERENCES family_members (id) ON DELETE CASCADE,
    contact_id   BIGINT REFERENCES contacts (id) ON DELETE CASCADE,
    completed_at TIMESTAMP WITH TIME ZONE,
    completed_by BIGINT REFERENCES users (id),
    CHECK (num_nonnulls(member_id, contact_id) = 1)
);
CREATE INDEX todo_completions_todo_idx ON todo_completions (todo_id);

CREATE TABLE todo_watchers (
    todo_id BIGINT NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (todo_id, user_id)
);
