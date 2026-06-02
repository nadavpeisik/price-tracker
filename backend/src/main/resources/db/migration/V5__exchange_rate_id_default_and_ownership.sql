-- Follow-up to V4. V4 dropped the IDENTITY from exchange_rate.id but did not
-- give the column a new default, leaving it without an auto-generator at the
-- DB level. Hibernate still works because it pre-assigns IDs from
-- exchange_rate_seq, but any direct SQL INSERT that omits id (seed migrations,
-- psql admin fixes, partial pg_restore) would fail with a NOT NULL violation.
--
-- Restoring the default closes that gap. OWNED BY ties the sequence to the
-- column so it drops with the table and pg_dump treats them as one unit.

ALTER TABLE exchange_rate
    ALTER COLUMN id SET DEFAULT nextval('exchange_rate_seq');

ALTER SEQUENCE exchange_rate_seq OWNED BY exchange_rate.id;
