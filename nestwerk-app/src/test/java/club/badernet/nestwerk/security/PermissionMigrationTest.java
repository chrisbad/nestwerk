package club.badernet.nestwerk.security;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class PermissionMigrationTest {

    @Autowired DataSource dataSource;

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("migtest4")
                .defaultSchema("migtest4")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void dropsUnknownPermissionsAndEnforcesCatalog() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS migtest4 CASCADE");
        flyway("3").migrate();
        Long benutzer = jdbc.queryForObject("SELECT id FROM migtest4.roles WHERE name = 'Benutzer'", Long.class);
        jdbc.update("INSERT INTO migtest4.role_permissions (role_id, permission) VALUES (?, 'VERALTET'), (?, 'MEMBER_MANAGE')",
                benutzer, benutzer);

        flyway("4").migrate();

        assertThat(jdbc.queryForList("SELECT permission FROM migtest4.role_permissions WHERE role_id = ?",
                String.class, benutzer))
                .contains("MEMBER_MANAGE", "CALENDAR_VIEW")
                .doesNotContain("VERALTET");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO migtest4.role_permissions (role_id, permission) VALUES (?, 'NEU')", benutzer))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("DROP SCHEMA migtest4 CASCADE");
    }
}
