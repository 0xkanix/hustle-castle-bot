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
 */
public final class Konten {

    private Konten() { }

    /** Diese Dateien gehoeren zu einem Konto. */
    static final List<String> DATEIEN = List.of(
            "arena-ich.txt", "eigenesteam.txt", "arena-notizblock.txt", "arena-daten.txt", "arena-liste.txt",
            "arena-lernen.txt", "arena-aufheben.txt", "arena-punkte-erzielt.txt", "arena-abgestuft.txt",
            "arena-aufnehmen.txt", "arena-verlierer.txt", "arena-plaetze.txt", "bezahlung.txt", "nahrung-heute.txt",
            // Review 28.10.: Arena-Listen sind je Konto getrennt -- dann auch, was Autor dort geloescht hat.
            "arena-entfernt.txt", "arena-auto-bilder.txt", SelbstKaempfe.DATEI);
    /** Bekommt ein neues Konto mit, damit es nicht bei null anfaengt (Bezahlart; Lager und Limits stellt man dann um). */
    static final List<String> MITNEHMEN = List.of("bezahlung.txt");
    /** Die Gegnerlisten (templates\arena\gegner\...) -- je Konto getrennt (Autor 21.10.). */
    static final List<String> LISTEN = List.of("sicher", "fastsicher", "machbar", "meiden", "pause", "niemals", "clans");

    static final String ORDNER = "konten";
    private static final Schalterdatei SCHALTER = new Schalterdatei(Ordner.bei("konten.txt"), "Konten", false);

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
        if (neu == null || neu.isBlank() || ArenaVorlagenDoppelt.derselbeName(alt, neu)) return false;
        try {
            Path altOrdner = bot.resolve(ORDNER).resolve(schluessel(alt.isEmpty() ? "konto" : alt));
            Path neuOrdner = bot.resolve(ORDNER).resolve(schluessel(neu));
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
            // 2. das neue Konto holen (ein neues bekommt die Bezahlart des alten mit)
            for (String f : DATEIEN) {
                Path q = neuOrdner.resolve(f);
                if (Files.exists(q)) Files.move(q, bot.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                else if (neuesKonto && MITNEHMEN.contains(f) && Files.exists(altOrdner.resolve(f))) {
                    Files.copy(altOrdner.resolve(f), bot.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (String l : LISTEN) verschieben(neuOrdner.resolve("gegner").resolve(l), eigeneListe(bot, l));
            schreiben(neuOrdner.resolve("name.txt"), neu.strip());
            schreiben(bot.resolve("konto-aktiv.txt"), neu.strip());
            Log.info("Konten", Sprache.t("Switched to account \"" + neu.strip() + "\"" + (neuesKonto ? " (new -- please do a team scan once)" : "")
                            + ". Its team scan, notebook, opponent lists and payment settings apply now.",
                    "Gewechselt zum Konto \"" + neu.strip() + "\"" + (neuesKonto ? " (neu -- bitte einmal einen Team-Scan machen)" : "")
                            + ". Ab jetzt gelten sein Team-Scan, Notizblock, Gegnerlisten und seine Bezahl-Einstellungen."));
            return true;
        } catch (Exception e) {
            Log.warn("Konten", Sprache.t("Account switch failed: ", "Kontowechsel fehlgeschlagen: ") + e.getMessage());
            return false;
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
