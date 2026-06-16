-- HSTS Prototype — Seed Data (Phase 2)
-- 6 distinct dummy exam questions. Run after schema.sql.

USE hsts_db;

INSERT INTO Questions (question_text, answer) VALUES
    ('What is the time complexity of binary search on a sorted array?', 'O(log n)'),
    ('Which design pattern ensures a class has only one instance?', 'Singleton'),
    ('In OSI terms, which layer is responsible for end-to-end reliable delivery (e.g., TCP)?', 'Transport layer (Layer 4)'),
    ('What SQL keyword removes duplicate rows from a result set?', 'DISTINCT'),
    ('In Java, which keyword prevents a class from being subclassed?', 'final'),
    ('What does ACID stand for in the context of database transactions?', 'Atomicity, Consistency, Isolation, Durability');
