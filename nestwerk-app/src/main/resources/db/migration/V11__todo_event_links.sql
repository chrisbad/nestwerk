-- Termine, die aus einem ToDo heraus angelegt wurden
CREATE TABLE todo_event_links (
    todo_id  BIGINT NOT NULL REFERENCES todos (id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    PRIMARY KEY (todo_id, event_id)
);
CREATE INDEX todo_event_links_event_idx ON todo_event_links (event_id);
