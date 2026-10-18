package club.badernet.nestwerk.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChoreRepository extends JpaRepository<Chore, Long> {

    List<Chore> findAllByActiveTrue();

    List<Chore> findAllByIcalUrlIsNotNullAndActiveTrue();
}
