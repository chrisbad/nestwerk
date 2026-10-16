package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MealRepository extends JpaRepository<Meal, Long> {

    List<Meal> findByDayBetweenOrderByDayAsc(LocalDate from, LocalDate to);
}
