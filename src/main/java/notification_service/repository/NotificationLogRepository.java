package notification_service.repository;

import notification_service.model.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByUserId(String userId);

    List<NotificationLog> findByStatus(NotificationLog.Status status);

    Optional<NotificationLog> findByIdempotencyKey(String idempotencyKey);
}
