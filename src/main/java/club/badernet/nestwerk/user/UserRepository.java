package club.badernet.nestwerk.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findAllByOrderByUsernameAsc();

    boolean existsByRoles_Id(Long roleId);

    List<User> findAllByRoles_Id(Long roleId);

    @Query("select count(distinct u) from User u join u.roles r "
            + "where u.enabled = true and r.allPermissions = true")
    long countActiveAdministrators();
}
