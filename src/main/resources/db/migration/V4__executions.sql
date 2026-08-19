-- =====================================================================
-- V4 — Executions, attempts and answers
-- ARCHITECTURE §5 lines 15-24 · PRD C-1, C-6 · ADR-010, ADR-011
-- S-2, S-16, S-18, S-19, S-21, S-25
--
-- NO PARTICIPATION COUNTER COLUMNS — deliberate. While an execution is
-- live, {started, finished, timed_out} are DERIVED by COUNT over
-- exam_attempts (see ix_exam_attempts_execution_status below), and are
-- frozen into the participation JSON when the execution closes (S-21).
-- Mutable counters would mean increment races on every submit.
--
-- All timing is server-authoritative (ADR-010); the client countdown is
-- cosmetic. DATETIME(3) throughout — millisecond precision, no implicit
-- timezone conversion, and timers are re-armed from these columns after
-- a server restart.
-- =====================================================================

-- code is 4 alphanumeric characters (C-1); entry is case-insensitive,
-- which utf8mb4_unicode_ci gives us on comparison.
CREATE TABLE exam_executions (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    exam_version_id BIGINT      NOT NULL,
    code            CHAR(4)     NOT NULL,
    open_at         DATETIME(3) NOT NULL,
    close_at        DATETIME(3) NOT NULL,
    extra_minutes   INT         NOT NULL DEFAULT 0,
    status          ENUM('SCHEDULED','LIVE','CLOSED') NOT NULL,
    created_by      BIGINT      NOT NULL,
    stats           JSON        NULL,
    participation   JSON        NULL,
    -- JPA @Version backstop (F10.3/F10.4 "releases (editing schedule)", ADR-008):
    -- two teachers extending or rescheduling the same execution at once.
    lock_version    INT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_exam_executions PRIMARY KEY (id),
    CONSTRAINT fk_exam_executions_version FOREIGN KEY (exam_version_id)
        REFERENCES exam_versions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_executions_creator FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_exam_executions_window CHECK (close_at > open_at),
    CONSTRAINT ck_exam_executions_extra CHECK (extra_minutes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Students find an execution by typing its code (S-16).
CREATE INDEX ix_exam_executions_code ON exam_executions (code, status);

-- One attempt per student per execution — the UNIQUE below is what makes
-- a double-start race impossible at the storage level, not just in the UI.
CREATE TABLE exam_attempts (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    execution_id   BIGINT      NOT NULL,
    student_id     BIGINT      NOT NULL,
    started_at     DATETIME(3) NOT NULL,
    ended_at       DATETIME(3) NULL,
    actual_minutes INT         NULL,
    status         ENUM('IN_PROGRESS','SUBMITTED','TIMED_OUT') NOT NULL,
    CONSTRAINT pk_exam_attempts PRIMARY KEY (id),
    CONSTRAINT uq_exam_attempts_student UNIQUE (execution_id, student_id),
    -- RESTRICT, not CASCADE: an attempt is a student's history. Deleting an execution
    -- that anyone has sat must fail loudly rather than silently take the attempts —
    -- and, through them, the grades — with it. Cancelling a SCHEDULED release still
    -- works, because nobody has attempted it yet.
    CONSTRAINT fk_exam_attempts_execution FOREIGN KEY (execution_id)
        REFERENCES exam_executions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_attempts_student FOREIGN KEY (student_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_exam_attempts_minutes CHECK (actual_minutes IS NULL OR actual_minutes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Serves the derived participation counts and the live monitor (F7).
CREATE INDEX ix_exam_attempts_execution_status ON exam_attempts (execution_id, status);

-- selected is NULL while unanswered — autosave writes a row per question
-- as the student moves through the form, so a disconnect loses nothing.
CREATE TABLE attempt_answers (
    attempt_id          BIGINT      NOT NULL,
    question_version_id BIGINT      NOT NULL,
    selected            TINYINT     NULL,
    saved_at            DATETIME(3) NOT NULL,
    CONSTRAINT pk_attempt_answers PRIMARY KEY (attempt_id, question_version_id),
    CONSTRAINT fk_attempt_answers_attempt FOREIGN KEY (attempt_id)
        REFERENCES exam_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_attempt_answers_question_version FOREIGN KEY (question_version_id)
        REFERENCES question_versions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_attempt_answers_selected CHECK (selected IS NULL OR selected BETWEEN 1 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
