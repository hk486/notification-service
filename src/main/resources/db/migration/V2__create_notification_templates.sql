CREATE TABLE notification_templates (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    body       TEXT NOT NULL,
    channel    ENUM('EMAIL', 'SMS', 'PUSH') NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

-- Default templates
INSERT INTO notification_templates (name, body, channel, created_at, updated_at) VALUES
('OTP',     'Your OTP is {{otp}}. Valid for 5 minutes. Do not share it with anyone.',   'SMS',   NOW(), NOW()),
('ORDER',   'Your order {{orderId}} is now {{status}}.',                                 'EMAIL', NOW(), NOW()),
('WELCOME', 'Hi {{name}}, welcome to {{app}}! We are glad to have you on board.',       'EMAIL', NOW(), NOW()),
('PROMO',   'Hey {{name}}, check out our latest deals: {{offer}}',                       'PUSH',  NOW(), NOW());
