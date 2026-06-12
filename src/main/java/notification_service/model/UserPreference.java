package notification_service.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Entity
@Table(name = "user_preferences",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "channel"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationLog.Channel channel;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "dnd_start")
    private LocalTime dndStart;

    @Column(name = "dnd_end")
    private LocalTime dndEnd;
}
