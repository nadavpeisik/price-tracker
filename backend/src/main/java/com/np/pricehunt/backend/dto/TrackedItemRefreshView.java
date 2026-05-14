package com.np.pricehunt.backend.dto;

import java.time.Instant;

public record TrackedItemRefreshView(Long id, String url, Instant lastChecked) {}
