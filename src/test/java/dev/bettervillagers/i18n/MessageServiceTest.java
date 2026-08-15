package dev.bettervillagers.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageServiceTest {

    @Test
    void replacesActivityPlaceholdersWithBareKeys() {
        String rendered = MessageService.applyPlaceholders(
                "{host} 在 {village} 举行 {activity}",
                "host", "罗兰", "village", "晨曦村", "activity", "贸易集市");

        assertEquals("罗兰 在 晨曦村 举行 贸易集市", rendered);
    }
}
