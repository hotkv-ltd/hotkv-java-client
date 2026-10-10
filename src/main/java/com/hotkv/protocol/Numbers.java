// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.protocol;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Formats floating point command arguments for the HotKV wire protocol.
 *
 * <p>The server reads numbers with {@code strtod} (sorted set scores, geo, bloom/cms/topk) or
 * with {@code std::from_chars} (cache thresholds, rate limiter rates, vectors, time series
 * values, feature values), and reads USD money with a strict fixed point parser
 * ({@code LLMBUDGET.SET}, {@code LLMSTATS.PRICE SET}) that accepts digits and one dot only:
 * no exponent, no sign, a leading digit, at most six fractional digits. {@link Double#toString}
 * writes {@code 1.0E-5} for small and {@code 1.0E7} for large values, which the strict parser
 * rejects. Every formatter here therefore writes plain positional decimal digits, never an
 * exponent, never a leading {@code +}, never a locale dependent separator, and writes negative
 * zero as {@code 0}.
 */
public final class Numbers {

    private static final BigDecimal MAX_USD = new BigDecimal("18446744073709.551615");

    private Numbers() {
    }

    /**
     * A finite double as the shortest plain decimal that round trips, for commands that take
     * only finite values. NaN and the infinities throw {@link IllegalArgumentException}.
     */
    public static String decimal(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("hotkv: " + d + " is not a finite number");
        }
        return plain(Double.toString(d));
    }

    /**
     * A double for sorted set scores and bounds: like {@link #decimal} but the infinities are
     * written as {@code inf} and {@code -inf}, which the server accepts there. NaN throws.
     */
    public static String score(double d) {
        if (Double.isNaN(d)) {
            throw new IllegalArgumentException("hotkv: NaN is not a valid number argument");
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        return plain(Double.toString(d));
    }

    /**
     * A vector component or similarity threshold: the server stores single precision floats, so
     * the value is narrowed first and the shortest float32 round trip form is written. A value
     * outside the float32 range (the server would refuse it as out of range) or a non finite
     * value throws.
     */
    public static String float32(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("hotkv: " + d + " is not a finite number");
        }
        float f = (float) d;
        if (Float.isInfinite(f)) {
            throw new IllegalArgumentException("hotkv: " + d + " is outside the single precision range");
        }
        return plain(Float.toString(f));
    }

    /**
     * An amount of USD for the commands whose server parser is exact fixed point
     * ({@code LLMBUDGET.SET} cost limit, {@code LLMSTATS.PRICE SET} prices): non negative, at
     * most six fractional digits, no exponent. The value is rounded half up to a micro dollar.
     * A positive value that would round to zero throws, because zero means "no limit" for a
     * budget and "free" for a price, which the caller did not ask for; so do negative, NaN,
     * infinite and out of range values.
     */
    public static String usd(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("hotkv: " + d + " is not a finite USD amount");
        }
        if (d < 0) {
            throw new IllegalArgumentException("hotkv: a USD amount must not be negative: " + d);
        }
        BigDecimal exact = new BigDecimal(Double.toString(d));
        BigDecimal micros = exact.setScale(6, RoundingMode.HALF_UP);
        if (d > 0 && micros.signum() == 0) {
            throw new IllegalArgumentException("hotkv: " + d
                    + " USD is below one micro dollar, the smallest amount the server stores");
        }
        if (micros.compareTo(MAX_USD) > 0) {
            throw new IllegalArgumentException("hotkv: " + d + " USD is too large");
        }
        return micros.signum() == 0 ? "0" : micros.stripTrailingZeros().toPlainString();
    }

    private static String plain(String javaRepresentation) {
        BigDecimal bd = new BigDecimal(javaRepresentation);
        if (bd.signum() == 0) {
            return "0";
        }
        return bd.stripTrailingZeros().toPlainString();
    }
}
