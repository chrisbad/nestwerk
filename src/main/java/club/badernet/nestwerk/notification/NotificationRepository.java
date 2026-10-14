package club.badernet.nestwerk.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    long countByUserIdAndReadAtIsNull(Long userId);

    List<Notification> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable page);

    List<Notification> findAllByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = current_timestamp where n.userId = :userId and n.readAt is null")
    int markAllRead(Long userId);
}
