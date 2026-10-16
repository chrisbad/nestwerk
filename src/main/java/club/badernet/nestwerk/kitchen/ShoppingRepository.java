package club.badernet.nestwerk.kitchen;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ShoppingRepository extends JpaRepository<ShoppingItem, Long> {

    List<ShoppingItem> findByStatusInOrderByCreatedAtAsc(Collection<ShoppingStatus> statuses);

    List<ShoppingItem> findByStatusAndOrigin(ShoppingStatus status, ShoppingOrigin origin);
}
