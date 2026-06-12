package notification_service.repository;

import notification_service.model.NotificationLog;
import notification_service.model.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByName(String name);

    Optional<NotificationTemplate> findByNameAndChannel(String name, NotificationLog.Channel channel);
}
