-- Switch exchange_rate.id from IDENTITY to a Hibernate-managed sequence so
-- JPA can pre-assign IDs and JDBC inserts can batch into a multi-row VALUES.
-- IDENTITY silently disables Hibernate batching (Hibernate must call
-- getGeneratedKeys() after every row to learn the DB-assigned id).
--
-- INCREMENT BY 50 must match @SequenceGenerator(allocationSize=50) in the entity.
-- The pooled-lo optimizer (Hibernate default) assumes ranges of (last_returned - allocation, last_returned];
-- mismatched values cause either ID collisions (increment < allocation) or huge gaps (increment > allocation).

CREATE SEQUENCE IF NOT EXISTS exchange_rate_seq
    INCREMENT BY 50;

-- Seed past max(id) of the existing IDENTITY-managed rows. GREATEST(1000, ...) gives
-- headroom on fresh DBs where the table is empty.
SELECT setval(
    'exchange_rate_seq',
    GREATEST(1000, (SELECT COALESCE(MAX(id), 0) + 50 FROM exchange_rate))
);

-- Drop the IDENTITY property; Hibernate now supplies IDs from exchange_rate_seq.
-- The implicit sequence pg created for IDENTITY (exchange_rate_id_seq) is dropped
-- automatically as part of DROP IDENTITY.
ALTER TABLE exchange_rate ALTER COLUMN id DROP IDENTITY IF EXISTS;
