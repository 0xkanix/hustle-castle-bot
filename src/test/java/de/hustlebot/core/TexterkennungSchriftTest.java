package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexterkennungSchriftTest {

    @Test
    void schriftHinweisNurFuerFremdeSchriften() {
        // nur Sprachen, die dieser PC auch hat -- ohne Windows-Texterkennung ist die Liste leer
        var weitere = Texterkennung.weitereSchriften();
        var han = Texterkennung.schriftHinweis(List.of("元素神"), List.of());
        for (String s : han) assertTrue(weitere.contains(s));
        if (weitere.contains("ja")) assertTrue(han.contains("ja"));
        if (weitere.contains("ko")) assertTrue(Texterkennung.schriftHinweis(List.of("홍길동"), null).contains("ko"));
        assertFalse(Texterkennung.schriftHinweis(List.of("Myth Gods", "Сволочь"), List.of()).contains("ja"));
        assertTrue(Texterkennung.schriftHinweis(List.of("Jinx Maruna"), List.of()).isEmpty());
    }

    @Test
    void hinweisePassenAufVolleTags() {
        // Code-Review 26.10.: Kurzform "zh-Hans"/"ar" muss die installierten vollen Tags finden
        var tags = List.of("zh-Hans-CN", "zh-Hant-TW", "ar-SA", "ja", "ko", "el");
        var aus = Texterkennung.passendeTags(new java.util.LinkedHashSet<>(List.of("zh-Hans", "zh-Hant", "zh", "ja")), tags);
        assertTrue(aus.contains("zh-Hans-CN") && aus.contains("zh-Hant-TW") && aus.contains("ja"));
        assertTrue(Texterkennung.passendeTags(java.util.Set.of("ar"), tags).contains("ar-SA"));
        assertFalse(Texterkennung.passendeTags(java.util.Set.of("el"), tags).contains("ja"));
    }
}
