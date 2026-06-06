package com.np.pricehunt.backend.util;

import java.util.Objects;

public final class Throwables {

    private Throwables() {}

    public static String summarize(Throwable e) {
        if (e == null) return null;
        return e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
    }
}
