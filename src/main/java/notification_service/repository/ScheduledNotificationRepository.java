package notification_service.repository;

import notification_service.model.ScheduledNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledNotificationRepository extends JpaRepository<ScheduledNotification, Long> {

    /**
     * Used by the scheduler every minute.
     * Fetches all PENDING rows whose scheduledAt is in the past (or exactly now).
     */
    @Query("SELECT s FROM ScheduledNotification s " +
           "WHERE s.status = 'PENDING' AND s.scheduledAt <= :now")
    List<ScheduledNotification> findDueNotifications(@Param("now") LocalDateTime now);

    /** Used by GET /api/v1/scheduled/{userId} */
    List<ScheduledNotification> findByUserIdOrderByScheduledAtAsc(String userId);
}
