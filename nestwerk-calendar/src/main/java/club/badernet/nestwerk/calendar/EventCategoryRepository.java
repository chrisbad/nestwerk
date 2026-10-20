package club.badernet.nestwerk.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventCategoryRepository extends JpaRepository<EventCategory, Long> {

    List<EventCategory> findAllByOrderByNameAsc();

    Optional<EventCategory> findByNameIgnoreCase(String name);
}
