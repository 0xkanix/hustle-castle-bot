package de.hustlebot.steuer;

import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Schalterdatei;
import de.hustlebot.core.Sprache;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Einstellungen mit Freunden teilen (Autor 16.10.): "Meine Einstellungen kopieren" legt einen Text-Code in die
 * Zwischenablage, der Freund fuegt ihn mit "Einstellungen einfuegen" ein, sieht, was sich aendert, und bestaetigt.
 *
 * Geteilt wird NUR, was in {@link #LISTE} steht -- Einstellungen, die fuer alle Spieler Sinn ergeben. Persoenliche
 * Werte (Nahrungslager, Nahrungs-Limit, Event-Pass, Lizenz, Discord, Sprache, Zeitzone, Kolosseum-Grenze,
 * Spielername, Team-/Arena-Daten, Bilder, Fensterlagen) stehen dort nicht und werden weder gelesen noch geschrieben.
 * Bei Dateien mit mehreren Schluesseln (bezahlung.txt, *.properties ...) nur die erlaubten Schluessel; alle anderen
 * Zeilen des Empfaengers bleiben unberuehrt.
 *
 * Code = "HCBSET1:" + base64url(gzip(UTF-8-Text)); der Text ist "HCBSET1", dann je Zeile "datei TAB base64url(inhalt)".
 */
public final class Einstellungsaustausch {

    /** Jeder Code beginnt so -- daran erkennt das Einfuegen-Fenster ihn auch in der Zwischenablage. */
    public static final String PRAEFIX = "HCBSET1:";
    private static final String KOPF = "HCBSET1";
    /** Hoechstens so gross darf der entpackte Code sein (und der gepackte). */
    static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_CODE_ZEICHEN = 200_000;
    private static final int MAX_ZEILE = 300;
    private static final int MAX_PROFILE = 30;
    private static final int MAX_PROFILZEILEN = 60;
    private static final String QUELLE = "Einstellungen";
    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Einstellungsaustausch() {
    }

    // ------------------------------------------------------------------
    //  Die feste Liste: nur diese Dateien (und Schluessel) werden je gelesen oder geschrieben
    // ------------------------------------------------------------------

    enum Art { SCHALTER, ZAHL, WORT, SCHLUESSEL, PROFIL, TEXT }

    /**
     * @param erlaubt   SCHLUESSEL: erlaubte Schluessel; WORT: erlaubte Woerter
     * @param muster    SCHLUESSEL: erlaubte Werte; TEXT: erlaubte Zeilen (regulaerer Ausdruck)
     * @param standard  Wert, wenn die Datei fehlt (so liest ihn der Bot dann auch)
     * @param neustart  true = der Bot liest die Datei nur beim Start (static-Feld ohne neu-Lesen)
     */
    record Eintrag(String datei, Art art, String en, String de, List<String> erlaubt, String muster,
                   String standard, int min, int max, boolean neustart) {
        String titel() {
            return Sprache.t(en, de);
        }
    }

    private static Eintrag schalter(String datei, String en, String de, boolean standardAn, boolean neustart) {
        return new Eintrag(datei, Art.SCHALTER, en, de, List.of(), null, standardAn ? "an" : "aus", 0, 0, neustart);
    }

    private static Eintrag zahl(String datei, String en, String de, int min, int max, int standard) {
        return new Eintrag(datei, Art.ZAHL, en, de, List.of(), null, String.valueOf(standard), min, max, true);
    }

    private static Eintrag wort(String datei, String en, String de, String... woerter) {
        return new Eintrag(datei, Art.WORT, en, de, List.of(woerter), null, null, 0, 0, true);
    }

    private static Eintrag schluessel(String datei, String en, String de, String muster, boolean neustart, String... schluessel) {
        return new Eintrag(datei, Art.SCHLUESSEL, en, de, List.of(schluessel), muster, null, 0, 0, neustart);
    }

    /** "alle" und je Kuerzel die Taste und das ".an" -- genau wie Tastenkuerzel sie speichert. */
    private static String[] tastenSchluessel() {
        List<String> s = new ArrayList<>();
        s.add("alle");
        for (Tastenkuerzel.Aktion a : Tastenkuerzel.Aktion.values()) {
            s.add(a.schluessel());
            s.add(a.schluessel() + ".an");
        }
        return s.toArray(new String[0]);
    }

    private static final List<Eintrag> LISTE = List.of(
            // Schalter an/aus (Schalterdatei, Kriegsplan, Mauswache lesen alle die erste Zeile "an"/"aus")
            schalter("leuchtturm.txt", "Lighthouse", "Leuchtturm", true, true),
            schalter("kolosseum.txt", "Colosseum", "Kolosseum", true, true),
            schalter("titanturm.txt", "Titan tower", "Titanen-Turm", true, true),
            // Code-Review 23.10.: seit 18.10. ist die Automatik ohne Datei AN (Kriegsplan) -- sonst galt "aus" als "keine Aenderung".
            schalter("kriegsplan-an.txt", "Clan war: deploy by itself", "Clan-Krieg selbst aufstellen", true, true),
            schalter("clanladen.txt", "Clan store: buy food chests", "Clan-Laden: Nahrungskisten kaufen", false, true),
            schalter("event-automatik.txt", "Event automation", "Event-Automatik", false, true),
            schalter("mauswache.txt", "Stop when the mouse moves", "Stopp bei Mausbewegung", true, false),   // liest jedes Mal neu
            schalter("arena-beobachter.txt", "Arena watcher", "Arena-Beobachter", true, false),
            schalter("konten.txt", "Several game accounts", "Mehrere Spielkonten", false, false),
            zahl("event-lupen.txt", "Magnifiers per field", "Lupen je Feld", 0, 10, 5),
            // Code-Review 24.10.: die neuen Event-Abstaende mit austauschen
            zahl("event-hammer-alle.txt", "Hammer event every N cathedrals", "Hammer-Event alle N Kathedralen", 0, 500, 100),
            zahl("event-titan-alle.txt", "Titan event every N cathedrals", "Titan-Event alle N Kathedralen", 0, 500, 100),
            zahl("event-stages-je-gang.txt", "Hammer event: at most N stages per visit", "Hammer-Event: hoechstens N Stages je Gang", 0, 500, 0),
            zahl("event-automatik-verlies.txt", "Event automation: dungeon runs in an emergency",
                    "Event-Automatik: Verlies-Runs im Notfall", 0, 500, 0),
            wort("event-art.txt", "Event automation: which event", "Event-Automatik: welches Event", "titan", "hammer"),
            // Nur die Bezahlart je Modus -- NICHT limit.*, ohne-limit-bis, lager (persoenlich)
            schluessel("bezahlung.txt", "Use food", "Nahrung benutzen", "(?i)[a-z+]{1,20}", true,
                    "dungeon", "cathedral", "arena"),
            // Aufstellung.konfigurieren liest neu -- kein Neustart, wenn es der Bot-Ordner ist
            schluessel("aufstellung.properties", "Squad", "Truppenaufstellung", "(?i)best|previous", false,
                    "dungeon", "cathedral", "arena", "clanwar_tower", "clanwar_outpost"),
            schluessel("event-kisten.txt", "Event chests", "Kisten im Event", "(?i)an|aus|on|off|ja|nein", true,
                    "holz", "eisen", "gold", "mithril", "mana", "nahrung", "fackeln"),
            schluessel("stigma-einstellungen.txt", "Stigma rules", "Stigma-Regeln", "[A-Za-z0-9, ]{0,300}", true,
                    "namen", "level", "haupt", "zweit", "behalten", "schnell", "rangliste"),
            schluessel("tastenkuerzel.properties", "Hotkey", "Tastenkürzel", "[A-Za-z0-9+ .\\-]{0,40}", true,
                    tastenSchluessel()),
            new Eintrag("profile.txt", Art.PROFIL, "Profile", "Profil", List.of(), null, null, 0, 0, true),
            new Eintrag("stigma-rangliste.txt", Art.TEXT, "Stigma ranking", "Stigma-Rangliste", List.of(),
                    "[^\\p{Cntrl}]{1,120}", null, 0, 40, true));   // Stigmaplan prueft die Zeilen selbst (hoechstens 40)

    private static final Map<String, Eintrag> EINTRAEGE = new LinkedHashMap<>();

    static {
        for (Eintrag e : LISTE) EINTRAEGE.put(e.datei(), e);
    }

    private static final Map<String, String[]> SCHLUESSEL_NAMEN = Map.ofEntries(
            Map.entry("dungeon", new String[]{"dungeon", "Verlies"}),
            Map.entry("cathedral", new String[]{"cathedral", "Kathedrale"}),
            Map.entry("arena", new String[]{"arena", "Arena"}),
            Map.entry("clanwar_tower", new String[]{"clan war tower", "Clan-Krieg Turm"}),
            Map.entry("clanwar_outpost", new String[]{"clan war outpost", "Clan-Krieg Außenposten"}),
            Map.entry("holz", new String[]{"wood", "Holz"}),
            Map.entry("eisen", new String[]{"iron", "Eisen"}),
            Map.entry("gold", new String[]{"gold", "Gold"}),
            Map.entry("mithril", new String[]{"mithril", "Mithril"}),
            Map.entry("mana", new String[]{"mana", "Mana"}),
            Map.entry("nahrung", new String[]{"food", "Nahrung"}),
            Map.entry("fackeln", new String[]{"torches", "Fackeln"}),
            Map.entry("namen", new String[]{"names", "Namen"}),
            Map.entry("level", new String[]{"levels", "Stufen"}),
            Map.entry("haupt", new String[]{"main stat", "Hauptwert"}),
            Map.entry("zweit", new String[]{"second stat", "Zweitwert"}),
            Map.entry("behalten", new String[]{"keep sets", "Sets behalten"}),
            Map.entry("schnell", new String[]{"fast", "schnell"}),
            Map.entry("rangliste", new String[]{"ranking", "Rangliste"}),
            Map.entry("alle", new String[]{"all hotkeys", "alle Kürzel"}));

    // Befehle in Profilen, die persoenliche Werte setzen -- fliegen beim Teilen raus (in beide Richtungen).
    private static final Set<String> PRIVATE_BEFEHLE = Set.of(
            "!zeitzone", "!timezone", "!english", "!englisch", "!en", "!deutsch", "!german", "!de",
            "!team", "!teamscan", "!autostart",
            "!notaus", "!not-aus", "!kill", "!emergency", "!shortcut", "!shortcuts", "!kuerzel",
            "!arenareserve", "!notizen", "!notizblock", "!notes", "!notiz", "!note", "!liste", "!list",
            "!off", "!aus", "!feierabend", "!pc", "!rechner", "!text", "!nurtext", "!plain");
    private static final Set<String> PRIVATE_SETTINGS = Set.of(
            "lager", "storage", "nahrungslimit", "foodlimit", "food-limit", "nahrungs-limit", "pass",
            "nahrungskisten", "foodchests", "kistenlimit", "zauberstaebe", "zauberstab", "staebe", "wands", "wand",
            "notaus", "not-aus", "panik", "emergency", "emergencykey", "panic",
            "tipp", "tip", "tipps", "tagestipp", "dailytip");
    private static final List<String> PRIVATE_WOERTER = List.of(
            "discord", "token", "lizenz", "licen", "http", "webhook", "kanal", "channel");

    // ------------------------------------------------------------------
    //  Oeffentliche Schnittstelle
    // ------------------------------------------------------------------

    /** Eine Aenderung fuer die Vorschau: Titel, alter und neuer Wert (zum Anzeigen). */
    public record Aenderung(String titel, String alt, String neu) {
    }

    /** Ergebnis von {@link #lesen}: was der Code enthaelt und was sich im Ordner aendern wuerde. */
    public static final class Vorschau {
        private final Map<String, String> neu;
        private final List<Aenderung> aenderungen;
        private final int ignoriert;
        private boolean neustart;
        private Path sicherung;
        private final List<String> fehler = new ArrayList<>();

        private Vorschau(Map<String, String> neu, List<Aenderung> aenderungen, int ignoriert, boolean neustart) {
            this.neu = neu;
            this.aenderungen = aenderungen;
            this.ignoriert = ignoriert;
            this.neustart = neustart;
        }

        public List<Aenderung> aenderungen() {
            return Collections.unmodifiableList(aenderungen);
        }

        public int anzahl() {
            return aenderungen.size();
        }

        public boolean leer() {
            return aenderungen.isEmpty();
        }

        /** So viele Eintraege im Code wurden uebergangen (fremde Datei, falscher Wert, kaputt). */
        public int ignoriert() {
            return ignoriert;
        }

        /** Muss der Bot neu starten, damit alles gilt? Nach {@link #anwenden} der tatsaechliche Stand. */
        public boolean neustartNoetig() {
            return neustart;
        }

        /** Ordner mit der Sicherung (erst nach {@link #anwenden}, sonst null). */
        public Path sicherung() {
            return sicherung;
        }

        /** Dateien, die sich beim Anwenden nicht schreiben liessen. */
        public List<String> fehler() {
            return List.copyOf(fehler);
        }

        /** Die Dateien, die der Code (gueltig) enthaelt. */
        Set<String> dateien() {
            return Collections.unmodifiableSet(neu.keySet());
        }

        /** Text fuer das Einfuegen-Fenster. */
        public String alsText() {
            StringBuilder b = new StringBuilder();
            if (aenderungen.isEmpty()) {
                b.append(Sprache.t("Nothing to change – your settings are already the same as in the code.",
                        "Nichts zu ändern – deine Einstellungen sind schon so wie im Code."));
            } else {
                b.append(Sprache.t("These settings will change (" + aenderungen.size() + "):",
                        "Diese Einstellungen ändern sich (" + aenderungen.size() + "):")).append("\n\n");
                for (Aenderung a : aenderungen) {
                    b.append("• ").append(a.titel()).append(":  ").append(a.alt()).append("  →  ").append(a.neu()).append('\n');
                }
            }
            if (ignoriert > 0) {
                b.append('\n').append(Sprache.t(ignoriert + " entries in the code were skipped (not allowed or damaged).",
                        ignoriert + " Einträge im Code wurden übersprungen (nicht erlaubt oder kaputt).")).append('\n');
            }
            b.append('\n').append(Sprache.t(
                    "Personal values (food storage, food limit, pass, licence, Discord, time zone, player name ...) stay as they are.",
                    "Persönliche Werte (Nahrungslager, Nahrungs-Limit, Pass, Lizenz, Discord, Zeitzone, Spielername …) bleiben, wie sie sind."));
            if (!aenderungen.isEmpty()) {
                b.append('\n').append(Sprache.t("A backup of your current settings is saved in the logs folder first.",
                        "Vorher wird eine Sicherung deiner jetzigen Einstellungen im Ordner logs abgelegt."));
                if (neustart) {
                    b.append('\n').append(Sprache.t("After applying: restart the bot so all settings take effect.",
                            "Danach: Bot neu starten, damit alle Einstellungen gelten."));
                }
            }
            return b.toString();
        }
    }

    /** Sammelt die teilbaren Einstellungen aus dem Ordner (fehlende Dateien werden uebersprungen). */
    public static String exportieren(Path ordner) {
        return export(ordner).code();
    }

    private record Export(String code, int anzahl) {
    }

    private static Export export(Path ordner) {
        Objects.requireNonNull(ordner, "ordner");
        StringBuilder text = new StringBuilder(KOPF).append('\n');
        int anzahl = 0;
        for (Eintrag e : LISTE) {
            String roh = rohLesen(ordner, e.datei());
            if (roh == null) continue;
            String k = kanon(e, roh);
            if (k == null || k.isEmpty()) continue;
            String zeile = e.datei() + '\t' + B64.encodeToString(k.getBytes(StandardCharsets.UTF_8)) + '\n';
            if (text.length() + zeile.length() > MAX_BYTES - 1024) {
                Log.warn(QUELLE, Sprache.t(e.datei() + " is too big to share -- left out.",
                        e.datei() + " ist zu gross zum Teilen -- weggelassen."));
                continue;
            }
            text.append(zeile);
            anzahl++;
        }
        Log.info(QUELLE, Sprache.t(anzahl + " settings files packed for sharing.",
                anzahl + " Einstellungsdateien zum Teilen eingepackt."));
        return new Export(verpacken(text.toString()), anzahl);
    }

    /**
     * Prueft einen Code und vergleicht ihn mit den Dateien im Ordner. Fremde Dateinamen, Pfade und falsche Werte
     * werden uebergangen. Kaputter Code: IllegalArgumentException mit verstaendlicher Meldung.
     */
    public static Vorschau lesen(String code, Path ordner) {
        Objects.requireNonNull(ordner, "ordner");
        String text = entpacken(code);
        List<String> zeilen = text.lines().toList();
        if (zeilen.isEmpty() || !zeilen.get(0).strip().equals(KOPF)) throw kaputt();

        Map<String, String> neu = new LinkedHashMap<>();
        int ignoriert = 0;
        for (int i = 1; i < zeilen.size(); i++) {
            String z = zeilen.get(i).strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            int tab = z.indexOf('\t');
            String name = tab > 0 ? z.substring(0, tab).strip() : z;
            Eintrag e = tab > 0 && sichererName(name) ? EINTRAEGE.get(name) : null;
            String k = null;
            if (e != null && !neu.containsKey(name)) {
                try {
                    k = kanon(e, new String(B64D.decode(z.substring(tab + 1).strip()), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException ex) {
                    k = null;
                }
            }
            if (k == null || k.isEmpty()) {
                ignoriert++;
                Log.warn(QUELLE, Sprache.t("Entry in the code skipped: ", "Eintrag im Code uebergangen: ")
                        + kurz(name.replaceAll("[^A-Za-z0-9._:/\\\\-]", "?"), 60));
                continue;
            }
            neu.put(name, k);
        }
        if (neu.isEmpty()) {
            throw new IllegalArgumentException(Sprache.t("The code contains no settings that can be shared.",
                    "Der Code enthält keine Einstellungen, die man teilen kann."));
        }

        List<Aenderung> aenderungen = new ArrayList<>();
        boolean neustart = false;
        for (Map.Entry<String, String> en : neu.entrySet()) {
            Eintrag e = EINTRAEGE.get(en.getKey());
            List<Aenderung> a = vergleichen(e, en.getValue(), ordner);
            aenderungen.addAll(a);
            if (!a.isEmpty() && e.neustart()) neustart = true;
        }
        return new Vorschau(neu, aenderungen, ignoriert, neustart);
    }

    /**
     * Schreibt die Einstellungen der Vorschau in den Ordner. Vorher kommt eine Kopie der betroffenen Dateien nach
     * logs/einstellungen-vorher-JJJJMMTT-HHMMSS/ (zum Rueckgaengigmachen). Liefert die Zahl der geaenderten
     * Einstellungen. Schlaegt die Sicherung fehl, wird nichts geschrieben (UncheckedIOException).
     */
    public static int anwenden(Vorschau v, Path ordner) {
        Objects.requireNonNull(v, "vorschau");
        Objects.requireNonNull(ordner, "ordner");
        v.fehler.clear();
        v.sicherung = null;
        v.neustart = false;

        // Gegen den JETZIGEN Stand vergleichen -- seit der Vorschau kann sich etwas geaendert haben.
        Map<Eintrag, List<Aenderung>> plan = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : v.neu.entrySet()) {
            Eintrag e = EINTRAEGE.get(en.getKey());
            if (e == null || ziel(ordner, e.datei()) == null) continue;
            List<Aenderung> a = vergleichen(e, en.getValue(), ordner);
            if (!a.isEmpty()) plan.put(e, a);
        }
        if (plan.isEmpty()) {
            Log.info(QUELLE, Sprache.t("Settings from the code: nothing to change.", "Einstellungen aus dem Code: nichts zu aendern."));
            return 0;
        }

        try {
            v.sicherung = sichern(ordner, plan.keySet());
        } catch (IOException ex) {
            Log.warn(QUELLE, Sprache.t("Backup failed, nothing changed: ", "Sicherung fehlgeschlagen, nichts geaendert: ") + ex.getMessage());
            throw new UncheckedIOException(Sprache.t("The backup did not work – nothing was changed: ",
                    "Die Sicherung hat nicht geklappt – nichts wurde geändert: ") + ex.getMessage(), ex);
        }

        int anzahl = 0;
        boolean aufstellung = false;
        for (Map.Entry<Eintrag, List<Aenderung>> en : plan.entrySet()) {
            Eintrag e = en.getKey();
            Path ziel = ziel(ordner, e.datei());
            try {
                schreiben(ziel, zusammenfuehren(e, v.neu.get(e.datei()), ziel));
            } catch (IOException | RuntimeException ex) {
                v.fehler.add(e.datei());
                Log.warn(QUELLE, Sprache.t(e.datei() + " could not be written: ", e.datei() + " liess sich nicht schreiben: ") + ex.getMessage());
                continue;
            }
            anzahl += en.getValue().size();
            if (e.neustart()) v.neustart = true;
            if (e.datei().equals("aufstellung.properties")) aufstellung = true;
            for (Aenderung a : en.getValue()) {
                Log.info(QUELLE, Sprache.t("From a friend's code: ", "Aus dem Code eines Freundes: ")
                        + a.titel() + ": " + a.alt() + " -> " + a.neu());
            }
        }
        // Die Truppenaufstellung laesst sich ohne Neustart neu lesen -- aber nur, wenn es der Ordner des Bots ist.
        if (aufstellung && !aufstellungNeuLaden(ordner)) v.neustart = true;

        Log.info(QUELLE, Sprache.t(anzahl + " settings taken over, backup in " + v.sicherung,
                anzahl + " Einstellungen uebernommen, Sicherung in " + v.sicherung)
                + (v.neustart ? Sprache.t(" -- restart needed.", " -- Neustart noetig.") : ""));
        return anzahl;
    }

    /** Text fuer die Meldung nach dem Anwenden. */
    public static String ergebnisText(Vorschau v, int anzahl) {
        if (anzahl == 0 && v.fehler().isEmpty()) {
            return Sprache.t("Nothing changed – your settings were already the same.",
                    "Nichts geändert – deine Einstellungen waren schon gleich.");
        }
        StringBuilder b = new StringBuilder(Sprache.t(anzahl + (anzahl == 1 ? " setting" : " settings") + " changed.",
                anzahl + (anzahl == 1 ? " Einstellung" : " Einstellungen") + " geändert."));
        if (v.sicherung() != null) {
            b.append('\n').append(Sprache.t("Backup of the old settings: ", "Sicherung der alten Einstellungen: ")).append(v.sicherung());
        }
        if (!v.fehler().isEmpty()) {
            b.append('\n').append(Sprache.t("Could not be written: ", "Ließ sich nicht schreiben: ")).append(String.join(", ", v.fehler()));
        }
        if (v.neustartNoetig()) {
            b.append("\n\n").append(Sprache.t("Restart the bot so all settings take effect.",
                    "Starte den Bot neu, damit alle Einstellungen gelten."));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------
    //  Fenster
    // ------------------------------------------------------------------

    /** "Meine Einstellungen kopieren": Code in die Zwischenablage und kurz Bescheid geben. */
    public static void kopierenDialog(Component owner, Path ordner) {
        String titel = Sprache.t("Copy my settings", "Meine Einstellungen kopieren");
        Export ex;
        try {
            ex = export(ordner);
        } catch (RuntimeException e) {
            Log.warn(QUELLE, Sprache.t("Copying failed: ", "Kopieren fehlgeschlagen: ") + e.getMessage());
            meldung(owner, titel, Sprache.t("Copying did not work: ", "Kopieren hat nicht geklappt: ") + e.getMessage(),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (ex.anzahl() == 0) {
            meldung(owner, titel, Sprache.t("There is nothing to share yet – no settings were found.",
                    "Es gibt noch nichts zu teilen – keine Einstellungen gefunden."), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ex.code()), null);
        } catch (RuntimeException e) {
            // Zwischenablage belegt: den Code zum Abschreiben zeigen.
            JTextArea feld = new JTextArea(ex.code(), 6, 48);
            feld.setLineWrap(true);
            feld.setEditable(false);
            feld.selectAll();
            meldung(owner, titel, new Object[]{html(Sprache.t("The clipboard is busy. Copy this text by hand (Ctrl+A, Ctrl+C):",
                    "Die Zwischenablage ist belegt. Kopiere den Text von Hand (Strg+A, Strg+C):")), new JScrollPane(feld)},
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        meldung(owner, titel, Sprache.t("Copied! Send this text to your friend. Personal values (food storage, pass, licence, "
                        + "Discord, time zone ...) are not included.",
                "Kopiert! Schick diesen Text deinem Freund. Persönliche Werte (Nahrungslager, Pass, Lizenz, Discord, "
                        + "Zeitzone …) sind nicht dabei."), JOptionPane.INFORMATION_MESSAGE);
    }

    /** "Einstellungen einfuegen": Code einfuegen, pruefen, Aenderungen ansehen, uebernehmen. */
    /**
     * Startet das ganze Programm neu (nur im fertigen Paket): eine kleine .bat wartet 4 Sekunden, bis dieses
     * Programm zu ist, und startet HustleBot.exe wieder. Liefert false, wenn das nicht geht.
     */
    /** Vom Steuerprogramm gesetzt: Motor anhalten und das Programm beenden. Ohne: nur beenden. */
    public static volatile Runnable beenden;

    static boolean programmNeuStarten() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null || exe.isBlank()) return false;
        try {
            // Code-Pruefung 16.10.: die .bat lief mit Arbeitsordner logs (der Bot fand danach seine Einstellungen
            // nicht), scheiterte an Umlauten im Pfad (Codepage) und "timeout" wartete ohne Tastatur nicht.
            // Jetzt PowerShell, Pfade nur ueber Umgebungsvariablen, Arbeitsordner = Bot-Ordner.
            Path botOrdner = de.hustlebot.core.Ordner.basis();
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-Command", "Start-Sleep -Seconds 5; Start-Process -FilePath $env:HB_EXE -WorkingDirectory $env:HB_ORDNER");
            pb.environment().put("HB_EXE", exe);
            pb.environment().put("HB_ORDNER", botOrdner.toString());
            pb.directory(botOrdner.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            Log.info(QUELLE, Sprache.t("Restarting the bot so the new settings take effect.",
                    "Bot startet neu, damit die neuen Einstellungen gelten."));
            Runnable b = beenden;
            if (b != null) b.run();
            else System.exit(0);
            return true;
        } catch (Exception e) {
            Log.warn(QUELLE, Sprache.t("Restart did not work: ", "Neustart ging nicht: ") + e.getMessage());
            return false;
        }
    }

    public static void einfuegenDialog(Component owner, Path ordner) {
        String titel = Sprache.t("Paste settings", "Einstellungen einfügen");
        JDialog d = new JDialog(fenster(owner), titel, Dialog.ModalityType.APPLICATION_MODAL);
        d.setAlwaysOnTop(true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JTextArea eingabe = new JTextArea(4, 48);
        eingabe.setLineWrap(true);
        String clip = zwischenablage();
        if (clip != null && clip.strip().startsWith(PRAEFIX)) eingabe.setText(clip.strip());

        JTextArea liste = new JTextArea(12, 48);
        liste.setEditable(false);
        liste.setLineWrap(true);
        liste.setWrapStyleWord(true);
        liste.setText(Sprache.t("Paste the text your friend sent you above, then click \"Check\".",
                "Füge oben den Text ein, den dein Freund geschickt hat, und klicke dann auf „Prüfen“."));

        JButton knopfPruefen = new JButton(Sprache.t("Check", "Prüfen"));
        JButton knopfAnwenden = new JButton(Sprache.t("Apply", "Übernehmen"));
        JButton knopfAbbrechen = new JButton(Sprache.t("Cancel", "Abbrechen"));
        knopfAnwenden.setEnabled(false);
        Vorschau[] stand = new Vorschau[1];

        knopfPruefen.addActionListener(e -> {
            stand[0] = null;
            knopfAnwenden.setEnabled(false);
            try {
                Vorschau v = lesen(eingabe.getText(), ordner);
                stand[0] = v;
                liste.setText(v.alsText());
                knopfAnwenden.setEnabled(!v.leer());
            } catch (IllegalArgumentException ex) {
                liste.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                Log.warn(QUELLE, Sprache.t("Checking the code failed: ", "Code pruefen fehlgeschlagen: ") + ex);
                liste.setText(Sprache.t("Checking did not work: ", "Prüfen hat nicht geklappt: ") + ex.getMessage());
            }
            liste.setCaretPosition(0);
        });
        // Neuer Text = alte Vorschau gilt nicht mehr.
        eingabe.getDocument().addDocumentListener(new DocumentListener() {
            private void geaendert() {
                stand[0] = null;
                knopfAnwenden.setEnabled(false);
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                geaendert();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                geaendert();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                geaendert();
            }
        });
        knopfAnwenden.addActionListener(e -> {
            Vorschau v = stand[0];
            if (v == null) return;
            int n;
            try {
                n = anwenden(v, ordner);
            } catch (RuntimeException ex) {
                Log.warn(QUELLE, Sprache.t("Applying failed: ", "Uebernehmen fehlgeschlagen: ") + ex);
                meldung(d, titel, Sprache.t("Applying did not work: ", "Übernehmen hat nicht geklappt: ") + ex.getMessage(),
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            d.dispose();
            meldung(owner, titel, ergebnisText(v, n),
                    v.fehler().isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            // Das meiste liest der Bot nur beim Start -- und schreibt beim naechsten Aendern seine alten Werte zurueck.
            // Darum gleich anbieten, das Programm neu zu starten (nur im fertigen Paket moeglich).
            if (n > 0 && System.getProperty("jpackage.app-path") != null) {
                int w = JOptionPane.showConfirmDialog(fenster(owner),
                        Sprache.t("Restart the bot now so all settings take effect?",
                                "Bot jetzt neu starten, damit alle Einstellungen gelten?"), titel, JOptionPane.YES_NO_OPTION);
                if (w == JOptionPane.YES_OPTION) programmNeuStarten();
            }
        });
        knopfAbbrechen.addActionListener(e -> d.dispose());

        JPanel oben = new JPanel(new BorderLayout(0, 4));
        oben.add(new JLabel(Sprache.t("Code from your friend:", "Code von deinem Freund:")), BorderLayout.NORTH);
        oben.add(new JScrollPane(eingabe), BorderLayout.CENTER);
        JPanel mitte = new JPanel(new BorderLayout(0, 4));
        mitte.add(new JLabel(Sprache.t("What will change:", "Was sich ändert:")), BorderLayout.NORTH);
        mitte.add(new JScrollPane(liste), BorderLayout.CENTER);
        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        knoepfe.add(knopfPruefen);
        knoepfe.add(knopfAnwenden);
        knoepfe.add(knopfAbbrechen);

        JPanel inhalt = new JPanel(new BorderLayout(0, 8));
        inhalt.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        inhalt.add(oben, BorderLayout.NORTH);
        inhalt.add(mitte, BorderLayout.CENTER);
        inhalt.add(knoepfe, BorderLayout.SOUTH);
        d.setContentPane(inhalt);
        d.getRootPane().setDefaultButton(knopfPruefen);
        d.pack();
        d.setLocationRelativeTo(owner);
        if (!eingabe.getText().isEmpty()) knopfPruefen.doClick();   // Code aus der Zwischenablage gleich pruefen
        d.setVisible(true);
    }

    private static Window fenster(Component owner) {
        if (owner == null) return null;
        if (owner instanceof Window w) return w;
        return SwingUtilities.getWindowAncestor(owner);
    }

    private static void meldung(Component owner, String titel, Object inhalt, int typ) {
        JOptionPane p = new JOptionPane(inhalt instanceof String s ? html(s) : inhalt, typ);
        JDialog d = p.createDialog(fenster(owner), titel);
        d.setAlwaysOnTop(true);
        d.setVisible(true);
        d.dispose();
    }

    private static JLabel html(String s) {
        String t = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return new JLabel("<html><div style='width:360px'>" + t + "</div></html>");
    }

    private static String zwischenablage() {
        try {
            Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!c.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            Object o = c.getData(DataFlavor.stringFlavor);
            return o instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Packen und Entpacken
    // ------------------------------------------------------------------

    static String verpacken(String text) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return PRAEFIX + B64.encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Code -> Klartext. Prueft Anfang und Groesse (hoechstens 64 KB, auch entpackt). */
    static String entpacken(String code) {
        String c = code == null ? "" : code.replaceAll("\\s+", "");
        if (!c.startsWith(PRAEFIX)) {
            throw new IllegalArgumentException(Sprache.t(
                    "This is not a settings code. It must start with " + PRAEFIX + " – copy the whole text your friend sent.",
                    "Das ist kein Einstellungs-Code. Er muss mit " + PRAEFIX + " anfangen – kopiere den ganzen Text, "
                            + "den dein Freund geschickt hat."));
        }
        String b64 = c.substring(PRAEFIX.length()).replace('+', '-').replace('/', '_');
        if (b64.isEmpty() || b64.length() > MAX_CODE_ZEICHEN) throw kaputt();
        byte[] gz;
        try {
            gz = B64D.decode(b64);
        } catch (IllegalArgumentException e) {
            throw kaputt();
        }
        if (gz.length > MAX_BYTES) throw zuGross();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            byte[] puffer = new byte[8192];
            int n;
            while ((n = in.read(puffer)) != -1) {
                out.write(puffer, 0, n);
                if (out.size() > MAX_BYTES) throw zuGross();
            }
        } catch (IOException e) {
            throw kaputt();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException kaputt() {
        return new IllegalArgumentException(Sprache.t(
                "The code is damaged or incomplete. Ask your friend to copy it again and send the whole text.",
                "Der Code ist kaputt oder unvollständig. Bitte deinen Freund, ihn neu zu kopieren und den ganzen Text zu schicken."));
    }

    private static IllegalArgumentException zuGross() {
        return new IllegalArgumentException(Sprache.t("The code is too big (more than 64 KB).",
                "Der Code ist zu groß (mehr als 64 KB)."));
    }

    // ------------------------------------------------------------------
    //  Dateien und Inhalte
    // ------------------------------------------------------------------

    /** Nur Namen aus der festen Liste: keine Pfade, kein "..", keine Unterordner. */
    static boolean sichererName(String datei) {
        return datei != null && datei.matches("[a-z0-9][a-z0-9\\-]*\\.(txt|properties)")
                && !datei.contains("..") && EINTRAEGE.containsKey(datei);
    }

    /** Die Datei im Ordner -- oder null, wenn der Name nicht erlaubt ist oder aus dem Ordner herausfuehrt. */
    private static Path ziel(Path ordner, String datei) {
        if (!sichererName(datei)) return null;
        Path basis = ordner.toAbsolutePath().normalize();
        Path p = basis.resolve(datei).normalize();
        return basis.equals(p.getParent()) ? p : null;
    }

    private static String rohLesen(Path ordner, String datei) {
        Path p = ziel(ordner, datei);
        return p == null ? null : rohLesen(p);
    }

    /** Liest eine Datei als Text (UTF-8, sonst Windows-1252 wie bei Profile); null, wenn es sie nicht gibt. */
    private static String rohLesen(Path p) {
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.size(p) > MAX_BYTES) {
                Log.warn(QUELLE, Sprache.t(p.getFileName() + " is too big -- skipped.", p.getFileName() + " ist zu gross -- uebergangen."));
                return null;
            }
            byte[] b = Files.readAllBytes(p);
            String s;
            try {
                s = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(b)).toString();
            } catch (CharacterCodingException e) {
                s = new String(b, ANSI);
            }
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (IOException | RuntimeException e) {
            Log.warn(QUELLE, Sprache.t(p.getFileName() + " could not be read: ", p.getFileName() + " liess sich nicht lesen: ") + e.getMessage());
            return null;
        }
    }

    /** Bringt Datei- oder Code-Inhalt in eine feste, gepruefte Form; null = nichts Gueltiges drin. */
    static String kanon(Eintrag e, String roh) {
        if (roh == null) return null;
        return switch (e.art()) {
            case SCHALTER -> schalterKanon(roh);
            case ZAHL -> zahlKanon(e, roh);
            case WORT -> wortKanon(e, roh);
            case SCHLUESSEL -> {
                Map<String, String> w = werte(e, roh);
                yield w.isEmpty() ? null : werteText(w);
            }
            case PROFIL -> {
                Map<String, List<String>> p = profileLesen(roh, true);
                yield p.isEmpty() ? null : profileText(p);
            }
            case TEXT -> textKanon(e, roh);
        };
    }

    private static String ersteZeile(String roh) {
        for (String zeile : roh.lines().toList()) {
            String t = zeile.strip();
            if (!t.isEmpty() && !t.startsWith("#")) return t;
        }
        return null;
    }

    private static String schalterKanon(String roh) {
        String z = ersteZeile(roh);
        if (z == null) return null;
        z = z.toLowerCase(Locale.ROOT);
        return Schalterdatei.istAn(z) ? "an" : Schalterdatei.istAus(z) ? "aus" : null;
    }

    private static String zahlKanon(Eintrag e, String roh) {
        String z = ersteZeile(roh);
        if (z == null) return null;
        String ziffern = z.replaceAll("[^0-9]", "");
        if (ziffern.isEmpty() || ziffern.length() > 6) return null;
        int n = Integer.parseInt(ziffern);
        return n >= e.min() && n <= e.max() ? String.valueOf(n) : null;
    }

    private static String wortKanon(Eintrag e, String roh) {
        String z = ersteZeile(roh);
        if (z == null) return null;
        String klein = z.toLowerCase(Locale.ROOT);
        for (String w : e.erlaubt()) {
            if (klein.startsWith(w)) return w;
        }
        return null;
    }

    private static String textKanon(Eintrag e, String roh) {
        List<String> gut = new ArrayList<>();
        for (String zeile : roh.lines().toList()) {
            String z = zeile.strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            if (!z.matches(e.muster()) || gut.size() >= e.max()) return null;
            gut.add(z);
        }
        return gut.isEmpty() ? null : String.join("\n", gut);
    }

    /** Erlaubte Schluessel mit gueltigem Wert, in der Reihenfolge der Liste (der letzte gilt, wie bei Properties). */
    private static Map<String, String> werte(Eintrag e, String roh) {
        Map<String, String> gefunden = new LinkedHashMap<>();
        if (roh != null) {
            for (String zeile : roh.lines().toList()) {
                String k = schluesselVon(zeile);
                if (k == null || !e.erlaubt().contains(k)) continue;
                String w = wertVon(zeile);
                if (w.length() <= MAX_ZEILE && w.matches("[\\x20-\\x7E]*") && w.matches(e.muster())) gefunden.put(k, w);
            }
        }
        Map<String, String> geordnet = new LinkedHashMap<>();
        for (String k : e.erlaubt()) {
            if (gefunden.containsKey(k)) geordnet.put(k, gefunden.get(k));
        }
        return geordnet;
    }

    private static String werteText(Map<String, String> werte) {
        StringBuilder b = new StringBuilder();
        werte.forEach((k, w) -> b.append(k).append('=').append(w).append('\n'));
        return b.toString();
    }

    private static int trenner(String zeile) {
        int gleich = zeile.indexOf('=');
        int punkt = zeile.indexOf(':');
        if (gleich < 0) return punkt;
        if (punkt < 0) return gleich;
        return Math.min(gleich, punkt);
    }

    /** Schluessel einer Zeile "schluessel=wert" (auch "schluessel: wert"), klein; null bei Kommentar/Leerzeile. */
    private static String schluesselVon(String zeile) {
        String z = zeile.strip();
        if (z.isEmpty() || z.startsWith("#") || z.startsWith("!")) return null;
        int t = trenner(z);
        if (t <= 0) return null;
        return z.substring(0, t).strip().toLowerCase(Locale.ROOT);
    }

    private static String wertVon(String zeile) {
        String z = zeile.strip();
        return z.substring(trenner(z) + 1).strip();
    }

    /**
     * Profile wie Profile.laden: "[Name]", darunter Befehle. bereinigen = fuer den Austausch: persoenliche Befehle
     * raus, Laengen begrenzen, leere Profile weg. Ohne bereinigen = die eigenen Profile des Empfaengers, wie sie sind.
     */
    private static Map<String, List<String>> profileLesen(String roh, boolean bereinigen) {
        Map<String, List<String>> profile = new LinkedHashMap<>();
        if (roh == null) return profile;
        String aktuell = null;
        for (String zeile : roh.lines().toList()) {
            String z = zeile.strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            if (z.startsWith("[") && z.endsWith("]") && z.length() > 2) {
                String name = z.substring(1, z.length() - 1).strip();
                boolean schlecht = name.isEmpty() || (bereinigen && (name.length() > 60
                        || !name.matches("[^\\p{Cntrl}\\[\\]]+")
                        || (!profile.containsKey(name) && profile.size() >= MAX_PROFILE)));
                aktuell = schlecht ? null : name;
                if (aktuell != null) profile.putIfAbsent(aktuell, new ArrayList<>());
            } else if (aktuell != null) {
                String befehl = z.startsWith("!") ? z : "!" + z;
                if (bereinigen && (befehl.length() > MAX_ZEILE || befehl.matches(".*\\p{Cntrl}.*")
                        || privaterBefehl(befehl) || profile.get(aktuell).size() >= MAX_PROFILZEILEN)) continue;
                profile.get(aktuell).add(befehl);
            }
        }
        if (bereinigen) profile.values().removeIf(List::isEmpty);
        return profile;
    }

    private static String profileText(Map<String, List<String>> profile) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<String>> p : profile.entrySet()) {
            b.append('[').append(p.getKey()).append("]\n");
            for (String z : p.getValue()) b.append(z).append('\n');
            b.append('\n');
        }
        return b.toString();
    }

    /** Setzt dieser Profil-Befehl einen persoenlichen Wert (Lager, Pass, Zeitzone, Discord ...)? */
    /** Nie aus einem fremden Profil uebernehmen (Hauptbefehle nach Befehlsnamen.kanonisch). */
    private static final Set<String> GEFAEHRLICH_KANON = Set.of(
            "!pc", "!emergency", "!off", "!clear", "!restart", "!autostart", "!timezone", "!team", "!update");

    static boolean privaterBefehl(String befehl) {
        String z = befehl.strip().toLowerCase(Locale.ROOT);
        if (!z.startsWith("!")) z = "!" + z;
        for (String wort : PRIVATE_WOERTER) {
            if (z.contains(wort)) return true;
        }
        if (z.matches(".*\\d{15,}.*")) return true;   // Nutzer- oder Kanal-ID
        String[] w = z.split("\\s+");
        // Code-Pruefung 16.10.: "!shutdown" (= !off) usw. kamen an der Liste vorbei -- jede Schreibweise zaehlt.
        String kanon = de.hustlebot.discord.Befehlsnamen.kanonisch(w[0]);
        if (PRIVATE_BEFEHLE.contains(w[0]) || PRIVATE_BEFEHLE.contains(kanon) || GEFAEHRLICH_KANON.contains(kanon)) return true;
        return (w[0].equals("!settings") || w[0].equals("!einstellungen")) && w.length >= 2 && PRIVATE_SETTINGS.contains(w[1]);
    }

    // ------------------------------------------------------------------
    //  Vergleichen und Schreiben
    // ------------------------------------------------------------------

    private static List<Aenderung> vergleichen(Eintrag e, String neuKanon, Path ordner) {
        String roh = rohLesen(ordner, e.datei());
        List<Aenderung> liste = new ArrayList<>();
        switch (e.art()) {
            case SCHLUESSEL -> {
                Map<String, String> alt = werte(e, roh);
                for (Map.Entry<String, String> w : werte(e, neuKanon).entrySet()) {
                    String a = alt.get(w.getKey());
                    if (!w.getValue().equals(a)) {
                        liste.add(new Aenderung(e.titel() + ": " + schluesselName(w.getKey()), anzeige(a), anzeige(w.getValue())));
                    }
                }
            }
            case PROFIL -> {
                Map<String, List<String>> alt = profileLesen(roh, false);
                for (Map.Entry<String, List<String>> p : profileLesen(neuKanon, true).entrySet()) {
                    List<String> a = alt.get(p.getKey());
                    if (!p.getValue().equals(a)) {
                        liste.add(new Aenderung(e.titel() + " \"" + p.getKey() + "\"", profilAnzeige(a), profilAnzeige(p.getValue())));
                    }
                }
            }
            default -> {
                String alt = kanon(e, roh);
                boolean standard = alt == null && e.standard() != null;
                if (standard) alt = e.standard();
                if (!neuKanon.equals(alt)) {
                    boolean text = e.art() == Art.TEXT;
                    String altText = text ? textAnzeige(alt) : anzeige(alt);
                    if (standard) altText += Sprache.t(" (default)", " (Standard)");
                    liste.add(new Aenderung(e.titel(), altText, text ? textAnzeige(neuKanon) : anzeige(neuKanon)));
                }
            }
        }
        return liste;
    }

    /** Der neue Inhalt der Datei. Schluessel-Dateien: nur die erlaubten Zeilen ersetzen, alles andere bleibt. */
    private static byte[] zusammenfuehren(Eintrag e, String neuKanon, Path ziel) throws IOException {
        String nl = System.lineSeparator();
        return switch (e.art()) {
            case SCHLUESSEL -> {
                // ISO-8859-1: jedes Byte bleibt, wie es war (Properties-Dateien sind ohnehin Latin-1).
                String alt = Files.isRegularFile(ziel, LinkOption.NOFOLLOW_LINKS)
                        ? Files.readString(ziel, StandardCharsets.ISO_8859_1) : "";
                String umbruch = alt.contains("\r\n") ? "\r\n" : alt.contains("\n") ? "\n" : nl;
                List<String> zeilen = new ArrayList<>(Arrays.asList(alt.split("\r?\n", -1)));
                if (!zeilen.isEmpty() && zeilen.get(zeilen.size() - 1).isEmpty()) zeilen.remove(zeilen.size() - 1);
                Map<String, String> neu = werte(e, neuKanon);
                Set<String> erledigt = new HashSet<>();
                for (int i = 0; i < zeilen.size(); i++) {
                    String k = schluesselVon(zeilen.get(i));
                    if (k != null && neu.containsKey(k)) {
                        zeilen.set(i, k + "=" + neu.get(k));
                        erledigt.add(k);
                    }
                }
                for (Map.Entry<String, String> w : neu.entrySet()) {
                    if (!erledigt.contains(w.getKey())) zeilen.add(w.getKey() + "=" + w.getValue());
                }
                yield (String.join(umbruch, zeilen) + umbruch).getBytes(StandardCharsets.ISO_8859_1);
            }
            case PROFIL -> {
                // Gleichnamige Profile ersetzen, neue hinten anfuegen, die eigenen des Empfaengers behalten.
                Map<String, List<String>> alle = profileLesen(rohLesen(ziel), false);
                alle.putAll(profileLesen(neuKanon, true));
                String text = "# Profile des Steuerprogramms. Ein Profil = Befehle wie in Discord, der Reihe nach.\n"
                        + "# Ergaenzt aus den Einstellungen eines Freundes (Einstellungen einfuegen).\n\n"
                        + profileText(alle);
                yield text.replace("\n", nl).getBytes(StandardCharsets.UTF_8);
            }
            case TEXT -> (neuKanon.replace("\n", nl) + nl).getBytes(StandardCharsets.UTF_8);
            // Nur der Wert, ohne Kommentarzeile: Mauswache und Kriegsplan lesen den ganzen Inhalt.
            case SCHALTER, ZAHL, WORT -> (neuKanon + nl).getBytes(StandardCharsets.UTF_8);
        };
    }

    private static void schreiben(Path ziel, byte[] inhalt) throws IOException {
        if (ziel == null) throw new IOException("not allowed");
        if (Files.isSymbolicLink(ziel)
                || (Files.exists(ziel, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(ziel, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(Sprache.t("not a normal file", "keine normale Datei"));
        }
        Path neu = ziel.resolveSibling(ziel.getFileName() + ".neu");
        Files.write(neu, inhalt);
        try {
            Files.move(neu, ziel, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.write(ziel, inhalt);
            } finally {
                Files.deleteIfExists(neu);
            }
        }
    }

    /** Kopiert die betroffenen Dateien nach logs/einstellungen-vorher-<Zeit>/ und schreibt dazu, wie man zurueckgeht. */
    private static Path sichern(Path ordner, Set<Eintrag> eintraege) throws IOException {
        Path logs = ordner.toAbsolutePath().normalize().resolve("logs");
        String stempel = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = logs.resolve("einstellungen-vorher-" + stempel);
        for (int i = 2; Files.exists(dir); i++) dir = logs.resolve("einstellungen-vorher-" + stempel + "-" + i);
        Files.createDirectories(dir);
        List<String> neu = new ArrayList<>();
        for (Eintrag e : eintraege) {
            Path p = ziel(ordner, e.datei());
            if (p != null && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) Files.copy(p, dir.resolve(e.datei()));
            else neu.add(e.datei());
        }
        String nl = System.lineSeparator();
        StringBuilder info = new StringBuilder()
                .append("Settings before pasting a friend's code / Einstellungen vor dem Einfuegen eines Freundes-Codes").append(nl)
                .append("To undo: stop the bot, copy these files back into the bot folder, start the bot.").append(nl)
                .append("Rueckgaengig: Bot beenden, diese Dateien zurueck in den Bot-Ordner kopieren, Bot starten.").append(nl);
        if (!neu.isEmpty()) {
            info.append(nl).append("These files did not exist before (delete them to undo) / Diese Dateien gab es vorher nicht (zum Rueckgaengigmachen loeschen):").append(nl);
            for (String n : neu) info.append("  ").append(n).append(nl);
        }
        Files.writeString(dir.resolve("LIESMICH.txt"), info.toString(), StandardCharsets.UTF_8);
        return dir;
    }

    /** Aufstellung hat ein oeffentliches neu-Lesen; nur benutzen, wenn der Ordner der des laufenden Bots ist. */
    private static boolean aufstellungNeuLaden(Path ordner) {
        try {
            if (!ordner.toAbsolutePath().normalize().equals(Ordner.basis().toAbsolutePath().normalize())) return false;
            de.hustlebot.bot.Aufstellung.konfigurieren(ordner.resolve("aufstellung.properties"));
            return true;
        } catch (RuntimeException | LinkageError e) {
            Log.warn(QUELLE, Sprache.t("Squad settings not reloaded: ", "Truppenaufstellung nicht neu gelesen: ") + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Anzeige
    // ------------------------------------------------------------------

    private static String schluesselName(String k) {
        String[] n = SCHLUESSEL_NAMEN.get(k);
        return n == null ? k : Sprache.t(n[0], n[1]);
    }

    private static String anzeige(String v) {
        if (v == null) return "–";
        if (v.isEmpty()) return Sprache.t("(empty)", "(leer)");
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "an", "on", "ja", "yes" -> Sprache.t("on", "an");
            case "aus", "off", "nein", "no" -> Sprache.t("off", "aus");
            case "tickets" -> Sprache.t("tickets only", "nur Tickets");
            case "food" -> Sprache.t("food only", "nur Nahrung");
            case "tickets+food" -> Sprache.t("tickets, then food", "erst Tickets, dann Nahrung");
            case "alles" -> Sprache.t("tickets, food, then food chests", "Tickets, Nahrung, dann Nahrungskisten");
            case "food+chests" -> Sprache.t("food, then food chests", "erst Nahrung, dann Nahrungskisten");   // Mia 21.10.: ohne Tickets
            default -> v;
        };
    }

    private static String profilAnzeige(List<String> befehle) {
        if (befehle == null) return "–";
        if (befehle.isEmpty()) return Sprache.t("(empty)", "(leer)");
        return kurz(String.join(" · ", befehle), 120);
    }

    private static String textAnzeige(String text) {
        return text == null ? "–" : kurz(text.replace("\n", ", "), 120);
    }

    private static String kurz(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
