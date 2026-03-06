package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.PriceRecord;

import com.np.pricehunt.backend.dto.PriceInfo;

public interface PriceExtractionService {
    PriceInfo extractPrice(String htmlContent);
}
