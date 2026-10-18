-- Unteraufgaben (beliebig tief), Wiederholung und Verknüpfungen
ALTER TABLE todos
    ADD COLUMN parent_id    BIGINT REFERENCES todos (id) ON DELETE CASCADE,
    ADD COLUMN rrule        VARCHAR(500),
    ADD COLUMN series_id    BIGINT,
    ADD COLUMN successor_id BIGINT REFERENCES todos (id) ON DELETE SET NULL,
    ADD CONSTRAINT todos_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id);
CREATE INDEX todos_parent_idx ON todos (parent_id);

CREATE TABLE todo_links (
    id         BIGSERIAL PRIMARY KEY,
    source_id  BIGINT      NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    target_id  BIGINT      NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    type       VARCHAR(20) NOT NULL CHECK (type IN ('BLOCKS', 'RELATES', 'DUPLICATES')),
    created_by BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    CHECK (source_id <> target_id),
    UNIQUE (source_id, target_id, type)
);
CREATE INDEX todo_links_target_idx ON todo_links (target_id);
