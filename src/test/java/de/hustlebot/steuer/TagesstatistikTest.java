package de.hustlebot.steuer;

import de.hustlebot.core.Laufstatistik;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F29, F30): Tageswerte ueberleben den Neustart; die Zeit in Klartext.
 */
class TagesstatistikTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 10, 24);

    private static Laufstatistik.RunRecord lauf(int nr, long sek, int verloren, String uhr) {
        return new Laufstatistik.RunRecord(nr, sek, verloren, 0, "", uhr);
    }

    @Test
    void werteUeberlebenDenNeustart(@TempDir Path d) {
        Path datei = d.resolve("statistik-tage.txt");
        Tagesstatistik a = new Tagesstatistik(datei);
        a.lauf(HEUTE, 14, 270, 1);
        a.lauf(HEUTE, 14, 280, 0);
        a.lauf(HEUTE.minusDays(1), 9, 300, 2);
        a.nahrung(HEUTE, 5_000_000);
        a.nahrung(HEUTE, 4_000_000);
        a.speichern();

        Tagesstatistik b = new Tagesstatistik(datei);   // "neuer Start"
        var heute = b.tagOderNull(HEUTE);
        assertNotNull(heute);
        assertEquals(2, heute.laeufe);
        assertEquals(550, heute.sekunden);
        assertEquals(1, heute.niederlagen);
        assertEquals(2, heute.jeStunde[14]);
        assertEquals(5_000_000, heute.nahrungErste);
        assertEquals(4_000_000, heute.nahrungLetzte);
        assertEquals(1, b.tagOderNull(HEUTE.minusDays(1)).laeufe);
    }

    @Test
    void neueLaeufeWerdenNachgetragenUndNieDoppelt(@TempDir Path d) {
        Tagesstatistik t = new Tagesstatistik(d.resolve("x.txt"));
        List<Laufstatistik.RunRecord> g = new ArrayList<>();
        g.add(lauf(1, 100, 0, "10:00:01"));
        g.add(lauf(2, 110, 1, "10:05:00"));
        assertTrue(t.nachtragen(g, HEUTE));
        assertFalse(t.nachtragen(g, HEUTE), "nichts Neues");
        g.add(lauf(3, 120, 0, "11:00:00"));
        assertTrue(t.nachtragen(g, HEUTE));
        assertEquals(3, t.tagOderNull(HEUTE).laeufe);

        // die Laufgeschichte wird bei einem neuen Modus geleert -- dann von vorn zaehlen
        List<Laufstatistik.RunRecord> neu = new ArrayList<>();
        neu.add(lauf(1, 90, 0, "12:00:00"));
        assertTrue(t.nachtragen(neu, HEUTE));
        assertEquals(4, t.tagOderNull(HEUTE).laeufe);
        assertEquals(2, t.tagOderNull(HEUTE).jeStunde[10]);
        assertEquals(1, t.tagOderNull(HEUTE).jeStunde[11]);
    }

    @Test
    void kaputteZeilenStoerenNicht(@TempDir Path d) throws Exception {
        Path datei = d.resolve("s.txt");
        Files.writeString(datei, "# Kopf\nkaputt;;;\n2026-10-23;5;100;1;-1;-1;0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,5\nquatsch\n");
        Tagesstatistik t = new Tagesstatistik(datei);
        assertEquals(5, t.tagOderNull(LocalDate.of(2026, 10, 23)).laeufe);
        assertEquals(5, t.tagOderNull(LocalDate.of(2026, 10, 23)).jeStunde[23]);
    }

    @Test
    void textZeigtHeuteSiebenTageUndGesamt(@TempDir Path d) {
        Tagesstatistik t = new Tagesstatistik(d.resolve("x.txt"));
        for (int i = 0; i < 3; i++) t.lauf(HEUTE, 10, 600, 0);
        for (int i = 0; i < 2; i++) t.lauf(HEUTE.minusDays(3), 10, 600, 1);
        t.lauf(HEUTE.minusDays(20), 10, 600, 0);
        String s = t.text(HEUTE, "");
        String[] zeilen = s.split("\n");
        assertEquals(3, zeilen.length);
        assertTrue(zeilen[0].contains("3 "), zeilen[0]);
        assertTrue(zeilen[1].contains("5 "), zeilen[1]);
        assertTrue(zeilen[2].contains("6 "), zeilen[2]);
        assertEquals(7, t.laeufeJeTag(HEUTE, 7).size());
        assertEquals(3.0, t.laeufeJeTag(HEUTE, 7).get(6).zahl());
        assertEquals(1, t.laeufeJeStunde(HEUTE).size());
    }

    @Test
    void stundenText() {
        assertEquals("5:12 h", Tagesstatistik.stunden(18720));
        assertEquals("0:01 h", Tagesstatistik.stunden(60));
    }

    // ---------------------------------------------------------------- F30

    private static final List<String> ZEILEN = List.of(
            "272000\t24.10. 14:05  4:32  272 s total | Loading 12.0 s | Start-up 5.0 s | Fights 90.5 s (12 x 7.54) | Blessings 20.0 s | Path choice 45.0 s | Windows 20.0 s | Floor change 10.0 s | Completion 4.0 s | of which the bot waits 150.0 s",
            "260000\t24.10. 14:10  4:20  260 s gesamt | Laden 10,0 s | Kampf 85,5 s (11 x 7,77) | Segen 20,0 s | Wegwahl 40,0 s | davon Bot wartet 130,0 s",
            "kaputte zeile",
            "250000\t24.10. 14:15  4:10  250 s total | Fights 80.0 s | Path choice 40.0 s | of which the bot waits 120.0 s");

    @Test
    void zeitberichteBeiderSprachenWerdenZusammengezaehlt() {
        var v = Zeitverteilung.aus(ZEILEN);
        assertEquals(3, v.runs());
        assertEquals(272 + 260 + 250, v.gesamtSek(), 0.001);
        assertEquals(150 + 130 + 120, v.wartetSek(), 0.001);
        assertEquals(90.5 + 85.5 + 80.0, v.abschnitte().get("kampf"), 0.001);
        assertEquals(45 + 40 + 40, v.abschnitte().get("wegwahl"), 0.001);
        assertEquals(400.0 / 782.0, v.wartAnteil(), 0.001);
    }

    @Test
    void textSagtAnteileUndEinenSatz() {
        String s = Zeitverteilung.text(Zeitverteilung.aus(ZEILEN));
        assertTrue(s.contains("%"));
        assertTrue(s.toLowerCase().contains("fights") || s.toLowerCase().contains("kämpfe"));
        assertTrue(s.contains("51 %"), "wartet 400 von 782 s");
        assertFalse(Zeitverteilung.text(Zeitverteilung.aus(ZEILEN.subList(0, 1))).contains("fights 33"), "zu wenige Runs: nur der Hinweis");
    }

    @Test
    void urteilNachWartezeit() {
        assertNotEquals(Zeitverteilung.urteil(0.2), Zeitverteilung.urteil(0.5));
        assertNotEquals(Zeitverteilung.urteil(0.5), Zeitverteilung.urteil(0.8));
        assertTrue(Zeitverteilung.urteil(0.8).toLowerCase().contains("virus"));
    }

    @Test
    void fehlendeDateiIstLeer(@TempDir Path d) {
        var v = Zeitverteilung.lesen(d.resolve("gibt-es-nicht.txt"), 10);
        assertEquals(0, v.runs());
        assertTrue(Zeitverteilung.text(v).contains("0"));
    }
}
