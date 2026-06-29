package com.np.pricehunt.backend.util;

public final class Throwables {

    private Throwables() {}

    /**
     * Compact one-line summary of a throwable — {@code "SimpleClassName"}, or {@code "SimpleClassName:
     * message"} when a message is present; {@code null} for a null throwable. Deliberately NOT a stack
     * trace: it feeds short audit/log fields ({@code scrape_attempt.failure_detail}, job-run summaries)
     * where a stable, low-noise string is wanted, not a multi-line dump.
     */
    public static String summarize(Throwable e) {
        if (e == null) return null;
        String message = e.getMessage();
        String name = e.getClass().getSimpleName();
        return (message == null || message.isBlank()) ? name : name + ": " + message;
    }
}
