package club.badernet.nestwerk.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    boolean existsByUserId(Long userId);
}
