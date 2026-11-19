package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoordinateTextTest {
    @Test
    void trimsTrailingZerosAndKeepsFractionDigits() {
        assertEquals("12.5", CoordinateText.plain(12.5));
        assertEquals("90", CoordinateText.plain(90.0));
        assertEquals("-0.5", CoordinateText.plain(-0.5));
        assertEquals("0.0001", CoordinateText.plain(0.0001));
        assertEquals("0", CoordinateText.plain(0.0));
    }

    @Test
    void staysPlainAcrossTheWorldBorderRange() {
        assertEquals("12345678.5", CoordinateText.plain(12345678.5));
        assertEquals("-29999984", CoordinateText.plain(-29999984.0));
        assertEquals("29999999.999999", CoordinateText.plain(29999999.999999));
    }
}
