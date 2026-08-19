-- =====================================================================
-- V1 — Core reference data & identity
-- ARCHITECTURE §5 lines 1-5 · PRD §5 (seed shape), S-1, S-3, S-4
--
-- Subjects and courses are seeded, read-only reference data (S-3).
-- Users are seeded only — there is no in-app user CRUD (S-4 / ADR-005);
-- passwords are BCrypt hashes written by the PR 3 seed loader.
-- =====================================================================

CREATE TABLE subjects (
    code2 CHAR(2)     NOT NULL,
    name  VARCHAR(100) NOT NULL,
    CONSTRAINT pk_subjects PRIMARY KEY (code2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE courses (
    code2        CHAR(2)      NOT NULL,
    subject_code CHAR(2)      NOT NULL,
    name         VARCHAR(100) NOT NULL,
    CONSTRAINT pk_courses PRIMARY KEY (code2),
    CONSTRAINT fk_courses_subject FOREIGN KEY (subject_code)
        REFERENCES subjects (code2) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- role drives every authorization decision (server/core/Authorization).
-- national_id is what a student types to start an attempt (S-18).
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          ENUM('STUDENT','TEACHER','PRINCIPAL') NOT NULL,
    national_id   VARCHAR(20)  NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_users_national_id ON users (national_id);

-- A course may be taught by more than one teacher (seed: co-teacher on Java).
CREATE TABLE course_teachers (
    course  CHAR(2) NOT NULL,
    teacher BIGINT  NOT NULL,
    CONSTRAINT pk_course_teachers PRIMARY KEY (course, teacher),
    CONSTRAINT fk_course_teachers_course FOREIGN KEY (course)
        REFERENCES courses (code2) ON DELETE CASCADE,
    CONSTRAINT fk_course_teachers_teacher FOREIGN KEY (teacher)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE enrollments (
    course  CHAR(2) NOT NULL,
    student BIGINT  NOT NULL,
    CONSTRAINT pk_enrollments PRIMARY KEY (course, student),
    CONSTRAINT fk_enrollments_course FOREIGN KEY (course)
        REFERENCES courses (code2) ON DELETE CASCADE,
    CONSTRAINT fk_enrollments_student FOREIGN KEY (student)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One coordinator per subject (S-1) — enforced by the PK on subject_code.
-- A teacher may coordinate more than one subject.
CREATE TABLE coordinators (
    subject_code CHAR(2) NOT NULL,
    teacher      BIGINT  NOT NULL,
    CONSTRAINT pk_coordinators PRIMARY KEY (subject_code),
    CONSTRAINT fk_coordinators_subject FOREIGN KEY (subject_code)
        REFERENCES subjects (code2) ON DELETE CASCADE,
    CONSTRAINT fk_coordinators_teacher FOREIGN KEY (teacher)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
