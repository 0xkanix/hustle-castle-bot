package de.hustlebot.core;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Liest eine Zahl vom Bildschirm ab, zum Beispiel den Ether-Zaehler oben.
 *
 * Der Bot kann von Haus aus keine Schrift lesen. Er kann aber Bilder
 * wiedererkennen – und eine Ziffer ist auch nur ein kleines Bild. Deshalb:
 * fuer jede Ziffer 0 bis 9 gibt es eine Vorlage (digit_0 ... digit_9). Die
 * werden im Zahlenfeld gesucht, von links nach rechts sortiert und
 * aneinandergehaengt. Aus "1", "4", "2", "5", "1", "8" wird 142518.
 *
 * Das Zahlenfeld selbst wird am Symbol davor gefunden (etwa ether_icon),
 * damit nicht der ganze Bildschirm nach Ziffern abgesucht werden muss.
 *
 * Fehlen die Vorlagen, liefert die Klasse einfach nichts zurueck. Der Rest des
 * Bots laeuft dann ganz normal weiter, nur ohne Ether-Zaehlung.
 */
public final class NumberReader {

    /** Namensanfang der Ziffernvorlagen: digit_0, digit_1, ... digit_9. */
    public static final String DIGIT_PREFIX = "digit_";
    /** Die Schluessel der Kathedrale haben eine eigene Schrift, also eigene Ziffern. */
    public static final String KEY_PREFIX = "key_";

    /**
     * Ab dieser Uebereinstimmung gilt eine Ziffer als erkannt. Hoch angesetzt,
     * weil sich 8 und 9 oder 3 und 8 sonst leicht verwechseln.
     */
    private static final double DIGIT_THRESHOLD = 0.86;

    /** Hoechstens so viele Ziffern werden je Vorlage gesucht. */
    private static final int MAX_PER_DIGIT = 12;

    private NumberReader() {
    }

    /** True, wenn alle zehn Ziffernvorlagen vorhanden sind. */
    public static boolean digitsAvailable(Matcher matcher) {
        return digitsAvailable(matcher, DIGIT_PREFIX);
    }

    /** Dasselbe fuer einen anderen Ziffernsatz, etwa key_0 bis key_9. */
    public static boolean digitsAvailable(Matcher matcher, String prefix) {
        for (int i = 0; i <= 9; i++) {
            if (!matcher.has(prefix + i)) return false;
        }
        return true;
    }

