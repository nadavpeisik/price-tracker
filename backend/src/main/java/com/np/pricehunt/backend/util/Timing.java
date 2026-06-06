package com.np.pricehunt.backend.util;

public final class Timing {

    private Timing() {}

    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
