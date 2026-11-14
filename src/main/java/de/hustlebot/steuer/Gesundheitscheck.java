package de.hustlebot.steuer;

import de.hustlebot.bot.DungeonMode;
import de.hustlebot.core.Eigenbilder;
import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Autor 24.10. (Ideenliste F33): Die Gesundheits-Ampel auf der Steuerung. Typische Freund-Probleme (Windows-Skalierung, Virenschutz
 * wie Norton, fehlende eigene Bilder) tauchten bisher erst als "es passiert nichts" auf. Jetzt steht vor dem Start je Punkt
 * gruen / gelb / rot mit einem Satz und einer Erklaerung, was zu tun ist. (Der PC-Check bleibt als eigenes Programm.)
 *
 * Sonst nur LESEN: das Spielfenster wird gesucht und gemessen, aber nie nach vorn geholt und nur auf Knopfdruck in der Groesse
 * gerichtet ({@link #groesseRichten()}); die zwei langsamen Punkte (Virenschutz, Energiesparplan) fragen Windows und laufen nur auf Knopfdruck.
 *
 * Die Bewertung steckt in kleinen Funktionen, die nur Zahlen/Text bekommen -- damit sie sich pruefen lassen.
 */
final class Gesundheitscheck {

    private Gesundheitscheck() {
    }

    enum Stufe { GRUEN, GELB, ROT, GRAU }

    /** Ein Punkt der Ampel: Kennung (fuer den "Beheben"-Knopf), Stufe, Titel, kurzer Befund und die Erklaerung (html, darf null sein). */
    record Punkt(String id, Stufe stufe, String titel, String befund, String hilfe) {
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Bewertung (rein, mit Zahlen und Text)
    // ------------------------------------------------------------------------------------------------------------

    static Punkt spielfensterAus(boolean da) {
        String t = Sprache.t("Game window", "Spielfenster");
        return da ? new Punkt("fenster", Stufe.GRUEN, t, Sprache.t("Hustle Castle is open", "Hustle Castle ist offen"), null)
                : new Punkt("fenster", Stufe.GELB, t, Sprache.t("not found -- start Hustle Castle", "nicht gefunden -- starte Hustle Castle"),
                Sprache.t("The bot needs the game window. Start Hustle Castle (the normal game window, not only the launcher) before you start the bot.",
                        "Der Bot braucht das Spielfenster. Starte Hustle Castle (das normale Spielfenster, nicht nur den Launcher), bevor du den Bot startest."));
    }

    /** Das Innenmass des Spielfensters gegen 1159 x 770 (wie GameWindow.innenPruefen). */
    static Punkt fenstergroesseAus(int breite, int hoehe) {
        String t = Sprache.t("Window size", "Fenstergröße");
        boolean passt = Math.abs(breite - 1159) <= 2 && Math.abs(hoehe - 770) <= 2;
        String mass = breite + " × " + hoehe;
        if (passt) return new Punkt("groesse", Stufe.GRUEN, t, mass + Sprache.t(" -- fits the pictures", " -- passt zu den Bildern"), null);
        return new Punkt("groesse", Stufe.ROT, t, mass + Sprache.t(" instead of 1159 × 770", " statt 1159 × 770"),
                Sprache.t("The bot finds its pictures only at the size 1159 × 770 (inside the window). Set Windows scaling to 100 %, restart the game "
                                + "and let the bot set the window size itself (it does this at the start). Otherwise it will not find anything.",
                        "Der Bot findet seine Bilder nur bei der Größe 1159 × 770 (innen im Fenster). Stell die Windows-Skalierung auf 100 %, starte das Spiel "
                                + "neu und lass den Bot die Fenstergröße selbst einstellen (er macht das beim Start). Sonst findet er nichts."));
    }

    /** Windows-Skalierung aus der DPI-Zahl (96 = 100 %, 120 = 125 %, 144 = 150 %). */
    static Punkt skalierungAusDpi(int dpi) {
        String t = Sprache.t("Windows scaling", "Windows-Skalierung");
        long prozent = Math.round(dpi * 100.0 / 96);
        if (prozent == 100) return new Punkt("skalierung", Stufe.GRUEN, t, "100 %", null);
        return new Punkt("skalierung", Stufe.GELB, t, prozent + " %" + Sprache.t(" -- 100 % is best", " -- 100 % ist am besten"),
                Sprache.t("With scaling above 100 % the game window often has a different size, and the bot then does not find its pictures "
                                + "(\"it runs but nothing happens\"). Set it to 100 % (Settings > System > Display > Scale), restart the game.",
                        "Bei einer Skalierung über 100 % hat das Spielfenster oft eine andere Größe, und der Bot findet seine Bilder nicht "
                                + "(„er läuft, aber es passiert nichts“). Stell sie auf 100 % (Einstellungen > System > Anzeige > Skalierung) und starte das Spiel neu."));
    }

    private static final String GUID_SPAREN = "a1841308-3541-4fab-bc81-f71556f20b4a";

    /** Der Energiesparplan aus der Ausgabe von "powercfg /getactivescheme". */
    static Punkt energieplanAusText(String ausgabe) {
        String t = Sprache.t("Power plan", "Energiesparplan");
        if (ausgabe == null || ausgabe.isBlank()) {
            return new Punkt("energie", Stufe.GRAU, t, Sprache.t("could not be read", "ließ sich nicht lesen"), null);
        }
        Matcher name = Pattern.compile("\\(([^)]+)\\)").matcher(ausgabe);
        String plan = name.find() ? name.group(1) : "?";
        if (ausgabe.toLowerCase(Locale.ROOT).contains(GUID_SPAREN)) {
            return new Punkt("energie", Stufe.GELB, t, plan + Sprache.t(" -- slows the PC down", " -- bremst den PC"),
                    Sprache.t("The power saver plan makes the PC slower and can switch the screen off during a run. Choose \"Balanced\" or "
                                    + "\"High performance\" (Settings > System > Power).",
                            "Der Energiesparplan macht den PC langsamer und kann mitten im Lauf den Bildschirm ausschalten. Wähl „Ausbalanciert“ oder "
                                    + "„Höchstleistung“ (Einstellungen > System > Netzbetrieb)."));
        }
        return new Punkt("energie", Stufe.GRUEN, t, plan, null);
    }

    /** Virenschutz aus den Namen, die Windows meldet (leer = nichts gemeldet). */
    static Punkt virenschutzAus(List<String> namen) {
        String t = Sprache.t("Virus protection", "Virenschutz");
        if (namen == null || namen.isEmpty()) {
            return new Punkt("viren", Stufe.GRAU, t, Sprache.t("nothing reported by Windows", "Windows meldet nichts"), null);
        }
        List<String> fremde = new ArrayList<>();
        for (String n : namen) {
            String k = n.toLowerCase(Locale.ROOT);
            if (!k.contains("defender") && !k.contains("windows")) fremde.add(n);
        }
        if (fremde.isEmpty()) {
            return new Punkt("viren", Stufe.GRUEN, t, String.join(", ", namen), null);
        }
        String liste = String.join(", ", fremde);
        return new Punkt("viren", Stufe.GELB, t, liste + Sprache.t(" -- may slow the bot", " -- kann den Bot bremsen"),
                Sprache.t("Third-party virus protection (" + liste + ") scans every click and every file of the bot, which can make it slow or block it "
                                + "(\"it runs but is slow\"). Open its settings and add the bot folder as an exception (\"exclusion\"). "
                                + "Never switch the protection off completely.",
                        "Ein fremder Virenschutz (" + liste + ") prüft jeden Klick und jede Datei des Bots, das kann ihn langsam machen oder sperren "
                                + "(„er läuft, aber langsam“). Öffne seine Einstellungen und nimm den Bot-Ordner als Ausnahme auf („Ausschluss“). "
                                + "Schalte den Schutz nie ganz ab."));
    }

    /** Die eigenen Bilder: die Pflichtbilder des Verlieses und wie viele Kaempfer schon da sind. */
    static Punkt bilderAus(boolean namensschild, boolean opfer, boolean opferName, int kaempfer, int kaempferGesamt) {
        String t = Sprache.t("Your own pictures", "Deine eigenen Bilder");
        String befund = Sprache.t(kaempfer + " of " + kaempferGesamt + " fighters", kaempfer + " von " + kaempferGesamt + " Kämpfern");
        if (!namensschild || !opfer || !opferName) {
            List<String> fehlt = new ArrayList<>();
            if (!namensschild) fehlt.add(Sprache.t("your player name", "dein Spielername"));
            if (!opfer) fehlt.add(Sprache.t("sacrifice fighter: face", "Opfer-Kämpfer: Gesicht"));
            if (!opferName) fehlt.add(Sprache.t("sacrifice fighter: name", "Opfer-Kämpfer: Name"));
            return new Punkt("bilder", Stufe.ROT, t, Sprache.t("missing: ", "fehlt: ") + String.join(", ", fehlt),
                    Sprache.t("The dungeon does not start without these pictures. Open the capture program (Tools tab) -- it takes you through them step by step.",
                            "Ohne diese Bilder startet das Verlies nicht. Öffne das Aufnahme-Programm (Reiter Werkzeuge) -- es führt dich Schritt für Schritt hindurch."));
        }
        if (kaempfer == 0) {
            return new Punkt("bilder", Stufe.GELB, t, Sprache.t("no fighters yet", "noch keine Kämpfer"),
                    Sprache.t("The bot recognises your fighters by their pictures (dungeon). Capture at least your most important ones with the capture program (Tools tab).",
                            "Der Bot erkennt deine Kämpfer an ihren Bildern (Verlies). Nimm mit dem Aufnahme-Programm (Reiter Werkzeuge) wenigstens die wichtigsten auf."));
        }
        return new Punkt("bilder", Stufe.GRUEN, t, Sprache.t("name tag, sacrifice fighter, ", "Namensschild, Opfer-Kämpfer, ") + befund, null);
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Messen (liest nur)
    // ------------------------------------------------------------------------------------------------------------

    /** Der Titel des Spielfensters wie in config.properties (window.title), sonst "Hustle Castle". */
    static String fensterTitel() {
        try {
            Path p = Ordner.bei("config.properties");
            if (Files.isRegularFile(p)) {
                Properties pr = new Properties();
                try (var in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    pr.load(in);
                }
                String t = pr.getProperty("window.title", "").strip();
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ignored) {
            // dann der Standardtitel
        }
        return "Hustle Castle";
    }

    /** Die schnellen Punkte (Spielfenster, Groesse, Skalierung, Bilder) -- ohne Programme zu starten ausser reg.exe. */
    /** Autor 01.11.: Auf "Check now" wird eine falsche Fenstergroesse gerichtet statt erklaert. Geht nur bei offenem, nicht minimiertem Spiel. */
    static boolean groesseRichten() {
        try {
            GameWindow fenster = new GameWindow(fensterTitel(), 1175, 809);
            return fenster.locateVisible() && !fenster.isMinimized() && fenster.innenmassRichten();
        } catch (Throwable e) {
            Log.debug("Steuerprogramm", "Fenstergroesse nicht gerichtet: " + e);
            return false;
        }
    }

    static List<Punkt> schnell() {
        List<Punkt> l = new ArrayList<>();
        String titel = fensterTitel();
        GameWindow fenster = new GameWindow(titel, 1175, 809);
        boolean da;
        try {
            da = fenster.istDa();
        } catch (Throwable e) {
            da = false;
        }
        l.add(spielfensterAus(da));
        if (da) {
            try {
                if (fenster.locateVisible()) {
                    Rectangle r = fenster.clientArea();
                    if (r != null && !fenster.isMinimized()) l.add(fenstergroesseAus(r.width, r.height));
                }
            } catch (Throwable ignored) {
                // ohne Groesse: der Punkt fehlt einfach
            }
        }
        l.add(skalierungAusDpi(dpi()));
        l.add(bilder());
        return l;
    }

    /** Der Windows-Skalierung zugrunde liegende DPI-Wert (96 = 100 %); ohne Angabe 96. */
    static int dpi() {
        for (String[] a : new String[][]{
                {"HKCU\\Control Panel\\Desktop\\WindowMetrics", "AppliedDPI"},
                {"HKCU\\Control Panel\\Desktop", "LogPixels"}}) {
            String aus = ausfuehren(3, "reg", "query", a[0], "/v", a[1]);
            Matcher m = Pattern.compile(a[1] + "\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").matcher(aus);
            if (m.find()) {
                try {
                    int dpi = Integer.parseInt(m.group(1), 16);
                    if (dpi >= 72 && dpi <= 480) return dpi;
                } catch (NumberFormatException ignored) {
                    // naechster Versuch
                }
            }
        }
        return 96;
    }

    /** Die Bilder: gibt es die Pflichtbilder in "My pictures" oder (aelter) in templates? */
    static Punkt bilder() {
        Path bot = Ordner.basis();
        Path vorlagen = Ordner.bei("templates");
        List<String> kaempfer = DungeonMode.bilderKaempfer();
        int da = 0;
        for (String k : kaempfer) if (vorlageDa(bot, vorlagen, k)) da++;
        return bilderAus(vorlageDa(bot, vorlagen, DungeonMode.bildNamensschild()), vorlageDa(bot, vorlagen, DungeonMode.bildOpferPortraet()),
                vorlageDa(bot, vorlagen, DungeonMode.bildOpferName()), da, kaempfer.size());
    }

    /** Liegt die Vorlage in "My pictures" (dort, wo das Aufnahme-Programm sie hinlegt) oder irgendwo unter templates? */
    static boolean vorlageDa(Path bot, Path vorlagen, String name) {
        try {
            if (Files.exists(Eigenbilder.ordnerFuer(bot, name).resolve(name + ".png"))) return true;
            if (!Files.isDirectory(vorlagen)) return false;
            try (Stream<Path> s = Files.walk(vorlagen)) {
                return s.anyMatch(p -> p.getFileName().toString().equals(name + ".png"));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Langsam (Windows fragen): der Energiesparplan. */
    static Punkt energieplan() {
        return energieplanAusText(ausfuehren(5, "powercfg", "/getactivescheme"));
    }

    /** Langsam (PowerShell, rund eine Sekunde): die Virenschutz-Programme, die Windows kennt. */
    static Punkt virenschutz() {
        String aus = ausfuehren(15, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance -Namespace root/SecurityCenter2 -ClassName AntiVirusProduct | ForEach-Object { $_.displayName }");
        List<String> namen = new ArrayList<>();
        for (String z : aus.split("\\R")) {
            String n = z.strip();
            if (!n.isEmpty() && !namen.contains(n)) namen.add(n);
        }
        return virenschutzAus(namen);
    }

    /** Ein Programm mit Zeitgrenze starten und seine Ausgabe lesen; bei jedem Fehler "". */
    private static String ausfuehren(int sekunden, String... befehl) {
        try {
            Process p = new ProcessBuilder(befehl).redirectErrorStream(true).start();
            StringBuilder b = new StringBuilder();
            Charset cs = Charset.forName(System.getProperty("sun.stdout.encoding", Charset.defaultCharset().name()));
            Thread leser = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), cs))) {
                    String z;
                    while ((z = r.readLine()) != null) b.append(z).append('\n');
                } catch (Exception ignored) {
                    // abgebrochen
                }
            });
            leser.setDaemon(true);
            leser.start();
            if (!p.waitFor(sekunden, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            leser.join(500);
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