    /** Welche Ziffernvorlagen noch fehlen, fuer eine verstaendliche Meldung. */
    public static List<String> missingDigits(Matcher matcher) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i <= 9; i++) {
            if (!matcher.has(DIGIT_PREFIX + i)) missing.add(DIGIT_PREFIX + i);
        }
        return missing;
    }

    /**
     * Liest die Zahl, die rechts neben einem Symbol steht.
     *
     * @param anchor     Name der Symbolvorlage, etwa "ether_icon"
     * @param widthShare wie weit nach rechts gesucht wird, als Anteil der
     *                   Fensterbreite (0.13 sind gut 13 Prozent)
     */
    public static OptionalLong readRightOf(Mat screen, Matcher matcher, String anchor,
                                           double widthShare) {
        return readRightOf(screen, matcher, anchor, widthShare, DIGIT_PREFIX);
    }

    /** Dasselbe mit einem anderen Ziffernsatz. */
    public static OptionalLong readRightOf(Mat screen, Matcher matcher, String anchor,
                                           double widthShare, String prefix) {
        if (!matcher.has(anchor)) return OptionalLong.empty();
        if (!digitsAvailable(matcher, prefix)) return OptionalLong.empty();

        Optional<Matcher.Hit> icon = matcher.find(screen, anchor, 0.82);
        if (icon.isEmpty()) return OptionalLong.empty();

        Matcher.Hit hit = icon.get();

        // Ausschnitt rechts neben dem Symbol. Etwas hoeher als das Symbol,
        // damit die Ziffern auch dann ganz drin sind, wenn sie groesser sind.
        int padY = Math.max(4, hit.height() / 3);
        int x = hit.x() + hit.width();
        int y = Math.max(0, hit.y() - padY);
        int w = (int) (screen.width() * widthShare);
        int h = Math.min(screen.height() - y, hit.height() + 2 * padY);

        w = Math.min(w, screen.width() - x);
        if (w <= 0 || h <= 0) return OptionalLong.empty();

        return readInRegion(screen, matcher, new Rect(x, y, w, h), prefix);
    }

    /**
     * Liest eine Zahl, die IN DER NAEHE eines Symbols steht -- nicht
     * zwangslaeufig rechts davon.
     *
     * Gebraucht wird das fuer die Multiplikator-Anzeige: dort steht die Zahl
     * mal rechts neben dem Text ("Number of journeys: 71"), mal als kleines
     * Schild links oben an der Ecke des Symbols. Ein fester Platz reicht dafuer
     * nicht, wohl aber ein Kasten rundherum.
     *
     * Die vier Werte sagen, wie weit der Kasten ueber das Symbol hinausreicht,
     * jeweils als Vielfaches von dessen Breite bzw. Hoehe. 1.0 nach links heisst
     * also: eine ganze Symbolbreite weiter nach links.
     *
     * Sollte die Zahl einmal daneben liegen, sind das die Zahlen zum
     * Nachstellen -- der gelesene Wert steht im Protokoll.
     */
    public static OptionalLong readAround(Mat screen, Matcher matcher, String anchor,
                                          double left, double right,
                                          double up, double down, String prefix) {
        if (!matcher.has(anchor)) return OptionalLong.empty();
        if (!digitsAvailable(matcher, prefix)) return OptionalLong.empty();

        Optional<Matcher.Hit> icon = matcher.find(screen, anchor, 0.82);
        if (icon.isEmpty()) return OptionalLong.empty();

        Matcher.Hit hit = icon.get();

        int x = (int) Math.round(hit.x() - hit.width() * left);
        int y = (int) Math.round(hit.y() - hit.height() * up);
        int w = (int) Math.round(hit.width() * (1 + left + right));
        int h = (int) Math.round(hit.height() * (1 + up + down));

        // Auf das Bild zurechtstutzen, damit kein Ausschnitt daneben liegt.
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, screen.width() - x);
        h = Math.min(h, screen.height() - y);
        if (w <= 0 || h <= 0) return OptionalLong.empty();

        return readInRegion(screen, matcher, new Rect(x, y, w, h), prefix);
    }

    /**
     * Wie breit ein Ziffernplatz ist -- Ziffer plus der schmale Zwischenraum
     * dahinter, als Vielfaches der Ziffernbreite.
     *
     * Gemessen an den Vorlagen: Die Ziffern der oberen Leiste sind 18 bis 20
     * Pixel breit und stehen praktisch ohne Abstand nebeneinander. Ein Zehntel
     * Luft ist grosszuegig gerechnet.
     */
    private static final double ZIFFERNPLATZ = 1.10;

    /**
     * Ab so vielen freien Ziffernplaetzen gilt eine Luecke nicht mehr als
     * fehlende Ziffer, sondern als Abstand zum naechsten Zaehler.
     *
     * Zwischen zwei Zaehlern der oberen Leiste steht ein ganzes Symbol; das
     * misst mit seinen Raendern rund sechzig Pixel, also drei Ziffernplaetze.
     * Eine oder zwei fehlende Ziffern bleiben darunter.
     */
    private static final int PLAETZE_NEUE_ZAHL = 3;

    /**
     * Zweite Chance fuer eine Ziffer, die im Loch stecken muss.
     *
     * Deutlich lockerer als DIGIT_THRESHOLD, und das ist Absicht: Gesucht wird
     * nur noch in einem winzigen Kaestchen, in dem NACHWEISLICH eine Ziffer
     * steht. Eine Verwechslung ist dort viel unwahrscheinlicher als bei der
     * Suche ueber das ganze Zahlenfeld -- und allemal besser als die Ziffer
     * stillschweigend wegzulassen.
     */
    private static final double LOCH_SCHWELLE = 0.70;

    /**
     * Was beim Zusammensetzen der Ziffern herauskam.
     *
     * @param ziffern  die Ziffern der ersten Zahl, von links nach rechts
     * @param loch     true, wenn mitten in der Zahl eine Ziffer fehlt, die
     *                 auch mit der zweiten Chance nicht zu finden war
     */
    private record Kette(List<Matcher.Hit> ziffern, boolean loch) {
    }

    /**
     * Setzt die erste zusammenhaengende Zahl zusammen -- und merkt, wenn
     * mitten darin eine Ziffer fehlt.
     *
     * WARUM ES DAS BRAUCHT, TEIL EINS. Der Ausschnitt rechts vom Symbol muss
     * breit genug sein, dass eine achtstellige Zahl ganz hineinpasst. Genau
     * daran ist "Particle of Light" gescheitert: Der Ausschnitt war zu schmal,
     * von 6 022 715 blieben die vorderen fuenf Ziffern uebrig, und im Discord
     * stand 60.227 statt sechs Millionen. Breiter machen allein reicht aber
     * nicht -- in der oberen Leiste stehen die Zaehler dicht nebeneinander
     * (Gold, Holz, Aepfel ...). Ein breiter Ausschnitt faengt sonst die Ziffern
     * des Nachbarn mit ein. Deshalb beides: breiter Ausschnitt, und bei der
     * ersten grossen Luecke abschneiden.
     *
     * WARUM ES DAS BRAUCHT, TEIL ZWEI -- und das hat am 6. September richtig
     * wehgetan. Wird eine einzelne Ziffer NICHT wiedererkannt, etwa weil ihre
     * Vorlage schief aufgenommen wurde, dann fiel sie bisher einfach weg und
     * die Nachbarn wurden aneinandergeklebt. Aus 140 Schluesseln wurden so 10.
     * Der Bot hielt sich fuer leer, meldete "alle Durchlaeufe erledigt" und
     * fuhr um halb neun morgens den Rechner herunter -- neuneinhalb Stunden zu
     * frueh.
     *
     * Eine Luecke von der Breite einer Ziffer ist deshalb kein Zufall, sondern
     * eine Meldung: Da steht etwas, das nicht erkannt wurde. Der Leser sucht
     * dort noch einmal nach, mit lockererer Schwelle. Findet er auch dann
     * nichts, gibt er die ganze Zahl auf. Keine Zahl ist besser als eine
     * falsche.
     *
     * @param sortiert die Ziffern, bereits von links nach rechts sortiert
     */
    private static Kette ersteZahl(Mat crop, Matcher matcher, String prefix,
                                   List<Matcher.Hit> sortiert) {
        if (sortiert.size() < 2) return new Kette(sortiert, false);

        double breite = 0;
        for (Matcher.Hit d : sortiert) breite += d.width();
        breite /= sortiert.size();

        double platz = Math.max(1.0, breite * ZIFFERNPLATZ);

        List<Matcher.Hit> ergebnis = new ArrayList<>();
        ergebnis.add(sortiert.get(0));

        for (int i = 1; i < sortiert.size(); i++) {
            Matcher.Hit vorher = sortiert.get(i - 1);
            Matcher.Hit jetzt = sortiert.get(i);

            int luecke = jetzt.x() - (vorher.x() + vorher.width());
            int plaetze = freiePlaetze(luecke, platz);

            // Kein nennenswerter Abstand: Ziffer an Ziffer, oder das schmale
            // Tausenderleerzeichen. Weiter im Text.
            if (plaetze <= 0) {
                ergebnis.add(jetzt);
                continue;
            }

            // So gross, dass ein Symbol dazwischenpasst: Hier faengt der
            // naechste Zaehler an. Die Zahl ist zu Ende.
            if (plaetze >= PLAETZE_NEUE_ZAHL) break;

            // Dazwischen: Da fehlt etwas. Noch einmal genau dort nachsehen.
            List<Matcher.Hit> nachgereicht =
                    lochFuellen(crop, matcher, prefix, vorher, jetzt);

            if (nachgereicht.size() < plaetze) {
                Log.debug("Zahlen", "Luecke von " + luecke + " Pixeln zwischen "
                        + vorher.name() + " und " + jetzt.name() + " -- dort muessten "
                        + plaetze + " Ziffern stehen, gefunden wurden "
                        + nachgereicht.size() + ". Die Zahl wird verworfen.");
                return new Kette(ergebnis, true);
            }

            ergebnis.addAll(nachgereicht);
            ergebnis.add(jetzt);
        }
        return new Kette(ergebnis, false);
    }

    /**
     * Wie viele Ziffern in eine Luecke passen -- grosszuegig zugunsten von
     * "da fehlt nichts".
     *
     * Der Vierteilplatz Zugabe ist kein Bauchgefuehl, sondern die Grenze
     * zwischen den beiden Abstaenden, die es innerhalb einer Zahl gibt:
     *
     *   Ziffer an Ziffer          fast null
     *   Tausenderleerzeichen      etwa ein halber Ziffernplatz ("6 022 715")
     *   -------------------------------------------------- Grenze bei 0,75
     *   eine fehlende Ziffer      ein ganzer Ziffernplatz
     *   Symbol des Nachbarzaehler drei Ziffernplaetze
     *
     * Ohne die Zugabe landete das Tausenderleerzeichen bei 0,52 -- und
     * kaufmaennisch gerundet ergab das eine fehlende Ziffer. Der Leser hat
     * daraufhin jede Zahl ueber tausend verworfen. Aufgefallen ist das nur,
     * weil die alte Probe mit "Particle of Light" noch einmal gelaufen ist.
     */
    private static int freiePlaetze(int luecke, double platz) {
        if (luecke <= 0 || platz <= 0) return 0;
        return Math.max(0, (int) Math.floor(luecke / platz + 0.25));
    }

    /**
     * Sucht die Ziffern, die zwischen zwei erkannten Ziffern fehlen.
     *
     * Das Kaestchen ist absichtlich winzig: genau der Zwischenraum, ein paar
     * Pixel Luft nach links und rechts, und in der Hoehe die Zeile selbst.
     * Weil dort nichts anderes stehen KANN als die fehlende Ziffer, darf die
     * Schwelle niedriger sein als sonst.
     */
    private static List<Matcher.Hit> lochFuellen(Mat crop, Matcher matcher, String prefix,
                                                 Matcher.Hit links, Matcher.Hit rechts) {
        List<Matcher.Hit> gefunden = new ArrayList<>();
        if (crop == null || crop.empty()) return gefunden;

        int rand = 2;
        int x = Math.max(0, links.x() + links.width() - rand);
        int breite = Math.min(crop.width() - x, (rechts.x() + rand) - x);

        int y = Math.max(0, Math.min(links.y(), rechts.y()) - rand);
        int hoehe = Math.min(crop.height() - y,
                Math.max(links.height(), rechts.height()) + 2 * rand);

        if (breite < 4 || hoehe < 4) return gefunden;

        Mat loch = null;
        try {
            loch = new Mat(crop, new Rect(x, y, breite, hoehe));

            List<Matcher.Hit> alle = new ArrayList<>();
            for (int wert = 0; wert <= 9; wert++) {
                for (Matcher.Hit treffer : matcher.findAll(loch, prefix + wert,
                        LOCH_SCHWELLE, 3)) {
                    // Die Fundstellen sind auf das Kaestchen bezogen. Zurueck
                    // auf den Ausschnitt umrechnen, sonst stimmt die
                    // Reihenfolge spaeter nicht.
                    alle.add(new Matcher.Hit(treffer.name(), treffer.x() + x,
                            treffer.y() + y, treffer.width(), treffer.height(),
                            treffer.score()));
                }
            }
            if (alle.isEmpty()) return gefunden;

            // Beste zuerst, dann Ueberschneidungen aussortieren -- genauso wie
            // bei der grossen Suche.
            alle.sort(Comparator.comparingDouble(Matcher.Hit::score).reversed());
            for (Matcher.Hit kandidat : alle) {
                boolean ueberschneidet = false;
                for (Matcher.Hit genommen : gefunden) {
                    int minGap = Math.max(3,
                            Math.min(kandidat.width(), genommen.width()) / 2);
                    if (Math.abs(kandidat.centerX() - genommen.centerX()) < minGap) {
                        ueberschneidet = true;
                        break;
                    }
                }
                if (!ueberschneidet) gefunden.add(kandidat);
            }
            gefunden.sort(Comparator.comparingInt(Matcher.Hit::centerX));
            return gefunden;

        } catch (Exception e) {
            Log.debug("Zahlen", "Luecke konnte nicht nachgeprueft werden: " + e.getMessage());
            return gefunden;
        } finally {
            if (loch != null) loch.release();
        }
    }

    /** Liest die Zahl in einem festen Ausschnitt des Bildes. */
    public static OptionalLong readInRegion(Mat screen, Matcher matcher, Rect region) {
        return readInRegion(screen, matcher, region, DIGIT_PREFIX);
    }

    /** Dasselbe mit einem anderen Ziffernsatz. */
    public static OptionalLong readInRegion(Mat screen, Matcher matcher, Rect region,
                                            String prefix) {
        Mat crop = null;
        try {
            crop = new Mat(screen, region);
            List<Matcher.Hit> digits = new ArrayList<>();

            for (int value = 0; value <= 9; value++) {
                String name = prefix + value;
                for (Matcher.Hit found : matcher.findAll(crop, name,
                        DIGIT_THRESHOLD, MAX_PER_DIGIT)) {
                    digits.add(found);
                }
            }
            if (digits.isEmpty()) return OptionalLong.empty();

            // Beste Treffer zuerst, damit sich bei Ueberschneidungen der
            // sicherere durchsetzt (8 und 9 liegen dicht beieinander).
            digits.sort(Comparator.comparingDouble(Matcher.Hit::score).reversed());

            List<Matcher.Hit> kept = new ArrayList<>();
            for (Matcher.Hit candidate : digits) {
                boolean overlaps = false;
                for (Matcher.Hit taken : kept) {
                    int minGap = Math.max(3, Math.min(candidate.width(), taken.width()) / 2);
                    if (Math.abs(candidate.centerX() - taken.centerX()) < minGap) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) kept.add(candidate);
            }

            // Jetzt von links nach rechts lesen.
            kept.sort(Comparator.comparingInt(Matcher.Hit::centerX));

            // ... aber nur bis zur ersten grossen Luecke, und nur wenn
            // zwischendrin keine Ziffer fehlt.
            Kette kette = ersteZahl(crop, matcher, prefix, kept);
            if (kette.loch()) return OptionalLong.empty();
            kept = kette.ziffern();

            StringBuilder number = new StringBuilder();
            for (Matcher.Hit digit : kept) {
                number.append(digit.name().substring(prefix.length()));
            }

            if (number.length() == 0 || number.length() > 12) return OptionalLong.empty();
            try {
                return OptionalLong.of(Long.parseLong(number.toString()));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        } catch (Exception e) {
            Log.debug("Zahlen", "Zahl konnte nicht gelesen werden: " + e.getMessage());
            return OptionalLong.empty();
        } finally {
            if (crop != null) crop.release();
        }
    }
}
