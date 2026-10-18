-- Belohnungen: Minutenkonto einlösen
CREATE TABLE rewards (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(100)             NOT NULL,
    description  VARCHAR(500),
    cost_minutes INT                      NOT NULL CHECK (cost_minutes BETWEEN 1 AND 100000),
    active       BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE reward_redemptions (
    id           BIGSERIAL PRIMARY KEY,
    reward_id    BIGINT                   NOT NULL REFERENCES rewards (id),
    member_id    BIGINT                   NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    cost_minutes INT                      NOT NULL,
    status       VARCHAR(10)              NOT NULL,
    requested_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    decided_at   TIMESTAMP WITH TIME ZONE,
    version      BIGINT                   NOT NULL DEFAULT 0
);
CREATE INDEX reward_redemptions_member_idx ON reward_redemptions (member_id, status);

INSERT INTO acl_object_types (key, label, sort_order)
VALUES ('REWARD', 'Belohnungen', 40);

INSERT INTO acl_actions (object_type, key, label, sort_order)
VALUES ('REWARD', 'VIEW', 'Konto und Belohnungen sehen, für sich einlösen', 10),
       ('REWARD', 'REDEEM', 'Für andere einlösen', 20),
       ('REWARD', 'MANAGE', 'Belohnungen verwalten', 30),
       ('REWARD', 'APPROVE', 'Einlösungen bestätigen', 40);

INSERT INTO acl_cells (object_type, action, relation)
SELECT 'REWARD', key, 'ANY'
FROM acl_actions
WHERE object_type = 'REWARD';

INSERT INTO acl_preset_rules (preset_key, object_type, action, relation)
SELECT 'ELTERNTEIL', object_type, action, relation
FROM acl_cells
WHERE object_type = 'REWARD';

INSERT INTO acl_preset_rules (preset_key, object_type, action, relation)
VALUES ('KIND', 'REWARD', 'VIEW', 'ANY');

INSERT INTO acl_rules (role_id, object_type, action, relation)
SELECT r.id, 'REWARD', 'VIEW', 'ANY'
FROM roles r
WHERE r.name = 'Benutzer';
