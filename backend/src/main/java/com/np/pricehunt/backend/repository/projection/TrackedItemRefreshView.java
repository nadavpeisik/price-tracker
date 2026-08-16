package com.np.pricehunt.backend.repository.projection;

import java.time.Instant;

public record TrackedItemRefreshView(Long id, String url, Instant lastChecked) {}
