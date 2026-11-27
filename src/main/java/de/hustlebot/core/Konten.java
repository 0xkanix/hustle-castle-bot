package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Konto-Profile (Autor 21.10. fuer Mia: "mehrere Accounts -- muss er bei jedem Account einen Team-Scan machen, oder
 * kann man Presets wie Account 1, 2, 3 speichern? Die Arena kostet je Account unterschiedlich viel Nahrung, und das
 * Nahrungslager ist verschieden gross." Autor: das Konto selbst erkennen, Gegnerlisten je Konto getrennt).
 *
 * WIE. Jedes Konto hat seine eigenen Dateien (Team-Scan, Notizblock, Gegnerlisten, Bezahlart und Lager ...). Die Dateien
 * des AKTIVEN Kontos stehen wie bisher im Bot-Ordner, die der anderen ruhen in konten\&lt;name&gt;\. Beim Wechsel tauscht
 * der Bot die Dateien aus -- so bleibt der Rest des Bots unveraendert, und wer nur ein Konto hat, merkt nichts.
 * Den Nahrungspreis der Arena liest der Bot ohnehin bei jeder Teilnahme vom Bildschirm.
 *
 * ERKANNT wird das Konto am Spielernamen oben links in der Burg (ohne Klick), bei jedem Start eines Modus. Ein neuer
 * Name zaehlt erst, wenn er zweimal gleich gelesen wurde (ein Lesefehler legt kein falsches Konto an).
 * Schalter: konten.txt ("an" = selbst erkennen; ohne Datei aus). Das aktive Konto steht in konto-aktiv.txt.
 *
 * Autor 21.11. (Robin spielte auf einem Zweitkonto, der Bot erkannte dessen Einheiten nicht): "Der Bot soll immer in der
 * Burg gestartet werden, den Namen sehen. Passt er und hat er schon Bilder von diesem Account, nimmt er die. Hat er noch
 * keine, soll er sie machen und dann erst den Modus spielen" -- in JEDEM Modus, Schalter ohne Datei jetzt AN (auch Freunde).
 * Erkennungsmerkmale nur Spieler-ID und Name: Name (Text + Namensbild, {@link Namensbild}) billig in der Burg; passt er
 * nicht, liest der Bot die Spieler-ID im Profilfenster (bot.KontoPruefung). Die ID entscheidet: bekannt = dieses Konto
 * (auch wenn es umbenannt wurde), unbekannt = neues Konto. Nichts davon steht fest im Code -- alles wird zur Laufzeit gelesen
 * und je Konto gespeichert (konto-id.txt, konto-namen.txt, konto-namensbild*.png wandern mit dem Konto). Die eigenen
 * Kaempferbilder (unit_1.png ...) wandern seit 21.11. ebenfalls mit (konten\&lt;konto&gt;\vorlagen\).
 */
public final class Konten {

    private Konten() { }

    /** Autor 21.11.: die Spieler-ID aus dem Profilfenster ("id: ..."). */
    static final String ID_DATEI = "konto-id.txt";
    /** Weitere Schreibweisen des Namens, die sicher zu diesem Konto gehoeren (per ID/Namensbild bestaetigt). */
    static final String NAMEN_DATEI = "konto-namen.txt";
    /** Bis zu drei Namensbilder je Konto (Burg-Ansicht, Schmuckschrift). */
    static final List<String> BILDER = List.of("konto-namensbild.png", "konto-namensbild-2.png", "konto-namensbild-3.png");

    /** Diese Dateien gehoeren zu einem Konto. */
    static final List<String> DATEIEN = List.of(
            "arena-ich.txt", "eigenesteam.txt", "arena-notizblock.txt", "arena-daten.txt", "arena-liste.txt",
            "arena-lernen.txt", "arena-aufheben.txt", "arena-punkte-erzielt.txt", "arena-abgestuft.txt",
            "arena-aufnehmen.txt", "arena-verlierer.txt", "arena-plaetze.txt", "bezahlung.txt", "nahrung-heute.txt",
            // Review 28.10.: Arena-Listen sind je Konto getrennt -- dann auch, was Autor dort geloescht hat.
            "arena-entfernt.txt", "arena-auto-bilder.txt", SelbstKaempfe.DATEI,
            // Autor 21.11.: die Erkennungsmerkmale wandern mit dem Konto
            ID_DATEI, NAMEN_DATEI, BILDER.get(0), BILDER.get(1), BILDER.get(2),
            // Autor 22.11.: die Liste zu den Team-Scan-Bildern (unit_auto_1 ...) gehoert ebenfalls dem Konto
            "einheiten-auto.txt");
    /** Bekommt ein neues Konto mit, damit es nicht bei null anfaengt (Bezahlart; Lager und Limits stellt man dann um). */
    static final List<String> MITNEHMEN = List.of("bezahlung.txt");
    /** Die Gegnerlisten (templates\arena\gegner\...) -- je Konto getrennt (Autor 21.10.). */
    static final List<String> LISTEN = List.of("sicher", "fastsicher", "machbar", "meiden", "pause", "niemals", "clans");

    static final String ORDNER = "konten";
    /** Autor 21.11.: Standard AN fuer alle ("in jedem Modus") -- "aus" in konten.txt schaltet ab. */
    private static final Schalterdatei SCHALTER = new Schalterdatei(Ordner.bei("konten.txt"), "Konten", true);

    public static boolean an() {
        return SCHALTER.lesen();
    }

    public static void setzen(boolean an) {
        SCHALTER.setzen(an, Sprache.t("Several game accounts: recognise the account by the player name (on/off)",
                "Mehrere Spielkonten: Konto am Spielernamen erkennen (an/aus)"));
    }

    /** Das aktive Konto (Spielername), "" = noch keins. */
    public static synchronized String aktiv() {
        return aktiv(Ordner.basis());
    }

    static String aktiv(Path bot) {
        try {
            Path d = bot.resolve("konto-aktiv.txt");
            return Files.exists(d) ? Dateien.text(d).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Ordnername eines Kontos: nur Buchstaben und Ziffern, klein. */
    static String schluessel(String name) {
        String f = NamensVergleich.form(name);
        return f.isEmpty() ? "konto" : f;
    }

    /** Alle bekannten Konten (das aktive zuerst). */
    public static synchronized List<String> bekannt() {
        return bekannt(Ordner.basis());
    }

    static List<String> bekannt(Path bot) {
        List<String> l = new ArrayList<>();
        String a = aktiv(bot);
        if (!a.isEmpty()) l.add(a);
        Path o = bot.resolve(ORDNER);
        if (!Files.isDirectory(o)) return l;
        try (Stream<Path> s = Files.list(o)) {
            for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                Path n = p.resolve("name.txt");
                String name = Files.exists(n) ? Dateien.text(n).strip() : p.getFileName().toString();
                if (!name.isEmpty() && l.stream().noneMatch(x -> ArenaVorlagenDoppelt.derselbeName(x, name))) l.add(name);
            }
        } catch (Exception e) {
            Log.debug("Konten", "konten\\ nicht lesbar: " + e.getMessage());
        }
        return l;
    }

    /** Ein neuer Name, der erst einmal gelesen wurde (wartet auf die Bestaetigung). */
    private static String neuGelesen = "";

    /**
     * Einen gelesenen Spielernamen einordnen. Leer = nichts zu tun (gleiches Konto, Schalter aus, Name unsicher, oder
     * das allererste Konto wird nur vermerkt). Sonst das Konto, zu dem gewechselt werden soll.
     */
    public static synchronized Optional<String> erkennen(String gelesen) {
        return erkennen(gelesen, Ordner.basis(), an());
    }

    static Optional<String> erkennen(String gelesen, Path bot, boolean schalterAn) {
        if (!schalterAn || gelesen == null || NamensVergleich.form(gelesen).length() < 2 || NamensVergleich.keinName(gelesen)) {
            return Optional.empty();
        }
        String a = aktiv(bot);
        if (a.isEmpty()) {   // das erste Konto: die Dateien im Bot-Ordner gehoeren ab jetzt ihm
            schreiben(bot.resolve("konto-aktiv.txt"), gelesen.strip());
            Log.info("Konten", Sprache.t("Account \"" + gelesen.strip() + "\" noted -- the current files belong to it.",
                    "Konto \"" + gelesen.strip() + "\" vermerkt -- die jetzigen Dateien gehoeren ihm."));
            return Optional.empty();
        }
        if (ArenaVorlagenDoppelt.derselbeName(a, gelesen)) {
            neuGelesen = "";
            return Optional.empty();
        }
        for (String k : bekannt(bot)) {
            if (ArenaVorlagenDoppelt.derselbeName(k, gelesen)) {
                neuGelesen = "";
                return Optional.of(k);
            }
        }
        // Unbekannt: erst beim zweiten gleichen Lesen ein neues Konto anlegen.
        if (NamensVergleich.form(gelesen).equals(NamensVergleich.form(neuGelesen))) {
            neuGelesen = "";
            return Optional.of(gelesen.strip());
        }
        neuGelesen = gelesen;
        Log.info("Konten", Sprache.t("Unknown player name \"" + gelesen.strip() + "\" -- if it is read like that again, it becomes a new account.",
                "Unbekannter Spielername \"" + gelesen.strip() + "\" -- wird er noch einmal so gelesen, wird es ein neues Konto."));
        return Optional.empty();
    }

    /** Wechselt zum Konto {@code neu}: Dateien und Gegnerlisten austauschen. true = gewechselt. */
    public static synchronized boolean wechseln(String neu, Path templates) {
        return wechseln(neu, Ordner.basis(), templates);
    }

    static boolean wechseln(String neu, Path bot, Path templates) {
        String alt = aktiv(bot);
        if (neu == null || neu.isBlank() || alt.strip().equalsIgnoreCase(neu.strip())) return false;
        try {
            // Autor 21.11.: Ordner ueber name.txt suchen (nicht nur ueber den Schluessel) -- so findet der Bot ein Konto auch,
            // wenn sein Ordnername anders entstanden ist; "Name #ID" (zwei Konten gleichen Namens) bekommt einen eigenen.
            Path altOrdner = ruheOrdner(bot, alt.isEmpty() ? "konto" : alt);
            Path neuOrdner = ruheOrdner(bot, neu);
            Files.createDirectories(altOrdner);
            Files.createDirectories(neuOrdner);
            if (!alt.isEmpty()) schreiben(altOrdner.resolve("name.txt"), alt);
            boolean neuesKonto = !Files.exists(neuOrdner.resolve("name.txt"));
            // 1. das alte Konto wegraeumen
            for (String f : DATEIEN) {
                Path d = bot.resolve(f);
                if (Files.exists(d)) Files.move(d, altOrdner.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
            for (String l : LISTEN) verschieben(eigeneListe(bot, l), altOrdner.resolve("gegner").resolve(l));
            for (String l : LISTEN) verschieben(templates.resolve("arena").resolve("gegner").resolve(l), altOrdner.resolve("gegner").resolve(l));
            // Autor 21.11.: die eigenen Kaempferbilder (unit_1.png, name_1.png, nameplate_me.png ...) gehoeren dem Konto
            vorlagenWeg(templates, altOrdner.resolve(VORLAGEN));
            vorlagenWeg(gelernt(bot), altOrdner.resolve(VORLAGEN_GELERNT));
            vorlagenWeg(Eigenbilder.wurzel(bot), altOrdner.resolve(VORLAGEN_EIGENE));
            // 2. das neue Konto holen (ein neues bekommt die Bezahlart des alten mit)
            for (String f : DATEIEN) {
                Path q = neuOrdner.resolve(f);
                if (Files.exists(q)) Files.move(q, bot.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                else if (neuesKonto && MITNEHMEN.contains(f) && Files.exists(altOrdner.resolve(f))) {
                    Files.copy(altOrdner.resolve(f), bot.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (String l : LISTEN) verschieben(neuOrdner.resolve("gegner").resolve(l), eigeneListe(bot, l));
            vorlagenHolen(neuOrdner.resolve(VORLAGEN), templates);
            vorlagenHolen(neuOrdner.resolve(VORLAGEN_GELERNT), gelernt(bot));
            vorlagenHolen(neuOrdner.resolve(VORLAGEN_EIGENE), Eigenbilder.wurzel(bot));
            schreiben(neuOrdner.resolve("name.txt"), neu.strip());
            schreiben(bot.resolve("konto-aktiv.txt"), neu.strip());
            Log.info("Konten", Sprache.t("Switched to account \"" + neu.strip() + "\"" + (neuesKonto ? " (new)" : "")
                            + ". Its team scan, notebook, opponent lists, fighter pictures and payment settings apply now.",
                    "Gewechselt zum Konto \"" + neu.strip() + "\"" + (neuesKonto ? " (neu)" : "")
                            + ". Ab jetzt gelten sein Team-Scan, Notizblock, Gegnerlisten, Kaempferbilder und seine Bezahl-Einstellungen."));
            if (brauchtEinheitenBilder(templates, gelernt(bot), Eigenbilder.wurzel(bot))) {
                Log.hinweis("Konten", Sprache.t("Account \"" + neu.strip() + "\" has no fighter pictures yet (unit_1 ... unit_4) -- "
                                + "the bot takes them first before it relies on them.",
                        "Das Konto \"" + neu.strip() + "\" hat noch keine Kaempferbilder (unit_1 ... unit_4) -- der Bot nimmt sie "
                                + "zuerst auf, bevor er sich auf sie verlaesst."));
            }
            return true;
        } catch (Exception e) {
            Log.warn("Konten", Sprache.t("Account switch failed: ", "Kontowechsel fehlgeschlagen: ") + e.getMessage());
            return false;
        }
    }

    // ---- Kaempferbilder je Konto (Autor 21.11.) --------------------------------------------------------------------

    /** Unterordner im Konto-Ordner: Vorlagen aus templates\** bzw. aus dem Lernordner gelernt\** (Matcher.GELERNT). */
    static final String VORLAGEN = "vorlagen", VORLAGEN_GELERNT = "vorlagen-gelernt";
    /** Autor 22.11.: auch aus "My pictures\**" (dort liegen selbst gemachte Bilder, der Matcher laedt auch von dort). */
    static final String VORLAGEN_EIGENE = "vorlagen-eigene";

    private static Path gelernt(Path bot) {
        return bot.resolve(Matcher.GELERNT.toString());
    }

    /**
     * Gehoert diese Vorlage zum Konto? Die eigenen Kaempfer: unit_1*, name_1* (jede Ziffer), unit_sacrifice*,
     * name_sacrifice*, nameplate_me*, seit 22.11. auch die Team-Scan-Bilder unit_auto_* / name_auto_* (Kaserne DIESES
     * Kontos). NICHT unit_dead* (Todesleiste, fuer alle Konten gleich).
     */
    static boolean kontoVorlage(String dateiname) {
        String n = dateiname.toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(".png")) return false;
        return n.matches("(unit|name)_(\\d|sacrifice|auto_).*") || n.startsWith("nameplate_me");
    }

    /** Alle Konto-Vorlagen unter {@code wurzel} (ohne die Arena-Gegnerlisten) nach {@code ziel}\&lt;relativer Pfad&gt;. */
    static void vorlagenWeg(Path wurzel, Path ziel) throws java.io.IOException {
        if (!Files.isDirectory(wurzel)) return;
        Path gegner = wurzel.resolve("arena").resolve("gegner");
        List<Path> treffer;
        try (Stream<Path> s = Files.walk(wurzel)) {
            treffer = s.filter(Files::isRegularFile).filter(p -> !p.startsWith(gegner))
                    .filter(p -> kontoVorlage(p.getFileName().toString())).toList();
        }
        for (Path p : treffer) {
            Path z = ziel.resolve(wurzel.relativize(p).toString());
            Files.createDirectories(z.getParent());
            Files.move(p, z, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Die gespeicherten Konto-Vorlagen zurueck an ihre Stelle unter {@code wurzel}. */
    static void vorlagenHolen(Path quelle, Path wurzel) throws java.io.IOException {
        if (!Files.isDirectory(quelle)) return;
        List<Path> dateien;
        try (Stream<Path> s = Files.walk(quelle)) {
            dateien = s.filter(Files::isRegularFile).toList();
        }
        for (Path p : dateien) {
            Path z = wurzel.resolve(quelle.relativize(p).toString());
            Files.createDirectories(z.getParent());
            Files.move(p, z, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Fehlen dem aktiven Konto Kaempferbilder? true, wenn mindestens eines von unit_1 ... unit_4 (auch unit_1_b.png usw.)
     * weder unter templates\** noch im Lernordner liegt. Fuer das Aufnehmen der Kaempferbilder (Kathedrale/Verlies).
     */
    public static boolean brauchtEinheitenBilder() {
        Path bot = Ordner.basis();
        return brauchtEinheitenBilder(bot.resolve("templates"), gelernt(bot), Eigenbilder.wurzel(bot));
    }

    static boolean brauchtEinheitenBilder(Path... wurzeln) {
        boolean[] da = new boolean[5];
        for (Path w : wurzeln) {
            if (w == null || !Files.isDirectory(w)) continue;
            try (Stream<Path> s = Files.walk(w)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (n.endsWith(".png") && n.matches("unit_[1-4]([^0-9].*)?")) da[n.charAt(5) - '0'] = true;
                });
            } catch (Exception e) {
                Log.debug("Konten", w + ": " + e.getMessage());
            }
        }
        return !(da[1] && da[2] && da[3] && da[4]);
    }

    // ---- Wo liegt ein Konto? ----------------------------------------------------------------------------------------

    /** konten\&lt;ordner&gt; eines ruhenden Kontos: der Ordner, dessen name.txt so heisst; sonst nach dem Schluessel. */
    static Path ruheOrdner(Path bot, String name) {
        Path o = bot.resolve(ORDNER);
        String n = name == null ? "" : name.strip();
        if (Files.isDirectory(o)) {
            try (Stream<Path> s = Files.list(o)) {
                for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                    Path d = p.resolve("name.txt");
                    if (Files.exists(d) && Dateien.text(d).strip().equalsIgnoreCase(n)) return p;
                }
            } catch (Exception e) {
                Log.debug("Konten", "konten\\ nicht lesbar: " + e.getMessage());
            }
        }
        return o.resolve(schluessel(n));
    }

    /** Wo die Dateien eines Kontos gerade liegen: die des aktiven im Bot-Ordner, die der anderen in konten\. */
    static Path ablage(Path bot, String name) {
        return aktiv(bot).equalsIgnoreCase(name == null ? "" : name.strip()) ? bot : ruheOrdner(bot, name);
    }

    // ---- Erkennungsmerkmale: Name, Namensbild, Spieler-ID (Autor 21.11.) -----------------------------------------------

    /** Was die Pruefung ergeben hat. konto = das betroffene Konto (bei NEU: der Name fuer das neue). */
    public enum Urteil { AKTIV, ANDERES, NEU, UNBEKANNT, UNLESBAR, ERSTES }

    public record Befund(Urteil urteil, String konto) {
        static Befund von(Urteil u) { return new Befund(u, ""); }
    }

    /** Ein brauchbarer gelesener Name (mindestens 2 Zeichen, kein Ueberschriften-Wort). */
    static boolean lesbar(String name) {
        return name != null && NamensVergleich.form(name).length() >= 2 && !NamensVergleich.keinName(name);
    }

    /** Die Spieler-ID des aktiven Kontos, "" = noch keine. */
    public static synchronized String id() {
        return idVon(Ordner.basis());
    }

    static String idVon(Path ablage) {
        try {
            Path d = ablage.resolve(ID_DATEI);
            return Files.exists(d) ? Dateien.text(d).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Das Konto mit dieser Spieler-ID ("" = keins). */
    static String kontoMitId(Path bot, String id) {
        if (id == null || id.isBlank()) return "";
        for (String k : bekannt(bot)) if (id.strip().equals(idVon(ablage(bot, k)))) return k;
        return "";
    }

    /** Passt der gelesene Name zu diesem Konto (Hauptname oder eine bestaetigte Schreibweise aus konto-namen.txt)? */
    static boolean namePasst(Path ablage, String konto, String gelesen) {
        if (!lesbar(gelesen)) return false;
        if (ArenaVorlagenDoppelt.derselbeName(konto, gelesen)) return true;
        for (String n : namenVon(ablage)) if (ArenaVorlagenDoppelt.derselbeName(n, gelesen)) return true;
        return false;
    }

    /**
     * Der Spielername in einem Kontonamen: ohne angehaengtes " #12345678" (zwei Konten gleichen Namens) und "" fuer ein
     * Konto, das nur nach seiner ID heisst ("ID 12345678" -- der Name war nicht lesbar).
     */
    static String kern(String konto) {
        String k = konto == null ? "" : konto.strip();
        if (k.matches("(?i)id \\d+")) return "";
        return k.replaceFirst("\\s+#\\d+$", "").strip();
    }

    /**
     * Autor 22.11.: Passt ein anderswo gelesener Spielername (z. B. im Team-Scan) zum AKTIVEN Konto? Auch eine bestaetigte
     * Schreibweise (konto-namen.txt, etwa nach dem Umbenennen) und "Name #ID" zaehlen. Ohne aktives Konto, ohne lesbaren
     * Namen oder bei einem Konto ohne bekannten Namen: true (nicht dagegen pruefen, wie bisher).
     */
    public static synchronized boolean passtZumAktiven(String gelesen) {
        return passtZumAktiven(gelesen, Ordner.basis());
    }

    static boolean passtZumAktiven(String gelesen, Path bot) {
        String a = aktiv(bot);
        if (a.isEmpty() || !lesbar(gelesen)) return true;
        String k = kern(a);
        return k.isEmpty() || namePasst(bot, k, gelesen);
    }

    private static List<String> namenVon(Path ablage) {
        try {
            Path d = ablage.resolve(NAMEN_DATEI);
            if (!Files.exists(d)) return List.of();
            return Dateien.zeilen(d).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Passt das Namensbild zu einem der gespeicherten Bilder dieses Kontos? */
    static boolean bildPasst(Path ablage, boolean[][] bild) {
        if (bild == null) return false;
        for (String b : BILDER) {
            boolean[][] m = Namensbild.laden(ablage.resolve(b));
            if (m != null && Namensbild.gleich(m, bild)) return true;
        }
        return false;
    }

    /**
     * Schritt 1 (billig, ohne Klick): Name und Namensbild aus der Burg einordnen. Das Bild zuerst -- gleiches Bild = gleiches
     * Konto, auch wenn die Texterkennung den Namen anders liest (nie wird aus einem Lesefehler ein Kontowechsel).
     * AKTIV = weiter; ANDERES = zu diesem bekannten Konto wechseln; UNBEKANNT/UNLESBAR = Spieler-ID im Profil lesen;
     * ERSTES = noch gar kein Konto vermerkt.
     */
    public static synchronized Befund nachName(String name, boolean[][] bild) {
        return nachName(name, bild, Ordner.basis());
    }

    static Befund nachName(String name, boolean[][] bild, Path bot) {
        String a = aktiv(bot);
        if (a.isEmpty()) return Befund.von(Urteil.ERSTES);
        if (bildPasst(bot, bild) || namePasst(bot, a, name)) return new Befund(Urteil.AKTIV, a);
        List<String> andere = bekannt(bot).stream().filter(k -> !k.equalsIgnoreCase(a)).toList();
        for (String k : andere) if (bildPasst(ruheOrdner(bot, k), bild)) return new Befund(Urteil.ANDERES, k);
        if (!lesbar(name)) return Befund.von(Urteil.UNLESBAR);
        for (String k : andere) if (namePasst(ruheOrdner(bot, k), k, name)) return new Befund(Urteil.ANDERES, k);
        return Befund.von(Urteil.UNBEKANNT);
    }

    /**
     * Schritt 2: die Spieler-ID aus dem Profilfenster einordnen (Autor 21.11.: "ID bekannt? dann dieses Konto (Name evtl.
     * umbenannt). ID unbekannt -> NEUES Konto anlegen"). {@code namen}: was gelesen wurde (Profil zuerst, dann Burg).
     * AKTIV = das aktive Konto (bekommt die ID, falls es noch keine hat); ANDERES = wechseln; NEU = neues Konto anlegen
     * (konto = sein Name); UNLESBAR = keine ID -- nichts aendern. Nur fuer ein schon vermerktes aktives Konto.
     */
    public static synchronized Befund nachId(String id, List<String> namen, boolean[][] bild) {
        return nachId(id, namen, bild, Ordner.basis());
    }

    static Befund nachId(String id, List<String> namen, boolean[][] bild, Path bot) {
        if (id == null || id.isBlank()) return Befund.von(Urteil.UNLESBAR);
        String a = aktiv(bot);
        String k = kontoMitId(bot, id);
        if (!k.isEmpty()) return new Befund(k.equalsIgnoreCase(a) ? Urteil.AKTIV : Urteil.ANDERES, k);
        List<String> gelesen = namen == null ? List.of() : namen.stream().filter(Konten::lesbar).map(String::strip).toList();
        // Autor 22.11.: eine einzige falsch gelesene Ziffer soll kein neues Konto anlegen (das verschiebt alle Dateien und
        // Bilder). Weicht die ID nur in EINER Ziffer von der eines bekannten Kontos ab UND passen Name oder Namensbild dazu,
        // ist es dieses Konto (die gespeicherte ID bleibt, merken() ueberschreibt nie).
        for (String b : bekannt(bot)) {
            Path o = ablage(bot, b);
            String bid = idVon(o);
            if (eineZifferAnders(bid, id.strip()) && (bildPasst(o, bild) || gelesen.stream().anyMatch(n -> namePasst(o, b, n)))) {
                return new Befund(b.equalsIgnoreCase(a) ? Urteil.AKTIV : Urteil.ANDERES, b);
            }
        }
        // Unbekannte ID. Hat das aktive Konto noch keine und passt Name oder Bild, ist es seine (erste Pruefung nach dem Update).
        if (!a.isEmpty() && idVon(bot).isEmpty()
                && (bildPasst(bot, bild) || gelesen.stream().anyMatch(n -> namePasst(bot, a, n)))) {
            return new Befund(Urteil.AKTIV, a);
        }
        // Ein bekanntes Konto ohne ID, dessen Name oder Bild passt
        for (String b : bekannt(bot)) {
            if (b.equalsIgnoreCase(a)) continue;
            Path o = ruheOrdner(bot, b);
            if (!idVon(o).isEmpty()) continue;
            if (bildPasst(o, bild) || gelesen.stream().anyMatch(n -> namePasst(o, b, n))) return new Befund(Urteil.ANDERES, b);
        }
        // Ein neues Konto. Heisst schon eines so (andere ID), bekommt es die ID dazu, damit die Ordner getrennt bleiben.
        String name = gelesen.isEmpty() ? "ID " + id.strip() : gelesen.get(0);
        String fertig = name;
        if (bekannt(bot).stream().anyMatch(b -> ArenaVorlagenDoppelt.derselbeName(b, fertig) || b.equalsIgnoreCase(fertig))) {
            name = name + " #" + id.strip();
        }
        return new Befund(Urteil.NEU, name);
    }

    /** Gleich lang und genau eine Ziffer anders (ein Lesefehler der Texterkennung)? */
    static boolean eineZifferAnders(String a, String b) {
        if (a == null || b == null || a.isEmpty() || a.length() != b.length()) return false;
        int anders = 0;
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) anders++;
        return anders == 1;
    }

    /** Das allererste Konto vermerken: die Dateien im Bot-Ordner gehoeren ab jetzt ihm. */
    public static synchronized void ersteVermerken(String name) {
        ersteVermerken(name, Ordner.basis());
    }

    static void ersteVermerken(String name, Path bot) {
        if (!lesbar(name) || !aktiv(bot).isEmpty()) return;
        schreiben(bot.resolve("konto-aktiv.txt"), name.strip());
        Log.info("Konten", Sprache.t("Account \"" + name.strip() + "\" noted -- the current files belong to it.",
                "Konto \"" + name.strip() + "\" vermerkt -- die jetzigen Dateien gehoeren ihm."));
    }

    /**
     * Fuer das AKTIVE Konto merken, was sicher zu ihm gehoert (per ID, Namensbild oder Name bestaetigt): die ID (nur wenn es
     * noch keine hat -- nie ueberschreiben), weitere Schreibweisen des Namens, das Namensbild (bis zu drei).
     */
    public static synchronized void merken(String id, List<String> namen, boolean[][] bild) {
        merken(id, namen, bild, Ordner.basis());
    }

    static void merken(String id, List<String> namen, boolean[][] bild, Path bot) {
        String a = aktiv(bot);
        if (a.isEmpty()) return;
        if (id != null && !id.isBlank() && idVon(bot).isEmpty()) {
            schreiben(bot.resolve(ID_DATEI), id.strip());
            Log.info("Konten", Sprache.t("Account \"" + a + "\": player ID " + id.strip() + " saved.",
                    "Konto \"" + a + "\": Spieler-ID " + id.strip() + " gespeichert."));
        }
        if (namen != null) {
            List<String> neu = new ArrayList<>(namenVon(bot));
            for (String n : namen) {
                if (!lesbar(n) || namePasst(bot, a, n) || neu.size() >= 10) continue;
                neu.add(n.strip());
            }
            if (neu.size() > namenVon(bot).size()) schreiben(bot.resolve(NAMEN_DATEI), String.join("\n", neu));
        }
        if (bild != null && !bildPasst(bot, bild)) {
            String frei = BILDER.stream().filter(b -> !Files.exists(bot.resolve(b))).findFirst().orElse(BILDER.get(BILDER.size() - 1));
            try {
                Namensbild.speichern(bild, bot.resolve(frei));
            } catch (Exception e) {
                Log.debug("Konten", frei + ": " + e.getMessage());
            }
        }
    }

    /** Alle Bilder (*.png) aus {@code von} nach {@code nach} (Ordner werden angelegt). */
    /** Autor 24.10.: die Listen liegen sichtbar in "My pictures\Arena lists" (safe, doable ...); im Konto-Ordner heissen sie wie bisher. */
    private static Path eigeneListe(Path bot, String l) {
        String praefix = switch (l) {
            case "sicher" -> "ar_sicher_";
            case "machbar" -> "ar_machbar_";
            case "fastsicher" -> "ar_fastsicher_";
            case "pause" -> "ar_pause_";
            case "meiden" -> "ar_ungern_";
            case "niemals" -> "ar_block_";
            default -> "ar_clan_";
        };
        return Eigenbilder.listenOrdner(bot, praefix);
    }

    private static void verschieben(Path von, Path nach) throws java.io.IOException {
        if (!Files.isDirectory(von)) return;
        Files.createDirectories(nach);
        try (Stream<Path> s = Files.list(von)) {
            for (Path p : s.filter(x -> x.getFileName().toString().toLowerCase().endsWith(".png")).toList()) {
                Files.move(p, nach.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void schreiben(Path d, String text) {
        try {
            Files.writeString(d, text + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Konten", d.getFileName() + ": " + e.getMessage());
        }
    }
}
