-- Login: persistent "stay signed in" tokens (Spring Security JdbcTokenRepositoryImpl schema)
CREATE TABLE persistent_logins (
    series    VARCHAR(64) PRIMARY KEY,
    username  VARCHAR(100) NOT NULL,
    token     VARCHAR(64)  NOT NULL,
    last_used TIMESTAMP    NOT NULL
);
CREATE INDEX persistent_logins_username_idx ON persistent_logins (username);
