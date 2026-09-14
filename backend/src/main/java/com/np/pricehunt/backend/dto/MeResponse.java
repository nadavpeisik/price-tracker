package com.np.pricehunt.backend.dto;

/**
 * What the backend knows about the calling account that the UI needs (issue #248). Identity attributes
 * (name, email, picture) come from the BFF's {@code /bff/me}, not from here.
 *
 * @param displayCurrency the effective, validated display currency: the stored preference when there is
 *     one, otherwise the configured default — the same code every money-quoting endpoint echoes
 */
public record MeResponse(String displayCurrency) {}
