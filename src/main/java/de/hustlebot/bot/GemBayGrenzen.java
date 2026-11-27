package de.hustlebot.bot;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Die Grenzen eines Besuchs in der Edelsteinbucht (Gem Bay) -- ohne Bild und ohne Spiel,
 * damit sie sich pruefen lassen (GemBayGrenzenTest). Jede Frage bekommt die Uhrzeit mit.
 *
 * Aus der Pruefung vom 04.10. (doku\DAUERLAUF-CHECK.md, Autor 05.10.: "so jetzt kannst du den
 * bot starten und die edelsteinbucht testen"):
 *   P1  rotes X mitten im Kampf             -> im Besuch nach einem Kampfzeichen nie ein blindes X,
 *                                              auf dem Heimweg erst 5 Minuten nach dem letzten
 *   P2  37 Klicks auf die Weltkarte in 58 s -> hoechstens 6 ohne Fortschritt
 *   P8  PAUSE ohne Ende nach dem Video      -> nach 90 s normal weiter, ohne Klick in die Werbung;
 *                                              bis die Bucht, Burg oder Weltkarte wieder da ist, kein X
 *   P9  "Yes" ohne Textpruefung             -> nur die Trupp-Warnung, nie mit Preis
 *   45 Minuten je Reise                     -> 15 Minuten je Besuch, danach sauber heim; steigen
 *                                              die Siege, darf die Reise zu Ende laufen (hoechstens 40)
 */
final class GemBayGrenzen {

    /**
     * Hoechstens so lange je Besuch, danach sauber zurueck in die Burg. Autor, 06.10.: "spiel so
     * viel Edelsteinbucht wie nur geht, bis du Diamanten ausgeben musst" -- bis 12 Reisen je Besuch
     * (Lauf 1, 06.10.: eine Reise dauerte 2,6 Minuten), also 45 statt 15 Minuten. Die Bremse gegen
     * Haenger bleibt LEERLAUF_MAX_MS (13 Minuten ohne Fortschritt).
     */
    static final long BESUCH_MAX_MS = 45 * 60_000L;
    /**
     * Steigt die Zahl der Siege ("Victories x/15") noch, darf eine laufende Reise ueber
     * BESUCH_MAX_MS hinaus zu Ende gespielt werden (Pruefung 05.10.: wie lange eine Reise dauert,
     * ist nicht gemessen) -- aber nie laenger als so lange je Besuch.
     */
    static final long BESUCH_HART_MS = 60 * 60_000L;
    /** So lange nach dem letzten neuen Sieg gilt die Reise als "kommt voran". */
    static final long SIEG_RUHE_MS = 5 * 60_000L;
    /**
     * "Start journey" nur, wenn vom Besuch noch so viel Zeit uebrig ist (sonst bezahlt und gleich
     * abgebrochen). Lauf 1 (06.10.): 2,6 Minuten je Reise -> 4 Minuten reichen (vorher 8).
     */
    static final long START_REST_MS = 4 * 60_000L;
    /**
     * Nach "Claim reward" in der Burg: so lange darf das Ablesen der Nahrung dauern, bevor der
     * Besuch aufgegeben wird (Lauf 1, 06.10.: nach 6 s aufgegeben, Reise 2 ausgelassen -- das
     * Belohnungsfenster oder die noch ladende Leiste standen wohl davor).
     */
    static final long NAHRUNG_LESEN_MAX_MS = 10_000L;
    /**
     * Fotozyklus in der Bucht (Autor, 06.10.): so lange nur den Kasten um "Battle begins" ansehen,
     * dann ein ganzes Bild als Sicherheitsblick (Kauf-Fenster, Stoerfenster, Einheit tot).
     */
    static final long FOTOZYKLUS_MS = 2000L;
    /** Abstand zwischen zwei Kastenaufnahmen im Fotozyklus. */
    static final long FOTOZYKLUS_TAKT_MS = 100L;
    /**
     * Autoclicker auf "Battle begins": hoechstens so viele Klicks, Abstand dazwischen. Abstand
     * 300 ms (Pruefung 06.10., vorher 80): das Spiel blendet den gedrueckten Knopf ueber ~150 ms
     * aus, und die Vorlage passt auf den halb durchsichtigen Knopf noch -- nach 80 ms haette der
     * Nachklick ins Kampfbild getroffen. Nach 300 ms ist er weg oder steht noch voll da.
     */
    static final int AUTOKLICK_MAX = 3;
    static final long AUTOKLICK_ABSTAND_MS = 300L;
    /** "Start journey" und "Yes" nie zweimal binnen so vieler Millisekunden (Autor, 06.10.: NIE mehrfach). */
    static final long EINZELKLICK_SPERRE_MS = 3000L;
    /** Im Kampf alle so viele Millisekunden ein ganzes Bild als Sicherheitsblick statt nur der Bucht-Vorlagen. */
    static final long SICHERHEITSBLICK_MS = 2000L;

    /**
     * Breite und Hoehe des Spielraums, aus dem die festen Kaesten stammen (Fotos logs\gembay-*.png
     * aus Lauf 1, 06.10.: 1159x770 = Spielraum 1:1).
     */
    static final int RAUM_BREITE = 1159, RAUM_HOEHE = 770;

    /**
     * Ein fester Kasten {x, y, breite, hoehe} aus dem 1159x770-Raum auf die echte Fenstergroesse
     * umgerechnet (Kastenaufnahme, Autor 06.10.). Breite und Hoehe werden getrennt skaliert, so
     * stimmt es auch bei leicht anderem Seitenverhaeltnis. Null bei unbrauchbarer Fenstergroesse.
     */
    static int[] kasten(int fensterBreite, int fensterHoehe, int[] grund) {
        if (fensterBreite < 200 || fensterHoehe < 200 || grund == null || grund.length < 4) return null;
        double fx = fensterBreite / (double) RAUM_BREITE, fy = fensterHoehe / (double) RAUM_HOEHE;
        int x = (int) Math.round(grund[0] * fx), y = (int) Math.round(grund[1] * fy);
        int b = (int) Math.round(grund[2] * fx), h = (int) Math.round(grund[3] * fy);
        x = Math.max(0, Math.min(x, fensterBreite - 1));
        y = Math.max(0, Math.min(y, fensterHoehe - 1));
        b = Math.min(b, fensterBreite - x);
        h = Math.min(h, fensterHoehe - y);
        if (b < 8 || h < 8) return null;
        return new int[]{x, y, b, h};
    }
    /** Nach dem Ende eines Besuchs hoechstens so lange fuer den Heimweg (Kampf abwarten, X, Weltkarte). */
    static final long HEIMWEG_MAX_MS = 5 * 60_000L;
    /**
     * Der Totmann im Motor haelt nach 15 Minuten ohne Fortschritt an (und dann steht die ganze
     * Warteschlange). Gem Bay beendet den Besuch schon nach so langer Zeit ohne Fortschritt selbst.
     */
    static final long LEERLAUF_MAX_MS = 13 * 60_000L;
    /**
     * So lange nach dem letzten Zeichen aus der Bucht kann auf dem Heimweg noch ein Kampf laufen:
     * kein rotes X. Der Kampf selbst zeigt keine Vorlage der Bucht (Protokoll 30.09., 15:37:44 bis
     * 15:38:52). Ein ganzer Kampf ist nie gemessen worden -- deshalb 5 statt 3 Minuten (Pruefung 05.10.).
     */
    static final long KAMPF_RUHE_MS = 5 * 60_000L;
    /** Hoechstens so viele Klicks auf "Gem Bay" in der Weltkarte, ohne dass ein Gem-Bay-Fenster aufgeht. */
    static final int MAX_KARTEN_KLICKS = 6;
    /** Das Video "Restore all units": nach so langer Zeit nicht mehr nur darauf warten. */
    static final long VIDEO_MAX_MS = 90_000L;
    /**
     * So lange nach dem Video-Start kein rotes X: Es koennte noch Werbung laufen, und in eine
     * Werbung wird nie geklickt (Autor: das kann eine fremde Webseite oeffnen). Eine Werbung
     * dauert 30 bis 61 Sekunden (Autor, siehe Nahrungsvideo).
     */
    static final long VIDEO_SCHUTZ_MS = 180_000L;
    /** Ein Fenster ohne den erwarteten Knopf ("Journey over!" ohne "Claim", Kauf-Fenster ohne "No"). */
    static final long OHNE_KNOPF_MS = 30_000L;
    /** Eine laengere Luecke zwischen zwei Schritten ist eine Pause (F10, !pause): sie zaehlt nicht. */
    static final long PAUSE_LUECKE_MS = 30_000L;

    /** "Noch nie" -- weit genug weg, dass jetzt - NIE nicht ueberlaeuft. */
    private static final long NIE = Long.MIN_VALUE / 4;

    private long besuchSeit, letzterSchritt;
    private long kampfZeichenUm = NIE, videoSeit = NIE, heimwegSeit = NIE, siegUm = NIE;
    private int kartenKlicks;
    /** Zuletzt gelesene Siege ("Victories x/15"), -1 = noch nicht gelesen. */
    private int siege = -1;
    /** Das Video kam nicht zurueck: bis wieder ein Spielbild erkannt ist, kein rotes X (P8). */
    private boolean videoHaengt;

    /** Ein neuer Besuch faengt an: alle Uhren und Zaehler frisch. */
    void besuchBeginnt(long jetzt) {
        besuchSeit = letzterSchritt = jetzt;
        kampfZeichenUm = videoSeit = heimwegSeit = siegUm = NIE;
        kartenKlicks = 0;
        siege = -1;
        videoHaengt = false;
    }

    /** Zu Beginn jedes Schritts. Eine Pause dazwischen verschiebt alle Uhren um ihre Laenge. */
    void schritt(long jetzt) {
        long luecke = jetzt - letzterSchritt;
        if (luecke > PAUSE_LUECKE_MS) {
            besuchSeit += luecke;
            if (kampfZeichenUm != NIE) kampfZeichenUm += luecke;
            if (videoSeit != NIE) videoSeit += luecke;
            if (heimwegSeit != NIE) heimwegSeit += luecke;
            if (siegUm != NIE) siegUm += luecke;
        }
        letzterSchritt = jetzt;
    }

    // --- Zeitgrenze je Besuch und Heimweg ---------------------------------------------

    long besuchMs(long jetzt) {
        return jetzt - besuchSeit;
    }

    /**
     * Ist die Zeit fuer diesen Besuch um? Nach BESUCH_MAX_MS ja -- ausser die Siege steigen noch
     * (Pruefung 05.10.: eine laufende Reise darf zu Ende gespielt werden), aber nie nach BESUCH_HART_MS.
     */
    boolean besuchAbgelaufen(long jetzt) {
        long d = besuchMs(jetzt);
        if (d > BESUCH_HART_MS) return true;
        return d > BESUCH_MAX_MS && !siegeSteigen(jetzt);
    }

    /** Darf noch eine Reise begonnen werden? Nur mit mindestens START_REST_MS Besuchszeit uebrig. */
    boolean startErlaubt(long jetzt) {
        return BESUCH_MAX_MS - besuchMs(jetzt) >= START_REST_MS;
    }

    /** Der Heimweg beginnt (nur beim ersten Aufruf, danach laeuft die Uhr weiter). */
    void heimwegBeginnt(long jetzt) {
        if (heimwegSeit == NIE) heimwegSeit = jetzt;
    }

    boolean heimwegZuLang(long jetzt) {
        return heimwegSeit != NIE && jetzt - heimwegSeit > HEIMWEG_MAX_MS;
    }

    long heimwegMs(long jetzt) {
        return heimwegSeit == NIE ? 0 : jetzt - heimwegSeit;
    }

    // --- Siege ("Victories x/15") ----------------------------------------------------------

    /**
     * Die Siege wurden gelesen. Steigt die Zahl um 1 bis 3, ist das Fortschritt; ein groesserer
     * Sprung ist eher ein Lesefehler. Faellt sie, hat eine neue Reise begonnen.
     */
    void siegeGelesen(int n, long jetzt) {
        if (n < 0 || n > 15) return;
        if (siege >= 0 && n > siege && n - siege <= 3) siegUm = jetzt;
        siege = n;
    }

    boolean siegeSteigen(long jetzt) {
        return siegUm != NIE && jetzt - siegUm < SIEG_RUHE_MS;
    }

    private static final Pattern SIEGE = Pattern.compile("(\\d{1,2})\\s*/\\s*[1l][5s]\\b");

    /** "Victories 3/15" -> 3; -1, wenn keine Zahl "x/15" darin steht. */
    static int siegeAusText(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = SIEGE.matcher(text.toLowerCase(Locale.ROOT));
        if (!m.find()) return -1;
        int n = Integer.parseInt(m.group(1));
        return n <= 15 ? n : -1;
    }

    // --- Kampf und Video: wann darf das rote X gedrueckt werden? ------------------------

    /** Etwas aus der Bucht war zu sehen oder wurde gedrueckt (Victories, Battle begins, To battle!). */
    void kampfZeichen(long jetzt) {
        kampfZeichenUm = jetzt;
    }

    /** Sicher draussen (Burg, Weltkarte, Fenster "Start journey", "Journey over!"). */
    void kampfVorbei() {
        kampfZeichenUm = NIE;
        videoHaengt = false;
    }

    boolean kampfMoeglich(long jetzt) {
        return jetzt - kampfZeichenUm < KAMPF_RUHE_MS;
    }

    void videoGestartet(long jetzt) {
        videoSeit = jetzt;
    }

    long videoMs(long jetzt) {
        return videoSeit == NIE ? 0 : jetzt - videoSeit;
    }

    boolean videoZuLang(long jetzt) {
        return videoSeit != NIE && jetzt - videoSeit > VIDEO_MAX_MS;
    }

    /** Das Video kam nach VIDEO_MAX_MS nicht zurueck: ab jetzt kein X, bis ein Spielbild erkannt ist. */
    void videoHaengt() {
        videoHaengt = true;
    }

    /** Die Bucht ist wieder zu sehen: die Werbung ist sicher vorbei. */
    void werbungVorbei() {
        videoHaengt = false;
    }

    boolean videoHaengtNoch() {
        return videoHaengt;
    }

    /** Koennte noch die Werbung des Videos laufen? */
    boolean videoMoeglich(long jetzt) {
        return videoHaengt || jetzt - videoSeit < VIDEO_SCHUTZ_MS;
    }

    /**
     * Das rote X auf einem unbekannten Bild nur, wenn sicher keine Werbung laeuft und kein Kampf
     * laufen kann (P1). Im laufenden Besuch nach einem Kampfzeichen nie -- dort endet der Besuch
     * ueber die Zeitgrenze. Auf dem Heimweg erst KAMPF_RUHE_MS nach dem letzten Zeichen aus der Bucht.
     */
    boolean xErlaubt(long jetzt, boolean heimweg) {
        if (videoMoeglich(jetzt)) return false;
        if (kampfZeichenUm == NIE) return true;
        return heimweg && !kampfMoeglich(jetzt);
    }

    // --- Weltkarte -----------------------------------------------------------------------

    /** Darf "Gem Bay" auf der Weltkarte noch einmal angeklickt werden? Zaehlt den Klick mit. */
    boolean kartenKlick() {
        if (kartenKlicks >= MAX_KARTEN_KLICKS) return false;
        kartenKlicks++;
        return true;
    }

    int kartenKlicks() {
        return kartenKlicks;
    }

    /** Ein Gem-Bay-Fenster ist aufgegangen: der Zaehler fuer die Weltkarte beginnt neu. */
    void fortschritt() {
        kartenKlicks = 0;
    }

    // --- Ein Fenster, dessen Knopf nicht passt ------------------------------------------------

    /**
     * Wie lange ein Bild schon (fast) ununterbrochen zu sehen ist -- fuer Fenster, deren Knopf
     * nicht passt (Pruefung 05.10.: "Journey over!" ohne "Claim", Kauf-Fenster ohne "No" warteten
     * ohne Grenze). Eine Luecke ueber 5 s faengt neu an.
     */
    static final class Anblick {
        private long seit, zuletzt;

        /** Das Bild ist jetzt zu sehen; liefert, seit wie vielen Millisekunden. */
        long gesehen(long jetzt) {
            if (seit == 0 || jetzt - zuletzt > 5000) seit = jetzt;
            zuletzt = jetzt;
            return jetzt - seit;
        }

        void vergessen() {
            seit = zuletzt = 0;
        }
    }

    // --- Texte ---------------------------------------------------------------------------

    /** Woran die Trupp-Warnung zu erkennen ist. "unlt": so las die Erkennung "units" am 30.09. */
    private static final Pattern TRUPP = Pattern.compile("squad|unit|unlt|battle");
    /** Woran ein Kauf, ein Preis oder eine Werbung zu erkennen ist -- dann nie "Yes". */
    private static final Pattern KAUF = Pattern.compile("buy|purchase|\\bgems?\\b|diamond|\\bgold\\b|\\bcoins?\\b"
            + "|price|\\bcosts?\\b|\\bpay|\\bstore\\b|\\bshop\\b|offer|restore|\\bwatch|video|\\bads?\\b"
            + "|[€$£]|\\busd\\b|\\beur\\b");
    /** "Yes" und "No" auf den Knoepfen, auch so, wie die Erkennung sie verliest ("ye5", "n0"). */
    private static final Pattern JA_NEIN = Pattern.compile("\\b[yv][e3][s5]\\b|\\bn[o0]\\b");

    /**
     * Darf "Yes" gedrueckt werden (P9)? Nur, wenn der Text des Fensters die Trupp-Warnung ist
     * ("Your squad does not have enough units ... Start the battle now?", "You can take more
     * units with you ...") und nirgends ein Preis steht: kein Kauf-Wort, keine Zahl (eine Zahl
     * kann ein Preis in Diamanten oder Gold sein). Erlaubt ist nur "3/5" (Plaetze im Trupp).
     * Lieber einmal zu viel ablehnen -- Kauf-Vorfall 03.10.
     *
     * @param text der Text UEBER den Knoepfen (dieser Bereich wurde am 30.09. sauber gelesen)
     */
    static boolean jaErlaubt(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (!TRUPP.matcher(t).find() || KAUF.matcher(t).find()) return false;
        // Die Ziffern-Regel bleibt streng (Kauf-Vorfall 03.10.). Dass am 08.10. eine "798" aus dem
        // Hintergrund mitgelesen wurde, ist im Lesebereich behoben (GemBayMode.jaText: nur das Fenster).
        String ohneBruch = t.replaceAll("\\d+\\s*/\\s*\\d+", " ");
        return !ohneBruch.matches("(?s).*\\d.*");
    }

    /**
     * Der Text ueber den Knoepfen UND die Knopfreihe (Pruefung 05.10.): die Ziffern-Regel gilt nur
     * oben. In der Knopfreihe liest die Erkennung Buchstaben gern als Ziffern ("ye5 n0") -- dort
     * zaehlen nur Kauf-Woerter, Waehrungszeichen und Zahlen, die nicht "Yes"/"No" sind.
     */
    static boolean jaErlaubt(String oben, String knoepfe) {
        return jaErlaubt(oben) && knopfreiheErlaubt(knoepfe);
    }

    /**
     * Steht in der Knopfreihe ein Preis? Nichts gelesen ist kein Preis (der Knopf selbst passte als
     * "Yes"-Vorlage). Ein Kauf-Wort, ein Waehrungszeichen oder eine Ziffer ausserhalb von "Yes"/"No"
     * ist einer -- dann nie "Yes".
     */
    static boolean knopfreiheErlaubt(String knoepfe) {
        if (knoepfe == null) return true;
        String t = knoepfe.toLowerCase(Locale.ROOT);
        if (KAUF.matcher(t).find()) return false;
        String rest = JA_NEIN.matcher(t).replaceAll(" ");
        return !rest.matches("(?s).*\\d.*");
    }

    /**
     * Vorsichtiger Standard fuer Freunde (Autor, 20.11.): nur kostenlose Reisen ("Free"). Autors Schluessel und
     * wer gembay-mehr.txt = "an" setzt, spielt wie bisher (bis der Preis in Diamanten steht). true = weiterspielen.
     */
    static boolean reiseErlaubt(boolean autor, boolean mehr, PreisArt art) {
        if (art == PreisArt.ANDERER_PREIS) return false;           // Diamanten: nie
        if (autor || mehr) return true;
        return art == PreisArt.FREI;                                // Freunde: nur "Free"; Nahrung/unklar = fertig
    }

    /** Nahrungskisten (Tasche/Clan-Laden) fuer die Gem Bay: nur Autor oder mit gembay-mehr.txt = "an". */
    static boolean kistenErlaubt(boolean autor, boolean mehr) {
        return autor || mehr;
    }

    /** Ist gembay-mehr.txt auf "an"? */
    static boolean mehrAn() {
        try {
            java.nio.file.Path d = java.nio.file.Path.of("gembay-mehr.txt");
            if (!java.nio.file.Files.exists(d)) return false;
            String t = java.nio.file.Files.readString(d).trim().toLowerCase(java.util.Locale.ROOT);
            return t.equals("an") || t.equals("ja") || t.equals("on") || t.equals("true");
        } catch (Exception e) {
            return false;
        }
    }

    /** Was unter "Start journey" steht. */
    enum PreisArt { NAHRUNG, FREI, ANDERER_PREIS, UNKLAR }

    private static final Pattern FREI_WORT = Pattern.compile("\\b(free|gratis|kostenlos)\\b");
    /** "Journey cost resets in: 15h 20m" ist eine Zeit, kein Preis. */
    private static final Pattern DAUER = Pattern.compile("\\d+\\s*[dh]\\b|\\d+\\s*m\\s*\\d+\\s*s\\b");

    /**
     * Der Preis unter "Start journey", aus den gelesenen Zeilen. Mit Apfel: Nahrung. Sonst
     * zaehlt die erste Zeile, die etwas sagt: "Free" = kostenlos, eine Zahl = ein Preis in
     * einer anderen Waehrung -- ohne Bild fuer den Diamanten (gb_preis_diamant fehlt) heisst
     * das: fuer heute sind alle Nahrungs-Reisen gespielt (Autor, 29.09.). Die Aufschrift
     * "Start journey" selbst und die Reset-Zeit werden uebersprungen ("5tart" ist keine Zahl).
     */
    static PreisArt preisArt(List<String> zeilen, boolean apfel) {
        if (apfel) return PreisArt.NAHRUNG;
        if (zeilen == null) return PreisArt.UNKLAR;
        for (String zeile : zeilen) {
            String z = zeile.toLowerCase(Locale.ROOT);
            if (z.contains("start") || z.contains("journey") || z.contains("reset") || DAUER.matcher(z).find()) {
                continue;
            }
            if (FREI_WORT.matcher(z).find()) return PreisArt.FREI;
            if (z.matches(".*\\d.*")) return PreisArt.ANDERER_PREIS;
        }
        return PreisArt.UNKLAR;
    }
}
