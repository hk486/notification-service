CREATE TABLE notification_logs (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        VARCHAR(255) NOT NULL,
    channel        ENUM('EMAIL', 'SMS', 'PUSH') NOT NULL,
    status         ENUM('PENDING', 'SENT', 'FAILED', 'DUPLICATE') NOT NULL,
    provider       VARCHAR(100) NOT NULL,
    message        TEXT,
    idempotency_key VARCHAR(255) UNIQUE,
    failure_reason VARCHAR(500),
    created_at     DATETIME NOT NULL,
    updated_at     DATETIME NOT NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
);
