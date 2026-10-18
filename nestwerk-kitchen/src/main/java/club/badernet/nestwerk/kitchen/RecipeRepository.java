package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    /** Mit Zutaten und Lebensmitteln, damit Listen ohne offene Sitzung die Nährwerte berechnen können. */
    @Query("SELECT DISTINCT r FROM Recipe r LEFT JOIN FETCH r.ingredients i LEFT JOIN FETCH i.food ORDER BY r.title")
    List<Recipe> findAllByOrderByTitleAsc();

    @Query("SELECT DISTINCT r FROM Recipe r LEFT JOIN FETCH r.ingredients i LEFT JOIN FETCH i.food "
            + "WHERE lower(r.title) LIKE lower(concat('%', :q, '%')) "
            + "OR lower(coalesce(r.tags, '')) LIKE lower(concat('%', :q, '%')) ORDER BY r.title")
    List<Recipe> search(String q);

    @Query("SELECT DISTINCT r FROM Recipe r LEFT JOIN FETCH r.ingredients i LEFT JOIN FETCH i.food "
            + "WHERE r.id IN (SELECT x.recipe.id FROM RecipeIngredient x WHERE x.food.id IN :foodIds)")
    List<Recipe> usingFoods(Collection<Long> foodIds);
}
