package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 29.10.: ueber den TEXT allein nie "sicher"/"machbar" mit Lesefehler -- "новый" las PaddleOCR als "HOBBIM". */
class ArenaListenSicherheitTest {

    private final Map<String, List<String>> listen = ArenaListenNetz.listenNamen(List.of(
            "ar_sicher_Hobbit", "ar_machbar_Maxkovalen", "ar_block_Xenoss", "ar_ungern_Lutorr"));

    @Test
    void sicherUndMachbarNurBeiGleichemNamen() {
        assertTrue(ArenaListenNetz.treffer(listen, "HOBBIM").isEmpty(), "ein Lesefehler macht keinen sicheren Sieg");
        assertTrue(ArenaListenNetz.treffer(listen, "Maxkova1en").isEmpty());
        assertEquals(ArenaListenNetz.SICHER, ArenaListenNetz.treffer(listen, "hobbit").get(0).liste());
        assertEquals(ArenaListenNetz.MACHBAR, ArenaListenNetz.treffer(listen, "Maxkovalen").get(0).liste());
    }

    @Test
    void niemalsUndMeidenBleibenTolerant() {
        assertEquals(ArenaListenNetz.NIEMALS, ArenaListenNetz.treffer(listen, "Xenos5").get(0).liste(), "ein Lesefehler -> trotzdem gesperrt");
        assertEquals(ArenaListenNetz.MEIDEN, ArenaListenNetz.treffer(listen, "Lutorn").get(0).liste());
    }
}
