-- OAuth 2.1 für den MCP-Server: KI-Assistenten melden sich mit dem normalen Nestwerk-Login an.
-- Tabellen des Spring Authorization Servers in der PostgreSQL-Variante (blob → text, timestamp → timestamptz).
CREATE TABLE oauth2_registered_client (
    id                            VARCHAR(100)             NOT NULL PRIMARY KEY,
    client_id                     VARCHAR(100)             NOT NULL UNIQUE,
    client_id_issued_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    client_secret                 VARCHAR(200),
    client_secret_expires_at      TIMESTAMP WITH TIME ZONE,
    client_name                   VARCHAR(200)             NOT NULL,
    client_authentication_methods VARCHAR(1000)            NOT NULL,
    authorization_grant_types     VARCHAR(1000)            NOT NULL,
    redirect_uris                 VARCHAR(1000),
    post_logout_redirect_uris     VARCHAR(1000),
    scopes                        VARCHAR(1000)            NOT NULL,
    client_settings               VARCHAR(2000)            NOT NULL,
    token_settings                VARCHAR(2000)            NOT NULL
);

CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100)             NOT NULL PRIMARY KEY,
    registered_client_id          VARCHAR(100)             NOT NULL,
    principal_name                VARCHAR(200)             NOT NULL,
    authorization_grant_type      VARCHAR(100)             NOT NULL,
    authorized_scopes             VARCHAR(1000),
    attributes                    TEXT,
    state                         VARCHAR(500),
    authorization_code_value      TEXT,
    authorization_code_issued_at  TIMESTAMP WITH TIME ZONE,
    authorization_code_expires_at TIMESTAMP WITH TIME ZONE,
    authorization_code_metadata   TEXT,
    access_token_value            TEXT,
    access_token_issued_at        TIMESTAMP WITH TIME ZONE,
    access_token_expires_at       TIMESTAMP WITH TIME ZONE,
    access_token_metadata         TEXT,
    access_token_type             VARCHAR(100),
    access_token_scopes           VARCHAR(1000),
    oidc_id_token_value           TEXT,
    oidc_id_token_issued_at       TIMESTAMP WITH TIME ZONE,
    oidc_id_token_expires_at      TIMESTAMP WITH TIME ZONE,
    oidc_id_token_metadata        TEXT,
    refresh_token_value           TEXT,
    refresh_token_issued_at       TIMESTAMP WITH TIME ZONE,
    refresh_token_expires_at      TIMESTAMP WITH TIME ZONE,
    refresh_token_metadata        TEXT,
    user_code_value               TEXT,
    user_code_issued_at           TIMESTAMP WITH TIME ZONE,
    user_code_expires_at          TIMESTAMP WITH TIME ZONE,
    user_code_metadata            TEXT,
    device_code_value             TEXT,
    device_code_issued_at         TIMESTAMP WITH TIME ZONE,
    device_code_expires_at        TIMESTAMP WITH TIME ZONE,
    device_code_metadata          TEXT,
    -- nicht Teil des Spring-Schemas: erlaubt das Aufräumen abgebrochener Anmeldungen
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX oauth2_authorization_principal_idx ON oauth2_authorization (principal_name, registered_client_id);
CREATE INDEX oauth2_authorization_state_idx ON oauth2_authorization (state);
CREATE INDEX oauth2_authorization_code_idx ON oauth2_authorization (authorization_code_value);
CREATE INDEX oauth2_authorization_access_idx ON oauth2_authorization (access_token_value);
CREATE INDEX oauth2_authorization_refresh_idx ON oauth2_authorization (refresh_token_value);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100)  NOT NULL,
    principal_name       VARCHAR(200)  NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
