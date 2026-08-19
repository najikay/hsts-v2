-- =====================================================================
-- V7 — Notifications
-- ARCHITECTURE §5 lines 35-37 · PRD F11 · ADR-007
--
-- Notifications are pushed live over the protocol's push channel and
-- persisted here so they survive a reconnect and can be listed unread.
-- (ref_type, ref_id) is a loose pointer to whatever the notification is
-- about — an exam version, an execution, a grade — deliberately untyped
-- so a new notification kind needs no migration.
-- =====================================================================

CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(50)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NULL,
    ref_type   VARCHAR(50)  NULL,
    ref_id     BIGINT       NULL,
    created_at DATETIME(3)  NOT NULL,
    read_at    DATETIME(3)  NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The bell badge counts unread per user; the list is newest-first.
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, read_at, created_at);
