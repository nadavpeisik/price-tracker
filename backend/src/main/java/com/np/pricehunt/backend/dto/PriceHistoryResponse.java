package com.np.pricehunt.backend.dto;

import java.util.List;

public record PriceHistoryResponse(
        Long trackedItemId,
        String shopName,
        String url,
        List<PricePointResponse> history
) {}
