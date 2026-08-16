-- Indexes for the dashboard query endpoint (issue #146).
--
-- Two indexes, each earning its place; deliberately no others.
--
-- Plain CREATE INDEX takes ACCESS EXCLUSIVE and blocks writers for the build. Safe only because
-- Flyway runs at startup on one instance, before traffic and before any scheduler. Rolling or
-- multi-instance deploys break that assumption and need CONCURRENTLY here (#176).

-- 1. The missing foreign-key index.
--
--    Postgres indexes a PRIMARY KEY and a UNIQUE constraint automatically, but NOT a foreign key —
--    it needs an index on the *referenced* side (product.id, the PK) and never creates one on the
--    *referencing* side. MySQL/InnoDB does, which is why the absence is easy to miss. So
--    tracked_item.product_id has been unindexed since V1, and every `WHERE product_id = ?` reads the
--    whole table.
--
--    Two kinds of reader pay for that:
--      * Queries we write — findByProduct (product detail, the single-product trend, and the
--        deprecated list endpoint, which calls it once PER PRODUCT on a page), plus this issue's
--        countByProduct for the listing cap and findByProductIdIn for the page's sparklines.
--      * The database itself, on DELETE. The FK has no ON DELETE clause, so it defaults to NO ACTION:
--        before removing a product row, Postgres must prove no tracked_item still references it. That
--        check is a scan of the child table per deleted parent — the classic unindexed-FK trap, and
--        the one nobody profiles because it hides inside a DELETE.
--
--    Honest scale note: at the admission cap (~500 products x 20 listings) tracked_item is a few
--    hundred pages and a sequential scan of it is sub-millisecond from cache. This is cheap
--    insurance that stops the cost scaling with the table, not a fix for measured pain.
CREATE INDEX idx_tracked_item_product ON tracked_item (product_id);

-- 2. The two-cutoff query's access path. That query ranks records inside two time windows across the
--    WHOLE tracked set, so its predicates are timestamp-only — there is no tracked_item_id list to
--    lead with. The existing idx_price_record_item_timestamp is (tracked_item_id, "timestamp"), whose
--    leading column is absent from the predicate, so it cannot serve this; without a timestamp-leading
--    index every dashboard request sequentially scans the entire ever-growing price_record history
--    twice (once per window). price_record is insert-only, so the extra B-tree costs one more write
--    per scrape and never fragments through updates.
CREATE INDEX idx_price_record_timestamp ON price_record ("timestamp");

-- Deliberately NOT added:
--   * product(name)     — the dashboard search is ILIKE '%term%'; a B-tree cannot serve a leading
--                         wildcard. A trigram (pg_trgm) index would, and is the right answer if
--                         search ever gets slow — but it needs an extension, so it is not free.
--   * tracked_item(shop_name) — the shop filter runs in Java over rows already in memory (the whole
--                         set is loaded for the summary tiles regardless), and shop names are
--                         low-cardinality, so an index would be read by nothing.
