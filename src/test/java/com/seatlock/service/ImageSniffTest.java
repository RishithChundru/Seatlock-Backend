package com.seatlock.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.seatlock.service.EventService.sniffImageType;
import static org.assertj.core.api.Assertions.assertThat;

class ImageSniffTest {
    @Test
    void detectsRealImageTypesAndRejectsEverythingElse() {
        assertThat(sniffImageType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0})).isEqualTo("image/jpeg");
        assertThat(sniffImageType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).isEqualTo("image/png");
        assertThat(sniffImageType("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("image/webp");
        assertThat(sniffImageType("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(sniffImageType("<html><script>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(sniffImageType(new byte[0])).isNull();
    }
}
