-- Enforce uppercase currency at the schema level. ProductQueryService.findBestPrice()
-- computes mixedCurrencies via .distinct() without normalizing case; this CHECK makes
-- that read-side assumption load-bearing instead of merely lucky (today's write paths
-- happen to normalize to upper, but a CHECK prevents drift).

ALTER TABLE price_record
    ADD CONSTRAINT chk_price_record_currency_upper
    CHECK (currency = upper(currency));
