package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Profile speichern, laden und anwenden -- ohne Spiel (Auftrag 1, 11.10.). */
class ProfileTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    @TempDir
    Path tmp;

    @Test
    void ersteDateiBekommtBeispiele() {
        Profile p = new Profile(tmp.resolve("profile.txt"));
        assertEquals(List.of("Nacht", "Tag", "Event", "Arena"), p.namen());
        assertEquals("Nacht", p.name(1));
        assertEquals("Arena", p.name(4));
        assertNull(p.name(5));
        assertNull(p.name(0));
        assertTrue(Files.exists(tmp.resolve("profile.txt")));
    }

    @Test
    void setzen_undWiederLaden() {
        Path datei = tmp.resolve("profile.txt");
        Profile p = new Profile(datei);
        assertTrue(p.setzen("Test", List.of("!settings meldungen 5", "", "# Kommentar", "mute", "  !wach aus  ")));

        Profile neu = new Profile(datei);
        assertEquals(List.of("!settings meldungen 5", "!mute", "!wach aus"), neu.befehle("Test"));
        assertEquals("!settings meldungen 5\n!mute\n!wach aus", neu.alsText("Test"));
        assertTrue(neu.namen().contains("Test"));
    }

    @Test
    void setzen_ersetztUndLoeschen() {
        Profile p = new Profile(tmp.resolve("profile.txt"));
        p.setzen("Nacht", List.of("!mute"));
        assertEquals(List.of("!mute"), p.befehle("Nacht"));
        assertTrue(p.loeschen("Nacht"));
        assertFalse(p.loeschen("Nacht"));
        assertTrue(p.befehle("Nacht").isEmpty());
        assertFalse(p.setzen("", List.of("!mute")));
        assertFalse(p.setzen("[x]", List.of("!mute")));
    }

    @Test
    void anwenden_schicktJedenBefehlDerReihe() {
        Profile p = new Profile(tmp.resolve("profile.txt"));
        p.setzen("Abend", List.of("!settings meldungen 10", "!mute"));
        List<String> geschickt = new ArrayList<>();
        assertEquals(2, p.anwenden("Abend", geschickt::add));
        assertEquals(List.of("!settings meldungen 10", "!mute"), geschickt);
        assertEquals(0, p.anwenden("gibt es nicht", geschickt::add));
        assertEquals(2, geschickt.size());
    }

    @Test
    void anwenden_einFehlerStopptDieAnderenNicht() {
        Profile p = new Profile(tmp.resolve("profile.txt"));
        p.setzen("X", List.of("!eins", "!zwei", "!drei"));
        List<String> geschickt = new ArrayList<>();
        p.anwenden("X", b -> {
            if (b.equals("!zwei")) throw new IllegalStateException("klemmt");
            geschickt.add(b);
        });
        assertEquals(List.of("!eins", "!drei"), geschickt);
    }

    @Test
    void handgeschriebeneDateiWirdGelesen() throws Exception {
        Path datei = tmp.resolve("profile.txt");
        Files.writeString(datei, "# Kommentar\n[Mein Profil]\nsettings pass gold\n!lupen 5\n\n[Leer]\n", StandardCharsets.UTF_8);
        Profile p = new Profile(datei);
        assertEquals(List.of("Mein Profil", "Leer"), p.namen());
        assertEquals(List.of("!settings pass gold", "!lupen 5"), p.befehle("Mein Profil"));
        assertTrue(p.befehle("Leer").isEmpty());
    }
}
