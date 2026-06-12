CREATE TABLE user_preferences (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   VARCHAR(255) NOT NULL,
    channel   ENUM('EMAIL', 'SMS', 'PUSH') NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    dnd_start TIME,
    dnd_end   TIME,
    UNIQUE KEY uq_user_channel (user_id, channel)
);
