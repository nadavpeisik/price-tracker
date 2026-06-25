-- Tri-state availability (issue #124).
--
-- Replaces price_record.available (boolean) with availability_status, a 3-value enum
-- {AVAILABLE, UNAVAILABLE, UNKNOWN}. A scrape fails only when price is missing; availability is
-- optional metadata, so "no signal" must be UNKNOWN, not a fabricated false. Matches
-- com.np.pricehunt.backend.domain.AvailabilityStatus. varchar + CHECK (not a native PG enum) to stay
-- consistent with the other enum columns (shop_name_source, status, origin) and to keep adding a
-- value (e.g. LIMITED/PREORDER later) a normal transactional Flyway migration — ALTER TYPE ... ADD
-- VALUE can't run in a txn and values can't be dropped/reordered.
--
-- Expand-then-drop within one migration — safe while single-instance (the app stops during migrate;
-- no old code reads the dropped column mid-deploy). When the app goes multi-instance / rolling, split
-- the DROP COLUMN into a later migration (expand/contract) and batch the backfill on a large table.

-- DEFAULT 'UNKNOWN' is metadata-only in PG11+ (no table rewrite) and fills existing rows.
ALTER TABLE price_record ADD COLUMN availability_status varchar(32) DEFAULT 'UNKNOWN';

-- `available` is NOT NULL (V1), so the ELSE never fires — kept as free defensive insurance.
UPDATE price_record
SET availability_status = CASE
    WHEN available IS TRUE THEN 'AVAILABLE'
    WHEN available IS FALSE THEN 'UNAVAILABLE'
    ELSE 'UNKNOWN'
END;

ALTER TABLE price_record ALTER COLUMN availability_status SET NOT NULL;
-- Drop the default: the application always sets availability explicitly; no DB-level default fallback.
ALTER TABLE price_record ALTER COLUMN availability_status DROP DEFAULT;

ALTER TABLE price_record
    ADD CONSTRAINT price_record_availability_status_check
    CHECK (availability_status IN ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN'));

ALTER TABLE price_record DROP COLUMN available;
