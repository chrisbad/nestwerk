-- Schule: Elternbriefe mit Frist und Unterschrift; Anhänge dürfen an Briefe gehängt werden
CREATE TABLE school_letter (
    id         BIGSERIAL PRIMARY KEY,
    year_id    BIGINT       NOT NULL REFERENCES school_year (id) ON DELETE CASCADE,
    title      VARCHAR(150) NOT NULL,
    due_on     DATE,
    note       VARCHAR(500),
    signed_at  TIMESTAMP WITH TIME ZONE,
    signed_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX school_letter_year_idx ON school_letter (year_id, due_on);

ALTER TABLE attachments ADD COLUMN letter_id BIGINT REFERENCES school_letter (id) ON DELETE CASCADE;
ALTER TABLE attachments DROP CONSTRAINT attachments_check;
ALTER TABLE attachments ADD CONSTRAINT attachments_check
    CHECK (num_nonnulls(todo_id, comment_id, contact_id, recipe_id, letter_id) = 1);
CREATE INDEX attachments_letter_idx ON attachments (letter_id);
