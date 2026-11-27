package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Vorlagenordner;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Die eigenen Kaempferbilder (unit_N = Portraet, name_N = Namensschild) SELBST aufnehmen.
 *
 * WARUM (Autor 21.11.): "Wenn er noch keine Bilder fuer diesen Account hat bezueglich den Einheiten, soll er sie machen
 * und dann erst den gewuenschten Modus spielen." Anlass: Robin spielte auf einem anderen Konto, seine alten Bilder
 * passten nicht mehr -- der Bot erkannte nie "Truppe steht" und drueckte endlos "Deploy the best".
 *
 * WANN. Auf dem Truppenbildschirm (Kathedrale und Verlies, Zeile "Drag and drop units ..." sichtbar), hoechstens einmal je
 * Modus-Start und VOR "Deploy the best":
 *  (a) es gibt keins der Bilder unit_1 bis unit_4 -> aufnehmen;
 *  (b) es gibt welche, aber auf keiner Karte passt ein Portraet (unit_*) -> passt auch kein Name, gehoeren sie zu einem
 *      anderen Konto; passen nur noch Namen, sind die Skins neu (dann behalten die Einheiten ihre Nummern). Die alten
 *      nach templates-aussortiert\kaempferbilder-&lt;Datum-Zeit&gt;\ VERSCHIEBEN (nie loeschen, nie ueberschreiben), neu
 *      aufnehmen;
 *  (c) die Portraets passen, nur bei einzelnen Einheiten nicht mehr (Name ja, Portraet nein: neuer Skin) -> nur deren
 *      unit_N neu (siehe {@link #abgleichen}).
 * Erst, wenn die Karten auf zwei Bildern gleich dastehen (das Fenster blendet sonst noch ein); (a) und (b) nur, wenn alle
 * Einheiten leben (eine gefallene ist ausgegraut -- so ein Portraet passt nie wieder), (c) nie fuer eine gefallene.
 * Klappt es nicht: Protokoll, normal weiter.
 *
 * WIE die Karten gefunden werden: am dunklen Rahmen ihrer Lebensleiste (gruen = lebt, rot = gefallen; der Rahmen ist
 * immer gleich). Die Reihe ist zentriert und mal 2, mal 6 Karten lang -- feste Stellen taugen nicht. Von der Leiste aus
 * liegen Portraet, Klassen-Abzeichen und Name immer gleich weit oben. GEMESSEN 21.11. an Fotos der Kathedrale
 * (Spielflaeche 1159 x 770, t006/t008/t022 aus pc-truppe2): Rahmen 67-69 breit und 14 hoch (Oberkante y 559 unten in der
 * Ablage, y 322 oben in der Truppe), Karten 112 auseinander, Abzeichen-Mitte 35 ueber der Oberkante, Namensschrift 16
 * bis 7 darueber. Autors eigene Bilder (unit_1..6 etwa 88 x 69, name_1..6 etwa 60 x 18) liegen genau so: Portraet 87 bis
 * 18 ueber der Oberkante, mittig.
 *
 * REIHENFOLGE: Fernkaempfer (Magier, Bogen) zuerst, dann der Rest (Schild und Unbekanntes). Innerhalb davon erst die
 * Karten oben in der Truppe (die hat das Spiel selbst ausgewaehlt), dann die Ablage, jeweils von links. So steht es auch
 * bei Autor: unit_1 bis unit_4 sind Magier und Bogenschuetzen, unit_5 ist der Tank -- und genau die vier ersten belebt die
 * Kathedrale wieder (REVIVE_ORDER).
 */
public final class Kaempferbilder {

    /** Klasse am Abzeichen unten am Portraet. */
    public enum Klasse {
        MAGIER, BOGEN, TANK, UNBEKANNT;

        /** Fernkaempfer kommen zuerst (Autor: Magier und Bogis sind seine Kaempfer 1 bis 4). */
        public boolean fern() {
            return this == MAGIER || this == BOGEN;
        }

        String de() {
            return switch (this) {
                case MAGIER -> "Magier";
                case BOGEN -> "Bogen";
                case TANK -> "Schild";
                default -> "?";
            };
        }

        String en() {
            return switch (this) {
                case MAGIER -> "mage";
                case BOGEN -> "archer";
                case TANK -> "tank";
                default -> "?";
            };
        }
    }

    /**
     * Eine Einheitenkarte. {@code leisteOben}/{@code leisteUnten}: Ober- und Unterkante des Leistenrahmens;
     * {@code name} ist null, wenn unter dem Portraet keine Schrift zu finden war.
     */
    public record Karte(int mitteX, int leisteOben, int leisteUnten, boolean lebt, Rect portraet, Rect name,
                        Klasse klasse, double klasseWert) {
    }

    /** Was auf diesem Truppenbildschirm zu tun ist. */
    public enum Schritt {
        /** Keine Karten zu sehen oder eine Einheit ist gefallen -- auf einem spaeteren Truppenbildschirm wieder. */
        SPAETER,
        /** Die vorhandenen Bilder passen zu diesem Konto -- fertig fuer diesen Modus-Start. */
        PASST,
        /** Noch ein zweites Bild abwarten (blendet das Fenster noch ein?). */
        NOCHMAL_SEHEN,
        /** Keine eigenen Bilder da: aufnehmen. */
        AUFNEHMEN,
        /** Bilder da, passen aber nicht: wegraeumen und neu aufnehmen. */
        ERSETZEN,
        /** Bilder passen, nur bei einzelnen Einheiten nicht mehr das Portraet (neuer Skin): nur diese unit_N neu. */
        SKINS
    }

    /** Was die vorhandenen Bilder auf diesem Truppenbildschirm wiedererkennen. */
    public enum Abgleich {
        /** Portraets erkannt, keine Einheit mit neuem Skin. */
        PASST,
        /** Portraets erkannt, aber bei einzelnen Einheiten passt nur noch der Name (Skin gewechselt). */
        EINZELNE_SKINS,
        /** Kein Portraet, aber Namen erkannt: alle Skins neu (oder das Spiel zeichnet die Karten anders). */
        NUR_NAMEN,
        /** Nichts erkannt: die Bilder gehoeren zu einem anderen Konto. */
        NICHTS
    }

    /** Wert einer Vorlage in einem Bereich: ab ihrer Schwelle der Wert, sonst 0 (auch wenn es die Vorlage nicht gibt). */
    @FunctionalInterface
    interface Wertung {
        double wert(String vorlage, Rect bereich);
    }

    /**
     * Ergebnis des Abgleichs je Karte (gleiche Reihenfolge wie die Karten): portraetNr = welches unit_N auf dem Portraet
     * passt, nameNr = welches name_N am besten auf den Namen passt (nur bei Karten ohne erkanntes Portraet gesucht), 0 =
     * keins. skins: Karten-Index -&gt; Nummer, deren Portraet neu aufzunehmen ist.
     */
    record Befund(Abgleich abgleich, int[] portraetNr, int[] nameNr, Map<Integer, Integer> skins) {
    }

    // ---- Masse in der Spielflaeche 1159 x 770 (siehe oben), werden mit der Bildhoehe umgerechnet ----
    private static final int LEISTE_MIN = 58, LEISTE_MAX = 80;          // Rahmenbreite 67-69
    private static final int LEISTE_HOCH_MIN = 10, LEISTE_HOCH_MAX = 17; // Rahmen 13-14 hoch
    private static final int ZUSAMMEN_X = 4, ZUSAMMEN_Y = 18;
    private static final int ABZEICHEN_UEBER = 35, ABZEICHEN_FENSTER = 14, ABZEICHEN_MIN = 100;
    private static final int PORTRAET_B = 88, PORTRAET_H = 69, PORTRAET_UEBER = 87;
    private static final int NAME_FENSTER_X = 54, NAME_FENSTER_UEBER = 21, NAME_RAND = 3;
    /** Suchkaesten fuer den Abgleich: Portraet plus Luft; Namenszeile (Karten stehen 112 auseinander, also hoechstens +-62). */
    private static final int PORTRAET_LUFT = 8, NAME_SUCHE_X = 62, NAME_SUCHE_UEBER = 28;
    /** Ab hier gilt ein Klassen-Zeichen als erkannt (an Autors Bildern: richtige Klasse 0,96-1,0, falsche hoechstens 0,38). */
    static final double KLASSE_AB = 0.75;
    /** So gleich muessen zwei Namen sein, damit es dieselbe Einheit ist (Klon "Ally in Reflection" im Lager). */
    private static final double GLEICHER_NAME_AB = 0.93;
    /** "Passt": wie squadDeployed() (0,82); Namen etwas milder. Fremde Bilder lagen am 21.11. bei hoechstens 0,74 / 0,67. */
    static final double UNIT_PASST_AB = 0.82, NAME_PASST_AB = 0.80;
    /** So viele Plaetze kennt der Bot (DungeonMode.PRIORITY_UNITS). */
    static final int HOECHSTENS = 6;

    /** Hoechstens so viele Truppenbildschirme je Modus-Start ansehen -- dann aufgeben, nie in einer Schleife haengen. */
    private static final int MAX_BLICKE = 12;
    /** Zwischen den zwei Bildern (Fenster blendet ein) so lange warten. */
    private static final long STABIL_WARTEN_MS = 450;
    private static final long STABIL_MIN_MS = 250;
    private static final long STABIL_MAX_MS = 8000;

    private static final Pattern KAEMPFERBILD = Pattern.compile("(?i)(unit|name)_[1-6]\\.png");

    private final String bereich;
    /** Nur fuer Tests: statt des Bot-Ordners (templates-aussortiert, gelernt) ein Wegwerf-Ordner. null = Bot-Ordner. */
    private final Path basisFuerTest;
    private boolean fertig;
    private int blicke;
    private int[][] ersteLage;
    private long ersteLageUm;

    /** @param bereich fuer das Protokoll, z. B. "Kathedrale" oder "Verlies" */
    public Kaempferbilder(String bereich) {
        this(bereich, null);
    }

    /** Fuer Tests: alles Wegraeumen und Lernordner unter {@code basisFuerTest} statt im Bot-Ordner. */
    Kaempferbilder(String bereich, Path basisFuerTest) {
        this.bereich = bereich;
        this.basisFuerTest = basisFuerTest;
    }

    /** Beim Modus-Start: wieder einmal pruefen duerfen. */
    public void neuerStart() {
        fertig = false;
        blicke = 0;
        ersteLage = null;
        ersteLageUm = 0;
    }

    // =====================================================================================================================
    //  Im Modus
    // =====================================================================================================================

    /**
     * Auf dem Truppenbildschirm aufrufen, BEVOR aufgestellt wird. Liefert WAITING, wenn noch ein zweites Bild gebraucht
     * wird, sonst null (normal weiterspielen -- auch nach einer Aufnahme und bei jedem Fehler).
     */
    public GameMode.Outcome pruefen(Mat screen, BotContext ctx) {
        // Der normale Weg (Bilder passen) kostet nur beim ersten Truppenbildschirm je Modus-Start etwas, danach
        // nichts mehr: "fertig" wird vor allem anderen geprueft (Kathedrale soll 65 s je Run schaffen).
        if (fertig) return null;
        return pruefen(screen, ctx.matcher(), ctx::sleep);
    }

    /** Wie {@link #pruefen(Mat, BotContext)}, ohne Spielfenster pruefbar: {@code schlafen} wartet die Millisekunden. */
    GameMode.Outcome pruefen(Mat screen, Matcher m, LongConsumer schlafen) {
        if (fertig || screen == null || screen.empty()) return null;
        if (++blicke > MAX_BLICKE) {
            fertig = true;
            Log.info(bereich, Sprache.t("Fighter pictures: no usable squad screen (all units alive, cards still) after "
                            + MAX_BLICKE + " looks -- playing on without new pictures.",
                    "Kaempferbilder: nach " + MAX_BLICKE + " Blicken kein brauchbarer Truppenbildschirm (alle leben, Karten "
                            + "ruhig) -- es wird ohne neue Bilder weitergespielt."));
            return null;
        }
        try {
            long los = System.nanoTime();
            List<Karte> karten = kartenFinden(screen);
            boolean da = bilderDa(m);
            Befund befund = da && !karten.isEmpty() ? abgleichen(screen, karten, (name, r) -> wert(m, screen, name, r)) : null;
            Abgleich abgleich = befund == null ? Abgleich.NICHTS : befund.abgleich();
            boolean alleLeben = karten.stream().allMatch(Karte::lebt);
            int[][] lage = lage(screen, karten);
            long jetzt = System.currentTimeMillis();
            boolean stabil = ersteLage != null && gleicheLage(ersteLage, lage)
                    && jetzt - ersteLageUm >= STABIL_MIN_MS && jetzt - ersteLageUm <= STABIL_MAX_MS;

            Schritt schritt = schritt(karten.size(), alleLeben, da, abgleich, stabil);
            switch (schritt) {
                case PASST -> {
                    fertig = true;
                    // Messung fuer das Tempo der Kathedrale: so viel kostet die Pruefung einmal je Modus-Start.
                    Log.debug(bereich, "Kaempferbilder passen zu diesem Konto (" + karten.size() + " Karten, "
                            + (System.nanoTime() - los) / 1_000_000 + " ms).");
                    return null;
                }
                case SPAETER -> {
                    ersteLage = null;
                    return null;
                }
                case NOCHMAL_SEHEN -> {
                    if (ersteLage == null || !gleicheLage(ersteLage, lage) || jetzt - ersteLageUm > STABIL_MAX_MS) {
                        ersteLage = lage;
                        ersteLageUm = jetzt;
                    }
                    schlafen.accept(STABIL_WARTEN_MS);
                    return GameMode.Outcome.WAITING;
                }
                case SKINS -> {
                    fertig = true;   // egal wie es ausgeht: einmal je Modus-Start
                    skinsUndMelden(screen, m, karten, befund.skins());
                    return null;
                }
                default -> {
                    fertig = true;   // egal wie es ausgeht: einmal je Modus-Start
                    // Alle Skins neu (nur die Namen passen noch): die Nummern der Namen behalten -- Autors Reihenfolge
                    // (wer zuerst wiederbelebt wird) bleibt so erhalten.
                    int[] nummernAusNamen = befund != null && befund.abgleich() == Abgleich.NUR_NAMEN ? befund.nameNr() : null;
                    aufnehmenUndMelden(screen, m, karten, nummernAusNamen, schritt == Schritt.ERSETZEN);
                    return null;
                }
            }
        } catch (Exception e) {
            fertig = true;
            Log.warn(bereich, Sprache.t("Fighter pictures could not be taken: ", "Kaempferbilder liessen sich nicht aufnehmen: ")
                    + e.getMessage());
            return null;
        }
    }

    /**
     * Hat dieses Konto schon eigene Kaempferbilder (eins von unit_1 bis unit_4)? Genau die vier braucht die Kathedrale
     * (REVIVE_ORDER, squadDeployed). Autor: "wenn er noch KEINE Bilder fuer diesen Account hat".
     *
     * Bewusst NICHT core.Konten.brauchtEinheitenBilder(): das sagt schon "braucht", wenn nur eins von unit_1..4 fehlt --
     * ein Konto mit nur drei Einheiten wuerde dann bei jedem Modus-Start neu aufgenommen (und Autor bekaeme jedes Mal
     * einen Bericht). Fehlen nur einzelne, entscheidet hier das Vergleichen (passen die vorhandenen, bleibt alles).
     * Soll es doch die Konten-Abfrage sein: nur diese Methode aendern.
     */
    static boolean bilderDa(Matcher m) {
        for (String name : DungeonMode.bilderKaempfer().subList(0, 4)) {
            if (m.has(name)) return true;
        }
        return false;
    }

    /** Ueberall, woher der Matcher Bilder laedt (templates, Lernordner, "My pictures"). */
    private List<Path> wurzeln(Path templates) {
        // Sonst bliebe ein altes name_2 irgendwo liegen und stuende neben dem neuen (bei gleichem Namen gilt die neuere).
        List<Path> wurzeln = new ArrayList<>();
        wurzeln.add(templates);
        Path gelernt = basisFuerTest != null ? basisFuerTest.resolve(Matcher.GELERNT) : Matcher.GELERNT;
        if (Files.isDirectory(gelernt)) wurzeln.add(gelernt);
        if (basisFuerTest == null && Matcher.EIGENE_BILDER != null && Files.isDirectory(Matcher.EIGENE_BILDER)) {
            wurzeln.add(Matcher.EIGENE_BILDER);
        }
        return wurzeln;
    }

    /** templates-aussortiert\kaempferbilder-&lt;Datum-Zeit&gt; -- jedes Mal ein neuer Ordner, nie ueberschreiben. */
    private Path wegOrdner() {
        Path basis = basisFuerTest != null ? basisFuerTest : Ordner.basis();
        return basis.resolve("templates-aussortiert").resolve("kaempferbilder-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
    }

    private void aufnehmenUndMelden(Mat screen, Matcher m, List<Karte> karten, int[] nummernAusNamen, boolean ersetzen)
            throws Exception {
        Path templates = m.templateRoot();
        Path ziel = templates.resolve(Vorlagenordner.fuer("unit_1"));   // templates\dungeon\einheiten
        Path weg = wegOrdner();

        Ergebnis e = aufnehmen(screen, karten, nummernAusNamen, ziel, wurzeln(templates), weg);
        if (e.reihe().isEmpty()) {
            Log.info(bereich, Sprache.t("Fighter pictures: no usable card found -- playing on without new pictures.",
                    "Kaempferbilder: keine brauchbare Karte gefunden -- es wird ohne neue Bilder weitergespielt."));
            return;
        }
        m.loadAll();   // die neuen Bilder gleich kennen, die weggeraeumten vergessen

        String grundEn = nummernAusNamen != null
                ? "only the names still matched, the portraits not -- new skins? Numbers kept, old ones moved to " + weg.getFileName()
                : ersetzen ? "the old ones did not match this account (moved to " + weg.getFileName() + ")"
                : "there were none for this account";
        String grundDe = nummernAusNamen != null
                ? "nur die Namen passten noch, die Portraets nicht -- neue Skins? Nummern behalten, alte verschoben nach "
                        + weg.getFileName()
                : ersetzen ? "die alten passten nicht zu diesem Konto (verschoben nach " + weg.getFileName() + ")"
                : "fuer dieses Konto gab es noch keine";
        String text = Sprache.t("Fighter pictures taken by the bot (" + grundEn + "): " + e.beschreibung(false)
                        + ". The order (ranged first) decides who is revived first.",
                "Kaempferbilder selbst aufgenommen (" + grundDe + "): " + e.beschreibung(true)
                        + ". Die Reihenfolge (Fernkaempfer zuerst) bestimmt, wer zuerst wiederbelebt wird.");
        Log.info(bereich, text);
        BotEngine.berichtMelden(text, e.bild());
    }

    /** Einzelne Einheiten mit neuem Skin: nur deren Portraet (unit_N) neu, Name und Nummer bleiben. */
    private void skinsUndMelden(Mat screen, Matcher m, List<Karte> karten, Map<Integer, Integer> skins) throws Exception {
        Path templates = m.templateRoot();
        Path weg = wegOrdner();
        Ergebnis e = skinsErneuern(screen, karten, skins, templates.resolve(Vorlagenordner.fuer("unit_1")),
                wurzeln(templates), weg);
        if (e.reihe().isEmpty()) return;
        m.loadAll();
        String text = Sprache.t("Fighter pictures: new skin -- portrait renewed for " + e.beschreibung(false)
                        + " (the name still matched, the old portrait not; old one moved to " + weg.getFileName() + ").",
                "Kaempferbilder: neuer Skin -- Portraet erneuert fuer " + e.beschreibung(true)
                        + " (der Name passte noch, das alte Portraet nicht mehr; altes verschoben nach " + weg.getFileName() + ").");
        Log.info(bereich, text);
        BotEngine.berichtMelden(text, e.bild());
    }

    /** Wert einer Vorlage im Bereich ab ihrer Schwelle (wie die Modi: Graustufen in Kathedrale/Verlies), sonst 0. */
    private static double wert(Matcher m, Mat screen, String name, Rect bereich) {
        if (!m.has(name)) return 0;
        return m.findImBereich(screen, name, bereich, name.startsWith("name_") ? NAME_PASST_AB : UNIT_PASST_AB)
                .map(Matcher.Hit::score).orElse(0.0);
    }

    // =====================================================================================================================
    //  Entscheidung (rein, pruefbar)
    // =====================================================================================================================

    /**
     * @param karten    wie viele Einheitenkarten zu sehen sind
     * @param alleLeben keine davon gefallen
     * @param bilderDa  eigene Bilder vorhanden ({@link #bilderDa})
     * @param abgleich  was die vorhandenen Bilder auf diesem Bildschirm erkennen ({@link #abgleichen})
     * @param stabil    dieselben Karten schon auf dem vorigen Bild
     */
    static Schritt schritt(int karten, boolean alleLeben, boolean bilderDa, Abgleich abgleich, boolean stabil) {
        if (karten <= 0) return Schritt.SPAETER;
        if (bilderDa && abgleich == Abgleich.PASST) return Schritt.PASST;
        // Einzelne Skins betreffen nur lebende Karten (abgleichen() laesst gefallene aus) -- eine gefallene andere stoert
        // nicht. Alles neu aufnehmen dagegen nur, wenn alle leben: ein ausgegrautes Portraet passt nie wieder.
        boolean einzelne = bilderDa && abgleich == Abgleich.EINZELNE_SKINS;
        if (!einzelne && !alleLeben) return Schritt.SPAETER;
        if (!stabil) return Schritt.NOCHMAL_SEHEN;
        if (einzelne) return Schritt.SKINS;
        return bilderDa ? Schritt.ERSETZEN : Schritt.AUFNEHMEN;
    }

    /**
     * Welche Karte erkennen die vorhandenen Bilder -- am Portraet (unit_N) oder nur noch am Namen (name_N)?
     *
     * WARUM je Karte und nicht "irgendein Bild passt irgendwo": Autor nannte ausdruecklich auch "Skins geaendert". Dann
     * passt der Name noch, das Portraet nicht mehr -- mit "irgendwas passt" wuerde nie neu aufgenommen. Gesucht wird je
     * Karte nur im eigenen kleinen Kasten (Portraet bzw. Namenszeile) und beim ersten Treffer aufgehoert -- so bleibt es
     * schnell (gemessen im Test tempoNormalerWeg).
     *
     * Einzelner neuer Skin (Karte i, Nummer N) nur, wenn: die Karte lebt, ihr Portraet kein unit_* trifft, ihr Name am
     * besten name_N trifft, keine andere Karte name_N traegt und unit_N auf KEINER Karte steht. Das letzte schuetzt Autors
     * Namen "Magier 1/2/3": eine Ersatzeinheit "Magier 4" ohne eigene Bilder trifft vielleicht name_1 -- aber Magier 1
     * steht mit seinem Portraet daneben, also bleibt unit_1, wie es ist.
     */
    static Befund abgleichen(Mat screen, List<Karte> karten, Wertung wertung) {
        int n = karten.size();
        int[] portraetNr = new int[n], nameNr = new int[n];
        double s = massstab(screen);
        int erkannt = 0, namen = 0;
        for (int i = 0; i < n; i++) {
            Karte k = karten.get(i);
            Rect pb = schneiden(weiter(k.portraet(), m(PORTRAET_LUFT, s)), screen);
            for (int nr = 1; nr <= HOECHSTENS && pb != null; nr++) {
                if (wertung.wert("unit_" + nr, pb) > 0) {
                    portraetNr[i] = nr;
                    erkannt++;
                    break;
                }
            }
            if (portraetNr[i] > 0) continue;
            Rect nb = nameBereich(screen, k, s);
            double bester = 0;
            for (int nr = 1; nr <= HOECHSTENS && nb != null; nr++) {
                double w = wertung.wert("name_" + nr, nb);
                if (w > bester) {
                    bester = w;
                    nameNr[i] = nr;
                }
            }
            if (nameNr[i] > 0) namen++;
        }
        if (erkannt == 0) return new Befund(namen > 0 ? Abgleich.NUR_NAMEN : Abgleich.NICHTS, portraetNr, nameNr, Map.of());

        Map<Integer, Integer> skins = new LinkedHashMap<>();
        Rect alle = huelle(screen, karten);
        for (int i = 0; i < n; i++) {
            int nr = nameNr[i];
            Karte k = karten.get(i);
            if (nr == 0 || !k.lebt() || k.name() == null) continue;
            if (anzahl(nameNr, nr) > 1 || anzahl(portraetNr, nr) > 0) continue;
            if (alle != null && wertung.wert("unit_" + nr, alle) > 0) continue;   // unit_N steht doch irgendwo
            skins.put(i, nr);
        }
        return new Befund(skins.isEmpty() ? Abgleich.PASST : Abgleich.EINZELNE_SKINS, portraetNr, nameNr, skins);
    }

    private static int anzahl(int[] werte, int wert) {
        int n = 0;
        for (int w : werte) if (w == wert) n++;
        return n;
    }

    private static Rect weiter(Rect r, int um) {
        return new Rect(r.x - um, r.y - um, r.width + 2 * um, r.height + 2 * um);
    }

    /** Die Namenszeile einer Karte (zwischen Abzeichen und Leiste), breit genug fuer Autors name_N (etwa 60 x 18). */
    private static Rect nameBereich(Mat screen, Karte k, double s) {
        return schneiden(new Rect(k.mitteX() - m(NAME_SUCHE_X, s), k.leisteOben() - m(NAME_SUCHE_UEBER, s),
                2 * m(NAME_SUCHE_X, s), m(NAME_SUCHE_UEBER, s)), screen);
    }

    /** Alle Portraets und Namen der Karten, mit etwas Luft. */
    private static Rect huelle(Mat screen, List<Karte> karten) {
        if (karten.isEmpty()) return null;
        int rand = (int) Math.round(30 * massstab(screen));
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = 0, y1 = 0;
        for (Karte k : karten) {
            Rect p = k.portraet();
            x0 = Math.min(x0, p.x);
            y0 = Math.min(y0, p.y);
            x1 = Math.max(x1, p.x + p.width);
            y1 = Math.max(y1, k.leisteOben());
        }
        x0 = Math.max(0, x0 - rand);
        y0 = Math.max(0, y0 - rand);
        x1 = Math.min(screen.cols(), x1 + rand);
        y1 = Math.min(screen.rows(), y1 + rand);
        return x1 > x0 && y1 > y0 ? new Rect(x0, y0, x1 - x0, y1 - y0) : null;
    }

    /** Fingerabdruck je Karte: Lage und mittlere Farbe des Portraets -- fuer "steht still" zwischen zwei Bildern. */
    static int[][] lage(Mat screen, List<Karte> karten) {
        int[][] l = new int[karten.size()][];
        for (int i = 0; i < karten.size(); i++) {
            Karte k = karten.get(i);
            Mat teil = new Mat(screen, k.portraet());
            Scalar mittel = Core.mean(teil);
            teil.release();
            l[i] = new int[]{k.mitteX(), k.leisteOben(), (int) mittel.val[0], (int) mittel.val[1], (int) mittel.val[2]};
        }
        return l;
    }

    static boolean gleicheLage(int[][] a, int[][] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i][0] - b[i][0]) > 2 || Math.abs(a[i][1] - b[i][1]) > 2) return false;
            for (int c = 2; c < 5; c++) if (Math.abs(a[i][c] - b[i][c]) > 6) return false;
        }
        return true;
    }

    // =====================================================================================================================
    //  Karten finden (rein, pruefbar)
    // =====================================================================================================================

    private static double massstab(Mat bild) {
        return bild.rows() / 770.0;
    }

    private static int m(double wert, double s) {
        return (int) Math.round(wert * s);
    }

    /** Dunkles Blaugruen des Leistenrahmens (und des Klassen-Abzeichens): gemessen RGB 10/97/127. */
    private static boolean rahmen(int b, int g, int r) {
        return r < 60 && g >= 60 && g <= 130 && b >= 90 && b <= 170 && b > g + 10;
    }

    private static boolean gruen(int b, int g, int r) {
        return g > 140 && g > r + 50 && g > b + 50;
    }

    /**
     * Alle Einheitenkarten im Bild (Truppe oben und Ablage unten), sortiert nach Reihe und dann von links.
     * Das Bild ist die Spielflaeche (BGR), wie der Bot sie sieht.
     */
    public static List<Karte> kartenFinden(Mat bild) {
        List<Karte> karten = new ArrayList<>();
        if (bild == null || bild.empty() || bild.channels() < 3 || bild.cols() < 200 || bild.rows() < 200) return karten;
        Mat dicht = bild.isContinuous() ? bild : bild.clone();
        int w = dicht.cols(), h = dicht.rows(), ch = dicht.channels();
        byte[] px = new byte[w * h * ch];
        dicht.get(0, 0, px);
        if (dicht != bild) dicht.release();
        double s = massstab(bild);

        // 1. Waagrechte Laeufe in Rahmenfarbe, so breit wie eine Leiste
        int lmin = m(LEISTE_MIN, s), lmax = m(LEISTE_MAX, s);
        List<int[]> laeufe = new ArrayList<>();   // {y, von, bis}
        for (int y = 0; y < h; y++) {
            int start = -1;
            int zeile = y * w * ch;
            for (int x = 0; x <= w; x++) {
                boolean an = false;
                if (x < w) {
                    int i = zeile + x * ch;
                    an = rahmen(px[i] & 0xff, px[i + 1] & 0xff, px[i + 2] & 0xff);
                }
                if (an && start < 0) start = x;
                else if (!an && start >= 0) {
                    int lang = x - start;
                    if (lang >= lmin && lang <= lmax) laeufe.add(new int[]{y, start, x - 1});
                    start = -1;
                }
            }
        }

        // 2. Ober- und Unterkante desselben Rahmens zusammenfassen
        boolean[] benutzt = new boolean[laeufe.size()];
        for (int i = 0; i < laeufe.size(); i++) {
            if (benutzt[i]) continue;
            benutzt[i] = true;
            int[] a = laeufe.get(i);
            double mitte = (a[1] + a[2]) / 2.0;
            int yMin = a[0], yMax = a[0], links = a[1], rechts = a[2];
            double summe = mitte;
            int n = 1;
            for (int j = i + 1; j < laeufe.size(); j++) {
                int[] b = laeufe.get(j);
                if (b[0] - a[0] > m(ZUSAMMEN_Y, s)) break;
                if (benutzt[j] || Math.abs((b[1] + b[2]) / 2.0 - mitte) > m(ZUSAMMEN_X, s)) continue;
                benutzt[j] = true;
                yMax = Math.max(yMax, b[0]);
                links = Math.min(links, b[1]);
                rechts = Math.max(rechts, b[2]);
                summe += (b[1] + b[2]) / 2.0;
                n++;
            }
            int hoch = yMax - yMin;
            if (n < 2 || hoch < m(LEISTE_HOCH_MIN, s) || hoch > m(LEISTE_HOCH_MAX, s)) continue;
            int cx = (int) Math.round(summe / n);

            // 3. Ist es wirklich eine Einheitenkarte? Das Klassen-Abzeichen (dunkler Kreis) muss darueber sitzen.
            if (!abzeichenDa(px, w, h, ch, cx, yMin - m(ABZEICHEN_UEBER, s), s)) continue;

            Rect portraet = new Rect(cx - m(PORTRAET_B / 2.0, s), yMin - m(PORTRAET_UEBER, s), m(PORTRAET_B, s), m(PORTRAET_H, s));
            if (portraet.x < 0 || portraet.y < 0 || portraet.x + portraet.width > w || portraet.y + portraet.height > h) continue;
            Rect name = namensKasten(px, w, h, ch, cx, yMin, s);
            boolean lebt = lebt(px, w, ch, links, (yMin + yMax) / 2, s);
            double[] klasse = klasse(bild, cx, yMin, s);
            karten.add(new Karte(cx, yMin, yMax, lebt, portraet, name, Klasse.values()[(int) klasse[0]], klasse[1]));
        }

        // Reihen bilden (gleiche Oberkante), dann von links
        karten.sort(Comparator.comparingInt(Karte::leisteOben));
        List<Karte> sortiert = new ArrayList<>();
        List<Karte> reihe = new ArrayList<>();
        for (Karte k : karten) {
            if (!reihe.isEmpty() && k.leisteOben() - reihe.get(0).leisteOben() > m(6, s)) {
                reihe.sort(Comparator.comparingInt(Karte::mitteX));
                sortiert.addAll(reihe);
                reihe.clear();
            }
            reihe.add(k);
        }
        reihe.sort(Comparator.comparingInt(Karte::mitteX));
        sortiert.addAll(reihe);
        return sortiert;
    }

    private static boolean abzeichenDa(byte[] px, int w, int h, int ch, int cx, int cy, double s) {
        int r = m(ABZEICHEN_FENSTER, s), n = 0;
        for (int y = Math.max(0, cy - r); y <= Math.min(h - 1, cy + r); y++) {
            for (int x = Math.max(0, cx - r); x <= Math.min(w - 1, cx + r); x++) {
                int i = (y * w + x) * ch;
                if (rahmen(px[i] & 0xff, px[i + 1] & 0xff, px[i + 2] & 0xff)) n++;
            }
        }
        return n >= ABZEICHEN_MIN * s * s;
    }

    /** Gruen am linken Ende der Leiste = lebt (die Leiste leert sich von rechts; gefallen = ganz rot). */
    private static boolean lebt(byte[] px, int w, int ch, int links, int y, double s) {
        int gruen = 0, n = 0;
        for (int x = links + m(4, s); x <= links + m(12, s) && x < w; x++) {
            int i = (y * w + x) * ch;
            if (gruen(px[i] & 0xff, px[i + 1] & 0xff, px[i + 2] & 0xff)) gruen++;
            n++;
        }
        return n > 0 && gruen * 2 > n;
    }

    /**
     * Enger Kasten um die dunkle Namensschrift zwischen Abzeichen und Leiste (Autors name_N: Schrift plus 2-4 Punkte
     * Rand). null, wenn da keine Schrift ist.
     */
    private static Rect namensKasten(byte[] px, int w, int h, int ch, int cx, int leisteOben, double s) {
        int x0 = Math.max(0, cx - m(NAME_FENSTER_X, s)), x1 = Math.min(w - 1, cx + m(NAME_FENSTER_X, s));
        int y0 = Math.max(0, leisteOben - m(NAME_FENSTER_UEBER, s)), y1 = leisteOben - Math.max(1, m(2, s));
        if (y1 <= y0) return null;
        int[] jeZeile = new int[y1 - y0 + 1];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = (y * w + x) * ch;
                int grau = ((px[i] & 0xff) + (px[i + 1] & 0xff) + (px[i + 2] & 0xff)) / 3;
                if (grau < 120) jeZeile[y - y0]++;
            }
        }
        // Von unten nach oben: der erste Block mit Schrift (Luecken von einer Zeile ueberbruecken)
        int unten = -1;
        for (int i = jeZeile.length - 1; i >= 0; i--) {
            if (jeZeile[i] >= 2) {
                unten = i;
                break;
            }
        }
        if (unten < 0) return null;
        int oben = unten, luecke = 0;
        for (int i = unten - 1; i >= 0; i--) {
            if (jeZeile[i] > 0) {
                oben = i;
                luecke = 0;
            } else if (++luecke > 1) {
                break;
            }
        }
        int links = Integer.MAX_VALUE, rechts = -1, dunkel = 0;
        for (int y = y0 + oben; y <= y0 + unten; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = (y * w + x) * ch;
                int grau = ((px[i] & 0xff) + (px[i + 1] & 0xff) + (px[i + 2] & 0xff)) / 3;
                if (grau < 120) {
                    links = Math.min(links, x);
                    rechts = Math.max(rechts, x);
                    dunkel++;
                }
            }
        }
        if (rechts < 0 || dunkel < 15 * s || rechts - links + 1 < m(8, s)) return null;
        int rand = Math.max(1, m(NAME_RAND, s));
        int kx0 = Math.max(0, links - rand), kx1 = Math.min(w - 1, rechts + rand);
        int ky0 = Math.max(y0, y0 + oben - rand), ky1 = Math.min(y1, y0 + unten + rand);
        return new Rect(kx0, ky0, kx1 - kx0 + 1, ky1 - ky0 + 1);
    }

    // ---- Klassen-Zeichen ----

    private static volatile Map<Klasse, Mat> zeichen;

    /** Die Klassen-Zeichen aus der Jar (templates\gelernt\dungeon\klassen, mit dem Nachschub ausgeliefert). */
    static Map<Klasse, Mat> zeichen() {
        Map<Klasse, Mat> z = zeichen;
        if (z != null) return z;
        synchronized (Kaempferbilder.class) {
            if (zeichen != null) return zeichen;
            Map<Klasse, Mat> neu = new EnumMap<>(Klasse.class);
            laden(neu, Klasse.MAGIER, "magier");
            laden(neu, Klasse.BOGEN, "bogen");
            laden(neu, Klasse.TANK, "tank");
            zeichen = neu;
            return neu;
        }
    }

    private static void laden(Map<Klasse, Mat> ziel, Klasse k, String name) {
        try (InputStream in = Kaempferbilder.class.getResourceAsStream("/nachschub/gelernt/dungeon/klassen/kb_klasse_" + name + ".png")) {
            if (in == null) return;
            Mat bild = Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_COLOR);
            if (!bild.empty()) ziel.put(k, bild);
        } catch (Exception e) {
            Log.debug("Kaempferbilder", "Klassen-Zeichen " + name + ": " + e.getMessage());
        }
    }

    /** {Klasse.ordinal, Wert}: bestes Zeichen im Kasten um die Abzeichen-Mitte; unter KLASSE_AB = UNBEKANNT. */
    private static double[] klasse(Mat bild, int cx, int leisteOben, double s) {
        int cy = leisteOben - m(ABZEICHEN_UEBER, s);
        Rect kasten = new Rect(cx - m(20, s), cy - m(16, s), m(40, s), m(32, s));
        kasten = schneiden(kasten, bild);
        Klasse beste = Klasse.UNBEKANNT;
        double wert = 0;
        if (kasten == null) return new double[]{beste.ordinal(), 0};
        Mat teil = new Mat(bild, kasten);
        Mat bgr = bgr(teil);
        try {
            for (Map.Entry<Klasse, Mat> e : zeichen().entrySet()) {
                Mat vorlage = e.getValue();
                if (Math.abs(s - 1.0) > 0.03) {
                    vorlage = new Mat();
                    Imgproc.resize(e.getValue(), vorlage, new org.opencv.core.Size(
                            Math.max(4, e.getValue().cols() * s), Math.max(4, e.getValue().rows() * s)));
                }
                if (vorlage.cols() > bgr.cols() || vorlage.rows() > bgr.rows()) continue;
                Mat erg = new Mat();
                Imgproc.matchTemplate(bgr, vorlage, erg, Imgproc.TM_CCOEFF_NORMED);
                double v = Core.minMaxLoc(erg).maxVal;
                erg.release();
                if (vorlage != e.getValue()) vorlage.release();
                if (v > wert) {
                    wert = v;
                    beste = e.getKey();
                }
            }
        } finally {
            if (bgr != teil) bgr.release();
            teil.release();
        }
        return new double[]{(wert >= KLASSE_AB ? beste : Klasse.UNBEKANNT).ordinal(), wert};
    }

    private static Rect schneiden(Rect r, Mat bild) {
        int x0 = Math.max(0, r.x), y0 = Math.max(0, r.y);
        int x1 = Math.min(bild.cols(), r.x + r.width), y1 = Math.min(bild.rows(), r.y + r.height);
        return x1 > x0 && y1 > y0 ? new Rect(x0, y0, x1 - x0, y1 - y0) : null;
    }

    private static Mat bgr(Mat teil) {
        if (teil.channels() == 3) return teil;
        Mat out = new Mat();
        Imgproc.cvtColor(teil, out, teil.channels() == 4 ? Imgproc.COLOR_BGRA2BGR : Imgproc.COLOR_GRAY2BGR);
        return out;
    }

    // =====================================================================================================================
    //  Reihenfolge und Aufnahme
    // =====================================================================================================================

    /**
     * Wer welche Nummer bekommt: nur lebende Karten mit Namen, doppelte Namen (Klon aus "Ally in Reflection") einmal,
     * Fernkaempfer zuerst, sonst die Reihenfolge von kartenFinden (Truppe oben vor Ablage, von links). Hoechstens 6.
     */
    static List<Karte> reihenfolge(Mat bild, List<Karte> karten) {
        List<Karte> reihe = reihenfolgeAlle(bild, karten);
        return reihe.size() > HOECHSTENS ? new ArrayList<>(reihe.subList(0, HOECHSTENS)) : reihe;
    }

    /**
     * Wie {@link #reihenfolge}, aber Karten, deren Name schon eine Nummer hat ({@code nameNr}, gleiche Reihenfolge wie
     * {@code karten}; null = keine), behalten diese Nummer. Die anderen fuellen die Luecken (Fernkaempfer zuerst); bleiben
     * Luecken, ruecken die hoeheren Nummern nach (unit_1..4 sollen nie fehlen, die braucht die Kathedrale).
     */
    static List<Karte> nummerieren(Mat bild, List<Karte> karten, int[] nameNr) {
        List<Karte> auto = reihenfolgeAlle(bild, karten);
        if (nameNr == null) return reihenfolge(bild, karten);
        TreeMap<Integer, Karte> benannt = new TreeMap<>();
        List<Karte> rest = new ArrayList<>();
        for (Karte k : auto) {
            int nr = 0;
            for (int i = 0; i < karten.size(); i++) if (karten.get(i) == k) nr = nameNr[i];
            if (nr > 0 && !benannt.containsKey(nr)) benannt.put(nr, k);
            else rest.add(k);
        }
        List<Karte> aus = new ArrayList<>();
        for (int nr = 1; nr <= HOECHSTENS; nr++) {
            if (benannt.containsKey(nr)) aus.add(benannt.remove(nr));
            else if (!rest.isEmpty()) aus.add(rest.remove(0));
            else if (!benannt.isEmpty()) aus.add(benannt.pollFirstEntry().getValue());
            else break;
        }
        return aus;
    }

    private static List<Karte> reihenfolgeAlle(Mat bild, List<Karte> karten) {
        List<Karte> brauchbar = new ArrayList<>();
        for (Karte k : karten) {
            if (!k.lebt() || k.name() == null) continue;
            boolean doppelt = false;
            for (Karte schon : brauchbar) {
                if (gleicherName(bild, schon.name(), k.name())) {
                    doppelt = true;
                    break;
                }
            }
            if (!doppelt) brauchbar.add(k);
        }
        List<Karte> reihe = new ArrayList<>();
        for (Karte k : brauchbar) if (k.klasse().fern()) reihe.add(k);
        for (Karte k : brauchbar) if (!k.klasse().fern()) reihe.add(k);
        return reihe;
    }

    private static boolean gleicherName(Mat bild, Rect a, Rect b) {
        if (Math.abs(a.width - b.width) > 4 || Math.abs(a.height - b.height) > 4) return false;
        int luft = 4;
        Rect suche = schneiden(new Rect(b.x - luft, b.y - luft, b.width + 2 * luft, b.height + 2 * luft), bild);
        if (suche == null || suche.width < a.width || suche.height < a.height) return false;
        Mat vorlage = new Mat(bild, a), feld = new Mat(bild, suche), erg = new Mat();
        try {
            Imgproc.matchTemplate(feld, vorlage, erg, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(erg).maxVal >= GLEICHER_NAME_AB;
        } finally {
            erg.release();
            feld.release();
            vorlage.release();
        }
    }

    /** Was aufgenommen wurde: reihe.get(i) bekam die Nummer nummern.get(i). */
    record Ergebnis(List<Karte> reihe, List<Integer> nummern, List<Path> weggeraeumt, byte[] bild) {
        String beschreibung(boolean deutsch) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < reihe.size(); i++) {
                if (i > 0) sb.append(", ");
                Klasse k = reihe.get(i).klasse();
                sb.append(nummern.get(i)).append(" = ").append(deutsch ? k.de() : k.en());
            }
            return sb.toString();
        }
    }

    private static List<Integer> eins(int bis) {
        List<Integer> l = new ArrayList<>();
        for (int i = 1; i <= bis; i++) l.add(i);
        return l;
    }

    /** Wie {@link #aufnehmen(Mat, List, int[], Path, List, Path)} ohne Nummern aus den Namen. */
    static Ergebnis aufnehmen(Mat bild, List<Karte> karten, Path ziel, List<Path> alteWurzeln, Path weg) throws Exception {
        return aufnehmen(bild, karten, null, ziel, alteWurzeln, weg);
    }

    /**
     * Raeumt alte Kaempferbilder weg (verschieben nach {@code weg}) und schreibt die neuen als unit_N/name_N nach
     * {@code ziel}. Wird ein altes Bild nicht weggeraeumt, wird NICHTS geschrieben (nie ueberschreiben).
     * {@code nameNr}: Nummern, die die Namen schon tragen (siehe {@link #nummerieren}); null = Reihenfolge wie immer.
     */
    static Ergebnis aufnehmen(Mat bild, List<Karte> karten, int[] nameNr, Path ziel, List<Path> alteWurzeln, Path weg)
            throws Exception {
        List<Karte> reihe = nummerieren(bild, karten, nameNr);
        if (reihe.isEmpty()) return new Ergebnis(reihe, List.of(), List.of(), null);
        List<Path> weggeraeumt = wegraeumen(alteWurzeln, weg);
        Files.createDirectories(ziel);
        for (int i = 0; i < reihe.size(); i++) {
            Karte k = reihe.get(i);
            schreiben(bild, k.portraet(), ziel.resolve("unit_" + (i + 1) + ".png"));
            schreiben(bild, k.name(), ziel.resolve("name_" + (i + 1) + ".png"));
        }
        List<Integer> nummern = eins(reihe.size());
        return new Ergebnis(reihe, nummern, weggeraeumt, uebersicht(bild, reihe, nummern));
    }

    /**
     * Neuer Skin bei einzelnen Einheiten: fuer jede Karte in {@code skins} (Karten-Index -&gt; Nummer) das alte unit_N
     * ueberall wegraeumen und das Portraet neu schreiben. name_N und alle anderen Bilder bleiben.
     */
    static Ergebnis skinsErneuern(Mat bild, List<Karte> karten, Map<Integer, Integer> skins, Path ziel,
                                  List<Path> alteWurzeln, Path weg) throws Exception {
        List<Karte> reihe = new ArrayList<>();
        List<Integer> nummern = new ArrayList<>();
        new TreeMap<>(skins).forEach((index, nr) -> {
            if (index >= 0 && index < karten.size() && nr >= 1 && nr <= HOECHSTENS && !nummern.contains(nr)) {
                reihe.add(karten.get(index));
                nummern.add(nr);
            }
        });
        if (reihe.isEmpty()) return new Ergebnis(reihe, nummern, List.of(), null);
        List<String> dateien = nummern.stream().map(nr -> "unit_" + nr + ".png").toList();
        List<Path> weggeraeumt = wegraeumen(alteWurzeln, weg, n -> dateien.stream().anyMatch(n::equalsIgnoreCase));
        Files.createDirectories(ziel);
        for (int i = 0; i < reihe.size(); i++) {
            schreiben(bild, reihe.get(i).portraet(), ziel.resolve("unit_" + nummern.get(i) + ".png"));
        }
        return new Ergebnis(reihe, nummern, weggeraeumt, uebersicht(bild, reihe, nummern));
    }

    private static void schreiben(Mat bild, Rect r, Path datei) {
        if (Files.exists(datei)) throw new IllegalStateException("schon da: " + datei);
        Mat teil = new Mat(bild, r);
        Mat bgr = bgr(teil);
        try {
            if (!Imgcodecs.imwrite(datei.toString(), bgr)) throw new IllegalStateException("nicht geschrieben: " + datei);
        } finally {
            if (bgr != teil) bgr.release();
            teil.release();
        }
    }

    /**
     * Verschiebt alle unit_1..6.png / name_1..6.png unter den Wurzeln nach {@code weg} (je Wurzel ein Unterordner mit
     * ihrem Namen, darunter der Pfad wie vorher). unit_dead*, name_sacrifice usw. bleiben.
     */
    static List<Path> wegraeumen(List<Path> wurzeln, Path weg) throws Exception {
        return wegraeumen(wurzeln, weg, n -> KAEMPFERBILD.matcher(n).matches());
    }

    /** Wie oben, aber nur die Dateien, deren Name {@code welche} erfuellt (neuer Skin: nur unit_N). */
    static List<Path> wegraeumen(List<Path> wurzeln, Path weg, Predicate<String> welche) throws Exception {
        List<Path> verschoben = new ArrayList<>();
        for (Path wurzel : wurzeln) {
            if (wurzel == null || !Files.isDirectory(wurzel)) continue;
            List<Path> alte;
            try (Stream<Path> s = Files.walk(wurzel, 8)) {
                alte = s.filter(Files::isRegularFile)
                        .filter(p -> welche.test(p.getFileName().toString()))
                        .toList();
            }
            String marke = wurzel.toAbsolutePath().normalize().getFileName() == null ? "ordner"
                    : wurzel.toAbsolutePath().normalize().getFileName().toString();
            for (Path alt : alte) {
                Path nach = weg.resolve(marke).resolve(wurzel.relativize(alt).toString());
                Files.createDirectories(nach.getParent());
                Files.move(alt, nach);   // ohne REPLACE_EXISTING: nie etwas ueberschreiben
                verschoben.add(nach);
            }
        }
        return verschoben;
    }

    /** Bild fuer den Bericht an Autor: Portraets nebeneinander, Name und Nummer darunter, doppelt so gross. */
    static byte[] uebersicht(Mat bild, List<Karte> reihe, List<Integer> nummern) {
        int zelleB = 0, zelleH = 0;
        for (Karte k : reihe) {
            int nameB = k.name() == null ? 0 : k.name().width, nameH = k.name() == null ? 0 : k.name().height;
            zelleB = Math.max(zelleB, Math.max(k.portraet().width, nameB));
            zelleH = Math.max(zelleH, k.portraet().height + nameH);
        }
        int rand = 8, schrift = 22;
        int breite = reihe.size() * (zelleB + rand) + rand, hoehe = zelleH + 3 * rand + schrift;
        Mat flaeche = new Mat(hoehe, breite, CvType.CV_8UC3, new Scalar(255, 255, 255));
        Mat gross = new Mat();
        try {
            for (int i = 0; i < reihe.size(); i++) {
                Karte k = reihe.get(i);
                int x = rand + i * (zelleB + rand);
                kopieren(bild, k.portraet(), flaeche, x, rand);
                kopieren(bild, k.name(), flaeche, x, rand + k.portraet().height + rand / 2);
                Imgproc.putText(flaeche, nummern.get(i) + " " + k.klasse().de(), new Point(x, hoehe - rand),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(40, 40, 40), 1);
            }
            Imgproc.resize(flaeche, gross, new org.opencv.core.Size(breite * 2, hoehe * 2), 0, 0, Imgproc.INTER_NEAREST);
            MatOfByte png = new MatOfByte();
            Imgcodecs.imencode(".png", gross, png);
            byte[] b = png.toArray();
            png.release();
            return b;
        } finally {
            gross.release();
            flaeche.release();
        }
    }

    private static void kopieren(Mat bild, Rect r, Mat ziel, int x, int y) {
        if (r == null || x + r.width > ziel.cols() || y + r.height > ziel.rows()) return;
        Mat teil = new Mat(bild, r);
        Mat bgr = bgr(teil);
        Mat zielTeil = new Mat(ziel, new Rect(x, y, r.width, r.height));
        bgr.copyTo(zielTeil);
        zielTeil.release();
        if (bgr != teil) bgr.release();
        teil.release();
    }

    @Override
    public String toString() {
        return "Kaempferbilder[" + bereich.toLowerCase(Locale.ROOT) + (fertig ? ", fertig" : "") + "]";
    }
}
