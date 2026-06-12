CREATE TABLE scheduled_notifications (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(255)    NOT NULL,
    channel         ENUM('EMAIL','SMS','PUSH') NOT NULL,
    priority        ENUM('HIGH','MEDIUM','LOW') NOT NULL,
    template_name   VARCHAR(100),
    template_data   TEXT,                        -- JSON map stored as string
    message         TEXT,
    idempotency_key VARCHAR(255)    NOT NULL UNIQUE,
    scheduled_at    DATETIME        NOT NULL,     -- when to dispatch
    status          ENUM('PENDING','DISPATCHED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at   DATETIME,

    PRIMARY KEY (id),
    INDEX idx_sn_status_scheduled (status, scheduled_at),   -- used by the poll query
    INDEX idx_sn_user_id          (user_id)
);
