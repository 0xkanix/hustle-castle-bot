package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * Autor 28.10.: derselbe Arena-Gegner soll nur EINMAL gefuehrt werden, auch wenn die Texterkennung seinen Namen jedes Mal
 * anders liest. "JtNXED", "JkNXED MURENA" und "JINXED MURENA" (Clan "Июльский", Kraft 34,7 bis 35,5 Mio) standen als vier
 * Eintraege in der Sperrliste -- jeder mit 3 Niederlagen, keiner wusste vom anderen.
 *
 * Derselbe Spieler ist (nur mit arena-schicht.txt "an", siehe {@link ArenaSchalter}; bei den Freunden aendert sich nichts),
 * wer
 * (1) dasselbe Namensbild hat -- Autors Idee: "wie eine Vorlage uebereinanderlegen, nur die weisse Schrift vergleichen".
 *     Das ist der Fingerabdruck der {@link Glyphenschrift} (nur die weisse Schrift, der Hintergrund des Schildes zaehlt
 *     nicht); der Clan darf dabei nicht widersprechen (Gegnerbuch.namensbildWiderspricht),
 * (2) auf die Einerstelle dieselbe Kraft hat (ab 1 Mio, keine runden Tausender -- wie bisher), oder
 * (3) dasselbe erste Namenswort hat -- mit den Verwechslern der Texterkennung (t, k, l, 1, | = i; 0 = o; nur Buchstaben
 *     und Ziffern, klein) -- UND eine bekannte Kraft hoechstens 1,5 % daneben. Im selben Clan reichen 3 % (wie sonst im
 *     Notizblock, Gegnerbuch.gleicheKraft): JtNXED 35.501.945 und JkNXED MURENA 34.737.932 liegen 2,2 % auseinander.
 *     Kurze erste Woerter (unter 4 Zeichen) nur mit 1 %. Ist eine Kraft unbekannt, zaehlt nur der ganz gleiche Name
 *     (mit Verwechslern) im selben Clan.
 * Zwei verschiedene Spieler mit aehnlichem Namen, aber anderer Kraft ("Denis" 20 Mio, "Dennis" 31 Mio) bleiben getrennt.
 *
 * Neue Eintraege pruefen das selbst (Gegnerbuch.kanonisch, ArenaSperre.finden). Den Altbestand legt {@link #beimStart()}
 * zusammen -- einmal je Datei-Stand, vorher eine Sicherung beider Dateien, danach ein Bericht in arena-zusammengefuehrt.txt.
 */
public final class ArenaZusammenfuehren {

    private ArenaZusammenfuehren() { }

    /** Erste Namenswoerter unter so vielen Zeichen ("Max") zaehlen nur mit Kraft +-1 %. */
    static final int KURZ = 4;
    /** Welche Namen wohin gewandert sind (neben arena-daten.txt, wird nur ergaenzt). */
    static final String BERICHT = "arena-zusammengefuehrt.txt";
    /** Der Datei-Stand nach dem letzten Zusammenfuehren -- gleicher Stand = nichts zu tun. */
    static final String MARKE = "arena-zusammengefuehrt.marke";
    /** Endung der Sicherungen (arena-daten.txt.vor-zusammenfuehren, arena-sperre.txt.vor-zusammenfuehren). */
    static final String SICHERUNG = ".vor-zusammenfuehren";

    private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ------------------------------------------------------------------ derselbe Spieler?

    /**
     * Derselbe Spieler nach Name, Clan und Kraft (Regeln (2) und (3) oben; das Namensbild pruefen Gegnerbuch und
     * ArenaSperre selbst, sie haben die Glyphen-Bibliothek). Kraft: 0 oder -1 = unbekannt.
     */
    static boolean gleicherSpieler(String nameA, String clanA, long kraftA, String nameB, String clanB, long kraftB) {
        boolean ka = ArenaSperre.kraftGueltig(kraftA), kb = ArenaSperre.kraftGueltig(kraftB);
        if (ka && kb && kraftA == kraftB) return true;
        String wort = erstesWort(nameA);
        if (wort.isEmpty() || !wort.equals(erstesWort(nameB))) return false;
        boolean clan = gleicherClan(clanA, clanB);
        // Pruefung 29.10.: "Lord Rekster / V Not in clan" und "lord / The Legend" (0,52 %) wurden eins -- je Spieler liegen
        // im Schnitt 4,9 andere im +-1,5-%-Band, ein haeufiges erstes Wort ("lord", "dark", "king") reicht nicht. Zwei
        // verschiedene bekannte Clans (auch "ohne Clan" gegen einen echten): nur mit langem ersten Wort (ab 5 Zeichen)
        // und fast gleicher Kraft (0,3 %) -- ein Clanwechsel (JINXED, 0,18 %) bleibt erkennbar.
        boolean andererClan = !clan && clanBekannt(clanA) && clanBekannt(clanB);
        if (andererClan && (!ka || !kb || wort.length() < 5)) return false;
        if (ka && kb) {
            double grenze = andererClan ? 0.003 : wort.length() < KURZ ? 0.01 : clan ? 0.03 : 0.015;
            return Math.abs(kraftA - kraftB) <= Math.max(kraftA, kraftB) * grenze;
        }
        // Eine Kraft unbekannt ("JINXED MURENA" ohne Kraft): nur der ganz gleiche Name im selben Clan.
        String ganz = ohneVerwechsler(nameA);
        return clan && ganz.length() >= KURZ && ganz.equals(ohneVerwechsler(nameB));
    }

    /** Das erste Namenswort ohne die Verwechsler der Texterkennung ("JkNXED MURENA" = "jinxed"), leer = keins. */
    static String erstesWort(String name) {
        if (name == null) return "";
        for (String w : name.strip().split("\\s+")) {
            String z = ohneVerwechsler(w);
            if (!z.isEmpty()) return z;
        }
        return "";
    }

    /** Klein, nur Buchstaben und Ziffern, t/k/l/1/| = i und 0 = o (so verliest die Texterkennung die Zierschrift). */
    static String ohneVerwechsler(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            char c = switch (ch) {
                case 't', 'k', 'l', '1', '|' -> 'i';
                case '0' -> 'o';
                default -> ch;
            };
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    /** Beide ein echter Clan (nicht leer, nicht "ohne Clan") und derselbe, auch mit Lesefehlern. */
    static boolean gleicherClan(String a, String b) {
        if (!echterClan(a) || !echterClan(b)) return false;
        return ArenaSperre.clanGleich(a, b) || NamensVergleich.gleicherClan(a, b);
    }

    /** Clan gelesen -- ein echter oder ausdruecklich "ohne Clan" (leer = nicht gelesen). */
    private static boolean clanBekannt(String c) {
        return c != null && !c.isBlank();
    }

    private static boolean echterClan(String c) {
        return c != null && !c.isBlank() && !Gegnerbuch.keinClan(c) && !c.strip().equalsIgnoreCase(Gegnerbuch.OHNE_CLAN);
    }

    // ------------------------------------------------------------------ Ketten (A=B und B=C: alle drei zusammen)

    /** Die Wurzel der Kette, zu der i gehoert. */
    static int wurzel(int[] kette, int i) {
        while (kette[i] != i) {
            kette[i] = kette[kette[i]];
            i = kette[i];
        }
        return i;
    }

    /** i und j in eine Kette (die kleinere Nummer wird Wurzel -- so bleibt die Reihenfolge der Datei). */
    static void verbinden(int[] kette, int i, int j) {
        int a = wurzel(kette, i), b = wurzel(kette, j);
        if (a == b) return;
        if (a < b) kette[b] = a;
        else kette[a] = b;
    }

    /** Die Ketten als Listen von Nummern, in der Reihenfolge der Datei. */
    static List<List<Integer>> gruppen(int[] kette) {
        Map<Integer, List<Integer>> g = new LinkedHashMap<>();
        for (int i = 0; i < kette.length; i++) g.computeIfAbsent(wurzel(kette, i), k -> new ArrayList<>()).add(i);
        return new ArrayList<>(g.values());
    }

    // ------------------------------------------------------------------ Altbestand zusammenfuehren

    /**
     * Beim Bot-Start: den Altbestand in arena-daten.txt (Notizblock) und arena-sperre.txt (Sperrliste) zusammenfuehren.
     * Nur mit Arena-Schalter; einmal je Datei-Stand; wirft nie (Fehler landen im Protokoll, der Start geht weiter).
     *
     * @return Zahl der zusammengelegten Eintraege (0 = nichts zu tun oder Schalter aus, -1 = Fehler)
     */
    public static int beimStart() {
        return zusammenfuehren(Gegnerbuch.DATEI, Gegnerbuch::gemeinsam);
    }

    /**
     * Wie oben, fuer Tests mit eigenen Dateien: daten = arena-daten.txt, buch = der Notizblock dazu (wird erst NACH der
     * Sicherung geholt -- schon das Laden kann die Datei aendern). Die Sperrliste ist {@link ArenaSperre#datei}.
     */
    static int zusammenfuehren(Path daten, Supplier<Gegnerbuch> buch) {
        try {
            if (!ArenaSchalter.an()) return 0;
            Path sperre = ArenaSperre.datei;
            Path marke = neben(daten, MARKE);
            if (Files.exists(marke) && Dateien.text(marke).strip().equals(stand(daten, sperre))) {
                return 0;   // seit dem letzten Mal unveraendert
            }
            if (!sichern(daten) || !sichern(sperre)) return -1;   // ohne Sicherung wird nichts angefasst
            List<String> imBuch = Files.exists(daten) ? buch.get().gleicheSpielerZusammenlegen() : List.of();
            List<String> inSperre = ArenaSperre.gleicheSpielerZusammenlegen();
            int zahl = imBuch.size() + inSperre.size();
            if (zahl > 0) bericht(neben(daten, BERICHT), daten, sperre, imBuch, inSperre);
            Gegnerbuch.sicherSchreiben(marke, stand(daten, sperre));
            if (zahl > 0) {
                Log.info("Arena", Sprache.t("Same players merged: " + imBuch.size() + " in the notebook, " + inSperre.size()
                                + " in the block list (see " + BERICHT + ").",
                        "Gleiche Spieler zusammengefuehrt: " + imBuch.size() + " im Notizblock, " + inSperre.size()
                                + " in der Sperrliste (siehe " + BERICHT + ")."));
            }
            return zahl;
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Merging same players did not work (the start goes on): ",
                    "Gleiche Spieler zusammenfuehren ging nicht (der Start geht weiter): ") + e.getMessage());
            return -1;
        }
    }

    private static Path neben(Path datei, String name) {
        return datei.toAbsolutePath().resolveSibling(name);
    }

    /** Einmalige Sicherung (nur wenn es noch keine gibt). @return false = Sicherung noetig, aber nicht moeglich */
    private static boolean sichern(Path datei) {
        Path ziel = datei.toAbsolutePath().resolveSibling(datei.getFileName() + SICHERUNG);
        if (!Files.exists(datei) || Files.exists(ziel)) return true;
        try {
            Files.copy(datei, ziel);
            return true;
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("No backup of " + datei.getFileName() + " -- nothing merged: ",
                    "Keine Sicherung von " + datei.getFileName() + " -- nichts zusammengefuehrt: ") + e.getMessage());
            return false;
        }
    }

    /** Pruefsumme und Groesse beider Dateien ("-" = Datei fehlt). */
    static String stand(Path daten, Path sperre) throws java.io.IOException {
        return "daten=" + pruefsumme(daten) + " sperre=" + pruefsumme(sperre);
    }

    private static String pruefsumme(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return "-";
        byte[] b = Files.readAllBytes(p);
        CRC32 crc = new CRC32();
        crc.update(b);
        return Long.toHexString(crc.getValue()) + "/" + b.length;
    }

    private static void bericht(Path ziel, Path daten, Path sperre, List<String> imBuch, List<String> inSperre)
            throws java.io.IOException {
        String nl = System.lineSeparator();
        StringBuilder b = new StringBuilder();
        b.append("# ").append(LocalDateTime.now().format(STEMPEL)).append(" -- ")
                .append(Sprache.t("same players merged (old name -> stays)", "gleiche Spieler zusammengefuehrt (alter Name -> bleibt)"))
                .append(nl);
        if (!imBuch.isEmpty()) {
            b.append(Sprache.t("Notebook", "Notizblock")).append(" (").append(daten.getFileName()).append("):").append(nl);
            for (String z : imBuch) b.append("  ").append(z).append(nl);
        }
        if (!inSperre.isEmpty()) {
            b.append(Sprache.t("Block list", "Sperrliste")).append(" (").append(sperre.getFileName()).append("):").append(nl);
            for (String z : inSperre) b.append("  ").append(z).append(nl);
        }
        b.append(Sprache.t("Backups: ", "Sicherungen: ")).append(daten.getFileName()).append(SICHERUNG).append(", ")
                .append(sperre.getFileName()).append(SICHERUNG).append(nl).append(nl);
        Files.writeString(ziel, b.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
