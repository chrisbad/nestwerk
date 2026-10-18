-- Kommentare, Verlauf und Anhänge
CREATE TABLE todo_comments (
    id         BIGSERIAL PRIMARY KEY,
    todo_id    BIGINT                   NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    author_id  BIGINT                   NOT NULL REFERENCES users (id),
    body       TEXT                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    edited_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX todo_comments_todo_idx ON todo_comments (todo_id, created_at);

-- Verlauf: wird nur ergänzt, nie geändert
CREATE TABLE todo_history (
    id        BIGSERIAL PRIMARY KEY,
    todo_id   BIGINT                   NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    actor_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    field     VARCHAR(50)              NOT NULL,
    old_value TEXT,
    new_value TEXT
);
CREATE INDEX todo_history_todo_idx ON todo_history (todo_id, at);

CREATE TABLE attachments (
    id           BIGSERIAL PRIMARY KEY,
    todo_id      BIGINT REFERENCES todos (id) ON DELETE CASCADE,
    comment_id   BIGINT REFERENCES todo_comments (id) ON DELETE CASCADE,
    contact_id   BIGINT REFERENCES contacts (id) ON DELETE CASCADE,
    filename     VARCHAR(255)             NOT NULL,
    content_type VARCHAR(100)             NOT NULL,
    size         BIGINT                   NOT NULL,
    storage_key  VARCHAR(64)              NOT NULL UNIQUE,
    uploaded_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    uploaded_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(todo_id, comment_id, contact_id) = 1)
);
CREATE INDEX attachments_todo_idx ON attachments (todo_id);
CREATE INDEX attachments_comment_idx ON attachments (comment_id);
CREATE INDEX attachments_contact_idx ON attachments (contact_id);
