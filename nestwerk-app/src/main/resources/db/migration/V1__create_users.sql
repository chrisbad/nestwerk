CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100)             NOT NULL UNIQUE,
    password_hash VARCHAR(255)             NOT NULL,
    role          VARCHAR(20)              NOT NULL,
    enabled       BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
