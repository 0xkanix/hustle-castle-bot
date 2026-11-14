package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelbstKaempfeTest {

    private static final LocalDateTime ZEIT = LocalDateTime.of(2026, 10, 30, 21, 5);

    @Test
    void unbekannterSpielerWirdMitGenauemNamenAngelegtUndGezaehlt(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        assertNull(SelbstKaempfe.finde(datei, "Jahamość"));

        SelbstKaempfe.ergebnis(datei, "Jahamość", "Clan", true, ZEIT, 12, 148);
        SelbstKaempfe.ergebnis(datei, "Jahamość", "", false, ZEIT, 30, 0);
        SelbstKaempfe.Eintrag e = SelbstKaempfe.ergebnis(datei, "Jahamość", "", true, ZEIT.plusMinutes(1), -1, -1);

        assertEquals("Jahamość", e.name());
        assertEquals("Clan", e.clan(), "ein leer gelesener Clan loescht den bekannten nicht");
        assertEquals("SNS", e.ergebnisse());
        assertEquals(2, e.siege());
        assertEquals(1, e.niederlagen());
        assertEquals(new SelbstKaempfe.Kampf(true, "2026-10-30 21:05", 12, 148), e.kaempfe().get(0));
        assertEquals("2026-10-30 21:06", SelbstKaempfe.finde(datei, "Jahamość").kaempfe().get(2).um());
    }

    @Test
    void imVergleichsfensterGesehenZaehltKeinenKampf(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.gesehen(datei, "Peter", "Die Ritter");
        SelbstKaempfe.gesehen(datei, "Peter", "");
        SelbstKaempfe.gesehen(datei, "", "Niemand");

        SelbstKaempfe.Eintrag e = SelbstKaempfe.finde(datei, "Peter");
        assertEquals("Die Ritter", e.clan());
        assertEquals("", e.ergebnisse());
        assertNull(SelbstKaempfe.finde(datei, "Niemand"));
    }

    @Test
    void ueberstehtNeuesLesenAuchMitFremdenSchriften(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.ergebnis(datei, "Сволочь", "", false, ZEIT, 9, 0);
        SelbstKaempfe.ergebnis(datei, "김철수", "", true, ZEIT, 20, 225);
        SelbstKaempfe.eingetragen(datei, "Сволочь", "meiden");

        assertEquals("meiden", SelbstKaempfe.finde(datei, "Сволочь").liste());
        assertEquals("S", SelbstKaempfe.finde(datei, "김철수").ergebnisse());
    }

    @Test
    void verschiedeneSpielerBleibenGetrennt(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.ergebnis(datei, "lino", "", true, ZEIT, 1, 1);
        SelbstKaempfe.ergebnis(datei, "Xenos", "", false, ZEIT, 1, 1);

        assertEquals("S", SelbstKaempfe.finde(datei, "lino").ergebnisse());
        assertEquals("N", SelbstKaempfe.finde(datei, "Xenos").ergebnisse());
        assertNull(SelbstKaempfe.finde(datei, ""));
    }

    /** Pruefung 30.10.: ein zu lockerer Vergleich mischte Siege und Niederlagen verschiedener Spieler. */
    @Test
    void aehnlicheNamenBleibenZweiSpieler(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.ergebnis(datei, "Dragon", "", true, ZEIT, 1, 1);
        SelbstKaempfe.ergebnis(datei, "Dragon99", "", false, ZEIT, 1, 1);
        assertEquals("S", SelbstKaempfe.finde(datei, "Dragon").ergebnisse());
        assertEquals("N", SelbstKaempfe.finde(datei, "Dragon99").ergebnisse());
    }

    @Test
    void einLesefehlerImNamenZaehltZumSelbenSpieler(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.ergebnis(datei, "Murena", "", true, ZEIT, 1, 1);
        assertEquals("SN", SelbstKaempfe.ergebnis(datei, "Mureno", "", false, ZEIT, 1, 1).ergebnisse());
    }

    @Test
    void gleicherNameInAnderemClanIstEinAndererSpieler(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.ergebnis(datei, "Peter", "Die Ritter", true, ZEIT, 1, 1);
        assertEquals("N", SelbstKaempfe.ergebnis(datei, "Peter", "Wolfsrudel", false, ZEIT, 1, 1).ergebnisse());
        assertEquals("SS", SelbstKaempfe.ergebnis(datei, "Peter", "Die Ritter", true, ZEIT, 1, 1).ergebnisse());
    }

    /** Pruefung 30.10.: eine nicht lesbare Datei darf beim naechsten Kampf nicht ueberschrieben werden. */
    @Test
    void nichtLesbareDateiWirdNichtUeberschrieben(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        byte[] kaputt = {(byte) 0xFF, (byte) 0xFE, (byte) 0xC0, 0x41};
        Files.write(datei, kaputt);
        assertEquals("S", SelbstKaempfe.ergebnis(datei, "lino", "", true, ZEIT, 1, 1).ergebnisse());
        SelbstKaempfe.gesehen(datei, "Xenos", "");
        assertEquals(kaputt.length, Files.size(datei));
        org.junit.jupiter.api.Assertions.assertArrayEquals(kaputt, Files.readAllBytes(datei));
    }

    @Test
    void entfernenGibtDieKaempfeZumUebernehmenHeraus(@TempDir Path ordner) {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        SelbstKaempfe.ergebnis(datei, "lino", "", true, ZEIT, 12, 148);
        SelbstKaempfe.ergebnis(datei, "Xenos", "", false, ZEIT, 1, 1);

        assertEquals("S", SelbstKaempfe.entfernen(datei, "lino").ergebnisse());
        assertNull(SelbstKaempfe.finde(datei, "lino"));
        assertNotNull(SelbstKaempfe.finde(datei, "Xenos"));
        assertNull(SelbstKaempfe.entfernen(datei, "lino"));
    }

    @Test
    void kaputteZeilenWerdenUebersprungen(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve(SelbstKaempfe.DATEI);
        Files.writeString(datei, "lino\t\tS,2026-10-30 21:05,12,148\t\r\nkaputt\r\n\t\tS,a,1,1\r\nfalsch\t\tX,a,1,1\r\nzahl\t\tS,a,b,1\r\n");
        assertNotNull(SelbstKaempfe.finde(datei, "lino"));
        assertNull(SelbstKaempfe.finde(datei, "kaputt"));
        assertNull(SelbstKaempfe.finde(datei, "falsch"));
        assertNull(SelbstKaempfe.finde(datei, "zahl"));
    }
}
