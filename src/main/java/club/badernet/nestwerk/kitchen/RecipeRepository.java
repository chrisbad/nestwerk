package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    List<Recipe> findAllByOrderByTitleAsc();

    @Query("SELECT r FROM Recipe r WHERE lower(r.title) LIKE lower(concat('%', :q, '%')) "
            + "OR lower(coalesce(r.tags, '')) LIKE lower(concat('%', :q, '%')) ORDER BY r.title")
    List<Recipe> search(String q);

    @Query("SELECT DISTINCT i.recipe FROM RecipeIngredient i WHERE i.food.id IN :foodIds")
    List<Recipe> usingFoods(Collection<Long> foodIds);
}
