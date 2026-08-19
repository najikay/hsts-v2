-- =====================================================================
-- V2 — Question bank
-- ARCHITECTURE §5 lines 6-9 · PRD C-2, C-7, C-8 · ADR-011, ADR-016
--
-- `questions` is the identity row: it never changes once created.
-- `question_versions` are IMMUTABLE (C-2 / ADR-011) — an edit inserts
-- version n+1 and old versions stay queryable, because past exams,
-- attempts and grades reference the exact version that was answered.
--
-- display_id5 = course(2) + serial3(3), allocated in E2.14 (S-8).
-- =====================================================================

CREATE TABLE questions (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    course      CHAR(2)  NOT NULL,
    serial3     SMALLINT NOT NULL,
    display_id5 CHAR(5)  NOT NULL,
    -- JPA @Version backstop (F10.3/F10.4, ADR-008): two teachers editing the same
    -- question race to create version n+1; the stale writer is rejected with CONFLICT.
    -- Named lock_version so it is never confused with the domain version numbers.
    lock_version INT     NOT NULL DEFAULT 0,
    CONSTRAINT pk_questions PRIMARY KEY (id),
    CONSTRAINT uq_questions_display_id UNIQUE (display_id5),
    CONSTRAINT uq_questions_course_serial UNIQUE (course, serial3),
    CONSTRAINT fk_questions_course FOREIGN KEY (course)
        REFERENCES courses (code2) ON DELETE RESTRICT,
    CONSTRAINT ck_questions_serial3 CHECK (serial3 BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- correct_answer is 1..4 — exactly one correct answer (C-8 / ADR-016).
-- The four answers must be pairwise distinct; the CHECK below is the
-- storage-level backstop (case-insensitive for free under
-- utf8mb4_unicode_ci). The full rule — trim + collapse whitespace before
-- comparing — is enforced in the service layer per ADR-016.
CREATE TABLE question_versions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    question_id    BIGINT       NOT NULL,
    version_no     INT          NOT NULL,
    text           TEXT         NOT NULL,
    a1             VARCHAR(500) NOT NULL,
    a2             VARCHAR(500) NOT NULL,
    a3             VARCHAR(500) NOT NULL,
    a4             VARCHAR(500) NOT NULL,
    correct_answer TINYINT      NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    difficulty     ENUM('EASY','MEDIUM','HARD') NOT NULL,
    image          MEDIUMBLOB   NULL,
    created_by     BIGINT       NOT NULL,
    created_at     DATETIME(3)  NOT NULL,
    CONSTRAINT pk_question_versions PRIMARY KEY (id),
    CONSTRAINT uq_question_versions_no UNIQUE (question_id, version_no),
    CONSTRAINT fk_question_versions_question FOREIGN KEY (question_id)
        REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fk_question_versions_author FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_question_versions_correct CHECK (correct_answer BETWEEN 1 AND 4),
    CONSTRAINT ck_question_versions_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_question_versions_distinct CHECK (
        a1 <> a2 AND a1 <> a3 AND a1 <> a4
                 AND a2 <> a3 AND a2 <> a4
                              AND a3 <> a4
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Auto-generation (F3.3 / S-13) filters the bank by topic + difficulty.
CREATE INDEX ix_question_versions_topic ON question_versions (topic, difficulty);
