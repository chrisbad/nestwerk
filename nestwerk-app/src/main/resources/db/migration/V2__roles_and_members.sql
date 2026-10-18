CREATE TABLE roles (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100)             NOT NULL,
    description     VARCHAR(255),
    system_role     BOOLEAN                  NOT NULL DEFAULT FALSE,
    all_permissions BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX roles_name_lower_idx ON roles (lower(name));

CREATE TABLE role_permissions (
    role_id    BIGINT       NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE family_members (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100)             NOT NULL,
    color      VARCHAR(7)               NOT NULL,
    user_id    BIGINT UNIQUE REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO roles (name, description, system_role, all_permissions)
VALUES ('Administrator', 'Vollzugriff auf alle Funktionen', TRUE, TRUE),
       ('Benutzer', 'Standardrolle für Familienmitglieder', TRUE, FALSE);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
         JOIN roles r ON (u.role = 'ADMIN' AND r.name = 'Administrator')
    OR (u.role = 'USER' AND r.name = 'Benutzer');

ALTER TABLE users DROP COLUMN role;
