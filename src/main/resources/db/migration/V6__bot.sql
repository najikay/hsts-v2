-- =====================================================================
-- V6 — Study bot
-- ARCHITECTURE §5 lines 27-34 · PRD F12 · ADR-009, ADR-018
-- S-27, S-28, S-30, S-32, S-33, S-34
--
-- One bot per course (S-30). Sources are teacher-uploaded material; the
-- raw bytes are kept so a source can be re-extracted or re-downloaded,
-- and extracted_text is what actually reaches the provider prompt.
--
-- bot_sessions.transcript is the JSON history the student sees (S-33).
-- bot_messages is the NORMALIZED analytics copy, dual-written with the
-- transcript inside the same transaction: analytics and aggregates query
-- THIS table, never the JSON. student_id here is internal only — the
-- S-34 analytics DTOs carry no identity fields.
-- =====================================================================

CREATE TABLE bots (
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    course CHAR(2)      NOT NULL,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_bots PRIMARY KEY (id),
    CONSTRAINT uq_bots_course UNIQUE (course),
    CONSTRAINT fk_bots_course FOREIGN KEY (course)
        REFERENCES courses (code2) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- version increments on re-upload so a stale extraction is detectable.
--
-- raw and extracted_text are NOT NULL (PR1 review): a source row only comes into
-- existence after a successful parse, since F12.2 reports parse failures immediately
-- instead of storing a half-source. NOT NULL alone would still admit a zero-length
-- value, which is the very thing the rule exists to prevent — a source that shows up in
-- the teacher's list and contributes nothing to the prompt — so the two CHECKs below
-- carry the rest of it. Whitespace-only text is left to the service, exactly as the
-- answer trim/collapse rule is in ADR-016.
CREATE TABLE bot_sources (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    bot_id         BIGINT       NOT NULL,
    type           ENUM('PDF','DOCX','TEXT') NOT NULL,
    title          VARCHAR(200) NOT NULL,
    raw            MEDIUMBLOB   NOT NULL,
    extracted_text MEDIUMTEXT   NOT NULL,
    added_by       BIGINT       NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    -- version is the §5 source-content version (bumped on re-upload); lock_version is
    -- the separate JPA @Version backstop (F10.3/F10.4 "bot sources", ADR-008).
    version        INT          NOT NULL DEFAULT 1,
    lock_version   INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_bot_sources PRIMARY KEY (id),
    CONSTRAINT fk_bot_sources_bot FOREIGN KEY (bot_id)
        REFERENCES bots (id) ON DELETE CASCADE,
    CONSTRAINT fk_bot_sources_author FOREIGN KEY (added_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_bot_sources_version CHECK (version >= 1),
    CONSTRAINT ck_bot_sources_raw_present CHECK (LENGTH(raw) > 0),
    CONSTRAINT ck_bot_sources_text_present CHECK (LENGTH(extracted_text) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE bot_sessions (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    bot_id     BIGINT      NOT NULL,
    student_id BIGINT      NOT NULL,
    started_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    transcript JSON        NOT NULL,
    CONSTRAINT pk_bot_sessions PRIMARY KEY (id),
    -- RESTRICT (PR1 review): bots are toggled inactive (F12.4), not deleted. Deleting one
    -- must not silently take the analytics corpus that the toggle exists to preserve.
    CONSTRAINT fk_bot_sessions_bot FOREIGN KEY (bot_id)
        REFERENCES bots (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bot_sessions_student FOREIGN KEY (student_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_bot_sessions_student ON bot_sessions (student_id, updated_at);

-- provider records which adapter answered (DeepSeek / Anthropic / none),
-- so the fallback chain of ADR-009 is measurable after the fact.
CREATE TABLE bot_messages (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    bot_id     BIGINT      NOT NULL,
    session_id BIGINT      NOT NULL,
    student_id BIGINT      NOT NULL,
    question   TEXT        NOT NULL,
    answer     MEDIUMTEXT  NOT NULL,
    provider   VARCHAR(40) NOT NULL,
    asked_at   DATETIME(3) NOT NULL,
    CONSTRAINT pk_bot_messages PRIMARY KEY (id),
    CONSTRAINT fk_bot_messages_bot FOREIGN KEY (bot_id)
        REFERENCES bots (id) ON DELETE RESTRICT,
    -- RESTRICT here too, one level down: deleting a SESSION would otherwise still wipe
    -- its messages and leave the same hole. Nothing in the system deletes a session —
    -- F12.10 is reopen-and-continue, and the only "remove" in F12 is F12.3, on sources —
    -- so this blocks no feature that exists.
    CONSTRAINT fk_bot_messages_session FOREIGN KEY (session_id)
        REFERENCES bot_sessions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bot_messages_student FOREIGN KEY (student_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The analytics aggregates of S-34 group by bot over a time window.
CREATE INDEX ix_bot_messages_bot_asked_at ON bot_messages (bot_id, asked_at);
