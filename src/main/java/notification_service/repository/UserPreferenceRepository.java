package notification_service.repository;

import notification_service.model.NotificationLog;
import notification_service.model.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    List<UserPreference> findByUserId(String userId);

    Optional<UserPreference> findByUserIdAndChannel(String userId, NotificationLog.Channel channel);
}
