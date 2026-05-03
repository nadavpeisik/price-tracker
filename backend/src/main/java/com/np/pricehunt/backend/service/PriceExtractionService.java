package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.ScrapeResponse;

public interface PriceExtractionService {
    PriceInfo extractPrice(ScrapeResponse scrapeResponse);
}
