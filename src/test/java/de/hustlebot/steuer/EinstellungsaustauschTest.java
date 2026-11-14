package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Einstellungen teilen (16.10.): Code kopieren -> bei einem Freund einfuegen, ohne persoenliche Werte. */
class EinstellungsaustauschTest {

    @TempDir
    Path tmp;

    private Path ordner(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name));
    }

    private static void schreiben(Path ordner, String datei, String inhalt) throws IOException {
        Files.writeString(ordner.resolve(datei), inhalt, StandardCharsets.UTF_8);
    }

    private static String lesen(Path ordner, String datei) throws IOException {
        return Files.readString(ordner.resolve(datei), StandardCharsets.UTF_8);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Alle Inhalte im Code, entschluesselt -- damit auch nichts Persoenliches base64-versteckt mitreist. */
    private static String allesImCode(String code) {
        StringBuilder b = new StringBuilder();
        for (String z : Einstellungsaustausch.entpacken(code).lines().toList()) {
            int tab = z.indexOf('\t');
            b.append(z).append('\n');
            if (tab > 0) b.append(new String(Base64.getUrlDecoder().decode(z.substring(tab + 1)), StandardCharsets.UTF_8)).append('\n');
        }
        return b.toString();
    }

    @Test
    void rundreiseInEinenAnderenOrdner() throws IOException {
        Path a = ordner("a");
        Path b = ordner("b");
        schreiben(a, "leuchtturm.txt", "# Leuchtturm\naus\n");
        // Code-Review 23.10.: ohne Datei ist der Kriegsplan AN -- "aus" muss beim Empfaenger ohne Datei ankommen.
        schreiben(a, "kriegsplan-an.txt", "aus\n\n# Kommentar\n");
        schreiben(a, "event-lupen.txt", "# Lupen\n7\n");
        schreiben(a, "bezahlung.txt", "dungeon=tickets\ncathedral=alles\narena=food\nlimit.dungeon=5000000\n"
                + "ohne-limit-bis=aus\nlager=1800000\n");
        schreiben(a, "profile.txt", "[Nacht]\n!settings leuchtturm an\n!settings lager 1500000\n!zeitzone Asia/Tokyo\n!mute\n");

        schreiben(b, "bezahlung.txt", "# meine Werte\ndungeon=alles\nlimit.dungeon=100\nlager=900000\n");
        schreiben(b, "profile.txt", "[Eigenes]\n!settings lager 2000000\n");

        String code = Einstellungsaustausch.exportieren(a);
        assertTrue(code.startsWith(Einstellungsaustausch.PRAEFIX));
        String inhalt = allesImCode(code);
        assertFalse(inhalt.contains("1800000"), "Lager ist persoenlich");
        assertFalse(inhalt.contains("5000000"), "Nahrungs-Limit ist persoenlich");
        assertFalse(inhalt.contains("Tokyo"), "Zeitzone im Profil ist persoenlich");
        assertFalse(inhalt.contains("1500000"), "Lager im Profil ist persoenlich");

        Einstellungsaustausch.Vorschau v = Einstellungsaustausch.lesen(code, b);
        assertEquals(0, v.ignoriert());
        // Leuchtturm, Krieg, Lupen, 3x Bezahlart, Profil "Nacht"
        assertEquals(7, v.anzahl(), v.alsText());
        assertTrue(v.neustartNoetig());

        int n = Einstellungsaustausch.anwenden(v, b);
        assertEquals(7, n);
        assertEquals("aus", lesen(b, "leuchtturm.txt").strip());
        assertEquals("aus", lesen(b, "kriegsplan-an.txt").strip());
        assertEquals("7", lesen(b, "event-lupen.txt").strip());

        String bezahlung = lesen(b, "bezahlung.txt");
        assertTrue(bezahlung.contains("dungeon=tickets"));
        assertTrue(bezahlung.contains("cathedral=alles"));
        assertTrue(bezahlung.contains("arena=food"));
        assertTrue(bezahlung.contains("# meine Werte"));
        assertTrue(bezahlung.contains("limit.dungeon=100"), "eigenes Limit bleibt");
        assertTrue(bezahlung.contains("lager=900000"), "eigenes Lager bleibt");
        assertFalse(bezahlung.contains("1800000"));
        assertFalse(bezahlung.contains("5000000"));

        String profile = lesen(b, "profile.txt");
        assertTrue(profile.contains("[Eigenes]"), "eigene Profile bleiben");
        assertTrue(profile.contains("!settings lager 2000000"), "eigene Befehle bleiben");
        assertTrue(profile.contains("[Nacht]"));
        assertTrue(profile.contains("!settings leuchtturm an"));
        assertTrue(profile.contains("!mute"));
        assertFalse(profile.contains("1500000"));
        assertFalse(profile.contains("Asia/Tokyo"));

        // Sicherung vorher, zum Rueckgaengigmachen
        Path sicherung = v.sicherung();
        assertNotNull(sicherung);
        assertTrue(Files.isDirectory(sicherung));
        assertTrue(sicherung.getFileName().toString().startsWith("einstellungen-vorher-"));
        assertEquals(b.toAbsolutePath().normalize().resolve("logs"), sicherung.getParent());
        assertTrue(Files.readString(sicherung.resolve("bezahlung.txt")).contains("dungeon=alles"));
        assertTrue(Files.readString(sicherung.resolve("LIESMICH.txt")).contains("leuchtturm.txt"), "neu angelegte Dateien stehen drin");
        assertTrue(Einstellungsaustausch.ergebnisText(v, n).contains("Restart") || Einstellungsaustausch.ergebnisText(v, n).contains("neu"));

        // Ein zweites Mal: nichts mehr zu tun
        Einstellungsaustausch.Vorschau nochmal = Einstellungsaustausch.lesen(code, b);
        assertTrue(nochmal.leer(), nochmal.alsText());
        assertEquals(0, Einstellungsaustausch.anwenden(nochmal, b));
    }

    @Test
    void persoenlicheDateienWerdenNieExportiert() throws IOException {
        Path a = ordner("a");
        Path b = ordner("b");
        schreiben(a, "leuchtturm.txt", "aus\n");
        schreiben(a, "event-pass.txt", "# Event-Pass\ngold\n");
        schreiben(a, "zeitzone.txt", "Asia/Tokyo\n");
        schreiben(a, "config.properties", "lizenz=GEHEIM-123\ndiscord.token=TOKEN-XYZ\n");
        schreiben(a, "kolosseum-grenze.txt", "7654321\n");
        schreiben(a, "dauerwerte.properties", "sprache=EN\nstats.minuten=60\n");
        schreiben(a, "steuerprogramm.properties", "steuerfenster.x=4321\n");
        schreiben(a, "titanturm-max.txt", "easy=33\n");

        String code = Einstellungsaustausch.exportieren(a);
        String alles = allesImCode(code);
        assertTrue(alles.contains("leuchtturm.txt"));
        for (String verboten : List.of("event-pass", "zeitzone", "config.properties", "kolosseum-grenze", "dauerwerte",
                "steuerprogramm", "titanturm-max", "GEHEIM", "TOKEN", "Tokyo", "7654321", "4321", "gold", "stats.minuten")) {
            assertFalse(alles.contains(verboten), verboten);
        }

        Einstellungsaustausch.Vorschau v = Einstellungsaustausch.lesen(code, b);
        assertEquals(Set.of("leuchtturm.txt"), v.dateien());
        Einstellungsaustausch.anwenden(v, b);
        for (String d : List.of("event-pass.txt", "zeitzone.txt", "config.properties", "kolosseum-grenze.txt",
                "dauerwerte.properties", "steuerprogramm.properties", "titanturm-max.txt")) {
            assertFalse(Files.exists(b.resolve(d)), d);
        }
    }

    @Test
    void manipulierterCodeMitFremdenDateinamen() throws IOException {
        Path b = ordner("b");
        String text = "HCBSET1\n"
                + "../config.properties\t" + b64("lizenz=X") + "\n"
                + "..\\leuchtturm.txt\t" + b64("aus") + "\n"
                + "logs/leuchtturm.txt\t" + b64("aus") + "\n"
                + "config.properties\t" + b64("discord.token=X") + "\n"
                + "event-pass.txt\t" + b64("kein") + "\n"
                + "C:\\Windows\\win.ini\t" + b64("x") + "\n"
                + "leuchtturm.txt\t" + b64("aus") + "\n";
        Einstellungsaustausch.Vorschau v = Einstellungsaustausch.lesen(Einstellungsaustausch.verpacken(text), b);
        assertEquals(6, v.ignoriert());
        assertEquals(Set.of("leuchtturm.txt"), v.dateien());
        assertEquals(1, Einstellungsaustausch.anwenden(v, b));
        assertEquals("aus", lesen(b, "leuchtturm.txt").strip());
        assertFalse(Files.exists(tmp.resolve("config.properties")));
        assertFalse(Files.exists(b.resolve("config.properties")));
        assertFalse(Files.exists(b.resolve("event-pass.txt")));
        assertFalse(Files.exists(b.resolve("logs").resolve("leuchtturm.txt")));

        // Falsche Werte fliegen raus
        Einstellungsaustausch.Vorschau falsch = Einstellungsaustausch.lesen(Einstellungsaustausch.verpacken("HCBSET1\n"
                + "kolosseum.txt\t" + b64("vielleicht") + "\n"
                + "event-lupen.txt\t" + b64("999") + "\n"
                + "aufstellung.properties\t" + b64("dungeon=rm -rf\n") + "\n"
                + "titanturm.txt\t" + b64("aus") + "\n"), b);
        assertEquals(3, falsch.ignoriert());
        assertEquals(Set.of("titanturm.txt"), falsch.dateien());

        // Nur Fremdes, kaputt, zu gross, falscher Anfang: abgelehnt
        String nurFremd = Einstellungsaustausch.verpacken("HCBSET1\n../config.properties\t" + b64("x") + "\n");
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen(nurFremd, b));
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen("hallo", b));
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen(null, b));
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen("HCBSET1:%%%kaputt", b));
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen("HCBSET1:" + b64("kein gzip"), b));
        String riesig = Einstellungsaustausch.verpacken("HCBSET1\n" + "a".repeat(70_000));
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen(riesig, b));
        String andererKopf = Einstellungsaustausch.verpacken("ANDERS\nleuchtturm.txt\t" + b64("aus") + "\n");
        assertThrows(IllegalArgumentException.class, () -> Einstellungsaustausch.lesen(andererKopf, b));
    }

    @Test
    void schluesselDateienBehaltenDieAnderenWerteDesEmpfaengers() throws IOException {
        Path a = ordner("a");
        Path b = ordner("b");
        schreiben(a, "tastenkuerzel.properties", "#Tastenkuerzel\nprotokoll=F7\nstatistik=Strg+Alt+S\nfremd=nicht-mitnehmen\n");
        schreiben(a, "aufstellung.properties", "dungeon = previous\narena = best\n");
        schreiben(b, "tastenkuerzel.properties", "#Meine Kuerzel\nalle=an\nprotokoll=Strg+Alt+A\nfremd=behalten\n");
        schreiben(b, "aufstellung.properties", "# Kommentar\ndungeon = best\narena = best\nunbekannt = bleibt\n");

        String code = Einstellungsaustausch.exportieren(a);
        assertFalse(allesImCode(code).contains("fremd"), "nicht erlaubte Schluessel reisen nicht mit");

        Einstellungsaustausch.Vorschau v = Einstellungsaustausch.lesen(code, b);
        // protokoll, statistik, dungeon -- arena ist schon "best"
        assertEquals(3, v.anzahl(), v.alsText());
        assertEquals(3, Einstellungsaustausch.anwenden(v, b));

        String kuerzel = lesen(b, "tastenkuerzel.properties");
        assertTrue(kuerzel.contains("protokoll=F7"));
        assertTrue(kuerzel.contains("statistik=Strg+Alt+S"));
        assertTrue(kuerzel.contains("fremd=behalten"));
        assertTrue(kuerzel.contains("alle=an"));
        assertTrue(kuerzel.contains("#Meine Kuerzel"));
        assertFalse(kuerzel.contains("Strg+Alt+A"));

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(b.resolve("aufstellung.properties"))) {
            p.load(in);
        }
        assertEquals("previous", p.getProperty("dungeon"));
        assertEquals("best", p.getProperty("arena"));
        assertEquals("bleibt", p.getProperty("unbekannt"));
        assertTrue(lesen(b, "aufstellung.properties").contains("# Kommentar"));
    }

    @Test
    void mauswacheGiltOhneNeustart() throws IOException {
        Path a = ordner("a");
        Path b = ordner("b");
        schreiben(a, "mauswache.txt", "aus\n");
        Einstellungsaustausch.Vorschau v = Einstellungsaustausch.lesen(Einstellungsaustausch.exportieren(a), b);
        assertEquals(1, v.anzahl());
        assertFalse(v.neustartNoetig());
        assertEquals(1, Einstellungsaustausch.anwenden(v, b));
        assertFalse(v.neustartNoetig());
        // Mauswache liest den ganzen Inhalt: darf nur "aus" sein, ohne Kommentar davor
        assertEquals("aus", lesen(b, "mauswache.txt").strip());
    }

    @Test
    void persoenlicheBefehleInProfilenWerdenErkannt() {
        for (String privat : List.of("!settings lager 2000000", "!settings nahrungslimit alle 30m", "!settings pass gold",
                "!zeitzone Europe/Berlin", "!team name Foo", "!english", "!settings tipp ping 123456789012345678",
                "!settings notaus F9")) {
            assertTrue(Einstellungsaustausch.privaterBefehl(privat), privat);
        }
        for (String teilbar : List.of("!settings leuchtturm an", "!settings bezahlen alle alles", "!mute",
                "!settings reports 10", "!kriegsplan an", "!settings lupen 5", "!ablauf")) {
            assertFalse(Einstellungsaustausch.privaterBefehl(teilbar), teilbar);
        }
    }
}
