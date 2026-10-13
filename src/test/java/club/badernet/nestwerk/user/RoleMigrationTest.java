package club.badernet.nestwerk.user;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class RoleMigrationTest {

    @Autowired
    DataSource dataSource;

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("migtest")
                .defaultSchema("migtest")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void migratesLegacyRoleColumnToUserRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS migtest CASCADE");
        flyway("1").migrate();
        jdbc.update("INSERT INTO migtest.users (username, password_hash, role) "
                + "VALUES ('alt-admin', 'x', 'ADMIN'), ('alt-user', 'x', 'USER')");

        flyway("2").migrate();

        List<String> pairs = jdbc.queryForList(
                "SELECT u.username || ':' || r.name FROM migtest.user_roles ur "
                        + "JOIN migtest.users u ON u.id = ur.user_id "
                        + "JOIN migtest.roles r ON r.id = ur.role_id ORDER BY 1", String.class);
        assertThat(pairs).containsExactly("alt-admin:Administrator", "alt-user:Benutzer");
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'migtest' AND table_name = 'users'", String.class))
                .doesNotContain("role");
        jdbc.execute("DROP SCHEMA migtest CASCADE");
    }
}
