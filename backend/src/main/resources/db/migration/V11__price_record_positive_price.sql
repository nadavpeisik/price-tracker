-- Issue #175: a price of zero is not a price.
--
-- PriceValidator has always rejected price <= 0, but it judged the value BEFORE numeric(19,4)
-- rounded it: an extracted 0.00004 passed "price > 0" and then landed in this column as 0.0000.
-- That zero is worse than a bad row -- it becomes the delta-check baseline, where max = 0 * 3 and
-- min = 0 / 3 both collapse to zero, so every later scrape of that listing is rejected as
-- DELTA_EXCEEDED and the listing stops updating permanently.
--
-- The application now normalizes to scale 4 before validating, so the rounded value is the one
-- checked. This migration handles the rows that predate that fix.

-- Delete before constraining, not after failing. A non-positive price is not data we could keep:
-- the app never intended to store it, nothing downstream can render it, and TrendEligibility
-- already refuses to read it. Adding the constraint without this would abort startup on exactly
-- the databases that carry a frozen listing -- so the migration meant to fix the bug would instead
-- prevent the app from booting and stop the self-healing validation from ever running.
--
-- Removing the row IS the repair, but only if last_checked comes with it: that column is set when a
-- record is saved, so a listing whose only observation just disappeared would still claim to have
-- been checked. It would then read as "no price, checked 2 days ago" on the API, and the scheduler
-- (WHERE last_checked IS NULL OR last_checked < cutoff) would wait out its window before retrying.
-- Recomputing from what survives -- NULL when nothing does -- puts the listing back in the queue now.
DO $$
DECLARE
    removed integer;
BEGIN
    CREATE TEMP TABLE v11_affected_items ON COMMIT DROP AS
        SELECT DISTINCT tracked_item_id AS id
        FROM price_record
        WHERE price <= 0 OR price = 'NaN'::numeric;

    DELETE FROM price_record WHERE price <= 0 OR price = 'NaN'::numeric;
    GET DIAGNOSTICS removed = ROW_COUNT;

    UPDATE tracked_item t
    SET last_checked = (SELECT max(p.timestamp) FROM price_record p WHERE p.tracked_item_id = t.id)
    WHERE t.id IN (SELECT id FROM v11_affected_items);

    IF removed > 0 THEN
        RAISE NOTICE
            'V11 removed % invalid price_record row(s); their listings resume on the next scrape',
            removed;
    END IF;
END $$;

-- The backstop for anything that writes this column without going through the application's
-- normalize-then-validate path: a repair script, a future importer, a direct psql session.
--
-- The NaN clause is not redundant: PostgreSQL sorts numeric NaN ABOVE every ordinary number, so
-- `price > 0` alone would happily accept it. BigDecimal cannot represent NaN, so the application
-- can never produce one -- but out-of-band writes are the entire reason this constraint exists, and
-- a merged migration cannot be edited to add the clause later.
ALTER TABLE price_record
    ADD CONSTRAINT price_record_price_positive CHECK (price > 0 AND price <> 'NaN'::numeric);
