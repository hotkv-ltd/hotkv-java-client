// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * USD amounts ({@code LLMBUDGET.SET}) must be sent as plain digits; these tests pin down that
 * no formatter ever writes an exponent, a leading plus, or a locale dependent separator.
 */
class NumbersTest {

    private static void assertPlain(String s) {
        assertFalse(s.contains("E") || s.contains("e"), "exponent in " + s);
        assertFalse(s.startsWith("+"), "leading plus in " + s);
        assertFalse(s.contains(","), "comma in " + s);
        assertTrue(s.matches("-?[0-9]+(\\.[0-9]+)?"), "not plain decimal: " + s);
    }

    @Test
    void decimalTinyValuesAreNotExponentForm() {
        assertEquals("0.00001", Numbers.decimal(1e-5));
        assertEquals("0.000000000001", Numbers.decimal(1e-12));
        assertEquals("0.0001", Numbers.decimal(0.0001));
        assertEquals("0.0000123", Numbers.decimal(1.23e-5));
        assertPlain(Numbers.decimal(Double.MIN_VALUE));
    }

    @Test
    void decimalHugeValuesAreNotExponentForm() {
        assertEquals("10000000", Numbers.decimal(1e7));
        assertEquals("1000000000000000000000", Numbers.decimal(1e21));
        assertEquals("123456789012345680000", Numbers.decimal(1.2345678901234568e20));
        assertPlain(Numbers.decimal(1e300));
        assertPlain(Numbers.decimal(Double.MAX_VALUE));
        assertEquals(301, Numbers.decimal(1e300).length());
    }

    @Test
    void decimalNegativeIntegralAndZero() {
        assertEquals("-0.5", Numbers.decimal(-0.5));
        assertEquals("-0.00001", Numbers.decimal(-1e-5));
        assertEquals("3", Numbers.decimal(3.0));
        assertEquals("-3", Numbers.decimal(-3.0));
        assertEquals("100", Numbers.decimal(100.0));
        assertEquals("0", Numbers.decimal(0.0));
        assertEquals("0", Numbers.decimal(-0.0));
        assertEquals("0.1", Numbers.decimal(0.1));
        assertEquals("2.5", Numbers.decimal(2.5));
        assertEquals("0.30000000000000004", Numbers.decimal(0.1 + 0.2));
    }

    @Test
    void decimalRejectsNanAndInfinity() {
        assertThrows(IllegalArgumentException.class, () -> Numbers.decimal(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Numbers.decimal(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Numbers.decimal(Double.NEGATIVE_INFINITY));
    }

    @Test
    void scoreAllowsInfinityButNotNan() {
        assertEquals("inf", Numbers.score(Double.POSITIVE_INFINITY));
        assertEquals("-inf", Numbers.score(Double.NEGATIVE_INFINITY));
        assertEquals("0.00001", Numbers.score(1e-5));
        assertEquals("1000000000000000000000", Numbers.score(1e21));
        assertThrows(IllegalArgumentException.class, () -> Numbers.score(Double.NaN));
    }

    @Test
    void float32NarrowsAndStaysPlain() {
        assertEquals("0.1", Numbers.float32(0.1));
        assertEquals("0.00001", Numbers.float32(1e-5));
        assertEquals("0", Numbers.float32(1e-50)); // underflows to zero in single precision
        assertEquals("0", Numbers.float32(-0.0));
        assertEquals("-3", Numbers.float32(-3.0));
        assertEquals("0.12345679", Numbers.float32(0.123456789012));
        assertPlain(Numbers.float32(3.0e38));
        assertThrows(IllegalArgumentException.class, () -> Numbers.float32(1e300));
        assertThrows(IllegalArgumentException.class, () -> Numbers.float32(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Numbers.float32(Double.POSITIVE_INFINITY));
    }

    @Test
    void usdIsExactFixedPointWithSixDecimals() {
        assertEquals("0.00001", Numbers.usd(1e-5));
        assertEquals("0.000001", Numbers.usd(1e-6));
        assertEquals("12.5", Numbers.usd(12.5));
        assertEquals("3", Numbers.usd(3.0));
        assertEquals("0", Numbers.usd(0.0));
        assertEquals("0", Numbers.usd(-0.0));
        assertEquals("15.000001", Numbers.usd(15.000001));
        assertEquals("1000000", Numbers.usd(1e6));
        assertEquals("10000000", Numbers.usd(1e7));
        assertEquals("0.123457", Numbers.usd(0.1234567)); // rounded half up to a micro dollar
        assertPlain(Numbers.usd(1e-5));
    }

    @Test
    void usdRejectsWhatTheServerCannotStore() {
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(-1.0));
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(-1e-5));
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(Double.POSITIVE_INFINITY));
        // positive but below one micro dollar: would read as 0, which means "no limit"
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(1e-7));
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(4.9e-324));
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(1e21));
        assertThrows(IllegalArgumentException.class, () -> Numbers.usd(1e300));
    }

    @Test
    void formattersIgnoreTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            for (Locale l : List.of(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("hu-HU"),
                    Locale.forLanguageTag("ar-EG"))) {
                Locale.setDefault(l);
                assertEquals("0.00001", Numbers.decimal(1e-5), l.toString());
                assertEquals("1234567.5", Numbers.decimal(1234567.5), l.toString());
                assertEquals("0.5", Numbers.float32(0.5), l.toString());
                assertEquals("12.5", Numbers.usd(12.5), l.toString());
                assertEquals("-1.25", Numbers.score(-1.25), l.toString());
            }
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void writerEncodesDoublesAndFloatsAsPlainDecimal() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RespWriter(out).writeCommand("X", 1e-5, 1e21, -0.0, 2.0f, 0.1f, Double.NEGATIVE_INFINITY);
        String wire = out.toString(StandardCharsets.UTF_8);
        assertEquals("*7\r\n$1\r\nX\r\n$7\r\n0.00001\r\n$22\r\n1000000000000000000000\r\n$1\r\n0\r\n$1\r\n2\r\n"
                + "$3\r\n0.1\r\n$4\r\n-inf\r\n", wire);
    }

    @Test
    void writerRejectsNanBeforeWritingAnyByte() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespWriter w = new RespWriter(out);
        assertThrows(IllegalArgumentException.class, () -> w.writeCommand("ZADD", "k", Double.NaN, "m"));
        assertThrows(IllegalArgumentException.class, () -> w.writeCommand(List.of("ZADD", "k", Float.NaN, "m")));
        assertThrows(IllegalArgumentException.class, () -> w.writeCommand("SET", "k", null));
        assertEquals(0, out.size(), "a rejected command must not leave a partial frame in the buffer");
    }
}
