package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FoodRepository extends JpaRepository<Food, Long> {

    Optional<Food> findByNameIgnoreCase(String name);

    Optional<Food> findByBarcode(String barcode);

    List<Food> findAllByOrderByNameAsc();

    @Query("SELECT f FROM Food f WHERE lower(f.name) LIKE lower(concat('%', :q, '%')) ORDER BY length(f.name), f.name")
    List<Food> search(String q);
}
