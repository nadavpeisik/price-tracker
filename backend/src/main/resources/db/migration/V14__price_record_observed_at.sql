-- #229: price_record."timestamp" was a Hibernate-generated name that is both a SQL keyword and a
-- type name, so references to it had to be quoted (or at least read as if they did). Rename it to observed_at — the <event>_at form
-- every other time column uses, and the alias the dashboard queries were already projecting it as.
-- The indexes follow the column; their names do not, so they are renamed alongside.
ALTER TABLE price_record RENAME COLUMN timestamp TO observed_at;

ALTER INDEX idx_price_record_item_timestamp RENAME TO idx_price_record_item_observed_at;
ALTER INDEX idx_price_record_timestamp RENAME TO idx_price_record_observed_at;
