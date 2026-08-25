-- created_at on product and tracked_item (issue #225).
--
-- Neither table has recorded its insert time since V1, and every row written without the column
-- loses that fact for good — so it lands now, ahead of the first feature that reads it (#226).
--
-- Backfill: the earliest price record reachable from the row is the best estimate — a listing existed
-- at least as early as its first observation, a product at least as early as its first listing. Rows
-- with no history get now(). Add nullable, backfill, then constrain, so the dev DB migrates too.

ALTER TABLE tracked_item ADD COLUMN created_at timestamp with time zone;

UPDATE tracked_item ti
SET created_at = COALESCE(
    (SELECT MIN(pr."timestamp") FROM price_record pr WHERE pr.tracked_item_id = ti.id),
    now());

ALTER TABLE tracked_item ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE product ADD COLUMN created_at timestamp with time zone;

UPDATE product p
SET created_at = COALESCE(
    (SELECT MIN(ti.created_at) FROM tracked_item ti WHERE ti.product_id = p.id),
    now());

ALTER TABLE product ALTER COLUMN created_at SET NOT NULL;

-- No DEFAULT now(): creation time is domain data here (the seeder back-dates it), so a hand-written
-- insert must choose one rather than silently get "now". It would not mask a broken @PrePersist
-- anyway — Hibernate writes the column explicitly, and a default never replaces an explicit NULL.

-- Immutable at the database, not just in the entity (updatable = false only governs SQL Hibernate
-- generates). Postgres has no column-level "immutable" constraint, so a BEFORE UPDATE trigger is the
-- mechanism — a plain BEFORE UPDATE, not UPDATE OF created_at, which only fires when the column is
-- named in SET and would miss another trigger rewriting NEW. A deliberate correction needs
-- ALTER TABLE ... DISABLE TRIGGER first — that ceremony is the point.
CREATE FUNCTION forbid_created_at_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'created_at is immutable (table %, id %)', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_product_created_at_immutable
    BEFORE UPDATE ON product
    FOR EACH ROW EXECUTE FUNCTION forbid_created_at_change();

CREATE TRIGGER trg_tracked_item_created_at_immutable
    BEFORE UPDATE ON tracked_item
    FOR EACH ROW EXECUTE FUNCTION forbid_created_at_change();
