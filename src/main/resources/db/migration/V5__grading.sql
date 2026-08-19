-- =====================================================================
-- V5 — Grading
-- ARCHITECTURE §5 lines 25-26 · PRD C-3, F8 · S-22, S-23, S-24
--
-- Order is fixed by C-3: auto-check → teacher approval → only then is the
-- grade visible to the student, together with the checked form (S-24).
-- status distinguishes the two stages; auto_score is never overwritten,
-- so an override is always auditable against what the machine computed.
-- override_reason is required by the service whenever final_score differs
-- from auto_score (F8) — a cross-column rule, so it lives in the service.
-- =====================================================================

CREATE TABLE grades (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    attempt_id      BIGINT      NOT NULL,
    auto_score      INT         NOT NULL,
    final_score     INT         NULL,
    status          ENUM('AUTO','APPROVED') NOT NULL,
    override_reason TEXT        NULL,
    teacher_comment TEXT        NULL,
    approved_by     BIGINT      NULL,
    approved_at     DATETIME(3) NULL,
    -- JPA @Version backstop (F10.3/F10.4 "grading a student's submission", ADR-008):
    -- two teachers approving or overriding the same grade at once.
    lock_version    INT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_grades PRIMARY KEY (id),
    CONSTRAINT uq_grades_attempt UNIQUE (attempt_id),
    -- RESTRICT: a grade is permanent student history (F9.1) and feeds the stored
    -- per-execution statistics (C-5). Deleting a graded attempt must fail, not cascade.
    CONSTRAINT fk_grades_attempt FOREIGN KEY (attempt_id)
        REFERENCES exam_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_grades_approver FOREIGN KEY (approved_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_grades_auto_score CHECK (auto_score BETWEEN 0 AND 100),
    CONSTRAINT ck_grades_final_score CHECK (final_score IS NULL OR final_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
