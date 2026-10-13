package club.badernet.nestwerk.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminInitializerTest {

    private final UserService userService = mock(UserService.class);

    @Test
    void createsAdminWhenNoUsersExist() {
        when(userService.hasUsers()).thenReturn(false);

        new AdminInitializer(userService, "admin", "secret-pw").run(new DefaultApplicationArguments());

        verify(userService).create("admin", "secret-pw", Role.ADMIN);
    }

    @Test
    void doesNothingWhenUsersExist() {
        when(userService.hasUsers()).thenReturn(true);

        new AdminInitializer(userService, "admin", "").run(new DefaultApplicationArguments());

        verify(userService, never()).create("admin", "", Role.ADMIN);
    }

    @Test
    void failsWithoutPasswordWhenNoUsersExist() {
        when(userService.hasUsers()).thenReturn(false);

        AdminInitializer initializer = new AdminInitializer(userService, "admin", " ");

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NESTWERK_ADMIN_PASSWORD");
    }
}
