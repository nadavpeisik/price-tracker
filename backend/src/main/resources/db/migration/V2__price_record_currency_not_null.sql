-- Lock down PriceRecord.currency at the schema level so future write paths
-- (Kafka consumers, admin tools, batch imports) cannot insert null. Java-side
-- validation in ProductTrackingService.isValidPrice() already rejects nulls;
-- this is defense in depth at the data layer.

ALTER TABLE price_record
    ALTER COLUMN currency SET NOT NULL;
