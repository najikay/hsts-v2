-- HSTS Prototype — Database Schema (Phase 2)
-- Data tier: MySQL. Run this once against your local MySQL instance.

CREATE DATABASE IF NOT EXISTS hsts_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE hsts_db;

-- Drop for a clean re-create during prototype development.
DROP TABLE IF EXISTS Questions;

CREATE TABLE Questions (
    id            INT          NOT NULL AUTO_INCREMENT,
    question_text TEXT         NOT NULL,
    answer        TEXT         NULL,
    PRIMARY KEY (id)

    -- FUTURE (AI vision): a JSON column for AI metadata
    -- (embeddings refs, generating provider/model, generation provenance,
    --  difficulty scoring, etc.). MySQL's native JSON type is the JSONB
    --  equivalent. Documented now, added later — the DAO is shaped to accept it.
    --
    -- , ai_metadata JSON NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
