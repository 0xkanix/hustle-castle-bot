package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Autor 21.11.: der Notizblock erkennt Gegner zuerst am Schriftbild (der Name als kleines Bild), mit Gegenprobe Kraft
 * (+/-1,5 %) und Clan. Fall aus dem Log vom 20.11. 23:09: "~ЦИРИ~" (Myth Gods, 35.465.161) gelesen als "-ЦИРИ-",
 * "-UMPM-" und "V Myth Gods" -- soll EIN Eintrag sein, unter einem echten Namen. Aehnliche Namen verschiedener Spieler
 * (Revan/Raven, Том/Тан) mit anderer Kraft bleiben getrennt.
 */
class GegnerbuchSchriftbildTest {

    private static final long ZIRI_KRAFT = 35_465_161;

    @TempDir
    Path ordner;

    private Gegnerbuch buch() {
        return new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"),
                new Glyphenschrift(ordner.resolve("glyphen")));
    }

    private static BufferedImage bild(String name) throws Exception {
        try (InputStream in = GegnerbuchSchriftbildTest.class.getResourceAsStream("/schriftbild/" + name)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    private static Schriftbild schild(String name) throws Exception {
        BufferedImage b = bild(name);
        if (b.getWidth() == 162 && b.getHeight() == 80) {   // ganzes Schild aus logs\schmuckschrift: nur die Namenszeile
            b = b.getSubimage(58, 0, 104, 33);
        }
        Schriftbild s = Schriftbild.aus(b, Schriftbild.SCHILD);
        assertNotNull(s, name);
        return s;
    }

    private static Glyphenschrift.Ergebnis nb(String datei) throws Exception {
        return Glyphenschrift.Ergebnis.nurBild(schild(datei));
    }

    @Test
    void ziriUnterDreiLesungenIstEinEintrag() throws Exception {
        Gegnerbuch b = buch();
        b.ergebnis("-ЦИРИ-", "Myth Gods", true, ZIRI_KRAFT, 15, 144, nb("ziri-schild.png"));
        // Fenster liest "-UMPM-": anderes Bild derselben Schrift (Autors Listenbild), gleiche Kraft
        b.merkeStaerke("-UMPM-", "Myth Gods", ZIRI_KRAFT, nb("ziri-liste.png"));
        // der Clan wurde als Name gelesen: "V Myth Gods" / "O'Myth Go s" -- ganzes Schild aus einem anderen Turnier
        b.ergebnis("V Myth Gods", "O'Myth Go s", false, ZIRI_KRAFT, 27, 0, nb("ziri-schild-gross-1.png"));
        assertEquals(1, b.anzahl(), "ein Spieler, ein Eintrag");
        Gegnerbuch.Notiz n = b.nachschlagen("-ЦИРИ-", "Myth Gods").orElseThrow();
        assertEquals(2, n.kaempfe());
        assertFalse(n.nameIstClan(), "zaehlt fuer die Bilanz");
        // Kampf ohne lesbaren Namen findet ihn am Bild
        b.ergebnis("", "", true, ZIRI_KRAFT, 12, 150, nb("ziri-schild-gross-2.png"));
        assertEquals(3, b.nachschlagen("-ЦИРИ-", "Myth Gods").orElseThrow().kaempfe());
        // wiedererkennen liefert den Namen der Notiz, egal was gelesen wurde
        String[] nc = b.wiedererkennen("RA~ЦИРИ~", "Myth Gods", ZIRI_KRAFT, nb("ziri-schild.png"));
        assertEquals("-ЦИРИ-", nc[0]);
    }

    @Test
    void eintragMitClanAlsNameBekommtDenEchtenNamen() throws Exception {
        Gegnerbuch b = buch();
        for (int i = 0; i < 4; i++) b.ergebnis("V Myth Gods", "O'Myth Go s", true, ZIRI_KRAFT, 15, 140, nb("ziri-schild.png"));
        assertTrue(b.nachschlagen("V Myth Gods", "O'Myth Go s").isEmpty(), "Clan als Name zaehlt nicht");
        b.ergebnis("-ЦИРИ-", "Myth Gods", false, ZIRI_KRAFT, 30, 0, nb("ziri-schild-gross-1.png"));
        assertEquals(1, b.anzahl());
        Gegnerbuch.Notiz n = b.alle().get(0);
        assertEquals("-ЦИРИ-", n.name());
        assertEquals(5, n.kaempfe());
        assertFalse(n.nameIstClan());
    }

    @Test
    void aehnlicherNameAndereKraftBleibtGetrennt() throws Exception {
        Gegnerbuch b = buch();
        // gleicher Clan, Kraft 10 % anders: Revan und Raven sind zwei Spieler
        b.ergebnis("Revan", "The Legend", true, 30_000_000, 15, 140, nb("revan.png"));
        b.ergebnis("Raven", "The Legend", false, 33_000_000, 25, 0, nb("raven-schild-gross.png"));
        assertEquals(2, b.anzahl());
        b.ergebnis("Том", "Дружба", true, 20_000_000, 15, 140, nb("tom.png"));
        b.ergebnis("Тан", "Перезагрузка", true, 21_000_000, 15, 140, nb("tan.png"));
        assertEquals(4, b.anzahl());
        // Kampf ohne lesbaren Namen mit Ravens Bild und Ravens Kraft geht zu Raven, nicht zu Revan
        b.ergebnis("", "The Legend", true, 33_100_000, 20, 130, nb("raven-schild-gross.png"));
        assertEquals(2, b.nachschlagen("Raven", "The Legend").orElseThrow().kaempfe());
        assertEquals(1, b.nachschlagen("Revan", "The Legend").orElseThrow().kaempfe());
    }

    @Test
    void gleichesBildAndererClanUndAndereKraftBleibtGetrennt() throws Exception {
        Gegnerbuch b = buch();
        b.ergebnis("Karen", "Les Cascades", true, 30_000_000, 15, 140, nb("karen.png"));
        b.ergebnis("Karen", "Teremok", true, 40_000_000, 15, 140, nb("karen.png"));
        assertEquals(2, b.anzahl(), "gleicher Name, aber anderer Clan und andere Kraft: zwei Spieler");
    }

    @Test
    void gegenprobe() {
        assertEquals(Gegnerbuch.Gegenprobe.BESTAETIGT, Gegnerbuch.gegenprobe("Myth Gods", 35_000_000, "Myth Gods", 35_400_000));
        assertEquals(Gegnerbuch.Gegenprobe.BESTAETIGT, Gegnerbuch.gegenprobe("Myth Gods", -1, "Myth Gods", 35_400_000));
        assertEquals(Gegnerbuch.Gegenprobe.BESTAETIGT, Gegnerbuch.gegenprobe("", 35_000_000, "Myth Gods", 35_400_000));
        assertEquals(Gegnerbuch.Gegenprobe.OFFEN, Gegnerbuch.gegenprobe("Myth Gods", 35_000_000, "Myth Gods", 38_000_000));
        assertEquals(Gegnerbuch.Gegenprobe.UNBEKANNT, Gegnerbuch.gegenprobe("", -1, "", -1));
        assertEquals(Gegnerbuch.Gegenprobe.WIDERSPRUCH, Gegnerbuch.gegenprobe("Teremok", 30_000_000, "Les Cascades", 33_000_000));
        assertEquals(Gegnerbuch.Gegenprobe.WIDERSPRUCH, Gegnerbuch.gegenprobe("", 30_000_000, "Teremok", 33_000_000));
        // 1,5 % ist die Grenze
        assertEquals(Gegnerbuch.Gegenprobe.BESTAETIGT, Gegnerbuch.gegenprobe("", 30_000_000, "", 30_440_000));
        assertEquals(Gegnerbuch.Gegenprobe.WIDERSPRUCH, Gegnerbuch.gegenprobe("", 30_000_000, "", 30_500_000));
    }

    @Test
    void schriftbilderBleibenInDerDateiUndFremdesBildKommtNichtDazu() throws Exception {
        Gegnerbuch b = buch();
        b.ergebnis("-ЦИРИ-", "Myth Gods", true, ZIRI_KRAFT, 15, 144, nb("ziri-schild.png"));
        String feld = b.nachschlagen("-ЦИРИ-", "Myth Gods").orElseThrow().schriftbilder();
        assertTrue(feld.startsWith("S"), feld);
        assertTrue(feld.length() < 400, "klein: " + feld.length() + " Zeichen");
        // neu laden: Spalte 20 bleibt
        Gegnerbuch neu = buch();
        assertEquals(feld, neu.nachschlagen("-ЦИРИ-", "Myth Gods").orElseThrow().schriftbilder());
        // ein ganz anderes Bild (Texterkennung hat verwechselt) kommt nicht zu ihm
        assertEquals(feld, Gegnerbuch.schriftbilderDazu(feld, List.of(schild("karen.png"))));
        // ein weiteres Bild desselben Namens schon
        String mehr = Gegnerbuch.schriftbilderDazu(feld, List.of(schild("ziri-liste.png")));
        assertEquals(2, Gegnerbuch.schriftbilder(mehr).size());
    }

    @Test
    void altbestandWirdPerBildZusammengelegtNurWennSicher() throws Exception {
        String ziri = schild("ziri-schild.png").text(), raven = schild("raven-schild-gross.png").text();
        String kopf = "# Name\tClan\tVerlauf\tStaerke\n";
        String z1 = zeile("-UMPM-", "Myth Gods", "", ZIRI_KRAFT, ziri);
        String z2 = zeile("V Myth Gods", "Myth Gods", "SSSSNSNSNS", ZIRI_KRAFT, ziri);
        String z3 = zeile("Raven", "Myth Gods", "SN", 31_000_000, raven);
        // gleiches Bild, aber Kraft 8 % anders und anderer Clan: getrennt lassen
        String z4 = zeile("Gods", "Teremok", "N", 38_300_000, ziri);
        Files.writeString(ordner.resolve("arena-daten.txt"), kopf + z1 + z2 + z3 + z4, StandardCharsets.UTF_8);
        Gegnerbuch b = buch();
        assertEquals(3, b.anzahl());
        Gegnerbuch.Notiz n = b.nachschlagen("-UMPM-", "Myth Gods").orElseThrow();
        assertEquals(10, n.kaempfe(), "Bilanz von \"V Myth Gods\" jetzt beim echten Namen");
        assertTrue(b.nachschlagen("Raven", "Myth Gods").isPresent());
        assertTrue(b.nachschlagen("Gods", "Teremok").isPresent());
    }

    private static String zeile(String name, String clan, String verlauf, long kraft, String schriftbild) {
        String[] t = new String[20];
        java.util.Arrays.fill(t, "");
        t[0] = name;
        t[1] = clan;
        t[2] = verlauf;
        t[3] = String.valueOf(kraft);
        t[4] = "-1";
        t[5] = "2026-11-20 23:12";
        t[6] = "-1";
        t[10] = "-1";
        t[11] = "-1";
        t[19] = schriftbild;
        return String.join("\t", t) + "\n";
    }
}
