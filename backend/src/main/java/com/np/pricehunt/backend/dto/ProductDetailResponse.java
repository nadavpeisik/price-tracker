package com.np.pricehunt.backend.dto;

import java.util.List;

public record ProductDetailResponse(
        Long id,
        String name,
        String description,
        List<TrackedItemSummary> trackedItems
) {}
