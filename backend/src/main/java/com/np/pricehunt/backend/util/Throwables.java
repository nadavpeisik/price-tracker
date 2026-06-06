package com.np.pricehunt.backend.util;

public final class Throwables {

    private Throwables() {}

    public static String summarize(Throwable e) {
        if (e == null) return null;
        String message = e.getMessage();
        String name = e.getClass().getSimpleName();
        return (message == null || message.isBlank()) ? name : name + ": " + message;
    }
}
