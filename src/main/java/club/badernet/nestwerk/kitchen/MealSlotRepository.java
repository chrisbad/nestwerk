package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MealSlotRepository extends JpaRepository<MealSlot, Long> {

    List<MealSlot> findAllByOrderBySortOrderAsc();
}
