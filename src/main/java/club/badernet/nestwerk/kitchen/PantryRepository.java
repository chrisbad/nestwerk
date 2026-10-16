package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PantryRepository extends JpaRepository<PantryItem, Long> {

    List<PantryItem> findByFoodId(Long foodId);

    List<PantryItem> findByBestBeforeLessThanEqual(LocalDate day);
}
