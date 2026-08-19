-- =====================================================================
-- V3 — Exams (the drawer)
-- ARCHITECTURE §5 lines 10-14 · PRD C-2, C-6 · ADR-011 · S-10, S-14
--
-- An Exam is a versioned DEFINITION that lives in the drawer; taking it
-- out is an ExamExecution (V4). One exam version → many executions.
-- Approval and scheduling bind to a specific exam VERSION (S-14), so an
-- approved version can never be silently edited underneath a release.
--
-- display_id6 = subject(2) + course(2) + serial2(2), allocated in E2.14 (S-10).
-- =====================================================================

CREATE TABLE exams (
    id          BIGINT  NOT NULL AUTO_INCREMENT,
    course      CHAR(2) NOT NULL,
    serial2     TINYINT NOT NULL,
    display_id6 CHAR(6) NOT NULL,
    author      BIGINT  NOT NULL,
    -- JPA @Version backstop (F10.3/F10.4, ADR-008).
    lock_version INT    NOT NULL DEFAULT 0,
    CONSTRAINT pk_exams PRIMARY KEY (id),
    CONSTRAINT uq_exams_display_id UNIQUE (display_id6),
    CONSTRAINT uq_exams_course_serial UNIQUE (course, serial2),
    CONSTRAINT fk_exams_course FOREIGN KEY (course)
        REFERENCES courses (code2) ON DELETE RESTRICT,
    CONSTRAINT fk_exams_author FOREIGN KEY (author)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_exams_serial2 CHECK (serial2 BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- student_text / teacher_text are the two free-text blocks the teacher
-- writes for the exam form. rejected_reason is required by F4 when a
-- coordinator rejects, and is carried back to the author (S-14).
CREATE TABLE exam_versions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    exam_id         BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    name            VARCHAR(150) NOT NULL,
    duration_min    INT          NOT NULL,
    student_text    TEXT         NULL,
    teacher_text    TEXT         NULL,
    status          ENUM('DRAFT','PENDING','APPROVED','REJECTED') NOT NULL,
    rejected_reason TEXT         NULL,
    created_at      DATETIME(3)  NOT NULL,
    -- status is the one mutable field on a version row (DRAFT → PENDING → APPROVED /
    -- REJECTED), so the approve-vs-reject race between two coordinators lands here.
    lock_version    INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_exam_versions PRIMARY KEY (id),
    CONSTRAINT uq_exam_versions_no UNIQUE (exam_id, version_no),
    CONSTRAINT fk_exam_versions_exam FOREIGN KEY (exam_id)
        REFERENCES exams (id) ON DELETE CASCADE,
    CONSTRAINT ck_exam_versions_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_exam_versions_duration CHECK (duration_min > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Points across one exam version must sum to 100. Per ARCHITECTURE §5 that
-- is enforced in the service layer and asserted in tests — deliberately NOT
-- a DDL constraint, since a table-level CHECK cannot span rows.
-- question_id is denormalised alongside question_version_id (PR1 review) so the DB
-- itself forbids the same question appearing twice in one exam version through two
-- different versions of it — PRD §6 "duplicate question in exam → prevented", which the
-- link table alone could not express because its key is the VERSION id.
--
-- The foreign key is deliberately COMPOSITE: (question_version_id, question_id) must
-- match a real (id, question_id) pair in question_versions. Without that, question_id
-- would be an unpoliced copy — write the wrong one and the UNIQUE below silently guards
-- nothing. The composite key makes the duplicate impossible rather than merely unlikely.
CREATE TABLE exam_version_questions (
    exam_version_id     BIGINT NOT NULL,
    question_id         BIGINT NOT NULL,
    question_version_id BIGINT NOT NULL,
    points              INT    NOT NULL,
    ord                 INT    NOT NULL,
    CONSTRAINT pk_exam_version_questions PRIMARY KEY (exam_version_id, question_version_id),
    CONSTRAINT uq_exam_version_questions_question UNIQUE (exam_version_id, question_id),
    CONSTRAINT uq_exam_version_questions_ord UNIQUE (exam_version_id, ord),
    CONSTRAINT fk_evq_exam_version FOREIGN KEY (exam_version_id)
        REFERENCES exam_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_evq_question_version FOREIGN KEY (question_version_id, question_id)
        REFERENCES question_versions (id, question_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evq_points CHECK (points BETWEEN 1 AND 100),
    CONSTRAINT ck_evq_ord CHECK (ord >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
