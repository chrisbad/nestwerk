CREATE TABLE calendar_events (
    id            BIGSERIAL PRIMARY KEY,
    uid           VARCHAR(255)             NOT NULL UNIQUE,
    title         VARCHAR(200)             NOT NULL,
    description   TEXT,
    location      VARCHAR(255),
    starts_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    all_day       BOOLEAN                  NOT NULL DEFAULT FALSE,
    timezone      VARCHAR(64)              NOT NULL DEFAULT 'Europe/Berlin',
    rrule         VARCHAR(500),
    private_event BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_by    BIGINT                   NOT NULL REFERENCES users (id),
    sequence      INTEGER                  NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX calendar_events_starts_at_idx ON calendar_events (starts_at);

CREATE TABLE calendar_event_members (
    event_id  BIGINT NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, member_id)
);

CREATE TABLE calendar_event_exceptions (
    id            BIGSERIAL PRIMARY KEY,
    event_id      BIGINT                   NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    recurrence_id TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled     BOOLEAN                  NOT NULL DEFAULT FALSE,
    title         VARCHAR(200),
    description   TEXT,
    location      VARCHAR(255),
    starts_at     TIMESTAMP WITH TIME ZONE,
    ends_at       TIMESTAMP WITH TIME ZONE,
    UNIQUE (event_id, recurrence_id)
);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
         CROSS JOIN (VALUES ('CALENDAR_VIEW'),
                            ('CALENDAR_EVENT_CREATE'),
                            ('CALENDAR_EVENT_EDIT_OWN'),
                            ('CALENDAR_EVENT_DELETE_OWN'),
                            ('CALENDAR_PRIVATE_CREATE')) AS p (permission)
WHERE r.name = 'Benutzer' AND r.system_role;
