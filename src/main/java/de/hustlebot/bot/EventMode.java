package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Hammer-Event: Vasen zerschlagen, bis der Zauberstab da ist.
 *
 * WIE DAS SPIEL GEHT (aus Autors drei Videos vom 6. September).
 *
 * Auf dem Feld stehen zwanzig Vasen, fuenf nebeneinander und vier
 * uebereinander. In dreien davon liegt etwas Besonderes, in sechzehn etwas
 * Gewoehnliches, und in genau einer der Zauberstab. Man sieht es den Vasen
 * nicht an.
 *
 * Zwei Werkzeuge:
 *
 *   Der HAMMER zerschlaegt eine Vase. Kostet einen Hammer, und man bekommt,
 *   was drin war.
 *
 *   Die LUPE ("See through") macht drei Vasen durchsichtig, ohne sie zu
 *   zerschlagen. Welche drei, sucht man sich aus: Es erscheint eine
 *   Dreier-Form -- mal eine Reihe, mal ein Winkel, wie bei Tetris -- die man
 *   ueber das Feld zieht und mit dem gruenen Haken bestaetigt.
 *
 * Sobald der Stab geholt ist, wird das Feld mit "Refresh the field" neu
 * gemischt und alles geht von vorn.
 *
 * AUTORS REGELN, die hier eingebaut sind:
 *
 *   - Es geht IMMER nur um den Zauberstab. Kisten werden mitgenommen, wenn
 *     eine Lupe sie zufaellig freilegt -- aber es wird keine einzige Vase
 *     extra dafuer zerschlagen.
 *   - Hoechstens FUENF Lupen je Feld. Danach nur noch Hammer.
 *   - Sind die Lupen alle, geht es ohne sie weiter.
 *   - Sind die HAEMMER alle, ist Schluss -- auch wenn noch Lupen da sind.
 *   - Die drei Kisten mit den Uhren unten werden NIE angeklickt, und jedes
 *     Angebot mit Preis wird weggedrueckt. Beides kostet echtes Geld.
 *
 * OHNE VORLAGEN TUT DIESE KLASSE NICHTS. Fehlen die Bilder, sagt der Motor
 * beim Start, welche -- aufzunehmen mit Capture-Images.exe, Gruppe "HAMMER-EVENT".
 */
public final class EventMode implements GameMode {

    // ------------------------------------------------------------------
    //  Vorlagen
    // ------------------------------------------------------------------

    /** "Event ends in:" -- daran haengt, ob wir ueberhaupt im Event sind. */
    private static final String T_TITEL      = "ev_title";
    /** Der Knopf "See through". */
    private static final String T_LUPE       = "ev_see_through";
    /** Das gruene Haken-Feld, waehrend die Dreier-Form liegt. */
    private static final String T_OK         = "ev_ok";
    /** Eine geschlossene Vase. */
    private static final String T_VASE       = "ev_vase_zu";
    /** "Magic Wand" mit gruenem Haken: der Stab ist geholt. */
    private static final String T_STAB_DA    = "ev_wand_fertig";
    /** "Refresh the field", gelb -- das Feld darf neu gemischt werden. */
    private static final String T_REFRESH    = "ev_refresh_gelb";
    /**
     * Derselbe Knopf, aber grau: Der Stab fehlt noch.
     *
     * Wird als GEGENPROBE benutzt. Der graue und der gelbe Knopf tragen
     * dieselbe Aufschrift und haben dieselbe Form -- nur die Farbe ist
     * anders. Verwechselt der Bot sie, wuerde er das Feld neu mischen,
     * bevor er den Stab hat, und die ganze Arbeit waere umsonst.
     *
     * Deshalb: gemischt wird nur, wenn der GELBE zu sehen ist und der
     * graue NICHT. Zwei Stimmen statt einer.
     */
    private static final String T_REFRESH_GRAU = "ev_refresh_grau";
    /**
     * Der gruene "Yes" in der Nachfrage "Are you sure you want to refresh
     * the field?".
     *
     * OHNE DIESEN KNOPF PASSIERT GAR NICHTS. Am 7. September stand im
     * Bericht dreizehnmal "Feld geschafft" -- in sechs Sekunden. Der Bot
     * hatte den gelben Knopf gedrueckt, die Nachfrage ging auf, er sah sie
     * nicht, und zaehlte trotzdem ein geschafftes Feld. Dreizehn Felder, die
     * es nie gegeben hat.
     *
     * Gezaehlt wird jetzt erst NACH dem Ja. Vorher ist nichts passiert.
     */
    private static final String T_REFRESH_YES = "ev_refresh_yes";
    /** Der Zauberstab, wie er in einer Glasvase aussieht. */
    private static final String T_STAB       = "ev_stab";
    /**
     * Der Stab, eng ausgeschnitten aus den Beweisfotos des Bots (29.09.,
     * templates\event-belohnungen): passt im Feld mit 0,92-1,00 -- die alte
     * Vorlage nur mit 0,76-0,80, knapp ueber der Grenze. Deshalb schlug der Bot
     * manchmal erst zwei Vasen kaputt, bevor er den offenen Stab sah (Autor).
     */
    private static final String T_STAB_KERN  = "ev_stab_kern";
    /** Der gruene "Open" im Kistenfenster. */
    private static final String T_OPEN       = "ev_open";
    /** Der grosse gruene "Claim". */
    private static final String T_CLAIM      = "ev_claim";
    /** Das rote X am Angebot "For the true treasure hunter!". */
    private static final String T_ANGEBOT_X  = "ev_angebot_x";
    /** Die Ueberschrift desselben Angebots. */
    private static final String T_ANGEBOT    = "ev_angebot_titel";
    /** Das Hammer-Symbol links, zum Ablesen des Standes. */
    private static final String T_HAMMER     = "ev_hammer_icon";
    /** Der "Map"-Knopf -- daran wird die Burg erkannt. */
    private static final String T_MAP        = "map_button";
    /** Sein zweites Aussehen. */
    private static final String T_MAP_ALT    = "map_button_alt";

    /**
     * Alles, was der Bot aus einer Vase mitnimmt, faengt so an.
     *
     * Absichtlich als Namensanfang und nicht als feste Liste: Autor nimmt
     * die Kisten auf, wie sie ihm im Spiel begegnen, und nennt sie, wie er
     * mag -- ev_kiste_mithril, ev_kiste_holz, ev_kiste_blau, egal. Der Bot
     * nimmt mit, was da ist. So muss niemand raten, welche Kiste welche ist.
     */
    private static final String KISTEN_PREFIX = "ev_kiste_";
    /** Die Diamanten heissen anders, gehoeren aber dazu. */
    private static final String T_GEMS       = "ev_gems";

    /**
     * Der Weg ueber den Event-Kalender (Autor, 28.09.) statt ueber den
     * Flaschengeist, der jedes Event anders aussieht: in der Burg das
     * Kalender-Symbol, im "Calendar of Events" die Zeile mit dem Hammer,
     * im Fenster danach "View".
     */
    private static final String T_KALENDER        = "ev_kalender";
    /** Geschlossenes Fass (Herbst-Event): eine Serie ev_fass_zu, ev_fass_zu_2 ... */
    private static final String T_FASS            = "ev_fass_zu";
    private static final String T_KALENDER_EINTRAG = "ev_kalender_eintrag";
    private static final String T_VIEW            = "ev_view";
    /**
     * Autor 24.10. (Ideenliste B10/B11): die Ueberschrift "Not enough hammers?" -- das Fenster, das das Spiel zeigt, sobald
     * mit 0 Haemmern eine Vase zerschlagen oder eine Kiste geoeffnet werden soll (Foto 14:26: logs\event-fenster-142635.png).
     */
    private static final String T_ZU_WENIG        = "ev_zu_wenig_haemmer";
    /** Die Kathedralen-Schluessel-Kiste -- wird vor allen anderen Kisten geholt. */
    private static final String T_KISTE_SCHLUESSEL = "ev_kiste_schluessel";

    /**
     * Der BLAUE HAMMER (Super Hammer, Herbst-Event 28.09.): trifft drei Felder
     * auf einmal, in einer Dreier-Form wie die Lupe -- aber gelb umrandet.
     * Autor: nur benutzen, wenn zwei gute Items (oder Stab + Item) direkt
     * nebeneinander aufgedeckt liegen. Passt die Form nicht auf beide:
     * rotes X, abbrechen, die Items einzeln holen.
     */
    private static final String T_SUPER_USE  = "ev_superhammer_use";
    /** Der gelbe Text "Drag the Super Hammer shape ..." -- daran unterscheidet er sich von der Lupe. */
    private static final String T_SUPER_TEXT = "ev_superhammer_text";
    /** Das rote X unter dem gruenen Haken, solange eine Form liegt -- bricht ab. */
    private static final String T_FORM_X     = "ev_form_x";
    /** Inhalts-Fenster einer schon abgeholten Kiste: "Reward claimed!" und sein rotes X (Nacht 28.10.). */
    private static final String T_ERHALTEN   = "ev_belohnung_erhalten";
    private static final String T_INHALT_X   = "ev_inhalt_x";
    /** Der gelbe Umriss in HSV (OpenCV-Farbton 0..179, Gelb bei etwa 25 bis 32). */
    private static final Scalar GELB_VON = new Scalar(20, 120, 170);
    private static final Scalar GELB_BIS = new Scalar(34, 255, 255);

    /**
     * Ab dieser Uebereinstimmung gilt eine Kiste als erkannt -- milder als
     * die uebliche Schwelle von 0,84.
     *
     * WARUM DAS GEHT. Ein Fehlgriff kostet hier fast nichts: Der Bot klickt
     * dann eine Vase an, die keine Kiste ist. Die wird zerschlagen -- und
     * zerschlagen haette er sie ohnehin, es geht ja um den Zauberstab. Der
     * Preis ist ein Hammer, den er sowieso ausgegeben haette.
     *
     * WARUM ES NOETIG IST. Am 7. September hat Autor eine Mithril-Kiste in
     * der untersten Reihe markiert, die der Bot nicht mitgenommen hat. Im
     * Protokoll steht daneben, wie knapp es bei den anderen war:
     *
     *   Kiste mitnehmen (ev_kiste_essen) ... Uebereinstimmung 0,851
     *
     * Also gerade eben ueber der Schwelle. Die Kisten stehen in einer
     * Glasvase, und das Glas faerbt und spiegelt -- da fehlt schnell ein
     * Hundertstel. Bei 0,78 ist Luft, ohne dass es beliebig wird.
     */
    private static final double KISTEN_SCHWELLE = 0.78;

    // ------------------------------------------------------------------
    //  Grenzen
    // ------------------------------------------------------------------

    /** So viele Lupen je Feld im Regelfall. Autors Zahl. */
    private static final int MAX_LUPEN = 5;

    /**
     * Und so viele hoechstens, wenn danach noch viele Vasen zu sind.
     *
     * Also genau EINE mehr. Autor hat um die sechste gebeten, nicht um eine
     * offene Reihe: "wenn noch 5 vasen zu sind obwohl er 5 lupen benutzt hat
     * soll er noch eine 6te lupe benutzen."
     */
    private static final int MAX_LUPEN_NOTFALLS = 5;   // Autor, 28.09.: "maximal 5 Lupen pro Stage"

    /**
     * So viele Vasen muessen noch zu sein, damit sich die sechste lohnt.
     *
     * Bei weniger deckt eine Lupe kaum noch etwas auf -- die Dreier-Form
     * passt dann meist nicht mehr auf die verstreuten Reste, und im
     * Protokoll steht "trifft dann 1 von 3 noch geschlossene Vasen".
     * Zuschlagen ist in dem Fall schneller und kostet nur einen Hammer.
     */
    private static final int NACHSCHLAG_AB = 5;

    /** Das Feld: fuenf Vasen nebeneinander, vier uebereinander. */
    static final int SPALTEN = 5;
    static final int REIHEN  = 4;

    /** Ab so vielen erkannten Vasen gilt das Raster als sicher vermessen. */
    private static final int RASTER_MINDESTENS = 16;

    /** Vasen werden in Graustufen gesucht -- die Farbe wechselt, die Form nicht. */
    private static final double VASE_SCHWELLE = 0.72;

    /**
     * So oft darf ein Vasenklick wirkungslos bleiben, bevor aufgegeben wird.
     *
     * Das ist die Reissleine fuer den Fall, dass die Haemmer alle sind und
     * das Spiel es anders anzeigt, als wir erwarten. Lieber sauber aufhoeren
     * als die ganze Nacht ins Leere klicken.
     */
    private static final int MAX_WIRKUNGSLOS = 8;

    /**
     * So oft wird bei einem wirkungslosen Schlag erst die freie Flaeche
     * angetippt, bevor der Bot an leere Haemmer glaubt.
     *
     * Drei Versuche reichen fuer jedes Fenster, das sich so schliessen
     * laesst -- und sie kosten zusammen keine fuenf Sekunden.
     */
    private static final int FREIE_FLAECHE_VERSUCHE = 3;

    /**
     * So oft wird in der Burg gezogen, um den Flaschengeist zu suchen.
     *
     * Zwoelf sind drei volle Runden durch alle vier Richtungen. Acht waren
     * es vorher -- zu wenig, seit auch nach oben und unten gesucht wird.
     */
    private static final int MAX_ZUEGE_ZUM_GEIST = 16;

    /**
     * Wie viele Zuege am Stueck in dieselbe Richtung gehen.
     *
     * Vier Zuege ueber je die halbe Bildbreite tragen die Ansicht zwei
     * Bildschirme weit -- genug, um von den Zimmern im Innern nach draussen
     * zu kommen. Wechselt die Richtung dagegen bei jedem Zug, bleibt die
     * Burg stehen, wo sie ist.
     */
    private static final int ZUEGE_JE_RICHTUNG = 4;

    /** Wie weit ein Zug traegt, als Anteil der Bildbreite bzw. -hoehe. */
    private static final double WEITE = 0.30;

    /**
     * So viele Bilder lang muss weder Event noch Burg zu sehen sein, bevor
     * der Bot ein rotes X drueckt.
     *
     * Eine Animation ist nach zwei, drei Bildern vorbei. Ein Fenster, das
     * wirklich im Weg steht, bleibt.
     */
    private static final int GEDULD_FREMDES_FENSTER = 5;

    /**
     * So oft darf der Bot ins Event gehen, ohne drin etwas zu erledigen.
     *
     * Zehn sind grosszuegig: Im normalen Betrieb geht er EINMAL hinein und
     * bleibt dann. Mehrere Male hintereinander heisst, dass ihn etwas wieder
     * hinauswirft -- und dann ist Nachsehen besser als Weitermachen.
     */
    private static final int MAX_EINTRITTE = 10;

    // ------------------------------------------------------------------
    //  Zustand
    // ------------------------------------------------------------------

    /** Wie oft ins Event gegangen wurde, ohne drin etwas zu erledigen. */
    private int eintritte = 0;

    /** Wie viele Bilder lang schon weder Event noch Burg zu sehen war. */
    private int fremdeBilder = 0;


    private int lupenBenutzt = 0;
    private int vasenZerschlagen = 0;
    private int kistenGeholt = 0;
    private int felderGeschafft = 0;
    private int wirkungslos = 0;
    private int zuegeZumGeist = 0;
    private long hammerStand = -1;
    private Raster raster = null;
    private boolean hinweisGezeigt = false;

    /**
     * Wie viele Vasen vor dem letzten Hammerschlag noch zu waren.
     *
     * DIE WICHTIGSTE SICHERUNG IN DIESER KLASSE. Sind die Haemmer alle,
     * passiert beim Klick auf eine Vase schlicht nichts -- kein Fenster,
     * keine Meldung, die wir kennen. Ohne diesen Vergleich wuerde der Bot
     * die ganze Nacht auf dieselbe Vase klopfen und dabei brav "Fortschritt"
     * melden, sodass auch die Untaetigkeitsuhr nicht anschlaegt.
     *
     * Wird die Zahl nach einem Schlag nicht kleiner, war der Schlag
     * wirkungslos. Achtmal hintereinander, und der Bot hoert auf.
     */
    private int vasenVorKlick = -1;

    /** Der letzte gezaehlte Schlag hat keine Vase zerschlagen (Bericht #AEEF, 24.10.). */
    private boolean schlagOhneWirkung;

    /**
     * Wie oft bei derselben Form schon gezogen wurde.
     *
     * Beim Legen wird nach jedem Zug neu gemessen. Klappt der Zug, steht die
     * Form richtig und wird bestaetigt. Klappt er nicht -- weil das Spiel den
     * Zug verschluckt hat --, wuerde sonst ewig gezogen. Nach drei Versuchen
     * wird deshalb einfach bestaetigt, wo sie liegt: zwei aufgedeckte Vasen
     * sind besser als keine.
     */
    private int zuegeAnDerForm = 0;
    /** Autor 24.10. (A37): schneller Zug (ziehenSofort) fuer die Lupen-Form; false, sobald einer verschluckt wurde. */
    private boolean zugSofort = true;
    /** Die Verschiebung {dx, dy} des letzten Zuges an dieser Form (null = noch keiner). */
    private int[] letzterZug = null;

    /** So oft hintereinander wurde ein Hammerstand von null gelesen. */
    private int nullGelesen = 0;

    /** True, wenn nur noch das Event-Fenster zugemacht werden muss. */
    private boolean amAufraeumen = false;

    @Override public String id()          { return "event"; }
    @Override public String displayName() { return Sprache.t("Hammer Event", "Hammer-Event"); }

    @Override
    public List<String> requiredTemplates() {
        // Nur die sieben, ohne die der Bot sich nicht zurechtfindet. Die
        // Kisten und der Stab sind freiwillig -- ohne sie spielt er das
        // Event trotzdem, nimmt eben nichts nebenbei mit.
        // Seit 28.09. der Kalender statt des Flaschengeists.
        return List.of(T_KALENDER, T_LUPE, T_OK, T_VASE, T_STAB_DA, T_REFRESH, T_CLAIM);
    }

    @Override
    public void reset() {
        hammerEingangGemeldet = hammerAusgangGemeldet = false;
        lupenBenutzt = 0;
        kistenGemeldet = false;
        nachschlagGemeldet = false;
        vasenZerschlagen = 0;
        kistenGeholt = 0;
        felderGeschafft = 0;
        wirkungslos = 0;
        zuegeZumGeist = 0;
        hammerStand = -1;
        raster = null;
        vasenVorKlick = -1;
        schlagOhneWirkung = false;
        zuegeAnDerForm = 0;
        nullGelesen = 0;
        amAufraeumen = false;
        schliessVersuche = 0;
        vasenBild = null;
        vasenGemerkt = List.of();
        eintritte = 0;
        fremdeBilder = 0;
        superTest = superTestAn();
        superTestLaeuft = false;
        zielStages = false;
        stagesSeitStabBlick = STAB_BLICK_ALLE;   // Zauberstaebe gleich beim Betreten ablesen
        if (superTest) Log.info("Event", Sprache.t("TEST blue hammer is on (superhammer-test.txt): use it once right at the "
                + "start and drag it into the top left corner.",
                "TEST blauer Hammer ist an (superhammer-test.txt): gleich am Anfang "
                + "einmal benutzen und in die linke obere Ecke ziehen."));
        eintragGeklicktUm = 0;
        kalenderVersuche = 0;
        zielPaar = null;
        paareVersucht.clear();
        paarPruefen = true;
        superUseUm = 0;
        superLeer = false;
        superNachprobiert = false;
        schlussStopp = false;
        schlussNichtRotFolge = 0;
        superBenutzt = 0;
        superOhneForm = 0;
        formOffen = false;
        formOhneHaken = 0;
        zuVorForm = null;
        stabGeholt = false;
        lupenGesperrt = false;
        lupeAbbruecheFeld = 0;
        if (lupenLeerBild != null && ++felderSeitLeer >= 5) lupenLeerBild = null;   // Autor 24.10.: neu pruefen
        superAbbruecheFeld = 0;
        superAnsehenBilder = 0;
        hakenGedrueckt = 0;
        blindZaehler = 0;
        umrissWarteBilder = 0;
        superOhneZiel = 0;
        lupeBestaetigtUm = 0;
        hinweisAngebot = false;
        neuGemischtUm = 0;
        lupeNichtGegriffen = 0;
        lupeGeklicktUm = 0;
        textWegUm = 0;
        lupeFehltSeit = 0;
        lupeFehltGewartet = false;
        formZellenJetzt = null;
        geplanteZellen = null;
        geplantBild = null;
        neuMischenGefragt = false;
        rasterGemeldet = false;
        hammerZuletztGemeldet = -1;
        kistenStellen.clear();
        neuBetreten = 0;
        fensterXVersuche = 0;
        haemmerAlle = false;
        keineHaemmerMehr = false;
        nurSuperHaemmer = false;
        schlussGemeldet = false;
        schlussGeprueftUm = 0;
        materialFotoGemacht = false;
        nahrungsKisten = 0;
        letzteKiste = null;
        Log.info("Event", Sprache.t("Mode reset. At most " + MAX_LUPEN
                + " magnifiers per field, after that hammer only.",
                "Modus zurueckgesetzt. Hoechstens " + MAX_LUPEN
                + " Lupen je Feld, danach nur noch Hammer."));
    }

    @Override
    public Map<String, String> extraStats() {
        Map<String, String> werte = new LinkedHashMap<>();
        werte.put(Sprache.t("Fields completed", "Felder geschafft"), String.valueOf(felderGeschafft));
        werte.put(Sprache.t("Vases smashed", "Vasen zerschlagen"), String.valueOf(vasenZerschlagen));
        werte.put(Sprache.t("Chests collected", "Kisten mitgenommen"), String.valueOf(kistenGeholt));
        werte.put(Sprache.t("Magnifiers in this field", "Lupen in diesem Feld"),
                Sprache.t(lupenBenutzt + " of " + MAX_LUPEN, lupenBenutzt + " von " + MAX_LUPEN));
        if (hammerZuletztGemeldet >= 0) {
            werte.put(Sprache.t("Hammers left", "Haemmer uebrig"), String.valueOf(hammerZuletztGemeldet));
        }
        return werte;
    }

    // ------------------------------------------------------------------
    //  Der Ablauf
    // ------------------------------------------------------------------

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        Stoerfenster.eventUnterwegs();   // "Active events" gehoert jetzt dem Event (nicht wegklicken)

        // Endstopp der Schlussphase (29.10. 22:35): einmal beschlossen, wird nicht mehr gespielt -- nur noch ein offenes
        // "Not enough hammers?"-Fenster schliessen, sonst anhalten. Vorher lief nach dem Warnsatz alles weiter (Stab,
        // Kiste ... bis zum Burg-Ziehen), weil das Fenster ueber dem Feld offen war und der Schritt ACTED meldete.
        if (schlussStopp) return schlussphaseFertig(screen, ctx);

        // AFK-Rauswurf ("are you still here?"): der gruene Update-Knopf laedt
        // das Spiel neu und bringt zurueck zur Burg. Hat Vorrang.
        // TEMPO (28.09.): Der Update-Knopf kommt selten, die Suche uebers ganze
        // Bild kostete aber in jedem Bild ~35 ms (Stage-Messung: 29x 1006 ms).
        // Hoechstens alle 3 Sekunden nachsehen reicht voellig.
        long jetztMs = System.currentTimeMillis();
        boolean afkPruefen = jetztMs - afkGeprueftUm > 3000;
        if (afkPruefen) afkGeprueftUm = jetztMs;
        if (afkPruefen && Stoerfenster.updateDruecken(screen, ctx, "Event")) {
            ctx.sleep(20000);
            return Outcome.ACTED;
        }

        // --- 0. Schon fertig, das Fenster ist zu. ----------------------
        //
        // Steht ganz oben, weil sonst der Ablauf darunter den Bot sofort
        // wieder ins Event schicken wuerde -- er sieht ja die Burg und
        // sucht dann pflichtbewusst den Flaschengeist.
        if (amAufraeumen) {
            Log.info("Event", Sprache.t("Done. ", "Fertig. ") + berichten());
            return Outcome.FINISHED;
        }

        // --- 1. Angebote mit Preis. Vorrang vor allem. -----------------
        //
        // "For the true treasure hunter!" -- 43,99 Euro. Das Fenster legt
        // sich ueber das Spielfeld und kommt ungefragt. Es wird
        // weggedrueckt, nie bedient.
        // NICHT DROSSELN: ein Angebot mit Preis muss in JEDEM Bild erkannt werden,
        // sonst klickt der Bot womoeglich in ein offenes Kauf-Fenster.
        if (angebotWegdruecken(screen, ctx)) {
            runs.progress(Sprache.t("Offer dismissed", "Angebot weggedrueckt"));
            return Outcome.ACTED;
        }

        // --- 1b. "Not enough hammers?" -- die Haemmer sind alle. -------
        //
        // Autor 24.10. (Ideenliste B10/B11): Am Ende jedes Event-Gangs zeigte das Spiel bei jedem Schlag/jeder Kiste dieses
        // Fenster, der Bot hielt es fuer "ein Fenster ueber dem Feld", schloss es, schlug wieder zu, verliess das Event
        // zweimal und gab nach 38-125 s (06:17, 09:38, 14:27) mit "No closed vase is left ... the bot stops" auf -- ohne
        // haemmerAlle zu setzen. Jetzt gilt die Ueberschrift selbst als Beweis: Fenster zu, Event verlassen, Haemmer alle.
        // Hoechstens alle 0,5 s nachsehen (kleiner Ausschnitt oben in der Mitte, ~1 ms).
        // Autor 28.10.: in der SCHLUSSPHASE (Zeit oben links rot) geht es ohne normale Haemmer mit den blauen
        // Super-Haemmern weiter, als waeren es normale. Sind auch die alle: anhalten und Bescheid sagen. Ausserhalb der
        // Schlussphase wie bisher: Event verlassen, der gewaehlte Modus spielt weiter, Super-Haemmer und Lupen bleiben liegen.
        // 29.10. 22:35: "keine blauen Haemmer" ist nur ein Zwischenstand -- sie fuellen sich beim Spielen nach (gruener Balken
        // x/20, bei 20 gibt es einen neuen; die Zahl links neben "Use!" stieg von 0 auf 1 und 2, trotzdem hielt der Bot an).
        // Darum vor dem Endstopp einmal je Feld frisch pruefen.
        if (nurSuperHaemmer && superLeer && !superFrischPruefen()) return schlussphaseFertig(screen, ctx);
        if (!keineHaemmerMehr && jetztMs - hammerFensterGeprueftUm >= 500) {
            hammerFensterGeprueftUm = jetztMs;
            if (zuWenigHaemmerFenster(ctx.matcher(), screen)) {
                // Ausnahme "alle Lupen" (Autor 28.10.): spielen, bis Haemmer UND Super-Haemmer leer sind -- wie in der Schlussphase.
                boolean schlussphase = this.schlussphase || de.hustlebot.core.Eventautomatik.alleLupen();
                if (schlussphase && superLeer && de.hustlebot.core.Eventautomatik.blaueHaemmerErlaubt()) superFrischPruefen();
                if (schlussphase && !superLeer && de.hustlebot.core.Eventautomatik.blaueHaemmerErlaubt()) {
                    if (!nurSuperHaemmer) {
                        nurSuperHaemmer = true;
                        Log.info("Event", Sprache.t("Final phase (the time top left is red) and the hammers are used up -- carrying on with "
                                + "the blue super hammers as if they were normal hammers.",
                                "Schlussphase (Zeit oben links rot) und die Haemmer sind alle -- weiter mit den blauen Super-Haemmern, "
                                + "als waeren es normale Haemmer."));
                    }
                    if (infoFensterSchliessen(screen, ctx)) return Outcome.ACTED;
                } else if (schlussphase) {
                    return schlussphaseFertig(screen, ctx);
                } else {
                    keineHaemmerMehr = true;
                    haemmerAlle = true;
                    Log.info("Event", Sprache.t("The game says \"Not enough hammers?\" -- the hammers are used up. Closing the window "
                            + "and leaving the event. ", "Das Spiel zeigt \"Not enough hammers?\" -- die Haemmer sind alle. Das Fenster "
                            + "wird geschlossen und das Event verlassen. ") + berichten());
                }
            }
        }
        if (keineHaemmerMehr) {
            if (zuWenigHaemmerFenster(ctx.matcher(), screen) && infoFensterSchliessen(screen, ctx)) {
                return Outcome.ACTED;
            }
            return aufraeumen(screen, ctx);
        }

        // --- 2. Die ueblichen Stoerfenster. ----------------------------
        //
        // TEMPO (28.09.): Rauswurf, Spielfehler und Belohnungsfenster sind
        // sechs bis acht Suchen uebers ganze Bild (je gut 20 ms) -- in jedem
        // Bild, obwohl sie im Event fast nie kommen. Hoechstens jede Sekunde
        // reicht; ist das Event NICHT zu sehen, wird vor jedem Weg-Klick
        // trotzdem sofort nachgesehen (Schritt 5). Das Angebot oben bleibt
        // in jedem Bild.
        boolean stoerJetzt = jetztMs - stoerGeprueftUm >= 2500;   // 29.09.: 1 -> 2,5 s (je Runde ~190 ms Suche)
        if (stoerJetzt) {
            stoerGeprueftUm = jetztMs;
            Outcome stoer = stoerfensterPruefen(screen, ctx, runs);
            if (stoer != null) return stoer;
        }
        // Der Belohnungs-Text des Events kommt nach JEDEM Neumischen. In den
        // ersten 6 s danach in jedem Bild nachsehen -- mit der Sekunden-Sperre
        // oben kam er bis zu 1 s zu spaet weg (Test 28.09.: 2,85 s von "Ja" bis
        // zur ersten Lupe). Nach dem ersten Mal kennt der Bot seine Stelle, dann
        // kostet die Suche kaum etwas.
        if (!stoerJetzt && jetztMs - neuGemischtUm < 6000
                && Stoerfenster.ereignisTextWegklicken(screen, ctx, "Event")) {
            textWegUm = System.currentTimeMillis();
            Log.debug("Event", "Belohnungs-Text " + (textWegUm - neuGemischtUm) + " ms nach dem Ja weggeklickt.");
            runs.progress(Sprache.t("Pop-up closed", "Stoerfenster geschlossen"));
            return Outcome.ACTED;
        }
        // Nacht 28.10. (gemessen an 13 Feldern): der Belohnungs-Text kommt ~1,3 s nach dem "Ja" und ist nach 1,4-1,9 s
        // weggeklickt. Jede erste Lupe 1,19-1,35 s nach dem Ja ging ins Leere (5 von 5: "did not take"), ab 2,97 s griff
        // jede (8 von 8); auch "blauen Hammer ansehen" in diesem Moment fand keine Form (bis 4 s verloren). Die alte Sperre
        // (B01: bis 18 Vasen zu sehen) half nicht -- mit dem gemerkten Raster "sieht" der Bot sofort 20. Darum nach dem
        // Ja NICHTS auf dem Feld, bis der Text weg ist oder FRISCH_WARTEN_MS um sind (kommt er nicht, ist das Feld dann fertig).
        if (neuGemischtUm > 0 && textWegUm < neuGemischtUm && jetztMs - neuGemischtUm < FRISCH_WARTEN_MS) {
            ctx.sleep(80);
            return Outcome.WAITING;
        }

        // Zauberstab-Grenze: an derselben ruhigen Stelle in die Rangliste sehen.
        if (!zielStages && de.hustlebot.core.Zauberstabziel.ziel() > 0
                && stagesSeitStabBlick >= STAB_BLICK_ALLE
                && System.currentTimeMillis() - textWegUm > 700
                && (textWegUm > neuGemischtUm || jetztMs - neuGemischtUm > 4000)
                && zauberstaebeAblesen(screen, ctx)) {
            return Outcome.ACTED;
        }

        // Rangliste fotografieren (Autor 21.10., aus dem Hauptbot: "!event ranking" -- Bild von der Platzierung).
        if (ranglisteAn != null && System.currentTimeMillis() - textWegUm > 700
                && (textWegUm > neuGemischtUm || jetztMs - neuGemischtUm > 4000)
                && ranglisteZeigen(screen, ctx)) {
            return Outcome.ACTED;
        }

        // Die eingestellte Zahl Stages ist geschafft: raus, sobald der Belohnungs-
        // Text nach dem Neumischen weg ist (sonst schluckt er den Klick aufs X).
        if (zielStages && System.currentTimeMillis() - textWegUm > 700
                && (textWegUm > neuGemischtUm || jetztMs - neuGemischtUm > 4000)) {
            return aufraeumen(screen, ctx);
        }

        // --- 2b. Die Nachfrage "Wirklich neu mischen?" -----------------
        //
        // Steht VOR allem anderen auf dem Feld: Solange die Nachfrage offen
        // ist, geht nichts weiter. Und erst mit dem Ja ist das Feld wirklich
        // geschafft -- das Druecken des gelben Knopfes allein hat noch nichts
        // bewirkt.
        //
        // ABER NUR, WENN DER BOT SELBST GEFRAGT HAT ODER SICHTBAR IM EVENT
        // STEHT. Alle Bestaetigungsdialoge des Spiels sehen gleich aus; im
        // Verwechslungstest passen dc_yes, cw_yes, end_journey_yes und
        // dc_finish_yes mit 0,87 bis 0,89 in diese Vorlage hinein. Ohne diese
        // Schranke wuerde der Bot auf jedem beliebigen Bildschirm ein gruenes
        // Ja druecken, ohne die Frage zu kennen -- und obendrein ein Feld
        // gutschreiben, das es nie gab. Dieselbe Lehre wie beim roten X.
        //
        // Die Reihenfolge im UND ist Absicht: erst nachsehen, ob das Ja
        // ueberhaupt da ist. Nur dann kostet die Schranke etwas, und das ist
        // ein paar Mal je Feld statt in jedem Bild.
        // TEMPO: das "Ja" nur, wenn der Bot selbst neu mischen wollte -- sonst
        // hoechstens alle 3 s (falls die Frage nach einem Neustart noch offen ist).
        boolean jaPruefen = neuMischenGefragt || jetztMs - jaGeprueftUm > 3000;
        if (jaPruefen) jaGeprueftUm = jetztMs;
        if (jaPruefen && ctx.visibleIfKnown(screen, T_REFRESH_YES)
                && (neuMischenGefragt || imEvent(screen, ctx))
                && ctx.clickIfVisibleIfKnown(screen, T_REFRESH_YES,
                        Sprache.t("Really refresh the field -- Yes", "Feld wirklich neu mischen -- Ja"))) {
            felderGeschafft++;
            // Autor 24.10.: je fehlerfreier Stage 0,1 % schneller, beim ersten Fehler eine Stufe zurueck und fest.
            tempo.rundeFertig(stageFehler == 0);
            if (felderGeschafft % 10 == 0) {
                Log.info("Event", Sprache.t("Speed after " + felderGeschafft + " fields: pauses x "
                        + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                        "Tempo nach " + felderGeschafft + " Feldern: Pausen x "
                        + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
            }
            stageFehler = 0;
            neuMischenGefragt = false;
            neuGemischtUm = System.currentTimeMillis();
            lupeNichtGegriffen = 0;
            lupeGeklicktUm = 0;
            lupeFehltSeit = 0;
            lupeFehltGewartet = false;
            superAnsehenVersuche = 0;
            geplanteZellen = null;
            lupenBenutzt = 0;
            kistenGemeldet = false;
            nachschlagGemeldet = false;
            rasterGemeldet = false;
            kistenStellen.clear();
            neuBetreten = 0;
            fensterXVersuche = 0;
            letzteKiste = null;
            paareVersucht.clear();
            zielPaar = null;
            paarPruefen = true;
            formOffen = false;
            stabGeholt = false;
            lupenGesperrt = false;
            lupeAbbruecheFeld = 0;
            if (lupenLeerBild != null && ++felderSeitLeer >= 5) lupenLeerBild = null;   // Autor 24.10.: neu pruefen
            superAbbruecheFeld = 0;
            superAnsehenBilder = 0;
            // Nacht 28.10. (Event-Pruefung M4): "Use! zweimal ohne Form" galt fuer den GANZEN Lauf als "keine blauen
            // Haemmer mehr" -- auch wenn beide Klicks nur in einem Belohnungs-Fenster landeten (7 von 42 "Ansehen"
            // gingen heute so ins Leere). Bei 87 Super-Haemmern drohte so ein falsches "alles verbraucht". In der
            // Ausnahme je Feld neu pruefen; sind sie wirklich alle, merkt der Bot es nach 2 Versuchen wieder.
            // 29.10. 22:35: auch die 3-Felder-Grenze war falsch -- die blauen Haemmer fuellen sich beim Spielen nach (gruener
            // Balken x/20, bei 20 gibt es einen neuen; die Zahl links neben "Use!" stieg von 0 auf 1 und 2, der Bot hielt mit 2
            // uebrigen an). "Keine blauen Haemmer" gilt darum nur bis zum naechsten Feld, in jedem Modus.
            superLeer = false;
            superOhneForm = 0;
            superNachprobiert = false;
            hakenGedrueckt = 0;
            blindZaehler = 0;
            umrissWarteBilder = 0;
            superOhneZiel = 0;
            raster = null;
            wirkungslos = 0;
            vasenVorKlick = -1;
            zuegeAnDerForm = 0;
            eintritte = 0;
            runs.completeRun();
            de.hustlebot.core.Laufzaehler.eventStage();   // fuer !stats, ueberlebt Neustarts
            stagesSeitStabBlick++;
            Log.info("Event", Sprache.t("Field " + felderGeschafft + " completed. ", "Feld " + felderGeschafft + " geschafft. ") + berichten());
            // Autor, 29.09.: "!start event 20" lief bis 22 weiter -- das Event hoerte nur
            // auf, wenn die Haemmer alle waren. Jetzt: nach der eingestellten Zahl raus.
            if (runs.isDone()) {
                zielStages = true;
                Log.info("Event", Sprache.t("All " + runs.targetRuns() + " stages completed -- leaving the event.",
                        "Alle " + runs.targetRuns() + " Stages geschafft -- das Event wird verlassen."));
            }
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }

        // --- 3. "You have obtained" -- Belohnung annehmen. -------------
        if (ctx.clickIfVisibleIfKnown(screen, T_CLAIM, Sprache.t("Claim reward", "Belohnung annehmen"))) {
            kisteAbgeholt();
            wirkungslos = 0;
            runs.progress(Sprache.t("Reward claimed", "Belohnung angenommen"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // --- 4. Kistenfenster -- oeffnen. ------------------------------
        //
        // Kostet einen Hammer, das kleine Zeichen auf dem Knopf sagt es.
        // Diamanten kostet es nicht.
        // NUR IN DER LINKEN DREIVIERTEL: Rechts unten steht bei 30/30 die
        // Event-Truhe mit einem eigenen gruenen "Open". Die gibt nur Event-Items,
        // keine Haemmer -- Autor: "nicht interessant", also nie anfassen.
        var kistenKnopf = ctx.matcher().has(T_OPEN) ? ctx.find(screen, T_OPEN)
                : java.util.Optional.<Matcher.Hit>empty();
        // Autor 28.10.: das grosse Fenster der Material-Kiste ("Contents", "Possible materials", "Open 1 Hammer") traf
        // ev_open nicht -- der Bot schloss es mit dem X und liess die Kiste liegen (76x in einer Nacht). Gleich nach
        // einem Kisten-Klick genuegt darum eine mildere Schwelle.
        if (kistenKnopf.isEmpty() && ctx.matcher().has(T_OPEN) && System.currentTimeMillis() - letzteKisteUm < 4000) {
            kistenKnopf = ctx.matcher().find(screen, T_OPEN, OPEN_MILD);
        }
        if (kistenKnopf.isPresent() && kistenKnopf.get().centerX() < ctx.width() * 0.75) {
            Matcher.Hit k = kistenKnopf.get();
            Log.action("Klick", Sprache.t("Open chest in the event  [" + T_OPEN + " at " + k.centerX() + ","
                    + k.centerY() + "]",
                    "Kiste im Event oeffnen  [" + T_OPEN + " bei " + k.centerX() + ","
                    + k.centerY() + "]"));
            ctx.input().click(k);
            kistenGeholt++;
            wirkungslos = 0;
            letzteKisteGeoeffnet = true;
            runs.progress(Sprache.t("Chest opened", "Kiste geoeffnet"));
            nachOeffnen(ctx, runs);
            return Outcome.ACTED;
        }

        // Nacht 28.10.: klickt der Bot eine schon abgeholte Kiste noch einmal an, zeigt das Spiel nur ihren Inhalt
        // ("Gold chest -- Contents: 12 500 000 -- Reward claimed!"). Das Fenster kannte der Bot nicht: er hielt sich
        // fuer ausserhalb des Events, zog 4x die "Burg" und schloss es erst dann mit dem grossen X -- 15 s je Klick,
        // bis zu einer Minute je Kiste (Stage 7: 16 Zuege, 90 s). Jetzt: sofort das X DIESES Fensters, die Kiste nie wieder.
        if (ctx.matcher().has(T_ERHALTEN)) {
            java.util.Optional<Matcher.Hit> erhalten = ctx.matcher().find(screen, T_ERHALTEN, 0.85);
            if (erhalten.isPresent()) {
                Matcher.Hit e = erhalten.get();
                if (letzteKiste != null) {
                    letzteKiste[2] = 99;
                    letzteKiste = null;
                }
                // Das X sitzt rechts oben im Fenster (gemessen: 341 rechts, 316-458 hoeher als der Text). Nur dort
                // suchen -- das gleich aussehende X des Event-Fensters ganz oben rechts wuerde das Event schliessen.
                java.util.Optional<Matcher.Hit> x = ctx.matcher().has(T_INHALT_X)
                        ? ctx.matcher().findImBereich(screen, T_INHALT_X,
                                new Rect(e.centerX() + 250, e.centerY() - 530, 200, 300), 0.85)
                        : java.util.Optional.empty();
                if (x.isPresent()) {
                    Log.action("Klick", Sprache.t("Close the contents window of a chest already collected  [" + T_INHALT_X
                                    + " at " + x.get().centerX() + "," + x.get().centerY() + "]",
                            "Inhalts-Fenster einer schon abgeholten Kiste schliessen  [" + T_INHALT_X + " bei "
                                    + x.get().centerX() + "," + x.get().centerY() + "]"));
                    ctx.input().click(x.get());
                    ctx.sleepUntilChanged(800);
                    return Outcome.ACTED;
                }
                // Manche Inhalts-Fenster haben KEIN eigenes X ("Diamonds 15 -- You will get -- Reward claimed!",
                // logs\event-fenster-223422.png). Dann das grosse X oben rechts: solange das Fenster davor liegt, schliesst
                // der Klick nur das Fenster (9x heute Abend belegt -- danach ging es jedes Mal sofort im Event weiter).
                if (ctx.clickIfVisibleIfKnown(screen, "close_x", Sprache.t(
                        "Close the contents window (it has no X of its own) with the X at the top right",
                        "Inhalts-Fenster (ohne eigenes X) mit dem X oben rechts schliessen"))) {
                    ctx.sleepUntilChanged(800);
                    return Outcome.ACTED;
                }
            }
        }

        // --- 5. Stehen wir ueberhaupt im Event? ------------------------
        // Autor 28.10.: bei offener Lupen-/Hammerform ist das Feld abgedunkelt -- Titel, Lupe und Vasen werden dann oft
        // nicht erkannt. Der Bot hielt sich fuer "in der Burg" und zog 16x die "Burg", in Wahrheit die Form. Liegt eine
        // Form offen (oder ist ihr Haken/Kreuz zu sehen), sind wir im Event: weiter zu Schritt 6, Haken druecken.
        boolean formDa = formOffen || hakenImFeld(screen, ctx).isPresent() || formKreuz(screen, ctx).isPresent();
        if (!formDa && !imEvent(screen, ctx)) {
            if (!stoerJetzt) {   // erst Stoerfenster ausschliessen, dann den Weg suchen
                Outcome stoer = stoerfensterPruefen(screen, ctx, runs);
                if (stoer != null) return stoer;
            }
            return zumEventGehen(screen, ctx, runs);
        }

        // Autor 28.10.: Schlussphase? Steht die Zeit oben links ROT da ("Argonius leaves in"), sonst weiss/gelb
        // ("Event ends in"). Hoechstens alle 30 s nachsehen -- die Farbe aendert sich nur einmal je Event.
        // 29.10. 22:27-22:34: liegt die Lupen-Form offen oder auf dem Feld, verdeckt sie die Zeit -- gemessen wurde 0 rote
        // Bildpunkte und die Schlussphase kippte im Wechsel (6x "Normale Event-Phase" mitten in der Schlussphase). Dann gar
        // nicht messen (und den 30-s-Takt nicht verbrauchen), und erst nach 2 Nullen hintereinander zurueck auf "nicht rot".
        if (!formDa && jetztMs - schlussGeprueftUm >= 30_000) {
            schlussGeprueftUm = jetztMs;
            boolean rot = schlussphaseJetzt(screen, ctx);
            schlussNichtRotFolge = rot ? 0 : schlussNichtRotFolge + 1;
            boolean s = schlussphaseGilt(schlussphase, rot, schlussNichtRotFolge);
            if (s != schlussphase) {
                schlussphase = s;
                Log.info("Event", s ? Sprache.t("Final phase of the event: the time top left is red. If the hammers run out, the "
                                + "super hammers take over; when everything is used up, the bot stops.",
                                "Schlussphase des Events: die Zeit oben links ist rot. Gehen die Haemmer aus, uebernehmen die "
                                + "Super-Haemmer; ist alles verbraucht, haelt der Bot an.")
                        : Sprache.t("Normal event phase (time top left not red).", "Normale Event-Phase (Zeit oben links nicht rot)."));
            }
        }
        zuegeZumGeist = 0;
        fremdeBilder = 0;              // das Event ist da, nichts steht im Weg

        // DAS FELD VERMESSEN, SOLANGE NOCH ALLE VASEN ZU SIND.
        //
        // Hier lag der Fehler vom 7. September abends. Gemessen wurde erst
        // beim Legen der ersten Form -- und da hatte die Lupe schon drei
        // Vasen aufgedeckt. Mit jeder weiteren wurden es weniger:
        //
        //   Nur 12 geschlossene Vasen gefunden, zum Vermessen sind 16 noetig.
        //   Nur 8 geschlossene Vasen gefunden ...
        //   Nur 4 geschlossene Vasen gefunden ...
        //
        // Dreizehnmal "Das Spielfeld liess sich nicht vermessen", und ohne
        // Raster wird die Form nie verschoben -- sie blieb in der Mitte
        // liegen, und die untere Reihe blieb zu. Genau das hat Autor
        // beschrieben.
        //
        // Jetzt wird bei jedem Bild versucht zu messen, solange noch kein
        // Raster steht. Gleich nach dem Neumischen sind alle zwanzig Vasen
        // da, dann klappt es sofort -- und es kostet nichts, weil die
        // Vasensuche fuer dieses Bild ohnehin schon gemacht ist.
        if (raster == null) rasterHolen(screen, ctx);

        // Der Hammerstand wird nebenbei mitgelesen, wenn er zu lesen ist.
        //
        // Auf eine einzelne Null wird NICHT gehoert. Genau daran ist der Bot
        // heute Morgen gescheitert: Ein verlesener Schluesselstand hat ihn
        // fuer leer gehalten und den Rechner abgeschaltet. Zweimal
        // hintereinander dieselbe Null ist dagegen glaubhaft.
        hammerAblesen(screen, ctx);
        if (hammerStand == 0 && ++nullGelesen >= 2) {
            Log.info("Event", Sprache.t("No hammers left. ", "Keine Haemmer mehr. ") + berichten());
            return aufraeumen(screen, ctx);
        }
        if (hammerStand != 0) nullGelesen = 0;

        // --- 6. Die Dreier-Form liegt gerade auf dem Feld. -------------
        //
        // Erkannt am gruenen Haken daneben -- der steht nur waehrend des
        // Legens da. Das ist ein eigener Zustand des Spiels und hat Vorrang
        // vor allem anderen auf dem Feld.
        // Die Form liegt: am gruenen Haken ODER am roten X darunter erkennen.
        // Testlauf 28.09.: Nach dem ersten Verschieben passte der Haken nicht
        // mehr (anderer Hintergrund) -- der Bot hielt die Form fuer weg, klickte
        // noch fuenf Mal "See through" und dann Vase fuer Vase ins Leere.
        // formOffen (28.09.): Nach "See through"/"Use!" WEISS der Bot, dass eine
        // Form liegt -- bis er den Haken gedrueckt hat. Am Bild allein ging es
        // nicht sicher: nach dem Verschieben passte der Haken nicht immer, und
        // der Bot drueckte "See through" ins Leere.
        if (formOffen || hakenImFeld(screen, ctx).isPresent() || formKreuz(screen, ctx).isPresent()) {
            return formLegen(screen, ctx, runs);
        }

        // --- 6b. Blauer Hammer (Super Hammer, Autor 28.09.). ----------
        //
        // Vor dem Stab und vor den Kisten: Liegen zwei gute Items (oder Stab
        // und Item) direkt nebeneinander offen, holt EIN blauer Hammer beide
        // -- statt zwei normaler.
        if (superhammerAnstossen(screen, ctx, runs)) return Outcome.ACTED;

        // --- 7. Liegt eine gute Belohnung offen? ZUERST mitnehmen. -----
        //
        // Autor, 28.09.: "immer wenn ein zauberstab aufgedeckt wird erst alle
        // guten belohnungen mitnehmen die ausserdem aufgedeckt wurden und dann
        // den zauberstab -> dann die stage resetten". Nur, was schon offen
        // liegt -- fuer Belohnungen wird keine Vase extra zerschlagen.
        //
        // Autor 24.10.: "erst alle Lupen benutzen und dann alle Kisten und dann als letztes den Zauberstab" -- vorher
        // hiess es Lupe, Kiste, Lupe, Kiste. Solange noch eine Lupe dran ist und der Stab nicht offen liegt, wird
        // keine Kiste geschlagen; liegt der Stab offen, keine Lupe mehr, sondern Kisten und dann der Stab.
        boolean stabFertig = stabGeholt || ctx.visibleIfKnown(screen, T_STAB_DA);
        var stab = stabFertig ? java.util.Optional.<Matcher.Hit>empty() : stabImFeld(screen, ctx);
        // Ausnahme "alle Lupen" (Autor 28.10.): auch bei offenem Stab erst alle Lupen, dann Belohnungen, dann Stab.
        boolean lupenZuerst = !stabFertig && (stab.isEmpty() || de.hustlebot.core.Eventautomatik.alleLupen())
                && lupeNochDran(screen, ctx);
        // Schlussphase ohne normale Haemmer: keine Kiste einzeln (das gaebe nur wieder "Not enough hammers?") -- die
        // holt der blaue Hammer in Schritt 6b.
        if (!lupenZuerst && !nurSuperHaemmer && kisteMitnehmen(screen, ctx, runs)) {
            return Outcome.ACTED;
        }

        // --- 8. Dann der Stab. ----------------------------------------
        //
        // Danach ist die Stage durch: keine Lupe und kein Hammer mehr auf
        // die uebrigen geschlossenen Faesser, sondern gleich neu mischen
        // (Schritt 9). stabGeholt merkt sich den Klick, falls der Haken bei
        // "Magic Wand" (ev_wand_fertig) nicht erkannt wird.
        if (stab.isPresent() && !nurSuperHaemmer && !lupenZuerst) {   // ohne normale Haemmer holt den Stab der blaue Hammer (6b)
            Matcher.Hit h = stab.get();
            kistenBeimStab(screen, ctx, Sprache.t("at the wand", "beim Stab"), "event-stab-");   // Beweis: welche Kisten mit welchem Wert liegen noch da?
            hellMerken(ctx);
            Log.action("Klick", Sprache.t("Take the magic wand  [" + h.name() + " at " + h.centerX() + "," + h.centerY()
                    + ", match " + String.format("%.3f", h.score()) + "]",
                    "Zauberstab holen  [" + h.name() + " bei " + h.centerX() + "," + h.centerY()
                    + ", Uebereinstimmung " + String.format("%.3f", h.score()) + "]"));
            ctx.input().click(h);
            stabGeholt = true;
            stabGeholtUm = System.currentTimeMillis();
            wirkungslos = 0;
            eintritte = 0;
            runs.progress(Sprache.t("Magic wand taken", "Zauberstab geholt"));
            oeffnenSchnell(ctx, runs);
            return Outcome.ACTED;
        }

        // --- 9. Alles eingesammelt: Feld neu mischen. ------------------
        //
        // Zwei Stimmen muessen zusammenpassen: der gruene Haken bei "Magic
        // Wand" UND der Knopf "Refresh the field", der nicht mehr grau sein
        // darf. Widersprechen sie sich, war der Haken ein Fehltreffer -- und
        // dann wird nicht gemischt, sondern weitergespielt. Ein zu frueh
        // gemischtes Feld wirft die ganze Arbeit weg.
        if (stabFertig && !ctx.visibleIfKnown(screen, T_REFRESH_GRAU)) {

            // Foto vor dem Neumischen (Autor, 29.09.: "pruef, ob der Bot alles erkennt"):
            // was jetzt noch offen liegt, hat er liegen gelassen.
            if (ctx.visibleIfKnown(screen, T_REFRESH)) {
                kistenBeimStab(screen, ctx, Sprache.t("before the refresh", "vor dem Neumischen"), "event-ende-");
                eigeneMotiveNachschneiden(screen, ctx);
            }
            if (ctx.clickIfVisibleIfKnown(screen, T_REFRESH,
                    Sprache.t("Magic wand taken -- refresh the field", "Zauberstab geholt -- Feld neu mischen"))) {
                // NICHT hier zaehlen. Jetzt kommt erst die Nachfrage, und
                // erst das Ja darauf mischt wirklich neu. Gezaehlt wird oben
                // bei Schritt 2b -- der sich hieran merkt, dass die Nachfrage
                // vom Bot selbst ausgeloest wurde.
                neuMischenGefragt = true;
                if (!ctx.matcher().has(T_REFRESH_YES) && !hinweisJa) {
                    hinweisJa = true;
                    Log.warn("Event", Sprache.t("The template " + T_REFRESH_YES + " is missing. After the "
                            + "yellow button the game asks \"Are you sure you want to "
                            + "refresh the field?\" -- without the picture of the green \"Yes\" "
                            + "the bot cannot get past it. Capture it with Capture-Images.exe.",
                            "Die Vorlage " + T_REFRESH_YES + " fehlt. Nach dem "
                            + "gelben Knopf fragt das Spiel \"Are you sure you want to "
                            + "refresh the field?\" -- ohne das Bild vom gruenen \"Yes\" "
                            + "kommt der Bot da nicht durch. Mit Capture-Images.exe aufnehmen."));
                }
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }

            // Haken da, aber der gelbe Knopf noch nicht zu sehen -- das
            // Spiel braucht einen Augenblick fuer die Umschaltung.
            ctx.sleep(tempo.ms(150));   // Tempo: 400 ms waren je Stage verschenkt
            return Outcome.WAITING;
        }
        // Stab geholt, Refresh noch grau: warten -- NIE eine Lupe oder einen
        // Hammer auf die uebrigen Faesser (Autor). Nach 20 s ohne gelben Knopf
        // war der Klick wohl daneben; dann normal weiter.
        if (stabGeholt) {
            long seitStab = System.currentTimeMillis() - stabGeholtUm;
            // Liegt der Stab nach 2,5 s noch offen da, hat etwas den Klick verschluckt
            // (29.09., 01:14: ein spaeter "Claim" -- danach 20 s gewartet, zweimal).
            // Nur, wenn der Haken bei "Magic Wand" fehlt und kein Fenster offen ist: hinter
            // einem Fenster ist der Stab abgedunkelt noch "zu sehen" (Code-Pruefung 29.09.).
            if (seitStab >= 3000 && !ctx.visibleIfKnown(screen, T_STAB_DA)
                    && !ctx.visibleIfKnown(screen, T_OPEN) && !ctx.visibleIfKnown(screen, T_CLAIM)
                    && stabImFeld(screen, ctx).isPresent()) {
                stabGeholt = false;
                stageFehler++;
                Log.info("Event", Sprache.t("The wand is still there after the click -- it will be taken again in a moment.",
                        "Der Stab liegt nach dem Klick noch da -- er wird gleich noch einmal geholt."));
                return Outcome.WAITING;
            }
            if (seitStab < 20_000) {
                ctx.sleep(tempo.ms(150));
                return Outcome.WAITING;
            }
            stabGeholt = false;
            Log.info("Event", Sprache.t("After the wand click \"Refresh the field\" did not turn yellow -- carrying on as usual.",
                    "Nach dem Stab-Klick wurde \"Refresh the field\" nicht gelb -- es geht normal weiter."));
        }

        // --- 10. Noch eine Lupe uebrig? -------------------------------
        //
        // Fuenf sind der Regelfall. GENAU EINE mehr, und nur wenn noch
        // mindestens fuenf Vasen zu sind -- Autor, wortgleich: "wenn noch 5
        // vasen zu sind obwohl er 5 lupen benutzt hat soll er noch eine 6te
        // lupe benutzen."
        //
        // Hier stand erst "ab drei zuen Vasen, hoechstens acht Lupen", und
        // das war zu grosszuegig gelesen. Im Bericht vom 8. September um
        // 00:41 steht "Lupe 8 von 8", waehrend nur noch drei Vasen zu waren
        // -- Autor: "manchmal sind nur noch 3 vasen offen der bot hat schon
        // 5-6 lupen benutzt und benutzt trotzdem noch eine siebte und achte
        // lupe". Bei drei zuen Vasen deckt eine Lupe im besten Fall drei
        // auf, meistens aber eine, weil die Form nicht dorthin passt: "Die
        // Form liegt schon gut (1 von 3 Vasen noch zu)". Dafuer lohnt sich
        // keine Lupe -- zuschlagen ist dann schneller und billiger.
        boolean nachschlag = !de.hustlebot.core.Eventautomatik.alleLupen() && lupenBenutzt >= MAX_LUPEN
                && lupenBenutzt < MAX_LUPEN_NOTFALLS
                && nochGeschlossen(screen, ctx) >= NACHSCHLAG_AB;

        if (nachschlag && !nachschlagGemeldet) {
            nachschlagGemeldet = true;
            Log.info("Event", Sprache.t("After " + lupenBenutzt + " magnifiers "
                    + nochGeschlossen(screen, ctx) + " vases are still closed -- that is "
                    + NACHSCHLAG_AB + " or more. One single extra "
                    + "magnifier will be used, after that hammer only.",
                    "Nach " + lupenBenutzt + " Lupen sind noch "
                    + nochGeschlossen(screen, ctx) + " Vasen zu -- das sind "
                    + NACHSCHLAG_AB + " oder mehr. Es wird noch eine einzige "
                    + "Lupe benutzt, danach nur noch Hammer."));
        }

        // Die harte Sperre. Autor: "niemals 7 oder mehr lupen benutzen !
        // niemals pro stage. 6 soll schon das absolute limit sein." Sie
        // steht bewusst zusaetzlich hier, unabhaengig davon, wie nachschlag
        // gerechnet wird -- an dieser Zeile kommt keine siebte Lupe vorbei.
        // Lupen nur mit Silber- oder Gold-Pass (Autor, 28.09.; !settings -> Hammer-Event).
        // Wie viele Lupen je Feld: einstellbar (!settings, Standard 5 -- Autor, 28.09.:
        // mit dem Gold-Pass kommen je Feld 3 zurueck, ohne Pass keine).
        // Nach jeder Lupe erst in Ruhe hinsehen (Autor, 28.09.): die Schritte davor
        // (Belohnungen, Stab) sollen den aufgedeckten Inhalt sehen, bevor die
        // naechste Lupe kommt.
        if (System.currentTimeMillis() - lupeBestaetigtUm < tempo.ms(LUPE_HINSEHEN_MS)
                || System.currentTimeMillis() - Math.max(textWegUm, Stoerfenster.textWeggeklicktUm()) < 600) {   // Text noch am Verschwinden (29.09.: 0,9 -> 0,6 s)
            ctx.sleep(tempo.ms(100));
            return Outcome.WAITING;
        }
        // Eine Lupe nur, wenn noch mindestens drei Faesser zu sind -- auf ein
        // aufgedecktes Feld laesst das Spiel keine Lupe legen (Autor).
        int zuJetzt = nochGeschlossen(screen, ctx);
        // Autor 24.10. (Ideenliste B01): die erste Lupe jedes neuen Feldes kam zu frueh -- das Feld war noch nicht fertig
        // gezeichnet (249 von 300 Feldern "did not take", je ~2,2 s). Erste Lupe erst, wenn das frische Feld steht
        // (mindestens 18 der 20 Vasen zu sehen), hoechstens 3 s nach dem Neumischen warten.
        if (lupenBenutzt == 0 && neuGemischtUm > 0 && zuJetzt < 18
                && System.currentTimeMillis() - neuGemischtUm < 3000) {
            ctx.sleep(80);
            return Outcome.WAITING;
        }
        boolean lupeSinnvoll = !lupenGesperrt && zuJetzt >= lupeAbZu(screen, ctx);
        if (de.hustlebot.core.Eventautomatik.lupenErlaubt() && lupeSinnvoll
                && lupenBenutzt < lupenGrenze()) {
            // Im Nachschlag zaehlt die Meldung bis MAX_LUPEN_NOTFALLS. Sonst
            // stuende da "Lupe 6 von 5", und das sieht im Protokoll nach
            // einem Fehler aus, wo gerade die gewollte Ausnahme greift.
            int wieViele = lupenGrenze();
            if (raster != null) {
                zuVorForm = geschlossenesRaster(screen, ctx, raster);
                formKontrolle(zuVorForm);
            }
            java.awt.image.BufferedImage zahlJetzt = lupenZahlBild(screen, ctx);
            if (!de.hustlebot.core.Eventautomatik.alleLupen()
                    && lupenLeerBild != null && zahlGleich(zahlJetzt, lupenLeerBild) >= ZAHL_GLEICH) {
                // Autor 24.10.: die Zahl sieht aus wie bei "keine Lupen" -- nicht druecken, auf diesem Feld nur Hammer.
                lupenGesperrt = true;
                Log.info("Event", Sprache.t("No magnifiers left (the number looks like last time when none were left) -- hammer only on this field.",
                        "Keine Lupen mehr (die Zahl sieht aus wie beim letzten Mal ohne Lupen) -- auf diesem Feld nur Hammer."));
                return Outcome.WAITING;   // naechstes Bild: erst die offenen Kisten, dann Hammer
            }
            zahlVorKlick = zahlJetzt;
            lupeVerlaengert = false;
            if (ctx.clickIfVisibleIfKnown(screen, T_LUPE,
                    Sprache.t("Magnifier " + (lupenBenutzt + 1) + " of " + wieViele, "Lupe " + (lupenBenutzt + 1) + " von " + wieViele))) {
                lupenBenutzt++;
                lupeGeklicktUm = System.currentTimeMillis();
                formOffen = true;
                formOhneHaken = 0;
                hakenGedrueckt = 0;
                blindZaehler = 0;
                umrissWarteBilder = 0;
                zielPaar = null;        // die naechste Form ist die Lupe, nicht der blaue Hammer
                wirkungslos = 0;
                eintritte = 0;          // wir sind angekommen und tun etwas
                runs.progress(Sprache.t("Magnifier used", "Lupe benutzt"));
                // Autor 24.10. (Ideenliste A02): nicht bis zu 1,5 s auf eine Aenderung warten. Gemessen an 1565 Lupen-Klicks
                // (Protokolle 05-54 und 13-27): kam eine Aenderung, dann nach Median 65-120 ms, hoechstens nach 447 ms;
                // blieb sie aus (116 Mal), war der Klick ins Leere gegangen und die 1,5 s waren verschenkt. Das Warten auf
                // die Form (und die Zeitgrenze fuer "Lupe hat nicht gegriffen", 1,8 s) laeuft danach unveraendert weiter.
                ctx.sleepUntilChanged(600);
                return Outcome.ACTED;
            }
            // KEIN LUPENKNOPF ZU SEHEN -- erst einmal je Feld kurz warten (Test
            // 28.09., 22:02: ein Angebot lag ueber dem Knopf, der Bot hielt die
            // Lupen fuer alle und nahm den Hammer). Kommt er in 2,5 s nicht,
            // sind wirklich keine mehr da.
            if (!lupeFehltGewartet) {
                if (lupeFehltSeit == 0) lupeFehltSeit = System.currentTimeMillis();
                if (System.currentTimeMillis() - lupeFehltSeit < 2500) {
                    ctx.sleep(tempo.ms(250));
                    return Outcome.WAITING;
                }
                lupeFehltGewartet = true;
            }
            // Kein Lupenknopf zu sehen: Es sind keine mehr da. Dann eben
            // ohne -- Autors Ansage.
            if (lupenBenutzt == 0 && !hinweisGezeigt) {
                hinweisGezeigt = true;
                Log.info("Event", Sprache.t("No magnifier button in sight. Playing on without "
                        + "magnifiers, hammer only.",
                        "Kein Lupenknopf zu sehen. Es wird ohne Lupen "
                        + "weitergespielt, nur mit Hammer."));
            }
        }

        // --- 11. Vase zerschlagen. -------------------------------------
        return vaseZerschlagen(screen, ctx, runs);
    }

    /**
     * Kommt in diesem Feld noch eine Lupe dran (dieselben Bedingungen wie Schritt 10)? Autor 24.10.: dann erst die
     * Lupen, die Kisten danach. Fehlt der Lupenknopf schon laenger (lupeFehltGewartet), sind keine mehr da.
     */
    private boolean lupeNochDran(Mat screen, BotContext ctx) {
        return de.hustlebot.core.Eventautomatik.lupenErlaubt() && !lupenGesperrt && !lupeFehltGewartet
                && lupenBenutzt < lupenGrenze()
                && nochGeschlossen(screen, ctx) >= lupeAbZu(screen, ctx);
    }

    /** Ausnahme "alle Lupen" (Autor 28.10.): je Stage so viele, wie es Felder gibt. */
    private static final int ALLE_LUPEN_GRENZE = SPALTEN * REIHEN;

    private int lupenGrenze() {
        return de.hustlebot.core.Eventautomatik.alleLupen() ? ALLE_LUPEN_GRENZE
                : de.hustlebot.core.Eventautomatik.maxLupen();
    }

    /** Ab so vielen zuen Vasen noch eine Lupe: sonst 3. Ausnahme "alle Lupen": schon ab einer -- Autor 28.10. 22:35:
     *  "er soll bei der Ausnahme-Regel ALLE Faesser aufdecken", auch wenn der Stab schon offen liegt (Lupen sind genug da). */
    private int lupeAbZu(Mat screen, BotContext ctx) {
        return de.hustlebot.core.Eventautomatik.alleLupen() ? 1 : 3;
    }

    // ------------------------------------------------------------------
    //  Einzelne Schritte
    // ------------------------------------------------------------------

    /**
     * Das Angebot mit Preis. Wird immer nur weggedrueckt.
     *
     * NUR WENN DIE UEBERSCHRIFT DA IST. Das ist keine Feinheit, sondern die
     * Lehre aus dem 7. September:
     *
     *     Klick: Angebot wegdruecken  [ev_angebot_x bei 1123,33]
     *     Klick: Flaschengeist -- ins Event
     *     Klick: Angebot wegdruecken  [ev_angebot_x bei 1123,33]
     *     Klick: Flaschengeist -- ins Event
     *     ...
     *
     * 1123,33 ist die obere rechte Ecke -- das rote X des EVENT-Fensters.
     * Das sieht genauso aus wie das des Angebots, und der Bot hat mit einer
     * Uebereinstimmung von 0,98 auch genau das gefunden. Er ging also ins
     * Event, machte es im selben Atemzug wieder zu, ging wieder rein, und so
     * fort, bis Autor den Not-Aus gedrueckt hat.
     *
     * Dasselbe war der Kathedrale schon einmal passiert; dort steht seither
     * eine ausfuehrliche Warnung im Code. Die Regel daraus, hier noch einmal:
     *
     *     EIN ROTES X IST KEIN BEWEIS FUER IRGENDEIN FENSTER.
     *     Erst die Ueberschrift sagt, welches Fenster offen ist.
     */
    /**
     * Ist das Angebot offen? KASTENSUCHE (Autor, 29.09.: "bei Knoepfen, die immer an
     * der gleichen Stelle sind"): Die Ueberschrift sitzt immer oben (das X bei
     * 1113,42) -- also nur die obere Bildhaelfte, dazu die schnelle Vorsuche in
     * halber Groesse. Vorher lief die Suche in JEDEM Bild uebers ganze Bild
     * (bis 112-mal je Stage, je gut 20 ms).
     */
    private boolean angebotDa(Mat bild, BotContext ctx) {
        if (!ctx.matcher().has(T_ANGEBOT)) return false;
        return !ctx.matcher().findAllSchnell(bild, T_ANGEBOT,
                new org.opencv.core.Rect(0, 0, bild.width(), bild.height() / 2),
                Matcher.DEFAULT_THRESHOLD - 0.12, Matcher.DEFAULT_THRESHOLD, 1).isEmpty();
    }

    private boolean angebotWegdruecken(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_ANGEBOT)) {
            // Ohne Ueberschrift laesst sich das Angebot nicht sicher
            // erkennen. Dann wird lieber gar nichts gedrueckt -- ein
            // stehengebliebenes Angebot haelt den Bot auf, ein falsch
            // gedruecktes X wirft ihn aus dem Event.
            if (!hinweisAngebot) {
                hinweisAngebot = true;
                Log.info("Event", Sprache.t("The template " + T_ANGEBOT + " is missing. Offers with a "
                        + "price are therefore not dismissed -- the red X alone "
                        + "is no safe sign, the event window has one too.",
                        "Die Vorlage " + T_ANGEBOT + " fehlt. Angebote mit "
                        + "Preis werden deshalb nicht weggedrueckt -- das rote X allein "
                        + "ist kein sicheres Zeichen, das gehoert auch dem Event-Fenster."));
            }
            return false;
        }
        if (!angebotDa(screen, ctx)) return false;

        if (ctx.clickIfVisibleIfKnown(screen, T_ANGEBOT_X,
                Sprache.t("Dismiss offer -- it is never bought", "Angebot wegdruecken -- es wird nie gekauft"))) {
            ctx.sleepUntilChanged(1200);
            return true;
        }
        // Rueckfall (Autor, 28.09.: "unbedingt auf das X oben in der Ecke"):
        // die Ueberschrift ist sicher da, also darf auch das allgemeine rote X
        // oben rechts gedrueckt werden -- aber nur dort oben rechts.
        var x = ctx.matcher().has("close_x") ? ctx.find(screen, "close_x")
                : java.util.Optional.<Matcher.Hit>empty();
        if (x.isPresent() && x.get().centerX() > ctx.width() * 0.80 && x.get().centerY() < ctx.height() * 0.20) {
            Log.action("Klick", Sprache.t("Dismiss offer (red X top right) -- it is never bought",
                    "Angebot wegdruecken (rotes X oben rechts) -- es wird nie gekauft"));
            ctx.input().click(x.get());
            ctx.sleepUntilChanged(1200);
            return true;
        }
        return false;
    }

    private boolean hinweisAngebot = false;
    private boolean hinweisJa = false;
    private boolean hinweisBurg = false;

    /** Sind wir im Event-Fenster? */
    private boolean imEvent(Mat screen, BotContext ctx) {
        // Zwei Anhaltspunkte, damit es auch dann klappt, wenn Autor die
        // Ueberschrift nicht aufgenommen hat.
        return ctx.visibleIfKnown(screen, T_TITEL)
            || ctx.visibleIfKnown(screen, T_LUPE)
            || ctx.visibleIfKnown(screen, vasenMotive(ctx).isEmpty() ? T_VASE : vasenMotive(ctx).get(0));
    }

    /**
     * Von der Burg zum Flaschengeist.
     *
     * Autors Beschreibung: in die Mitte des Bildschirms druecken und von
     * rechts nach links ziehen, bis der Flaschengeist auftaucht, dann auf
     * ihn drauf.
     */
    private Outcome zumEventGehen(Mat screen, BotContext ctx, RunTracker runs) {

        // Seit 28.09. NUR ueber den Event-Kalender (Autor: den Flaschengeist,
        // der jedes Event anders aussieht, nicht mehr suchen).
        if (!ctx.matcher().has(T_KALENDER)) {
            Log.error("Event", Sprache.t("The template " + T_KALENDER + " is missing -- without the calendar icon "
                    + "the bot cannot find its way into the event. Please capture it with Capture-Images.exe (group HAMMER-EVENT). "
                    + "The event is skipped.",
                    "Die Vorlage " + T_KALENDER + " fehlt -- ohne das Kalender-Symbol findet "
                    + "der Bot nicht ins Event. Bitte mit Capture-Images.exe aufnehmen (Gruppe HAMMER-EVENT). "
                    + "Das Event wird ausgelassen."));
            return Outcome.FINISHED;
        }
        Outcome kalender = ueberDenKalender(screen, ctx, runs);
        if (kalender != null) return kalender;

        // Sind wir ueberhaupt in der Burg? Erkannt am "Map"-Knopf unten
        // links, in beiden Aussehen.
        boolean inDerBurg = ctx.visibleIfKnown(screen, T_MAP)
                         || ctx.visibleIfKnown(screen, T_MAP_ALT);

        // AUF DER WELTKARTE (Test 28.09., 22:00): Nach Leuchtturm, Kolosseum
        // oder Titanen-Turm steht das Spiel auf der Weltkarte. Der Bot zog dort
        // 16 Mal am Bild, suchte das Kalender-Symbol der Burg und gab auf.
        // Erst mit "Home" unten links in die Burg.
        if (!inDerBurg && (Weltkarte.erkannt(screen, ctx) || ctx.visibleIfKnown(screen, Weltkarte.T_HOME))
                && zuegeZumGeist < MAX_ZUEGE_ZUM_GEIST) {
            zuegeZumGeist++;
            Weltkarte.heim(screen, ctx, "Event");
            return Outcome.ACTED;
        }

        // HIER STAND EIN KLICK NEBEN DEN "MAP"-KNOPF -- ER IST WIEDER RAUS.
        //
        // Die Idee war, die Ansicht auf die Burg zurueckzuholen, bevor
        // gesucht wird. Der Knopf rechts neben "Map" ist aber nicht die
        // Burg-Ansicht, sondern das CLAN-TERRITORIUM. Autor: "jetzt drueckt
        // er immer auf mein clan terretorium statt auf das event."
        //
        // Geraten war der Abstand ohnehin -- an einem Bildschirmfoto
        // abgemessen, ohne zu wissen, was der Knopf tut. Ein Klick, von dem
        // man nicht weiss, was er ausloest, gehoert nicht in einen Bot, der
        // nachts allein laeuft.
        //
        // Das eigentliche Problem bleibt: Die Suche schiebt die Ansicht weg.
        // Deshalb zieht sie jetzt nur noch kurz und wechselt die Richtung --
        // so pendelt der Bot um die Stelle, an der er angefangen hat, statt
        // an den Rand der Burg zu wandern.

        // Ein fremdes Fenster im Weg? Das rote X bringt zurueck -- aber erst
        // nach mehreren Bildern hintereinander.
        //
        // WARUM DIE GEDULD. close_x passt mit 0,98 auf das rote X des
        // EVENT-Fensters -- nachgemessen, es ist dieselbe Schaltflaeche. Und
        // hierher kommt der Bot immer dann, wenn er das Event GERADE NICHT
        // erkennt: waehrend einer Belohnungseinblendung, einer Animation,
        // eines Bildwechsels. In genau einem solchen Augenblick wuerde ein
        // sofortiger Klick sein eigenes Event zumachen -- derselbe Fehler
        // wie beim Angebot am 7. September, nur durch die Nebentuer.
        //
        // Eine Animation dauert ein paar Bilder. Ein Fenster, das wirklich im
        // Weg steht, bleibt stehen. Also wird gezaehlt: Erst wenn der Bot
        // fuenf Bilder lang WEDER im Event NOCH in der Burg war, ist da
        // wirklich etwas Fremdes, und dann darf das X gedrueckt werden.
        if (inDerBurg) {
            fremdeBilder = 0;
        } else if (++fremdeBilder >= GEDULD_FREMDES_FENSTER
                && ctx.clickIfVisibleIfKnown(screen, "close_x",
                        Sprache.t("Close window to get to the castle", "Fenster schliessen, um in die Burg zu kommen"))) {
            fremdeBilder = 0;
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // HIER STAND FRUEHER EIN "return UNKNOWN".
        //
        // Und zwar immer dann, wenn der "Map"-Knopf nicht erkannt wurde. Der
        // Bot tat dann gar nichts: kein Zug, kein Klick, keine Zeile im
        // Protokoll. Am 7. September sind so dreizehn Sekunden vergangen, in
        // denen zwischen "Start: Hammer-Event" und dem ersten Klick auf den
        // Flaschengeist NICHTS im Bericht steht -- weitergegangen ist es erst,
        // als Autor selbst die Burg verschoben hat.
        //
        // Stillstehen ist die schlechteste aller Moeglichkeiten. Ziehen kostet
        // nichts: In der Burg verschiebt es das Bild, sonst passiert nichts.
        // Also wird gezogen, ob die Burg nun sicher erkannt wurde oder nicht.
        if (!inDerBurg && !hinweisBurg) {
            hinweisBurg = true;
            Log.info("Event", Sprache.t("The castle cannot be recognised by the \"Map\" button. The bot "
                    + "drags the view anyway and looks for the calendar icon -- "
                    + "standing still would be worse.",
                    "Die Burg ist am \"Map\"-Knopf nicht zu erkennen. Der Bot "
                    + "verschiebt das Bild trotzdem und sucht das Kalender-Symbol -- "
                    + "stehenbleiben waere schlechter."));
        }

        if (zuegeZumGeist >= MAX_ZUEGE_ZUM_GEIST) {
            // ABSICHTLICH KEIN ABBRUCH MIT FEHLER.
            //
            // Ein Fehler wuerde die ganze Warteschlange verwerfen -- und
            // damit stuende der Bot den Rest der Nacht still, nur weil ein
            // Event nicht zu finden war. Dieselbe Ueberlegung wie beim
            // Clan-Krieg: Der Botengang faellt aus, die Arbeit geht weiter.
            Log.error("Event", Sprache.t("The calendar icon was not found in the castle. Please check whether "
                    + "the template " + T_KALENDER + " still matches (without the red exclamation mark). "
                    + "The event is skipped, the next mode carries on as usual.",
                    "Das Kalender-Symbol wurde in der Burg nicht gefunden. Bitte pruefen, ob "
                    + "die Vorlage " + T_KALENDER + " noch passt (ohne das rote Ausrufezeichen). "
                    + "Das Event wird ausgelassen, der naechste Modus laeuft normal weiter."));
            return Outcome.FINISHED;
        }

        int breite = ctx.width();
        int hoehe = ctx.height();
        if (breite < 200 || hoehe < 200) return Outcome.WAITING;

        // SYSTEMATISCH IN ALLE VIER RICHTUNGEN.
        //
        // Erst wurde nur waagerecht gesucht, nach Autors Beschreibung "von
        // rechts nach links ziehen". Am 7. September zog der Bot achtmal --
        // viermal hin, viermal zurueck -- und sah den Flaschengeist nie:
        //
        //   17:07:54  Burg verschieben (1 von 8)
        //   ...
        //   17:08:06  Burg verschieben (8 von 8, jetzt in die andere Richtung)
        //   17:08:09  Der Flaschengeist wurde in der Burg nicht gefunden.
        //
        // Auf Autors Video wandert die Ansicht aber nach OBEN, aufs oberste
        // Stockwerk -- die Burg ist ein Turm, man faehrt sie hoch und
        // runter. Waagerecht allein findet den Geist also nie, wenn man auf
        // der falschen Etage steht.
        //
        // Jetzt wird der Reihe nach gezogen: nach unten (die Ansicht faehrt
        // hoch), nach rechts, nach links, nach oben -- und wieder von vorn.
        // Ein Zug in die falsche Richtung kostet eineinhalb Sekunden; einer,
        // der gar nicht erst versucht wird, kostet das ganze Event.
        int mx = breite / 2, my = hoehe / 2;
        int weitX = (int) (breite * WEITE);
        int weitY = (int) (hoehe * WEITE);

        // DIE REIHENFOLGE IST NICHT BELIEBIG.
        //
        // Zuerst nach rechts -- das ist die Richtung, die im Betrieb
        // funktioniert hat. Dann nach links, falls die Burg schon am
        // Anschlag stand. Die beiden senkrechten stehen HINTEN, als
        // Rueckfall.
        //
        // Vorher stand "Ansicht nach oben" an erster Stelle. Autor dazu:
        // "bot scrollt erst nach oben und dann in die richtige richtung, er
        // soll nicht nach oben scrollen." Ein Bild aus der Botsicht gab ihm
        // recht -- nach dem ersten Zug schaute der Bot von weit oben auf die
        // Burg, und der Flaschengeist war unten aus dem Bild geschoben. Der
        // erste Zug hat die Lage also nicht nur nicht verbessert, sondern
        // verschlechtert.
        int[][] richtungen = {
                { mx - weitX, my, mx + weitX, my },   // nach rechts  -- der bewaehrte
                { mx + weitX, my, mx - weitX, my },   // nach links   -- zurueck
                { mx, my + weitY, mx, my - weitY },   // Ansicht runter
                { mx, my - weitY, mx, my + weitY },   // Ansicht hoch -- zuletzt
        };
        String[] namen = {Sprache.t("to the right", "nach rechts"), Sprache.t("to the left", "nach links"),
                          Sprache.t("view down", "Ansicht nach unten"), Sprache.t("view up", "Ansicht nach oben")};

        // MEHRERE ZUEGE JE RICHTUNG, SONST KOMMT DER BOT NIE VOM FLECK.
        //
        // Vorher wechselte die Richtung bei JEDEM Zug: rechts, links, hoch,
        // runter -- und danach stand die Burg wieder genau da, wo sie
        // angefangen hatte. Zwoelf Zuege waren drei solche Runden, also
        // dreimal nichts.
        //
        // Ein Bild aus der Botsicht hat es gezeigt: Der Bot schaute die
        // ganze Zeit ins INNERE der Burg, auf die Zimmer. Der Flaschengeist
        // steht aber draussen. Dahin kommt man nicht mit vier Zuegen, die
        // sich gegenseitig aufheben, sondern nur, indem man eine Richtung
        // durchhaelt.
        //
        // Jetzt sind es ZUEGE_JE_RICHTUNG Zuege am Stueck, und jeder geht
        // ueber gut die halbe Bildbreite statt ueber ein Viertel.
        int wahl = (zuegeZumGeist / ZUEGE_JE_RICHTUNG) % richtungen.length;
        int von = richtungen[wahl][0], vonY = richtungen[wahl][1];
        int bis = richtungen[wahl][2], bisY = richtungen[wahl][3];

        // Wie nah war der Bot dran? Ohne diese Zahl bleibt "nicht gefunden"
        // eine Behauptung -- mit ihr weiss man, ob die Vorlage knapp
        // verfehlt oder gar nicht da war.
        // ALLE Vorlagen melden, nicht nur die erste.
        //
        // Hier stand nur ev_genie -- und als Autor drei weitere aufgenommen
        // hatte, sagte das Protokoll trotzdem nichts ueber sie. Seine Frage
        // war berechtigt: "hast du dem bot schon gesagt er soll auf die
        // neuen vorlagen achten?" Benutzt hat er sie, gemeldet nicht.
        // Wie nah war der Bot am Kalender-Symbol? Ohne die Zahl bleibt "nicht
        // gefunden" eine Behauptung.
        var kalenderTreffer = ctx.matcher().find(screen, T_KALENDER, 0.40);
        Log.debug("Event", "Wie nah dran: " + T_KALENDER + " " + (kalenderTreffer.isPresent()
                ? String.format("%.3f", kalenderTreffer.get().score()) : "nichts"));

        zuegeZumGeist++;
        Log.action("Event", Sprache.t("Drag the castle (" + namen[wahl] + ") to find the "
                + "calendar icon ("
                + zuegeZumGeist + " of " + MAX_ZUEGE_ZUM_GEIST + ")",
                "Burg verschieben (" + namen[wahl] + "), um das "
                + "Kalender-Symbol zu finden ("
                + zuegeZumGeist + " von " + MAX_ZUEGE_ZUM_GEIST + ")"));
        ctx.input().drag(von, vonY, bis, bisY);
        runs.progress(Sprache.t("Castle dragged", "Burg verschoben"));
        ctx.sleepUntilChanged(1200);
        return Outcome.ACTED;
    }

    /**
     * Das orange "HC"-Abzeichen oben rechts an jeder SONDER-Belohnung (Autor,
     * 28.09.: Essenz-Kisten, Diamanten, alle "Special rewards"). Ausgeschnitten
     * aus einem Foto des Bots (Ordner gelernt\); getestet an 8 Feldern: 10 von 10
     * Sonder-Belohnungen mit 0,72 bis 1,00, kein Fehltreffer.
     */
    private static final String T_HC = "ev_kiste_hc";   // 29.09.: nicht mehr als Belohnung, siehe kistenNamen()

    /**
     * VORAUSKLICK "OPEN" (Autor, 29.09.): Nach dem Klick auf eine Kiste, ein
     * HC-Item oder den Stab kommt das Fenster mit "Open" (Hammer darunter) fast
     * sofort. In schneller Folge an seiner Stelle nachsehen und im selben Moment
     * druecken (vorher im Mittel 0,31 s). Nie die Truhe 30/30 rechts unten.
     */
    private void oeffnenSchnell(BotContext ctx, RunTracker runs) {
        long ende = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < ende) {
            ctx.sleep(tempo.ms(30));
            Mat bild = ctx.refresh();
            if (bild == null) return;
            if (angebotDa(bild, ctx)) return;
            var k = ctx.matcher().has(T_OPEN) ? ctx.find(bild, T_OPEN) : java.util.Optional.<Matcher.Hit>empty();
            if (k.isPresent() && k.get().centerX() < ctx.width() * 0.75) {
                Log.action("Klick", Sprache.t("Open chest in the event (right away)  [" + T_OPEN + " at " + k.get().centerX()
                        + "," + k.get().centerY() + "]",
                        "Kiste im Event oeffnen (sofort)  [" + T_OPEN + " bei " + k.get().centerX()
                        + "," + k.get().centerY() + "]"));
                ctx.input().click(k.get());
                kistenGeholt++;
                letzteKisteGeoeffnet = true;
                runs.progress(Sprache.t("Chest opened", "Kiste geoeffnet"));
                nachOeffnen(ctx, runs);
                return;
            }
            if (ctx.visibleIfKnown(bild, T_CLAIM)) return;   // gleich "Claim" -- das macht der Schritt
        }
    }

    /**
     * NACH "OPEN" NICHT FEST WARTEN (Autor, 29.09.): Bei Kisten kommt nach der
     * Animation "You have obtained" mit "Claim" -- dann sofort druecken. Bei
     * vielen Items kommt gar nichts; dann soll es sofort weitergehen, statt bis
     * zu 2,6 s auf einen Claim zu warten, der nie kommt. "Frei" heisst: ab 2,0 s
     * nach "Open" zwei Bilder hintereinander so hell wie vorher und ohne "Open".
     * Gemessen 29.09., 01:12-01:16: Der Claim kam in 21 von 21 Faellen, aber erst
     * 1,24-1,64 s nach "Open" -- das Feld war da schon wieder hell. Mit 0,3 s
     * verschluckte der spaete Claim die naechste Lupe (je Fall ~2 s verloren).
     */
    /** Nach so vielen Nahrungskisten das Event verlassen (0 = nie; Kathedralen-Kreislauf). */
    private int nahrungsGrenze = 0;
    /** Nahrungskisten in diesem Gang ins Event. */
    private int nahrungsKisten = 0;

    public void nahrungsGrenze(int n) {
        nahrungsGrenze = Math.max(0, n);
    }

    public int nahrungsKisten() {
        return nahrungsKisten;
    }

    private void nachOeffnen(BotContext ctx, RunTracker runs) {
        if (letzteKisteName != null && de.hustlebot.core.Eventkisten.istNahrung(letzteKisteName)) {
            nahrungsKisten++;
            Log.info("Event", Sprache.t("Food chest " + nahrungsKisten + (nahrungsGrenze > 0 ? " of " + nahrungsGrenze : "")
                    + " on this visit.",
                    "Nahrungskiste " + nahrungsKisten + (nahrungsGrenze > 0 ? " von " + nahrungsGrenze : "")
                    + " in diesem Gang."));
            if (nahrungsGrenze > 0 && nahrungsKisten >= nahrungsGrenze && !zielStages) {
                zielStages = true;
                Log.info("Event", Sprache.t(nahrungsKisten + " food chests collected -- leaving the event, "
                        + "on to the Cathedral (Autor, 29.09.).",
                        nahrungsKisten + " Nahrungskisten geholt -- das Event wird verlassen, "
                        + "weiter mit der Kathedrale (Autor, 29.09.)."));
            }
        }
        long start = System.currentTimeMillis(), ende = start + 2600;
        int frei = 0;
        while (System.currentTimeMillis() < ende) {
            ctx.sleep(tempo.ms(40));
            Mat bild = ctx.refresh();
            if (bild == null) return;
            if (angebotDa(bild, ctx)) return;
            if (zuWenigHaemmerFenster(ctx.matcher(), bild)) return;   // Autor 24.10. (B10/B11): kein Claim, die Haemmer sind alle
            if (ctx.clickIfVisibleIfKnown(bild, T_CLAIM, Sprache.t("Claim reward (right away)", "Belohnung annehmen (sofort)"))) {
                kisteAbgeholt();
                wirkungslos = 0;
                runs.progress(Sprache.t("Reward claimed", "Belohnung angenommen"));
                ctx.sleepUntilChanged(600);
                return;
            }
            // Nicht an Knoepfen erkennen: Ein dunkler Schleier aendert die normierte
            // Bildsuche kaum, See through "passt" auch dahinter (Code-Pruefung 29.09.).
            // Stattdessen: Das Feld ist wieder so hell wie vor dem Klick.
            double hell = feldHelligkeit(ctx.frame());
            boolean feldFrei = hellVorKlick > 0 && hell >= hellVorKlick * 0.88
                    && !ctx.visibleIfKnown(bild, T_OPEN);
            if (feldFrei && System.currentTimeMillis() - start >= CLAIM_SPAETESTENS_MS) {
                if (++frei >= 2) {
                    kisteAbgeholt();
                    Log.debug("Event", "Nach \"Open\" kein Claim -- das Feld ist frei, es geht sofort weiter ("
                            + (System.currentTimeMillis() - start) + " ms).");
                    return;
                }
            } else {
                frei = 0;
            }
        }
    }

    /** So spaet kommt "Claim" nach "Open" hoechstens (gemessen bis 1,64 s) -- vorher gilt das Feld nie als frei. */
    private static final long CLAIM_SPAETESTENS_MS = 2000;

    /** Wurde fuer die zuletzt angeklickte Kiste "Open" gedrueckt? */
    private boolean letzteKisteGeoeffnet = false;
    /** Mittlere Helligkeit des Feldes, bevor Kiste/HC/Stab angeklickt wurden (0 = unbekannt). */
    private double hellVorKlick = 0;

    private void hellMerken(BotContext ctx) {
        double h = feldHelligkeit(ctx.frame());
        if (h > 0) hellVorKlick = h;
    }

    /** Mittlere Helligkeit im Spielfeld (jeder 6. Bildpunkt), 0 = kein Bild/Raster. */
    private double feldHelligkeit(java.awt.image.BufferedImage bild) {
        org.opencv.core.Rect f = feldBereich();
        if (bild == null || f == null) return 0;
        long summe = 0;
        int n = 0;
        for (int y = Math.max(0, f.y); y < Math.min(bild.getHeight(), f.y + f.height); y += 6) {
            for (int x = Math.max(0, f.x); x < Math.min(bild.getWidth(), f.x + f.width); x += 6) {
                int p = bild.getRGB(x, y);
                summe += ((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114;
                n++;
            }
        }
        return n == 0 ? 0 : summe / (1000.0 * n);
    }

    /** Ist das Feld (Spalte, Reihe) schon abgeholt (als nicht mehr anklickbar gemerkt)? */
    private boolean feldAbgeholt(int sp, int re) {
        if (raster == null) return false;
        int mx = raster.mitteX(sp), my = raster.mitteY(re);
        for (int[] st : kistenStellen) {
            if (Math.abs(st[0] - mx) < 30 && Math.abs(st[1] - my) < 30 && st[2] > 3) return true;
        }
        return false;
    }

    /** Eine Belohnung wurde angenommen: die zuletzt angeklickte Kiste ist abgeholt. */
    private void kisteAbgeholt() {
        // Nur, wenn fuer diese Kiste wirklich "Open" gedrueckt wurde -- ein Claim
        // nach einem Hammerschlag gehoert nicht zu ihr (Code-Pruefung 29.09.).
        // Nacht 28.10. (Event-Pruefung H1b): in der Ausnahme "alle Lupen" gibt es keine Hammerschlaege auf Faesser --
        // ein Claim bis 4 s nach dem Kisten-Klick gehoert zu DIESER Kiste. Ohne Merken blieb das Kistenbild stehen und
        // der Bot klickte sie erneut ("Kiste -> Claim -> Kiste", 20x im Log, danach das "Reward claimed!"-Fenster).
        boolean ausnahmeGleichDanach = de.hustlebot.core.Eventautomatik.alleLupen()
                && System.currentTimeMillis() - letzteKisteUm < 4000;
        if (letzteKiste != null && (letzteKisteGeoeffnet || ausnahmeGleichDanach)) {
            letzteKiste[2] = 99;
            letzteKiste[3] = 1;   // abgeholt -- nicht spaeter als "laesst sich nicht mitnehmen" melden
            letzteKiste = null;
        }
    }

    /** Ab hier zaehlt ein Kisten-Treffer, wenn er mittig in einem offenen Feld liegt. */
    private static final double KISTEN_SCHWELLE_MILD = 0.73;
    /** Dasselbe fuer die Nahrungskiste (echte Treffer 0,83-0,84; Autor will keine verpassen). */
    private static final double KISTEN_SCHWELLE_NAHRUNG = 0.70;

    /**
     * Ab wann ein Treffer MITTIG in einem offenen Feld zaehlt. Vorlagen "*_feld"
     * sind aus Fotos vom Spielfeld geschnitten (29.09.): Die eigene Kiste passt
     * 0,73-1,00, alle anderen hoechstens 0,54 -- da reichen 0,70.
     */
    private static double mildeSchwelle(String name) {
        if (name.endsWith("_feld") || de.hustlebot.core.Eventkisten.istNahrung(name)) return KISTEN_SCHWELLE_NAHRUNG;
        return KISTEN_SCHWELLE_MILD;
    }
    private int belohnungFotos = 0;

    /**
     * Passt an der Stelle eines Treffers eine ABGESCHALTETE Kiste (!settings kiste
     * holz aus ...) besser als die gefundene? Dann liegt dort wohl die -- Holz-,
     * Eisen- und Goldkiste sehen sich aehnlich.
     */
    private boolean abgeschaltetGewinnt(Mat screen, BotContext ctx, Matcher.Hit h) {
        List<String> aus = new ArrayList<>();
        for (String n : ctx.matcher().names()) {
            if (n.startsWith(KISTEN_PREFIX) && !de.hustlebot.core.Eventkisten.vorlageErlaubt(n)) aus.add(n);
        }
        if (aus.isEmpty()) return false;
        org.opencv.core.Rect um = new org.opencv.core.Rect(h.x() - 25, h.y() - 25, h.width() + 50, h.height() + 50);
        for (String n : aus) {
            if (!ctx.matcher().has(n)) continue;
            var t = ctx.matcher().findImBereich(screen, n, um, 0.5);
            // Mit Abstand: die Vorlagen passen im Feld alle nur 0,74-0,84, ein
            // Hundertstel mehr ist Zufall -- lieber eine Holzkiste zu viel als eine
            // gewollte Goldkiste zu wenig.
            if (t.isPresent() && t.get().score() > h.score() + 0.03) {
                Log.debug("Event", "Treffer " + h.name() + " " + String.format("%.2f", h.score()) + " bei "
                        + h.centerX() + "," + h.centerY() + " ist eher " + n + " (" + String.format("%.2f", t.get().score())
                        + ") -- abgeschaltet, bleibt liegen.");
                return true;
            }
        }
        return false;
    }

    /**
     * Foto jeder Belohnung, die der Bot mitnimmt (Autor, 29.09.: "wieso hat er das
     * Wappen genommen?") -- logs\event-genommen\<Zeit>_<Vorlage>_<Wert>.png,
     * hoechstens 80 je Start. So ist nach einem Lauf zu sehen, was wirklich dalag.
     */
    private void belohnungFoto(BotContext ctx, Matcher.Hit h, String name) {
        if (belohnungFotos >= 80) return;
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return;
        try {
            int w = raster != null ? raster.schrittX() : 96, hh = raster != null ? raster.schrittY() : 80;
            int x = Math.max(0, Math.min(bild.getWidth() - w, h.centerX() - w / 2));
            int y = Math.max(0, Math.min(bild.getHeight() - hh, h.centerY() - hh / 2));
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "event-genommen");
            String datei = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss-SSS"))
                    + "_" + name.replace("ev_kiste_", "") + "_" + String.format("%.2f", h.score()).replace(',', '.') + ".png";
            de.hustlebot.core.Beleg.schreiben(bild.getSubimage(x, y, w, hh), "png", ordner.resolve(datei).toFile());
            belohnungFotos++;
        } catch (Exception e) {
            Log.debug("Event", "Belohnungs-Foto nicht gespeichert: " + e.getMessage());
        }
    }
    private int stabFotos = 0;

    /**
     * Vor dem Stab-Klick: die besten Werte aller Kisten-Vorlagen im Feld ins
     * Protokoll, dazu ein Foto (hoechstens 12) -- so ist nach einem Test zu
     * sehen, ob eine Kiste liegen geblieben ist und mit welchem Wert.
     */
    private void kistenBeimStab(Mat screen, BotContext ctx, String wann, String datei) {
        org.opencv.core.Rect feld = feldBereich();
        if (feld == null) return;
        StringBuilder werte = new StringBuilder();
        for (String name : kistenNamen(ctx)) {
            var alle = ctx.matcher().findAllSchnell(screen, name, feld, 0.40, 0.5, 1);   // Tempo: halb vorsuchen
            if (alle.isEmpty()) continue;
            Matcher.Hit t = alle.get(0);
            werte.append(werte.length() > 0 ? ", " : "").append(name.replace("ev_kiste_", ""))
                    .append(' ').append(String.format("%.2f", t.score()))
                    .append('@').append(t.centerX()).append(',').append(t.centerY());
        }
        String foto = "";
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild != null && stabFotos < 60) {   // 29.09.: auch vor jedem Neumischen, darum mehr
            stabFotos++;
            try {
                java.nio.file.Path pfad = java.nio.file.Path.of("logs", datei
                        + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
                if (de.hustlebot.core.Beleg.schreiben(bild, "png", pfad.toFile())) foto = Sprache.t("  Photo: ", "  Foto: ") + pfad;
            } catch (Exception e) {
                Log.debug("Event", "Foto nicht gespeichert: " + e.getMessage());
            }
        }
        Log.info("Event", Sprache.t("Chests " + wann + " (score@spot, from 0.50): " + (werte.length() > 0 ? werte : "none") + foto,
                "Kisten " + wann + " (Wert@Stelle, ab 0,50): " + (werte.length() > 0 ? werte : "keine") + foto));
    }

    // ------------------------------------------------------------------
    //  Eigene Event-Motive selbst nachschneiden (Autor, 02.10.)
    // ------------------------------------------------------------------

    /** Was ein Freund im Aufnahme-Werkzeug als "Eigenes Event-Motiv N" aufgenommen hat. */
    private static final String EIGEN_PREFIX = "ev_kiste_eigen_";
    /** Ab diesem Wert ist es "wahrscheinlich das Motiv, nur schlecht aufgenommen". */
    private static final double NACHSCHNITT_AB = 0.55;
    /** Je Motiv hoechstens einmal je Bot-Lauf nachschneiden. */
    private final java.util.Set<String> nachgeschnitten = new java.util.HashSet<>();

    /**
     * Autor, 02.10.: "Wenn er es nicht erkennt oder das Bild schlecht aufgenommen ist, soll der Bot
     * es selber zusammenschneiden." Am Ende eines Feldes (vor dem Neumischen) liegt aufgedeckt, was
     * nicht mitgenommen wurde. Passt dort ein eigenes Motiv nur maessig (0,55 bis unter die
     * Kisten-Schwelle), MITTIG in einem offenen Feld, und passt dort keine andere bekannte Kiste
     * besser, wird das Feld in der Groesse der anderen Feld-Vorlagen (52x56) ausgeschnitten und als
     * ..._auto gespeichert (Ordner gelernt\event-belohnungen). Ab sofort wird es mitgenommen.
     */
    private void eigeneMotiveNachschneiden(Mat screen, BotContext ctx) {
        org.opencv.core.Rect feld = feldBereich();
        if (raster == null || feld == null) return;
        boolean[][] zu = null;
        for (String name : new ArrayList<>(ctx.matcher().names())) {
            if (!name.startsWith(EIGEN_PREFIX) || name.endsWith("_auto")) continue;
            String auto = name + "_auto";
            if (ctx.matcher().has(auto) || nachgeschnitten.contains(name)) continue;
            List<Matcher.Hit> treffer = ctx.matcher().findAllSchnell(screen, name, feld, NACHSCHNITT_AB - 0.10,
                    NACHSCHNITT_AB, 1);
            if (treffer.isEmpty()) continue;
            Matcher.Hit h = treffer.get(0);
            if (h.score() >= mildeSchwelle(name)) continue;   // wird ohnehin erkannt
            int sp = (int) Math.round((h.centerX() - raster.x0()) / (double) raster.schrittX());
            int re = (int) Math.round((h.centerY() - raster.y0()) / (double) raster.schrittY());
            if (sp < 0 || sp >= SPALTEN || re < 0 || re >= REIHEN) continue;
            if (Math.abs(h.centerX() - raster.mitteX(sp)) > raster.schrittX() / 4
                    || Math.abs(h.centerY() - raster.mitteY(re)) > raster.schrittY() / 4) continue;
            if (zu == null) zu = geschlossenesRasterNachVorlage(screen, ctx, raster);
            if (zu[sp][re]) continue;   // geschlossene Vase: da ist nichts zu sehen
            // Passt dort eine andere bekannte Kiste besser, ist es die -- nicht falsch lernen.
            org.opencv.core.Rect zelle = new org.opencv.core.Rect(
                    Math.max(0, raster.mitteX(sp) - raster.schrittX() / 2),
                    Math.max(0, raster.mitteY(re) - raster.schrittY() / 2),
                    Math.min(raster.schrittX(), screen.width() - Math.max(0, raster.mitteX(sp) - raster.schrittX() / 2)),
                    Math.min(raster.schrittY(), screen.height() - Math.max(0, raster.mitteY(re) - raster.schrittY() / 2)));
            boolean andereBesser = false;
            for (String andere : kistenNamen(ctx)) {
                if (andere.equals(name) || andere.startsWith(name + "_")) continue;
                if (ctx.matcher().findImBereich(screen, andere, zelle, Math.max(0.5, h.score() - 0.02)).isPresent()) {
                    andereBesser = true;
                    break;
                }
            }
            if (andereBesser) continue;
            int w = 52, hh = 56;
            int x0 = Math.max(0, Math.min(screen.width() - w, raster.mitteX(sp) - w / 2));
            int y0 = Math.max(0, Math.min(screen.height() - hh, raster.mitteY(re) - hh / 2));
            Mat teil = new Mat(screen, new org.opencv.core.Rect(x0, y0, w, hh));
            try {
                java.nio.file.Path ordner = Matcher.GELERNT.resolve("event-belohnungen");
                java.nio.file.Files.createDirectories(ordner);
                java.nio.file.Path datei = ordner.resolve(auto + ".png");
                org.opencv.imgcodecs.Imgcodecs.imwrite(datei.toString(), teil);
                ctx.matcher().vorlageNachladen(datei);
                nachgeschnitten.add(name);
                Log.info("Event", Sprache.t("Own motif " + name.substring(EIGEN_PREFIX.length()) + " matched only "
                        + String.format("%.2f", h.score()) + " -- re-cut by the bot (" + datei
                        + "). From now on it is collected.",
                        "Eigenes Motiv " + name.substring(EIGEN_PREFIX.length()) + " war nur "
                        + String.format("%.2f", h.score()) + " zu erkennen -- selbst nachgeschnitten (" + datei
                        + "). Ab jetzt wird es mitgenommen."));
            } catch (Exception e) {
                Log.debug("Event", "Eigenes Motiv nicht nachgeschnitten: " + e.getMessage());
            } finally {
                teil.release();
            }
        }
    }

    /** Nimmt mit, was eine Lupe an Kisten freigelegt hat. */
    private boolean kisteMitnehmen(Mat screen, BotContext ctx, RunTracker runs) {
        // TEMPO (28.09.): Ist die Lage des Feldes bekannt, nur dort suchen --
        // gut ein Sechstel des Bildes, gleiche Werte. 13 Kisten-Vorlagen in
        // jedem Bild waren sonst rund 13 x 22 ms. Nebenbei kann so auch die
        // Truhe 30/30 unten rechts nie als Kiste gelten.
        org.opencv.core.Rect feld = feldBereich();
        // MILDERE SCHWELLE MIT ABSICHERUNG (Autor, 28.09., 23:26: Stahl-, Mana- und
        // Holzkiste liegen gelassen; im Protokoll "Fackel-Kiste 0,768, gebraucht
        // 0,78" -- unter der Lupe sehen die Kisten etwas anders aus). Zwischen 0,73
        // und 0,78 zaehlt ein Treffer nur, wenn er MITTIG in einem Feld liegt, das
        // schon offen ist: nur dort kann eine Kiste liegen, ein Hammer wird also
        // nicht auf einen Zufallstreffer verschwendet.
        boolean[][] zuJetzt = null;
        for (Matcher.Hit h : belohnungenImFeld(screen, ctx)) {
            String name = h.name();
            if (h.score() < KISTEN_SCHWELLE) {
                if (raster == null) continue;
                int sp = (int) Math.round((h.centerX() - raster.x0()) / (double) raster.schrittX());
                int re = (int) Math.round((h.centerY() - raster.y0()) / (double) raster.schrittY());
                boolean mittig = sp >= 0 && sp < SPALTEN && re >= 0 && re < REIHEN
                        && Math.abs(h.centerX() - raster.mitteX(sp)) <= raster.schrittX() / 4
                        && Math.abs(h.centerY() - raster.mitteY(re)) <= raster.schrittY() / 4;
                if (!mittig) continue;
                if (zuJetzt == null) zuJetzt = geschlossenesRasterNachVorlage(screen, ctx, raster);
                if (zuJetzt[sp][re]) continue;   // geschlossenes Fass -- dort liegt keine offene Kiste
            }
            // Holz/Eisen abgeschaltet: passt dort eine abgeschaltete Kiste besser, ist es die.
            if (abgeschaltetGewinnt(screen, ctx, h)) continue;
            // Autor 28.10.: nie GEZIELT auf Kraken (er hat schon sehr viele) -- auch nicht, wenn eine Vorlage mild trifft.
            if (EventVorlagenSchwellen.krakenAnStelle(screen, ctx.matcher(), h)) continue;

            // DIESELBE KISTE NICHT ENDLOS ANKLICKEN.
            //
            // Autor am 7. September: "hat er sich am ende auf ein item in
            // einer vase fixiert das er schon genommen hatte ... ist halt
            // knallrot."
            //
            // Bleibt eine Kiste nach dem Klick liegen -- weil ein Fenster im
            // Weg lag, weil die Haemmer alle sind, oder weil das Bild sie
            // faelschlich dort sieht --, dann findet der Bot sie im
            // naechsten Bild wieder und klickt wieder. Und wieder.
            //
            // Nach drei Versuchen an derselben Stelle wird diese Kiste fuer
            // dieses Feld in Ruhe gelassen. Der Bot macht dann mit dem
            // weiter, worum es ohnehin geht: dem Zauberstab.
            //
            // JEDE STELLE HAT IHREN EIGENEN ZAEHLER. Frueher gab es nur
            // einen einzigen Merker fuer die zuletzt angeklickte Kiste, und
            // zwei haengende Kisten haben sich damit gegenseitig den Zaehler
            // geloescht: A klicken, B klicken (Stelle anders, Zaehler auf
            // null), A klicken, B klicken -- endlos, und weil jeder Klick
            // als Fortschritt zaehlt, hat keine Notbremse gegriffen.
            int[] stelle = stelleMerken(h.centerX(), h.centerY());

            if (stelle[2] > 3) {
                if (stelle[3] == 0) {
                    stelle[3] = 1;      // nur einmal ins Protokoll
                    Log.info("Event", Sprache.t("The chest at " + h.centerX() + "," + h.centerY()
                            + " (" + name + ") cannot be collected -- it stays "
                            + "where it is. The bot takes care of the magic wand and leaves "
                            + "it alone for this field.",
                            "Die Kiste bei " + h.centerX() + "," + h.centerY()
                            + " (" + name + ") laesst sich nicht mitnehmen -- sie bleibt "
                            + "liegen. Der Bot kuemmert sich um den Zauberstab und laesst "
                            + "sie fuer dieses Feld in Ruhe."));
                }
                continue;
            }
            stelle[2]++;

            Log.action("Event", Sprache.t("Collect chest (" + name + ")  [" + name + " at "
                    + h.centerX() + "," + h.centerY() + ", match "
                    + String.format("%.3f", h.score()) + "]",
                    "Kiste mitnehmen (" + name + ")  [" + name + " bei "
                    + h.centerX() + "," + h.centerY() + ", Uebereinstimmung "
                    + String.format("%.3f", h.score()) + "]"));
            letzteKiste = stelle;
            letzteKisteName = name;
            letzteKisteUm = System.currentTimeMillis();
            schlagOhneWirkung = false;
            letzteKisteGeoeffnet = false;
            hellMerken(ctx);
            ctx.input().click(h);
            belohnungFoto(ctx, h, name);   // nach dem Klick: dasselbe Bild, aber der Klick wartet nicht aufs Schreiben
            wirkungslos = 0;
            eintritte = 0;
            runs.progress(Sprache.t("Chest collected", "Kiste mitgenommen"));
            oeffnenSchnell(ctx, runs);
            return true;
        }

        // Keine Kiste erkannt. Einmal je Feld hinschreiben, wie nah die
        // beste dran war -- sonst bleibt "hat sie nicht mitgenommen" eine
        // Behauptung ohne Zahl. Genau daran hing die Mithril-Kiste, die
        // Autor am 7. September markiert hat.
        if (!kistenGemeldet) {
            kistenGemeldet = true;
            StringBuilder naechste = new StringBuilder();
            double beste = 0;
            String bester = "keine";
            org.opencv.core.Rect feldHier = feldBereich();
            for (String name : kistenNamen(ctx)) {
                if (feldHier == null) break;   // Tempo 29.09.: war 35x uebers ganze Bild (~0,9 s je Stage)
                var alle = ctx.matcher().findAllSchnell(screen, name, feldHier, 0.43, 0.55, 1);
                if (!alle.isEmpty() && alle.get(0).score() > beste) {
                    beste = alle.get(0).score();
                    bester = name;
                }
            }
            if (beste > 0) {
                naechste.append(bester).append(" ").append(String.format("%.3f", beste));
                Log.debug("Event", "Keine Kiste erkannt. Am naechsten dran: " + naechste
                        + "   (gebraucht werden "
                        + String.format("%.2f", KISTEN_SCHWELLE) + ")");
            }
        }
        return false;
    }

    /** Damit der Hinweis auf knapp verfehlte Kisten nur einmal je Feld kommt. */
    private boolean kistenGemeldet = false;

    /** Dasselbe fuer den Hinweis auf die zusaetzliche Lupe. */
    private boolean nachschlagGemeldet = false;

    /** Dasselbe fuer die Meldung, dass das Feld noch nicht zu vermessen ist. */
    private boolean rasterGemeldet = false;

    /**
     * Hat der Bot selbst den gelben Knopf gedrueckt und wartet auf die
     * Nachfrage "Wirklich neu mischen?"
     *
     * Nur dann -- oder wenn das Event sichtbar ist -- darf das gruene Ja
     * gedrueckt werden. Alle Bestaetigungsdialoge des Spiels sehen gleich
     * aus, und ein blind gedruecktes Ja kann alles Moegliche bedeuten.
     */
    private boolean neuMischenGefragt = false;

    /** Wie viele Vasen auf diesem Bild noch geschlossen sind. */
    private int nochGeschlossen(Mat screen, BotContext ctx) {
        return geschlosseneVasen(screen, ctx).size();
    }

    /**
     * Jede Kistenstelle dieses Feldes mit ihrem eigenen Zaehler.
     *
     * Ein Eintrag ist {x, y, Versuche, schonGemeldet}. Zwei Stellen gelten
     * als dieselbe, wenn sie weniger als 20 Bildpunkte auseinander liegen --
     * so viel wackelt die Vorlagensuche von Bild zu Bild.
     */
    private final List<int[]> kistenStellen = new ArrayList<>();

    /** Sucht die Stelle heraus oder legt sie an. */
    /** Zuletzt angeklickte Kiste (ihr Eintrag in kistenStellen) und ihr Name. */
    private int[] letzteKiste = null;
    private String letzteKisteName = null;
    /** Wann zuletzt eine Kiste angeklickt wurde (fuer das Fenster "Material chest"). */
    private long letzteKisteUm;
    /** So oft wurde auf diesem Feld das X eines Info-Fensters gedrueckt. */
    private int fensterXVersuche;

    /**
     * Ein Info-Fenster ueber dem Feld schliessen: das rote X, das NICHT oben rechts in
     * der Ecke sitzt (dort ist das X des ganzen Events). Gesucht werden alle bekannten
     * roten X (close_x, ad_close*, ev_angebot_x).
     */
    private boolean infoFensterSchliessen(Mat screen, BotContext ctx) {
        int eckeX = (int) (ctx.width() * 0.90), eckeY = (int) (ctx.height() * 0.12);
        for (String name : ctx.matcher().names()) {
            if (!name.equals("close_x") && !name.equals("ev_angebot_x") && !name.startsWith("ad_close")) continue;
            for (Matcher.Hit h : ctx.matcher().findAll(screen, name, 0.80, 4)) {
                if (h.centerX() >= eckeX && h.centerY() <= eckeY) continue;   // das Event-X oben rechts
                Log.action("Klick", Sprache.t("Close info window in the event  [" + name + " at " + h.centerX() + ","
                        + h.centerY() + ", match " + String.format("%.3f", h.score()) + "]",
                        "Info-Fenster im Event schliessen  [" + name + " bei " + h.centerX() + ","
                        + h.centerY() + ", Uebereinstimmung " + String.format("%.3f", h.score()) + "]"));
                ctx.input().click(h);
                ctx.sleepUntilChanged(1200);
                return true;
            }
        }
        return false;
    }
    /** Wie oft auf diesem Feld das Event verlassen und neu betreten wurde. */
    private int neuBetreten = 0;

    /** Bildschirmfoto nach logs\\event-fenster-HHmmss.png -- um unbekannte Fenster zu sehen. */
    private void fensterFoto(BotContext ctx) {
        try {
            java.awt.image.BufferedImage bild = ctx.frame();
            if (bild == null) return;
            java.nio.file.Path datei = java.nio.file.Path.of("logs", "event-fenster-"
                    + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            boolean foto = de.hustlebot.core.Beleg.schreiben(bild, "png", datei.toFile());
            Log.info("Event", foto ? Sprache.t("No barrel in sight -- photo saved: ", "Kein Fass zu sehen -- Foto gespeichert: ") + datei
                    : Sprache.t("No barrel in sight.", "Kein Fass zu sehen."));
        } catch (Exception e) {
            Log.debug("Event", "Foto nicht gespeichert: " + e.getMessage());
        }
    }

    private int[] stelleMerken(int x, int y) {
        for (int[] s : kistenStellen) {
            if (Math.abs(x - s[0]) < 20 && Math.abs(y - s[1]) < 20) return s;
        }
        int[] neu = new int[]{x, y, 0, 0};
        kistenStellen.add(neu);
        return neu;
    }

    /**
     * Alle Kistenvorlagen, die Autor aufgenommen hat.
     *
     * Wird bei jedem Aufruf frisch aus den geladenen Namen gebildet. Das
     * kostet fast nichts und hat einen Vorteil: Nimmt er mitten in der
     * Nacht eine neue Kiste auf und laedt die Vorlagen neu, benutzt der Bot
     * sie sofort.
     */
    private List<String> kistenNamen(BotContext ctx) {
        List<String> namen = new ArrayList<>();
        for (String name : ctx.matcher().names()) {
            // Das HC-Abzeichen ist keine Belohnung: Jede Sonder-Belohnung traegt es,
            // auch das Wappen, das Autor nicht will (29.09.). Mitgenommen wird nur,
            // was als Vorlage in templates\event-belohnungen liegt -- und davon nur
            // die eingeschalteten Kisten (!settings kiste holz aus ...).
            if (!name.startsWith(KISTEN_PREFIX) || name.equals(T_HC)) continue;
            if (!de.hustlebot.core.Eventkisten.vorlageErlaubt(name)) continue;
            namen.add(name);
        }
        java.util.Collections.sort(namen);
        // Nahrung gleich nach den Schluesseln (Autor, 29.09.: "extra Auge auf Nahrungskisten").
        for (String n : new ArrayList<>(namen)) {
            if (de.hustlebot.core.Eventkisten.istNahrung(n)) { namen.remove(n); namen.add(0, n); }
        }
        // Die Kathedralen-Schluessel zuerst (Autor, 28.09.: "unbedingt alle
        // cathedral schluessel mitnehmen") -- vor jeder anderen Kiste und
        // damit sicher vor dem Neumischen.
        // Seit 28.10. auch der goldene Schluessel (ev_kiste_schluessel_gold_feld).
        for (String n : new ArrayList<>(namen)) {
            if (n.startsWith(T_KISTE_SCHLUESSEL)) { namen.remove(n); namen.add(0, n); }
        }
        // Autor 28.10.: die roten Haemmer als Belohnung noch davor -- sie bringen Haemmer zurueck, und die sind knapp.
        for (String n : HAMMER_BELOHNUNGEN) if (namen.remove(n)) namen.add(0, n);
        if (ctx.matcher().has(T_GEMS)) namen.add(T_GEMS);
        return namen;
    }

    /** Wann zuletzt die Zeile im Kalender angeklickt wurde -- nur danach gilt ein "View". */
    private long eintragGeklicktUm = 0;
    /** Wie oft der Kalender geoeffnet wurde, ohne ins Event zu kommen. */
    private int kalenderVersuche = 0;

    /**
     * Burg -> Kalender -> Zeile des Hammer-Events -> "View" (Autor, 28.09.).
     *
     * "View" nur kurz nach dem Klick auf die Zeile: Im Event selbst steht
     * rechts unten auch ein gruenes "View" (die Truhe mit 0/30). Das soll der
     * Bot nie druecken.
     *
     * @return null, wenn nichts davon zu sehen ist -- dann verschiebt der
     *         Ablauf darunter die Burg, bis das Kalender-Symbol auftaucht
     */
    private Outcome ueberDenKalender(Mat screen, BotContext ctx, RunTracker runs) {
        if (System.currentTimeMillis() - eintragGeklicktUm < 15_000
                && ctx.clickIfVisibleIfKnown(screen, T_VIEW, Sprache.t("Open the Hammer Event (View)", "Hammer-Event oeffnen (View)"))) {
            eintragGeklicktUm = 0;
            kalenderVersuche = 0;
            if (++eintritte > MAX_EINTRITTE) {
                Log.error("Event", Sprache.t("The bot has entered the event " + eintritte + " times "
                        + "without getting anything done there -- it is going round in circles. The "
                        + "event is skipped, the next mode carries on.",
                        "Der Bot ist " + eintritte + " Mal ins Event gegangen, "
                        + "ohne dort etwas zu erledigen -- er dreht sich im Kreis. Das "
                        + "Event wird ausgelassen, der naechste Modus laeuft weiter."));
                return Outcome.FINISHED;
            }
            runs.progress(Sprache.t("Event opened", "Event geoeffnet"));
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }
        if (ctx.clickIfVisibleIfKnown(screen, T_KALENDER_EINTRAG, Sprache.t("Pick the Hammer Event in the calendar", "Im Kalender das Hammer-Event waehlen"))) {
            eintragGeklicktUm = System.currentTimeMillis();
            fremdeBilder = 0;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (ctx.clickIfVisibleIfKnown(screen, T_KALENDER, Sprache.t("Open the event calendar", "Event-Kalender oeffnen"))) {
            if (++kalenderVersuche > 6) {
                Log.error("Event", Sprache.t("The calendar opened " + (kalenderVersuche - 1) + " times, but the "
                        + "Hammer Event row (template " + T_KALENDER_EINTRAG + ") or \"View\" ("
                        + T_VIEW + ") was not found. The event is skipped.",
                        "Der Kalender ging " + (kalenderVersuche - 1) + " Mal auf, aber die "
                        + "Zeile des Hammer-Events (Vorlage " + T_KALENDER_EINTRAG + ") oder \"View\" ("
                        + T_VIEW + ") wurde nicht gefunden. Das Event wird ausgelassen."));
                kalenderVersuche = 0;
                return Outcome.FINISHED;
            }
            fremdeBilder = 0;
            zuegeZumGeist = 0;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        return null;
    }

    /**
     * Die Bilder der geschlossenen Faesser: die Serie ev_fass_zu (Herbst-Event,
     * beliebig viele Motive), sonst die alte Vase ev_vase_zu.
     */
    private List<String> vasenMotive(BotContext ctx) {
        List<String> faesser = ctx.matcher().serie(T_FASS);
        if (!faesser.isEmpty()) return faesser;
        return ctx.matcher().has(T_VASE) ? List.of(T_VASE) : List.of();
    }

    /**
     * Hat das Event selbst festgestellt, dass die Haemmer alle sind (Schlaege ohne
     * Wirkung)? Sonst endete es aus einem anderen Grund -- etwa einem Fenster ueber dem
     * Feld (29.09., 20:13: Material-Truhe, 0 Faesser, der Kreislauf hielt die Haemmer
     * fuer alle und spielte Verlies).
     */
    private boolean haemmerAlle;

    /** Autor 24.10. (B10/B11): "Not enough hammers?" gesehen -- das Event wird beendet (haemmerAlle). */
    private boolean keineHaemmerMehr;
    private long hammerFensterGeprueftUm;

    /**
     * Zeigt das Spiel gerade "Not enough hammers?" (Ueberschrift des Fensters, mittig oben)? Nur ein Ausschnitt der oberen
     * Bildhaelfte wird durchsucht. Die Schwelle liegt hoch: das Fenster sieht immer gleich aus, und ein Fehltreffer
     * wuerde das Event vorzeitig beenden.
     */
    static boolean zuWenigHaemmerFenster(Matcher matcher, Mat screen) {
        if (!matcher.has(T_ZU_WENIG)) return false;
        int w = screen.width(), h = screen.height();
        Rect bereich = new Rect((int) (w * 0.25), (int) (h * 0.09), (int) (w * 0.50), (int) (h * 0.16));
        return matcher.findImBereich(screen, T_ZU_WENIG, bereich, ZU_WENIG_SCHWELLE).isPresent();
    }

    private static final double ZU_WENIG_SCHWELLE = 0.88;

    public boolean haemmerAlle() {
        return haemmerAlle;
    }

    /** Wie viele Vasen (Faesser) in diesem Auftrag zerschlagen wurden. 0 = keine Haemmer da. */
    public int vasenZerschlagen() {
        return vasenZerschlagen;
    }

    /** Zerschlaegt irgendeine noch geschlossene Vase. */
    private Outcome vaseZerschlagen(Mat screen, BotContext ctx, RunTracker runs) {
        List<Matcher.Hit> vasen = geschlosseneVasen(screen, ctx);

        // SUPER-HAMMER STATT NORMALER (Autor, 29.09.): Die Lupen sind durch, der Stab noch
        // nicht gefunden -- trifft die gemerkte Form drei geschlossene Faesser, einen blauen
        // Hammer nehmen statt bis zu drei normaler. Nie auf offene, leere Felder.
        if (!vasen.isEmpty() && superStattNormal(screen, ctx, runs)) return Outcome.ACTED;
        if (nurSuperHaemmer) {
            // Schlussphase ohne normale Haemmer: nie ein normaler Schlag. Findet der blaue Hammer lange kein Ziel
            // (Form unbekannt, nichts passt), ist hier nichts mehr zu holen -- anhalten und melden.
            if (++superOhneZiel > SUPER_OHNE_ZIEL_MAX) return schlussphaseFertig(screen, ctx);
            ctx.sleep(tempo.ms(300));
            return Outcome.WAITING;
        }

        if (vasen.isEmpty()) {
            // Keine geschlossene Vase mehr, aber auch kein Haken beim Stab:
            // Da stimmt etwas nicht. Nicht wild draufklicken, sondern warten
            // und mitzaehlen.
            ++wirkungslos;
            if (wirkungslos == 3) fensterFoto(ctx);
            // NEU BETRETEN STATT AUFHOEREN (Test 28.09., 22:03): Nach dem Klick auf
            // ein Extra-Item (ev_kiste_extra_3, ein Hammer-Symbol) lag etwas ueber
            // dem Feld, der Bot sah kein Fass mehr und beendete nach 8 Bildern
            // den ganzen Auftrag (2 von 15 Stages). Jetzt: das Item nicht noch
            // einmal anklicken, das Event verlassen und ueber den Kalender neu
            // betreten -- hoechstens zweimal je Feld.
            // GLEICH NACH EINER KISTE (Nachtlauf 29.09., 07:58): Die Material-Truhe
            // oeffnet ein Fenster mit rotem X ueber dem Feld. Der Bot wartete acht
            // Bilder (~4 s), bevor er X drueckte -- und danach ging es sofort weiter.
            // Liegt die Kiste keine 4 s zurueck, reichen zwei Bilder ohne Fass.
            boolean nachKiste = System.currentTimeMillis() - letzteKisteUm < 4000;
            // ERST DAS X DES INFO-FENSTERS (29.09., 19:55): Die Material-Truhe zeigt
            // "Reward claimed!" in einem Fenster mit eigenem rotem X in der Mitte. Der Bot
            // drueckte das X oben rechts -- das schliesst das ganze Event. Nach zwei solchen
            // Runden gab er auf, beim naechsten Gang galten die Haemmer als alle, und der
            // Kreislauf spielte Verlies statt Event.
            if (nachKiste && wirkungslos >= 2 && !materialFotoGemacht) {
                materialFotoGemacht = true;   // Autor 28.10.: einmal das Kisten-Fenster fotografieren, um "Open" sicher zu lernen
                fensterFoto(ctx);
            }
            if (wirkungslos >= 2 && fensterXVersuche < 3 && infoFensterSchliessen(screen, ctx)) {
                fensterXVersuche++;
                wirkungslos = 0;
                if (letzteKiste != null) letzteKiste[2] = 99;   // diese Kiste nicht noch einmal
                return Outcome.ACTED;
            }
            // HAEMMER ALLE, FELD VERDECKT (Bericht #AEEF, 24.10.): Der letzte Schlag zerschlug nichts (Hammerzahl 0),
            // danach sah der Bot gar keine Vase mehr. Er ging zweimal neu ins Event (22 s) und meldete am Ende "Stab
            // nicht geholt" statt "Haemmer alle" -- haemmerAlle blieb falsch. Hatte der letzte Schlag keine Wirkung
            // und keine Kiste kam dazwischen, sind die Haemmer alle.
            if (schlagOhneWirkung && !nachKiste && wirkungslos >= MAX_WIRKUNGSLOS) {
                haemmerAlle = true;
                Log.warn("Event", Sprache.t("The last hit shattered nothing and no vase is in sight any more. "
                        + "The hammers are probably used up. The bot stops. ",
                        "Der letzte Schlag hat nichts zerschlagen, und keine Vase ist mehr zu sehen. "
                        + "Vermutlich sind die Haemmer alle. Der Bot hoert auf. ") + berichten());
                return aufraeumen(screen, ctx);
            }
            if ((wirkungslos >= MAX_WIRKUNGSLOS || (nachKiste && wirkungslos >= 2)) && neuBetreten < 2) {
                neuBetreten++;
                wirkungslos = 0;
                if (letzteKiste != null) letzteKiste[2] = 99;   // nie wieder anklicken
                Log.warn("Event", Sprache.t("No barrel in sight -- probably a window is lying over the field"
                        + (letzteKisteName != null ? " (last clicked: " + letzteKisteName + ")" : "")
                        + ". Leaving the event and entering it again (attempt " + neuBetreten + ").",
                        "Kein Fass zu sehen -- vermutlich liegt ein Fenster ueber dem Feld"
                        + (letzteKisteName != null ? " (zuletzt angeklickt: " + letzteKisteName + ")" : "")
                        + ". Das Event wird verlassen und neu betreten (" + neuBetreten + ". Mal)."));
                if (ctx.clickIfVisibleIfKnown(screen, "close_x", Sprache.t("Leave the event (get rid of the window)", "Event verlassen (Fenster loswerden)"))) {
                    ctx.sleepUntilChanged(1500);
                }
                return Outcome.ACTED;
            }
            if (wirkungslos >= MAX_WIRKUNGSLOS) {
                Log.warn("Event", Sprache.t("No closed vase is left in sight on the field, "
                        + "but the magic wand does not count as taken yet. "
                        + "The bot stops. ",
                        "Auf dem Feld ist keine geschlossene Vase mehr zu "
                        + "sehen, der Zauberstab gilt aber noch nicht als geholt. "
                        + "Der Bot hoert auf. ") + berichten());
                return aufraeumen(screen, ctx);
            }
            ctx.sleep(tempo.ms(500));
            return Outcome.WAITING;
        }

        // Hat der letzte Schlag ueberhaupt etwas bewirkt?
        //
        // Wenn nicht, sind mit grosser Wahrscheinlichkeit die Haemmer alle.
        // Das Spiel sagt es nicht, aber die Zahl der geschlossenen Vasen
        // verraet es: Sie muesste nach jedem Schlag um eins fallen.
        if (vasenVorKlick >= 0 && vasen.size() >= vasenVorKlick) {
            wirkungslos++;
            stageFehler++;
            schlagOhneWirkung = true;

            // ERST EINMAL DIE FREIE FLAECHE ANTIPPEN.
            //
            // Der haeufigere Grund fuer einen wirkungslosen Schlag sind
            // naemlich nicht fehlende Haemmer, sondern ein Fenster, das der
            // Bot nicht kennt und das ueber dem Spielfeld liegt.
            //
            // Am 7. September stand so eines im Weg: "Lighthouse Banner
            // Relic Fragment", die Beschreibung einer eben eingesammelten
            // Belohnung. Der Bot klickte fuenfmal hintereinander auf
            // dieselbe Stelle -- 483,274, mitten hinter dem Fenster -- und
            // wunderte sich, dass nichts geschah. Schlimmer: Das Fenster ist
            // tuerkis wie eine Glasvase, also hielt er seine Flaeche fuer
            // aufgedeckte Vasen und seinen Rand fuer die Tetris-Form.
            // Deshalb kam achtmal hintereinander "Form: hoch" heraus, und
            // die untere Reihe blieb unberuehrt.
            //
            // Ein Tipp auf eine freie Stelle macht solche Fenster zu. Der
            // Fleck unten links im dunklen Feld gehoert zu keinem Knopf --
            // dort kann nichts Unerwuenschtes ausgeloest werden. Dieselbe
            // Loesung wie in der Kathedrale beim Abschlussbildschirm.
            if (wirkungslos <= FREIE_FLAECHE_VERSUCHE) {
                int fx = (int) (ctx.width() * 0.13);
                int fy = (int) (ctx.height() * 0.92);
                Log.action("Event", Sprache.t("Nothing happens -- probably a window is lying "
                        + "over the field. Tapping free space at " + fx + "," + fy,
                        "Es tut sich nichts -- vermutlich liegt ein Fenster "
                        + "ueber dem Feld. Freie Flaeche antippen bei " + fx + "," + fy));
                ctx.input().click(fx, fy);
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }

            if (wirkungslos >= MAX_WIRKUNGSLOS) {
                haemmerAlle = true;
                Log.warn("Event", Sprache.t("For " + MAX_WIRKUNGSLOS + " hits no vase has "
                        + "shattered. The hammers are probably used up. The bot "
                        + "stops. ",
                        "Seit " + MAX_WIRKUNGSLOS + " Schlaegen zerspringt "
                        + "keine Vase mehr. Vermutlich sind die Haemmer alle. Der Bot "
                        + "hoert auf. ") + berichten());
                return aufraeumen(screen, ctx);
            }
        } else {
            wirkungslos = 0;
            schlagOhneWirkung = false;
        }

        // Wenn das Feld vermessen ist, wird die Farbe gefragt statt der
        // Vorlage.
        //
        // Die Vorlage ev_vase_zu passt auch auf AUFGEDECKTE Vasen -- sie
        // behalten ja ihren goldenen Sockel. Ein Schlag auf eine schon
        // aufgedeckte Vase kostet einen Hammer und bringt nichts Neues; der
        // Stab liegt garantiert nicht darunter, man saehe ihn ja.
        int zielX, zielY;
        int nochZu = vasen.size();

        Raster r = raster;
        if (r != null) {
            boolean[][] zu = geschlossenesRaster(screen, ctx, r);
            int besteS = -1, besteZ = -1;
            nochZu = 0;
            for (int reihe = 0; reihe < REIHEN; reihe++) {
                for (int spalte = 0; spalte < SPALTEN; spalte++) {
                    if (!zu[spalte][reihe]) continue;
                    nochZu++;
                    if (besteS < 0) { besteS = spalte; besteZ = reihe; }
                }
            }
            if (besteS < 0) {
                // Farbe sagt: alles offen. Dann ist hier nichts mehr zu tun.
                if (++wirkungslos >= MAX_WIRKUNGSLOS) {
                    Log.warn("Event", Sprache.t("All vases are uncovered, but the magic wand "
                            + "does not count as taken yet. The bot stops. ",
                            "Alle Vasen sind aufgedeckt, der Zauberstab gilt "
                            + "aber noch nicht als geholt. Der Bot hoert auf. ") + berichten());
                    return aufraeumen(screen, ctx);
                }
                ctx.sleep(tempo.ms(400));
                return Outcome.WAITING;
            }
            zielX = r.mitteX(besteS);
            zielY = r.mitteY(besteZ);
        } else {
            // Kein Raster: dann eben nach der Vorlage, von oben links nach
            // unten rechts. Die Maus springt so wenigstens nicht umher.
            vasen.sort(Comparator.comparingInt(Matcher.Hit::centerY)
                                 .thenComparingInt(Matcher.Hit::centerX));
            zielX = vasen.get(0).centerX();
            zielY = vasen.get(0).centerY();
        }

        // 29.10.: in der Ausnahme "alle Lupen" schlaegt der Bot nur noch, wenn die Lupe auf diesem Feld abgelehnt wurde
        // und der Zauberstab noch unter einem Fass liegt (sonst holt Schritt 8 den Stab) -- ohne Stab kein Neumischen.
        // Eigener Wortlaut, damit die Nachtwache diesen noetigen Schlag von Verschwendung unterscheidet.
        boolean stabSuche = de.hustlebot.core.Eventautomatik.alleLupen() && lupenGesperrt;
        Log.action("Event", Sprache.t("Smash vase at " + zielX + ","
                + zielY + " (" + nochZu + " still closed)" + (stabSuche ? " -- magnifier refused, looking for the hidden wand" : ""),
                "Vase zerschlagen bei " + zielX + ","
                + zielY + " (noch " + nochZu + " zu)" + (stabSuche ? " -- Lupe abgelehnt, Zauberstab noch versteckt" : "")));
        vasenVorKlick = vasen.size();
        ctx.input().click(zielX, zielY);
        vasenZerschlagen++;
        eintritte = 0;              // wir sind angekommen und tun etwas
        runs.progress(Sprache.t("Vase smashed", "Vase zerschlagen"));
        // Kam "Annehmen", hat der Schlag gewirkt -- dann NICHT die Faesser
        // nachzaehlen: Direkt danach verdeckt das verschwindende Fenster Faesser,
        // die Zahl sinkt scheinbar nicht, und der Bot tippte bis zu dreimal
        // "Es tut sich nichts" ins Leere (Test 28.09., 23:05: bis 5 s je Schlag).
        if (annehmenAbwarten(ctx, runs, 2000)) {
            vasenVorKlick = -1;
            schlagOhneWirkung = false;
        }
        return Outcome.ACTED;
    }

    /**
     * Macht das Event-Fenster zu, bevor der Modus abgibt.
     *
     * Wichtig fuer die Warteschlange: Nach dem Event kommt oft wieder die
     * Kathedrale, und die faengt in der Burg an. Bliebe das Event-Fenster
     * offen, muesste sie sich erst muehsam herausfinden.
     */
    private Outcome aufraeumen(Mat screen, BotContext ctx) {
        if (!hammerAusgangGemeldet && hammerEingangGemeldet) {
            hammerAusgangGemeldet = true;
            hammerMelden(screen, ctx, false);
        }
        // MEHRERE VERSUCHE, nicht nur einer. Frueher wurde amAufraeumen
        // gleich beim ersten Versuch gesetzt: War das rote X in genau diesem
        // einen Bild verdeckt -- von einer Belohnung, vom Angebot --, gab der
        // Modus sofort auf und liess das Event-Fenster offen stehen. Der
        // naechste Modus fing dann mitten im Event an und erkannte nichts.
        if (ctx.clickIfVisibleIfKnown(screen, "close_x",
                Sprache.t("Leave the event -- back to the castle", "Event verlassen -- zurueck in die Burg"))) {
            amAufraeumen = true;
            ctx.sleepUntilChanged(900);
            return Outcome.ACTED;
        }
        if (++schliessVersuche <= SCHLIESS_VERSUCHE) {
            ctx.sleep(tempo.ms(400));
            return Outcome.WAITING;
        }
        Log.warn("Event", Sprache.t("The red X was not in sight after " + SCHLIESS_VERSUCHE + " attempts. "
                + "The mode hands over anyway -- the next one may then start "
                + "inside the event and has to find its own way out.",
                "Das rote X war nach " + SCHLIESS_VERSUCHE + " Versuchen "
                + "nicht zu sehen. Der Modus gibt trotzdem ab -- der naechste faengt "
                + "dann eventuell noch im Event an und muss sich selbst herausfinden."));
        amAufraeumen = true;
        return Outcome.FINISHED;
    }

    /** Wie oft nach dem roten X gesucht wird, bevor der Modus abgibt. */
    private static final int SCHLIESS_VERSUCHE = 6;
    private int schliessVersuche = 0;

    /** Das Bild, zu dem die gemerkten Vasen gehoeren. */
    private Mat vasenBild = null;
    private List<Matcher.Hit> vasenGemerkt = List.of();

    /**
     * Alle noch geschlossenen Vasen im Bild -- einmal je Bild gesucht.
     *
     * WARUM GEMERKT WIRD. Diese Suche ist die teuerste im ganzen Modus: Sie
     * rechnet das Bild erst in Graustufen um und sucht dann bis zu
     * fuenfundzwanzig Vasen darin. Innerhalb EINES Bildes wird sie aber bis
     * zu dreimal gebraucht -- beim Vermessen des Feldes, beim Ausrechnen der
     * besten Stelle und beim Aussuchen der naechsten Vase.
     *
     * Dreimal dieselbe Rechnung fuer dasselbe Bild ist dreimal derselbe
     * Strom. Deshalb wird das Ergebnis behalten, solange das Bild dasselbe
     * ist. Bei jeder neuen Aufnahme legt der Motor ein neues Bild an, und
     * daran wird es erkannt -- es kann also nie ein veralteter Stand
     * benutzt werden.
     */
    private List<Matcher.Hit> geschlosseneVasen(Mat screen, BotContext ctx) {
        List<String> motive = vasenMotive(ctx);
        if (motive.isEmpty()) return List.of();
        if (screen == vasenBild) return vasenGemerkt;

        // In Graustufen: Die Vasen sind rosa, blau, gruen und lila, aber
        // ueberall dasselbe Muster aus Goldbaendern und weissem Rhombus.
        // Seit 28.09. mehrere Motive (Faesser in vier Farben, "so viele Bilder
        // wie moeglich" -- Autor): alle durchsuchen, doppelte Stellen nur einmal.
        // Autor 24.10. (Ideenliste A04): steht das Raster, nur im Spielfeld suchen statt im ganzen Fenster -- die neun
        // Fass-Bilder kosteten gemessen 57 s von 88 s Vergleichszeit (je 11 ms und Bild). Gleiche Rechnung, gleiche
        // Schwelle, nur ein Fuenftel der Bildpunkte. Findet der Ausschnitt gar nichts (Fenster darueber, Raster
        // verrutscht?), wird wie frueher im ganzen Bild nachgesehen.
        List<Matcher.Hit> alle = Vasensuche.suchen(ctx.matcher(), screen, motive, VASE_SCHWELLE, SPALTEN * REIHEN + 5,
                Vasensuche.feldBereich(raster, screen.width(), screen.height()));
        if (alle.isEmpty() && raster != null) {
            alle = Vasensuche.suchen(ctx.matcher(), screen, motive, VASE_SCHWELLE, SPALTEN * REIHEN + 5, null);
        }
        vasenGemerkt = alle;
        vasenBild = screen;
        return vasenGemerkt;
    }

    // Autor 24.10.: "Wie viele Haemmer bekomme ich in einer Stunde Event-Ablauf?" -- dafuer den Stand beim Betreten und
    // beim Verlassen ins Protokoll (Windows-Texterkennung; die Ziffern-Vorlagen fehlen). Zwei Lesungen je Gang.
    private boolean hammerEingangGemeldet, hammerAusgangGemeldet;

    private long hammerPerTexterkennung(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_HAMMER) || !de.hustlebot.core.Texterkennung.istDa()) return -1;
        java.util.Optional<Matcher.Hit> treffer = ctx.find(screen, T_HAMMER);
        java.awt.image.BufferedImage bild = ctx.frame();
        if (treffer.isEmpty() || bild == null) return -1;
        Matcher.Hit t = treffer.get();
        int x = Math.min(bild.getWidth() - 1, t.x() + t.width());
        int y = Math.max(0, t.y() - 4);
        int w = Math.min((int) (ctx.width() * 0.12), bild.getWidth() - x);
        int h = Math.min(t.height() + 8, bild.getHeight() - y);
        if (w <= 10 || h <= 5) return -1;
        java.awt.image.BufferedImage feld = bild.getSubimage(x, y, w, h);
        // Nur vergroessert lesen -- kleine Schrift erkennt die Texterkennung unvergroessert nicht (ArenaLeser).
        for (String z : de.hustlebot.core.Texterkennung.lies(de.hustlebot.core.Texterkennung.weisseSchrift(feld, 3))) {
            String d = z.replaceAll("[^0-9]", "");
            if (!d.isEmpty() && d.length() <= 6) return Long.parseLong(d);
        }
        return -1;
    }

    /**
     * Autor 24.10.: "Der Bot merkt erst ziemlich spaet, dass er keine Lupen mehr hat -- er drueckt meistens dreimal".
     * Ueber "See through" steht die Zahl der Lupen (Foto 24.10.: "2"). Die Windows-Texterkennung liest eine einzelne
     * Ziffer nicht -- darum wird das Zahlenfeld als Bild verglichen: vor und nach dem Klick (geaendert = Lupe verbraucht)
     * und mit dem gemerkten Bild von "keine Lupen". null = Knopf nicht zu sehen.
     */
    private java.awt.image.BufferedImage lupenZahlBild(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_LUPE)) return null;
        java.util.Optional<Matcher.Hit> treffer = ctx.find(screen, T_LUPE);
        java.awt.image.BufferedImage bild = ctx.frame();
        if (treffer.isEmpty() || bild == null) return null;
        Matcher.Hit t = treffer.get();
        double f = ctx.height() / 770.0;
        int x = Math.max(0, t.centerX() - (int) Math.round(52 * f));
        int y = Math.max(0, t.y() - (int) Math.round(55 * f));
        int w = Math.min((int) Math.round(95 * f), bild.getWidth() - x);
        int h = Math.min((int) Math.round(34 * f), bild.getHeight() - y);
        if (w <= 10 || h <= 5) return null;
        java.awt.image.BufferedImage kopie = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        kopie.getGraphics().drawImage(bild.getSubimage(x, y, w, h), 0, 0, null);
        return kopie;
    }

    /** Wie gleich zwei Zahlenfelder sind (1 = gleich), aus dem mittleren Helligkeitsunterschied. */
    static double zahlGleich(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b) {
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return 0;
        long summe = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int hp = ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3;
                int hq = ((q >> 16 & 255) + (q >> 8 & 255) + (q & 255)) / 3;
                summe += Math.abs(hp - hq);
            }
        }
        return 1.0 - summe / (255.0 * a.getWidth() * a.getHeight());
    }

    private void hammerMelden(Mat screen, BotContext ctx, boolean eingang) {
        long n = hammerPerTexterkennung(screen, ctx);
        String wann = eingang ? "beim Betreten" : "beim Verlassen";
        Log.info("Event", Sprache.t("Hammers ", "Haemmer ") + wann + ": " + (n >= 0 ? String.valueOf(n) : Sprache.t("not readable", "nicht lesbar")));
    }

    /**
     * Liest den Hammerstand. Gelingt das nicht, wird er auf -1 gesetzt --
     * "unbekannt", nicht "wie eben".
     *
     * DAS IST DER GANZE WITZ DER DOPPELPRUEFUNG. Sie soll zwei GETRENNTE
     * Messungen sehen, bevor sie glaubt, dass die Haemmer alle sind. Blieb
     * der alte Wert stehen, wenn das Lesen misslang, dann pruefte sie
     * zweimal dieselbe Zahl: eine einzige verlesene Null hat den Bot schon
     * im naechsten Bild aus dem Event geworfen. Umgekehrt genauso -- war die
     * echte Null nicht lesbar, hielt er ewig den letzten positiven Stand.
     */
    private void hammerAblesen(Mat screen, BotContext ctx) {
        if (!hammerEingangGemeldet && ctx.matcher().has(T_HAMMER) && ctx.find(screen, T_HAMMER).isPresent()) {
            hammerEingangGemeldet = true;
            hammerMelden(screen, ctx, true);
        }
        if (!ctx.matcher().has(T_HAMMER)) { hammerStand = -1; return; }
        if (!de.hustlebot.core.NumberReader.digitsAvailable(ctx.matcher())) {
            hammerStand = -1;
            return;
        }

        hammerStand = -1;
        de.hustlebot.core.NumberReader
                .readRightOf(screen, ctx.matcher(), T_HAMMER, 0.12)
                .ifPresent(wert -> {
                    if (wert != hammerZuletztGemeldet) {
                        hammerZuletztGemeldet = wert;
                        Log.debug("Event", "Haemmer: " + wert);
                    }
                    hammerStand = wert;
                });
    }

    /** Nur fuers Protokoll: der zuletzt gemeldete Stand, damit nicht jedes
     *  Bild dieselbe Zahl schreibt. */
    private long hammerZuletztGemeldet = -1;

    private String berichten() {
        return Sprache.t("Fields: " + felderGeschafft + ", vases: " + vasenZerschlagen
             + ", chests: " + kistenGeholt
             + (hammerZuletztGemeldet >= 0
                     ? ", hammers left: " + hammerZuletztGemeldet : ""),
             "Felder: " + felderGeschafft + ", Vasen: " + vasenZerschlagen
             + ", Kisten: " + kistenGeholt
             // Der zuletzt GELESENE Stand, nicht der aktuelle: hammerStand
             // ist -1, sobald ein einzelnes Bild nicht lesbar war, und dann
             // stuende hier gar nichts mehr.
             + (hammerZuletztGemeldet >= 0
                     ? ", Haemmer uebrig: " + hammerZuletztGemeldet : ""));
    }

    // ==================================================================
    //  Die Dreier-Form legen
    // ==================================================================

    /**
     * Legt die Dreier-Form auf die Stelle mit den meisten geschlossenen
     * Vasen und bestaetigt.
     *
     * WARUM DAS UEBERHAUPT GERECHNET WERDEN MUSS. Die Lupe deckt drei Vasen
     * auf. Liegt die Form auf einer Vase, die schon Glas ist, ist dieses
     * Drittel verschenkt. Bei fuenf Lupen je Feld sind das schnell mehrere
     * verlorene Blicke -- und jeder verlorene Blick heisst spaeter mehr
     * Haemmer.
     *
     * Deshalb: alle moeglichen Stellen durchrechnen und die nehmen, die die
     * meisten noch geschlossenen Vasen erwischt.
     *
     * Geht etwas davon schief -- kein Raster, kein blauer Umriss --, wird
     * die Form einfach dort bestaetigt, wo sie liegt. Das ist immer noch
     * besser als steckenzubleiben.
     */
    private Outcome formLegen(Mat screen, BotContext ctx, RunTracker runs) {

        // Liegt die GELBE Form des blauen Hammers (am gelben Text erkannt, oder
        // der Bot hat selbst "Use!" gedrueckt und das Ziel steht noch)? Sonst ist
        // es die Lupe. NICHT an superUseUm haengen: das steht nach dem ersten
        // Verschieben schon auf 0 -- dann haette die Lupen-Logik die gelbe Form
        // einfach bestaetigt, wo sie gerade lag. Ein Lupen-Klick loescht zielPaar.
        if (superNurAnsehen || ctx.visibleIfKnown(screen, T_SUPER_TEXT) || zielPaar != null) {
            return superhammerLegen(screen, ctx, runs);
        }

        // Bericht #9P2L (Ben, 3.90, 25.10.): der Umriss war nicht zu finden, der Bot
        // drueckte den Haken 10 873 Mal -- das Spiel nimmt die Form auf schon offenen
        // Vasen nicht an. Darum eine Grenze auch fuer "bestaetigen, wo sie liegt".
        // Autor 28.10.: aber nicht schon nach drei Haken -- das Spiel braucht oft 3-4
        // Bestaetigungen (~1,5 s), bis die Lupe greift. Ab 3.100 brach so fast jede Lupe
        // ab (0,9 statt 3,3 Lupen je Feld, dafuer nur noch Haemmer). Jetzt erst nach 6 s.
        if (blindHaengtFest()) {
            return lupeAbbrechen(screen, ctx,
                    Sprache.t("The game does not accept the magnifier", "Das Spiel nimmt die Lupe nicht an"));
        }

        // Nach ZWEI Zuegen ist Schluss mit dem Feilschen.
        //
        // Nach jedem Zug wird neu gemessen. Sitzt die Form richtig, kommt
        // 0/0 heraus und sie wird bestaetigt. Verschluckt das Spiel den Zug,
        // kaeme immer wieder dieselbe Verschiebung -- und der Bot zoege bis
        // zum Morgengrauen.
        //
        // Hier standen drei. Autor: "manchmal entscheidet er sich 3 mal um
        // bevor er endlich auf den gruenen hacken drueckt." Jeder Zug kostet
        // gut zwei Sekunden, und der dritte bringt so gut wie nie noch
        // etwas -- wenn zwei Zuege die Form nicht an die Stelle bringen,
        // liegt es nicht am Zaehlen. Zwei aufgedeckte Vasen sind besser als
        // keine, aber vor allem sind sie besser als sechs Sekunden Warten.
        if (zuegeAnDerForm >= 2) {
            Log.info("Event", Sprache.t("The shape could not be dragged to the best spot "
                    + "in two attempts. It is confirmed where it lies.",
                    "Die Form liess sich nach zwei Versuchen nicht an die "
                    + "beste Stelle ziehen. Sie wird bestaetigt, wo sie liegt."));
            blindBestaetigtMerken();
            return bestaetigen(screen, ctx, runs);
        }

        Raster r = rasterHolen(screen, ctx);
        List<int[]> form = r == null ? List.of() : formFelder(screen, r);

        // Autor 28.10.: den blauen Umriss erst ein paar Bilder lang suchen, bevor die Lupe blind
        // hingelegt wird -- sonst greift das Vorausdenken (Rand zuerst, Platz fuer die naechsten
        // Lupen) nicht. Bis 3.92 wurden so rund 4 von 10 Lupen nur hingelegt.
        if (r != null && form.isEmpty() && umrissWarteBilder < UMRISS_WARTE_BILDER) {
            umrissWarteBilder++;
            ctx.sleep(tempo.ms(120));
            return Outcome.WAITING;
        }
        if (r == null || form.isEmpty()) {
            if (r == null) {
                Log.info("Event", Sprache.t("The playing field could not be measured. The shape "
                        + "is confirmed where it lies.",
                        "Das Spielfeld liess sich nicht vermessen. Die Form "
                        + "wird dort bestaetigt, wo sie liegt."));
            } else {
                Log.info("Event", Sprache.t("The blue outline of the shape was not found. The "
                        + "shape is confirmed where it lies.",
                        "Der blaue Umriss der Form war nicht zu finden. Die "
                        + "Form wird dort bestaetigt, wo sie liegt."));
            }
            blindBestaetigtMerken();
            return bestaetigen(screen, ctx, runs);
        }

        formMitschreiben(form);

        // Geplant wird mit dem Stand VOR der Form (Testlauf 28.09., 15:35): solange
        // sie liegt, ist das Bild abgedunkelt und die Faesser unter ihr sehen
        // anders aus -- der Bot hielt sie fuer offen und schob die Form hin und her.
        boolean[][] zu = zuVorForm != null ? zuVorForm : geschlossenesRaster(screen, ctx, r);
        int[] besteVerschiebung = besteStelle(form, zu);

        // Trifft die Form nirgends mehr ein geschlossenes Fass, oder nimmt das Spiel
        // den Haken nicht an (Testlauf 15:49: zehnmal bestaetigt, Form blieb) --
        // abbrechen und fuer dieses Feld keine Lupe mehr.
        if (besteVerschiebung[2] == 0 || hakenGedrueckt >= 3) {
            return lupeAbbrechen(screen, ctx, besteVerschiebung[2] == 0
                    ? Sprache.t("The magnifier would not hit a single closed barrel any more", "Die Lupe traefe kein geschlossenes Fass mehr")
                    : Sprache.t("The game does not accept the magnifier", "Das Spiel nimmt die Lupe nicht an"));
        }

        if (besteVerschiebung[0] == 0 && besteVerschiebung[1] == 0) {
            Log.action("Event", Sprache.t("The shape already lies well (" + besteVerschiebung[2]
                    + " of 3 vases still closed). Confirming.",
                    "Die Form liegt schon gut (" + besteVerschiebung[2]
                    + " von 3 Vasen noch zu). Wird bestaetigt."));
            hakenGedrueckt++;
            formZellenJetzt = new ArrayList<>(form);
            return bestaetigen(screen, ctx, runs);
        }

        // Angefasst wird moeglichst in der MITTE der blauen Umrandung.
        //
        // Autor hat es vorgemacht: "in die mitte der blauen umrandung gehen
        // und dann die maus gedrueckt halten und ziehen." Hier stand vorher
        // das erste Feld der Form -- bei einem Winkel ist das eine Ecke, und
        // an einer Ecke liegt der Griff dicht am Rand.
        //
        // Die Mitte von drei Feldern liegt aber nicht immer AUF einem Feld:
        // Bei einem Winkel faellt der Schwerpunkt in die Luecke. Deshalb
        // nicht der Schwerpunkt selbst, sondern das Feld der Form, das ihm
        // am naechsten liegt. So ist der Griff so mittig wie moeglich und
        // liegt sicher innerhalb der Umrandung.
        int[] griff = mittigstesFeld(form);
        int vonX = r.mitteX(griff[0]);
        int vonY = r.mitteY(griff[1]);
        int nachX = r.mitteX(griff[0] + besteVerschiebung[0]);
        int nachY = r.mitteY(griff[1] + besteVerschiebung[1]);

        Log.action("Event", Sprache.t("Move the shape by " + besteVerschiebung[0] + "/"
                + besteVerschiebung[1] + " cells -- it then hits "
                + besteVerschiebung[2] + " of 3 still closed vases.",
                "Form verschieben um " + besteVerschiebung[0] + "/"
                + besteVerschiebung[1] + " Felder -- trifft dann "
                + besteVerschiebung[2] + " von 3 noch geschlossene Vasen."));
        // Autor 24.10. (Ideenliste A37): die Form mit EINEM schnellen Zug ans Ziel -- ziehenSofort (Maus springt hin, 6 kurze
        // Zwischenschritte, ~190 ms) statt dragSchnell (Hingleiten, 10 Schritte, ~370 ms), im Titan-Event laeuft dieselbe Art
        // Form seit 09.10. so. Gemessen (Protokolle 05-54 und 13-27): 1146 + 400 Zuege, in keinem einzigen blieb der Zug
        // ohne Wirkung (nie dieselbe Verschiebung zweimal hintereinander). Verlangt das naechste Bild trotzdem noch einmal
        // GENAU dieselbe Verschiebung, hat das Spiel den schnellen Zug verschluckt: dann gilt fuer den Rest wieder
        // dragSchnell, und der Fehler zaehlt fuers Tempo-Lernen (eine Stufe zurueck).
        if (zugSofort && zuegeAnDerForm == 1 && letzterZug != null
                && letzterZug[0] == besteVerschiebung[0] && letzterZug[1] == besteVerschiebung[1]) {
            zugSofort = false;
            stageFehler++;
            Log.info("Event", Sprache.t("The quick drag did not move the shape -- from now on the slower drag is used.",
                    "Der schnelle Zug hat die Form nicht bewegt -- ab jetzt wieder der langsamere Zug."));
        }
        letzterZug = new int[]{besteVerschiebung[0], besteVerschiebung[1]};
        zuegeAnDerForm++;
        if (zugSofort) ctx.input().ziehenSofort(vonX, vonY, nachX, nachY);
        else ctx.input().dragSchnell(vonX, vonY, nachX, nachY);
        runs.progress(Sprache.t("Shape moved", "Form verschoben"));

        // Kurz warten reicht: Die Form springt beim Loslassen sofort auf ihr
        // Feld, da ist keine Animation abzuwarten. Hier standen 900, dann 450
        // Millisekunden "bis sich das Bild aendert" -- im Test 28.09. endete
        // das Warten 34 von 34 Mal erst an der Obergrenze (die Aenderung ist zu
        // klein fuer die Erkennung). Also fest 150 ms; das naechste Bild prueft
        // ohnehin, ob die Form richtig liegt.
        ctx.sleep(tempo.ms(150));
        return Outcome.ACTED;
    }

    // ------------------------------------------------------------------
    //  Der blaue Hammer (Super Hammer)
    // ------------------------------------------------------------------

    /** Die beiden Felder {Spalte, Reihe}, die der blaue Hammer gerade treffen soll. */
    private int[][] zielPaar = null;
    /** Paare, fuer die es auf diesem Feld schon nicht geklappt hat. */
    private final java.util.Set<String> paareVersucht = new java.util.HashSet<>();
    /** Nur nach einer Lupe (oder einem Super Hammer) neu suchen -- das Suchen kostet. */
    private boolean paarPruefen = true;
    /** Wann "Use!" gedrueckt wurde; kommt danach keine Form, sind die blauen Haemmer alle. */
    private long superUseUm = 0;
    private boolean superLeer = false;
    /** Vor dem Endstopp wurden die blauen Haemmer in diesem Feld schon einmal frisch geprueft (je Feld hoechstens einmal). */
    private boolean superNachprobiert = false;
    private int superFotos = 0;

    /**
     * Vor dem Endstopp (Schlussphase, keine Haemmer): sind die blauen Haemmer nur "leer", weil eine ALTE Probe es sagte, einmal je
     * Feld neu probieren -- sie fuellen sich beim Spielen nach.
     */
    static boolean frischPruefen(boolean superLeer, boolean schonNachprobiert) {
        return superLeer && !schonNachprobiert;
    }

    /** true = die blauen Haemmer werden vor dem Endstopp noch einmal frisch geprueft (nicht anhalten). */
    private boolean superFrischPruefen() {
        if (!frischPruefen(superLeer, superNachprobiert)) return false;
        superNachprobiert = true;
        superLeer = false;
        superOhneForm = 0;
        Log.info("Event", Sprache.t("Before stopping: checking the blue hammers once more (they refill while playing).",
                "Vor dem Anhalten: die blauen Haemmer noch einmal frisch pruefen (sie fuellen sich beim Spielen nach)."));
        return true;
    }

    /** Foto, wenn beim Ansehen des blauen Hammers gar keine Form kam (29.10.: 2 Super-Haemmer da, trotzdem 0 Felder). */
    private void superOhneFormFoto(BotContext ctx) {
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null || superFotos >= 5) return;
        superFotos++;
        try {
            java.nio.file.Path pfad = java.nio.file.Path.of("logs", "superhammer-ohne-form-"
                    + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            boolean foto = de.hustlebot.core.Beleg.schreiben(bild, "png", pfad.toFile());
            Log.info("Event", foto ? Sprache.t("Blue hammer without a shape -- photo: ", "Blauer Hammer ohne Form -- Foto: ") + pfad
                    : Sprache.t("Blue hammer without a shape.", "Blauer Hammer ohne Form."));
        } catch (Exception e) {
            Log.debug("Event", "Foto nicht gespeichert: " + e.getMessage());
        }
    }
    private int superBenutzt = 0;
    private int superOhneForm = 0;
    /** So lange nach "Use!" auf die gelbe Form warten. */
    private static final long SUPER_GEDULD_MS = 6_000;

    /**
     * Zwei gute Items (oder Stab + Item) direkt nebeneinander offen? Dann
     * "Use!" druecken. true = gedrueckt oder es wird auf die Form gewartet.
     */
    private boolean superhammerAnstossen(Mat screen, BotContext ctx, RunTracker runs) {
        // Autor 23.10. (Hammer-Event ohne Pass): neben dem Superhammer liegt ein Schloss -- dann gibt es keinen blauen
        // Hammer, gar nicht erst "Use!" versuchen.
        if (!superLeer && superUseUm == 0 && ctx.visibleIfKnown(screen, "ev_schloss_superhammer")) {
            superLeer = true;
            Log.info("Event", Sprache.t("Super Hammer is locked (no pass) -- normal hammers only.",
                    "Superhammer ist gesperrt (kein Pass) -- nur normale Haemmer."));
            return false;
        }
        if (superUseUm > 0) {
            if (System.currentTimeMillis() - superUseUm < SUPER_GEDULD_MS) {
                ctx.sleep(tempo.ms(300));         // die Form baut sich auf
                return true;
            }
            // Keine Form. Das Ziel bleibt gemerkt -- kommt sie doch noch (Spiel
            // ruckelt), wird sie richtig gelegt. Erst beim zweiten Mal hintereinander
            // gelten die blauen Haemmer als alle.
            superUseUm = 0;
            if (++superOhneForm >= 2) {
                superLeer = true;
                zielPaar = null;
                Log.info("Event", Sprache.t("\"Use!\" pressed twice, but no shape appeared -- probably no "
                        + "blue hammers left. Carrying on without them.",
                        "\"Use!\" zweimal gedrueckt, aber keine Form kam -- vermutlich keine "
                        + "blauen Haemmer mehr. Ab jetzt ohne."));
            }
            return false;
        }
        // Blaue Haemmer nur mit dem Gold-Pass (Autor, 28.09.). Ohne Abbrech-Knopf
        // nie: passt die Form nicht, muss der Bot zurueck koennen.
        // TEST (Autor, 29.09.: "benutz mal einen ganz am Anfang, zieh ihn einfach in die
        // linke obere Ecke, nur zum Test, ob es ueberhaupt funktioniert"). Einmalig.
        if (superTest && !superLeer && raster != null && de.hustlebot.core.Eventautomatik.blaueHaemmerErlaubt()
                && ctx.matcher().has(T_SUPER_USE) && ctx.matcher().has(T_FORM_X)
                && System.currentTimeMillis() - Math.max(textWegUm, Stoerfenster.textWeggeklicktUm()) > 1000
                && ctx.clickIfVisibleIfKnown(screen, T_SUPER_USE, Sprache.t("TEST blue hammer (Use!) -- shape into the top left corner", "TEST Blauer Hammer (Use!) -- Form in die linke obere Ecke"))) {
            superTestLaeuft = true;
            zielPaar = new int[][]{{0, 0}, {1, 0}};
            zuVorForm = geschlossenesRaster(screen, ctx, raster);
            formOffen = true;
            formOhneHaken = 0;
            superUseUm = System.currentTimeMillis();
            zuegeAnDerForm = 0;
            eintritte = 0;
            runs.progress(Sprache.t("Blue hammer", "Blauer Hammer"));
            ctx.sleepUntilChanged(1000);
            return true;
        }
        // FORM ANSEHEN (Autor, 29.09.: "einmal auf Use klicken, um sich die Form zu merken,
        // und nach jeder Benutzung wieder"). Die Form bleibt gleich, bis sie benutzt wird:
        // ist sie unbekannt, in einem ruhigen Moment "Use!" -- ansehen, merken, rotes X.
        // Kostet keinen Hammer und ~1 s; danach nur noch "Use!", wenn ein Paar hineinpasst.
        if (superFormGemerkt == null && !superTest && !superLeer && superAnsehenVersuche < 4 && raster != null
                && System.currentTimeMillis() - superBenutztUm > 3000   // erst nach der Hammer-Animation (Test 02:48)
                && de.hustlebot.core.Eventautomatik.blaueHaemmerErlaubt()
                && ctx.matcher().has(T_SUPER_USE) && ctx.matcher().has(T_FORM_X)
                && System.currentTimeMillis() - lupeBestaetigtUm > LUPE_HINSEHEN_MS
                && System.currentTimeMillis() - Math.max(textWegUm, Stoerfenster.textWeggeklicktUm()) > 1000
                && ctx.clickIfVisibleIfKnown(screen, T_SUPER_USE, Sprache.t("Blue hammer: look at the shape (cancelled right after)", "Blauer Hammer: Form ansehen (wird gleich abgebrochen)"))) {
            superAnsehenVersuche++;
            superNurAnsehen = true;
            zielPaar = null;
            formOffen = true;
            formOhneHaken = 0;
            superUseUm = System.currentTimeMillis();
            zuegeAnDerForm = 0;
            eintritte = 0;
            ctx.sleepUntilChanged(1000);
            return true;
        }
        if (superAbbruecheFeld >= SUPER_ABBRUECHE_JE_FELD) return false;   // Autor 24.10.: nicht fuenfmal hintereinander
        if (!de.hustlebot.core.Eventautomatik.blaueHaemmerErlaubt() || superLeer || raster == null || !ctx.matcher().has(T_SUPER_USE)
                || !ctx.matcher().has(T_FORM_X)) {
            return false;
        }
        // Autor 28.10.: auf aufgedeckte Belohnungen erst AM SCHLUSS -- wenn keine Lupe mehr dran ist oder der Zauberstab
        // schon offen liegt. Vorher deckt die naechste Lupe vielleicht noch mehr gute Felder nebeneinander auf.
        if (!nurSuperHaemmer && lupeNochDran(screen, ctx)
                && (de.hustlebot.core.Eventautomatik.alleLupen() || stabImFeld(screen, ctx).isEmpty())) return false;
        int[][] paar = gutesPaar(screen, ctx, raster);
        if (paar == null) return false;
        // Fuer das Feinlegen (superStelle) zaehlen geschlossene Faesser UND gute Felder -- so landet das dritte Feld
        // der Form lieber auf einer weiteren Belohnung als auf einem leeren Fass.
        boolean[][] zuJetzt = geschlossenesRaster(screen, ctx, raster);
        boolean[][] wert = new boolean[SPALTEN][REIHEN];
        for (int s = 0; s < SPALTEN; s++) wert[s] = zuJetzt[s].clone();   // Kopie: das Raster selbst nicht veraendern
        for (int[] g : guteItemZellen(screen, ctx, raster)) wert[g[0]][g[1]] = true;
        zuVorForm = wert;
        final int wieViele = letzteSuperGut;
        if (!ctx.clickIfVisibleIfKnown(screen, T_SUPER_USE, Sprache.t("Blue hammer (Use!): catches " + wieViele
                + " good item(s) at " + feldText(paar[0]) + " and " + feldText(paar[1]),
                "Blauer Hammer (Use!): trifft " + wieViele + " gute Felder bei " + feldText(paar[0]) + " und " + feldText(paar[1])))) {
            return false;
        }
        zielPaar = paar;
        formOffen = true;
        formOhneHaken = 0;
        superUseUm = System.currentTimeMillis();
        zuegeAnDerForm = 0;
        eintritte = 0;
        runs.progress(Sprache.t("Blue hammer", "Blauer Hammer"));
        ctx.sleepUntilChanged(1000);
        return true;
    }

    /**
     * Alle Belohnungen im Feld, JE BILD NUR EINMAL gesucht (Tempo, 29.09.): 35
     * Vorlagen kosteten je Suche uebers Feld ~300 ms, und Mitnehmen, Paar-Suche und
     * Hinsehen suchten jedes fuer sich -- ein Bild brauchte so bis 0,9 s. Jetzt die
     * schnelle Vorsuche in halber Groesse (Vorschwelle 0,12 unter der Schwelle;
     * gemessen: halb liegt hoechstens 0,09 unter voll), Nachpruefung in voller Groesse.
     * Reihenfolge wie kistenNamen() (Schluessel, Nahrung, Rest), je Vorlage bestes zuerst.
     */
    private List<Matcher.Hit> belohnungenImFeld(Mat screen, BotContext ctx) {
        if (screen == belohnungenVon) return belohnungen;
        org.opencv.core.Rect feld = feldBereich();
        List<Matcher.Hit> alle = new ArrayList<>();
        for (String name : kistenNamen(ctx)) {
            // Nacht 28.10. (Kreuztabelle): Mana/Eisen/Mithril/gruene Essenz passen im Feld unter der alten Schwelle und
            // blieben liegen -- eigene Schwellen mit >= 0,06 Abstand zu allem Fremden (EventVorlagenSchwellen).
            double mild = EventVorlagenSchwellen.schwelle(name, mildeSchwelle(name));
            if (feld != null) alle.addAll(ctx.matcher().findAllSchnell(screen, name, feld, mild - 0.12, mild, 4));
            else ctx.matcher().find(screen, name, KISTEN_SCHWELLE).ifPresent(alle::add);
        }
        belohnungenVon = screen;
        belohnungen = alle;
        return alle;
    }
    private Mat belohnungenVon;
    private List<Matcher.Hit> belohnungen = List.of();

    /**
     * Die Form des blauen Hammers, wie sie zuletzt zu sehen war (Autor, 29.09.: "die
     * Super-Hammer-Form bleibt immer gleich, solange bis man sie einmal benutzt").
     * null = unbekannt (dann einmal "Use!" druecken und nachsehen). Bleibt ueber die
     * Stages hinweg, nur ein Neustart des Bots vergisst sie.
     */
    private List<int[]> superFormGemerkt = null;
    /** Wann zuletzt ein blauer Hammer wirklich benutzt wurde. */
    private long superBenutztUm = 0;
    /** Gerade nur zum Ansehen "Use!" gedrueckt (danach rotes X). */
    private boolean superNurAnsehen = false;
    /** Ansehen-Versuche in dieser Stage (hoechstens 4, falls die Form nicht erkannt wird; Autor 27.10.: vorher 2). */
    private int superAnsehenVersuche = 0;
    /** Autor 27.10. (Idee 1): die gelbe Form ist nach "Use!" oft erst nach einem Moment fertig gezeichnet -- so viele Bilder lang geduldig nachlesen, bevor abgebrochen wird. */
    private int superAnsehenBilder = 0;
    private static final int SUPER_ANSEHEN_BILDER = 8;   // Autor 27.10.: laenger geduldig nachlesen (gegen "0 Felder"-Abbrueche); vorher 5

    /** Die eingestellte Zahl Stages ist geschafft -- Event verlassen. */
    private boolean zielStages = false;

    // --- Zauberstab-Grenze (Autor, 29.09.) --------------------------------------
    private static final String T_REITER_RANKINGS = "ev_reiter_rankings";
    private static final String T_REITER_GAME = "ev_reiter_game";
    /** So viele Stages zwischen zwei Blicken in die Rangliste (~3 s je Blick). */
    private static final int STAB_BLICK_ALLE = 10;
    /** Stages seit dem letzten Blick; beim Betreten des Events gleich faellig. */
    private int stagesSeitStabBlick = STAB_BLICK_ALLE;
    private boolean stabVorlagenGemeldet = false;

    /** Wunsch aus Discord ("!event ranking"): wohin das Bild der Rangliste geht -- sonst null. */
    private volatile java.util.function.Consumer<byte[]> ranglisteAn = null;

    /** Merkt den Wunsch vor; erledigt wird er an der naechsten ruhigen Stelle im Event. */
    public void ranglisteSchicken(java.util.function.Consumer<byte[]> ziel) {
        ranglisteAn = ziel;
    }

    /**
     * Reiter "Rankings" oeffnen, ein Bild schicken, zurueck zu "Game" (Autor 21.10., aus dem Hauptbot "!event rangliste").
     * Wie bei der Zauberstab-Grenze ueber die Bilder ev_reiter_rankings und ev_reiter_game (der Hauptbot rechnet die
     * Stelle vom Reiter "Quests" aus -- den gibt es hier nicht).
     *
     * @return true, wenn geklickt wurde
     */
    private boolean ranglisteZeigen(Mat screen, BotContext ctx) {
        java.util.function.Consumer<byte[]> ziel = ranglisteAn;
        if (ziel == null) return false;
        if (!ctx.matcher().has(T_REITER_RANKINGS) || !ctx.matcher().has(T_REITER_GAME)) {
            ranglisteAn = null;   // ohne die Bilder kommt der Bot nicht zurueck aufs Spielfeld -- gar nicht erst oeffnen
            Log.warn("Event", Sprache.t("Ranking picture skipped: the pictures ev_reiter_rankings and ev_reiter_game are missing "
                    + "(Capture-Images.exe, HAMMER-EVENT).",
                    "Rangliste nicht fotografiert: die Bilder ev_reiter_rankings und ev_reiter_game fehlen "
                    + "(Capture-Images.exe, HAMMER-EVENT)."));
            return false;
        }
        if (!ctx.clickIfVisible(screen, T_REITER_RANKINGS, Sprache.t("Open the rankings (picture for Discord)", "Rangliste oeffnen (Bild fuer Discord)"))) {
            return false;   // Reiter gerade nicht zu sehen -- beim naechsten Bild noch einmal
        }
        ranglisteAn = null;
        ctx.sleepUntilChanged(1500);
        ctx.sleep(tempo.ms(900));   // die Liste laedt und rollt zur eigenen Platzierung
        ctx.refresh();
        try {
            java.awt.image.BufferedImage bild = ctx.frame();
            if (bild != null) ziel.accept(de.hustlebot.core.ScreenCapture.toPreviewPng(bild, 1200));
        } catch (Exception e) {
            Log.warn("Event", Sprache.t("Ranking: picture not made -- ", "Rangliste: Bild nicht erzeugt -- ") + e.getMessage());
        }
        // Zurueck aufs Spielfeld.
        for (int i = 0; i < 12; i++) {
            Mat bild = ctx.refresh();
            if (bild != null && ctx.clickIfVisible(bild, T_REITER_GAME, Sprache.t("Back to the playing field (Game tab)", "Zurueck zum Spielfeld (Reiter Game)"))) {
                ctx.sleepUntilChanged(1200);
                return true;
            }
            ctx.sleep(tempo.ms(250));
        }
        Log.warn("Event", Sprache.t("Ranking: the \"Game\" tab was not found.", "Rangliste: der Reiter \"Game\" war nicht zu finden."));
        return true;
    }

    /**
     * Zauberstab-Grenze: im Reiter "Rankings" die eigene Zahl ablesen
     * ({@link Zauberstaebe}), dann zurueck zum Reiter "Game". Ist die Grenze
     * erreicht, wird das Event wie nach der Stage-Zahl verlassen.
     *
     * @return true, wenn geklickt wurde
     */
    private boolean zauberstaebeAblesen(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_REITER_RANKINGS) || !ctx.matcher().has(T_REITER_GAME)) {
            if (!stabVorlagenGemeldet) {
                stabVorlagenGemeldet = true;
                Log.warn("Event", Sprache.t("The magic wand limit is on, but the pictures ev_reiter_rankings and "
                        + "ev_reiter_game are missing (Capture-Images.exe, HAMMER-EVENT). Until then nothing is read.",
                        "Die Zauberstab-Grenze ist an, aber die Bilder ev_reiter_rankings und "
                        + "ev_reiter_game fehlen (Capture-Images.exe, HAMMER-EVENT). Bis dahin wird nicht abgelesen."));
            }
            stagesSeitStabBlick = 0;
            return false;
        }
        if (!ctx.clickIfVisible(screen, T_REITER_RANKINGS, Sprache.t("Open the rankings (read the magic wands)", "Rangliste oeffnen (Zauberstaebe ablesen)"))) {
            return false;   // Reiter gerade nicht zu sehen -- beim naechsten Bild noch einmal
        }
        stagesSeitStabBlick = 0;
        java.util.OptionalLong n = java.util.OptionalLong.empty();
        long ende = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < ende && n.isEmpty()) {
            ctx.sleep(tempo.ms(250));
            if (ctx.refresh() == null) continue;
            n = Zauberstaebe.ausRangliste(ctx.frame());
        }
        // Zurueck aufs Spielfeld.
        boolean zurueck = false;
        for (int i = 0; i < 12 && !zurueck; i++) {
            Mat bild = ctx.refresh();
            if (bild != null && ctx.clickIfVisible(bild, T_REITER_GAME, Sprache.t("Back to the playing field (Game tab)", "Zurueck zum Spielfeld (Reiter Game)"))) {
                zurueck = true;
                ctx.sleepUntilChanged(1200);
            } else {
                ctx.sleep(tempo.ms(250));
            }
        }
        if (!zurueck) {
            Log.warn("Event", Sprache.t("The \"Game\" tab was not found after the rankings.",
                    "Der Reiter \"Game\" war nach der Rangliste nicht zu finden."));
        }
        if (n.isEmpty()) {
            Log.warn("Event", Sprache.t("Magic wands in the rankings not readable -- next attempt in "
                    + STAB_BLICK_ALLE + " stages.",
                    "Zauberstaebe in der Rangliste nicht lesbar -- naechster Versuch in "
                    + STAB_BLICK_ALLE + " Stages."));
            return true;
        }
        de.hustlebot.core.Zauberstabziel.merken(n.getAsLong());
        Log.info("Event", Sprache.t("Magic wands: " + n.getAsLong() + " of "
                + de.hustlebot.core.Zauberstabziel.ziel() + ".",
                "Zauberstaebe: " + n.getAsLong() + " von "
                + de.hustlebot.core.Zauberstabziel.ziel() + "."));
        if (de.hustlebot.core.Zauberstabziel.erreicht()) {
            zielStages = true;
            Log.info("Event", Sprache.t("Magic wand limit " + de.hustlebot.core.Zauberstabziel.ziel()
                    + " reached -- leaving the event, on to the Cathedral.",
                    "Zauberstab-Grenze " + de.hustlebot.core.Zauberstabziel.ziel()
                    + " erreicht -- das Event wird verlassen, weiter mit der Kathedrale."));
        }
        return true;
    }

    /** Hammer-Phase: blauen Hammer auf drei geschlossene Faesser, wenn die gemerkte Form das hergibt. */
    private boolean superStattNormal(Mat screen, BotContext ctx, RunTracker runs) {
        List<int[]> form = superFormGemerkt;
        // Autor 24.10.: der blaue Hammer wurde 5x hintereinander gedrueckt und wieder abgebrochen (Form nicht sauber
        // erkannt) -- nach 2 Abbruechen auf diesem Feld nur noch normale Haemmer.
        if (superAbbruecheFeld >= SUPER_ABBRUECHE_JE_FELD) return false;
        if (form == null || superLeer || formOffen || raster == null || superUseUm > 0
                || !de.hustlebot.core.Eventautomatik.blaueHaemmerErlaubt()
                || !ctx.matcher().has(T_SUPER_USE) || !ctx.matcher().has(T_FORM_X)) return false;
        boolean[][] zu = geschlossenesRaster(screen, ctx, raster);
        // Autor 28.10.: auf GESCHLOSSENE Faesser nur, wenn die gemerkte Form perfekt passt (alle drei Felder zu) --
        // in der Suchphase nach dem Zauberstab lieber ein blauer Hammer als drei normale. Nie "irgendwie" auf Faesser.
        // In der Schlussphase ohne normale Haemmer (nurSuperHaemmer) ersetzt er den normalen Hammer: dann reicht ein Fass.
        int mindestens = nurSuperHaemmer ? 1 : 3;
        int[][] ziel = null;
        int besteZu = mindestens - 1;
        for (int dx = -(SPALTEN - 1); dx <= SPALTEN - 1; dx++) {
            for (int dy = -(REIHEN - 1); dy <= REIHEN - 1; dy++) {
                boolean passt = true;
                List<int[]> geschlossen = new ArrayList<>(), andere = new ArrayList<>();
                for (int[] f : form) {
                    int sp = f[0] + dx, re = f[1] + dy;
                    if (sp < 0 || sp >= SPALTEN || re < 0 || re >= REIHEN) { passt = false; break; }
                    (zu[sp][re] ? geschlossen : andere).add(new int[]{sp, re});
                }
                int zuC = geschlossen.size();
                if (!passt || zuC <= besteZu) continue;
                geschlossen.addAll(andere);
                int[][] paar = {geschlossen.get(0), geschlossen.get(1)};
                if (paareVersucht.contains(paarSchluessel(paar))) continue;
                ziel = paar;
                besteZu = zuC;
            }
        }
        if (ziel == null) return false;
        final int wieViele = besteZu;
        if (!ctx.clickIfVisibleIfKnown(screen, T_SUPER_USE, Sprache.t("Blue hammer (Use!) instead of normal hammers: hits "
                + wieViele + " closed barrel(s)", "Blauer Hammer (Use!) statt normaler Haemmer: trifft " + wieViele
                + " geschlossene(s) Fass/Faesser"))) return false;
        zielPaar = ziel;
        zuVorForm = zu;
        formOffen = true;
        formOhneHaken = 0;
        superUseUm = System.currentTimeMillis();
        zuegeAnDerForm = 0;
        eintritte = 0;
        runs.progress(Sprache.t("Blue hammer", "Blauer Hammer"));
        ctx.sleepUntilChanged(1000);
        return true;
    }

    /** Einmal-Test des blauen Hammers (Datei superhammer-test.txt, erste Zeile "an"). */
    private boolean superTest = false;
    private boolean superTestLaeuft = false;
    private static final java.nio.file.Path SUPER_TEST = java.nio.file.Path.of("superhammer-test.txt");

    private static boolean superTestAn() {
        try {
            return java.nio.file.Files.exists(SUPER_TEST)
                    && java.nio.file.Files.readString(SUPER_TEST).trim().toLowerCase().startsWith("an");
        } catch (Exception e) {
            return false;
        }
    }

    /** Test vorbei (gelungen oder nicht): nur einmal, danach steht "aus" in der Datei. */
    private void superTestErledigt() {
        superTest = false;
        superTestLaeuft = false;
        try {
            java.nio.file.Files.writeString(SUPER_TEST, "aus\n" + Sprache.t(
                    "# One-off test of the blue hammer -- \"an\" (on) = use it once at the next event start, into the top left corner\n",
                    "# Einmal-Test blauer Hammer -- \"an\" = beim naechsten Event-Start einmal in die linke obere Ecke\n"));
        } catch (Exception e) {
            Log.debug("Event", "superhammer-test.txt nicht geschrieben: " + e.getMessage());
        }
    }

    /**
     * Die noch nicht abgeholten GUTEN ITEMS (Kisten-Vorlagen oder der Stab) als Rasterfelder. Grundlage fuer den blauen
     * Hammer (Autor 27.10.: "nur einsetzen, wenn er mindestens ein gutes Item trifft"). Je Bild nur einmal gesucht.
     */
    private List<int[]> guteItemZellen(Mat screen, BotContext ctx, Raster r) {
        // Dieselben Treffer und Regeln wie beim Mitnehmen (29.09.): echte Kisten passen im Feld nur 0,74-0,84.
        List<Matcher.Hit> kandidaten = new ArrayList<>(belohnungenImFeld(screen, ctx));
        stabImFeld(screen, ctx).ifPresent(kandidaten::add);
        List<int[]> zellen = new ArrayList<>();
        boolean[][] zu = null;
        for (Matcher.Hit h : kandidaten) {
            boolean stab = h.name().equals(T_STAB) || h.name().equals(T_STAB_KERN);
            int sp = (int) Math.round((h.centerX() - r.x0()) / (double) r.schrittX());
            int re = (int) Math.round((h.centerY() - r.y0()) / (double) r.schrittY());
            if (sp < 0 || re < 0 || sp >= SPALTEN || re >= REIHEN) continue;
            if (feldAbgeholt(sp, re)) continue;   // schon abgeholt
            if (!stab && h.score() < KISTEN_SCHWELLE) {
                boolean mittig = Math.abs(h.centerX() - r.mitteX(sp)) <= r.schrittX() / 4
                        && Math.abs(h.centerY() - r.mitteY(re)) <= r.schrittY() / 4;
                if (!mittig) continue;
                if (zu == null) zu = geschlossenesRasterNachVorlage(screen, ctx, r);
                if (zu[sp][re]) continue;
            }
            if (!stab && abgeschaltetGewinnt(screen, ctx, h)) continue;
            if (!stab && EventVorlagenSchwellen.krakenAnStelle(screen, ctx.matcher(), h)) continue;   // nie gezielt auf Kraken
            boolean schon = false;
            for (int[] c : zellen) schon |= c[0] == sp && c[1] == re;
            if (!schon) zellen.add(new int[]{sp, re, stab ? 1 : 0});   // [2] = 1: das ist der Zauberstab
        }
        return zellen;
    }

    /**
     * Beste Stelle fuer den blauen Hammer auf GUTEN FELDERN (Autor 28.10.): dort, wo die gemerkte Form die meisten
     * aufgedeckten Belohnungen trifft -- schon ab EINER, am liebsten 2-3. Der Zauberstab zaehlt mit, reicht allein aber
     * nicht (dafuer genuegt ein normaler Hammer) -- ausser in der Schlussphase ohne normale Haemmer. Kraken-Kisten und
     * Helden-Splitter duerfen dabei mit unter der Form liegen (Autor). Nach dem Stab holt Schritt 7 die uebrigen
     * Belohnungen trotzdem noch, bevor neu gemischt wird.
     *
     * @return Zielpaar (zwei getroffene Felder, gute zuerst) fuer superStelle, sonst null
     */
    private int[][] gutesPaar(Mat screen, BotContext ctx, Raster r) {
        letzteSuperGut = 0;
        if (superFormGemerkt == null || superFormGemerkt.size() != 3) return null;
        List<int[]> gut = guteItemZellen(screen, ctx, r);
        if (gut.isEmpty()) return null;
        int[][] ziel = null;
        int besteGut = 0, besteBelohnung = 0;
        for (int dx = -(SPALTEN - 1); dx <= SPALTEN - 1; dx++) {
            for (int dy = -(REIHEN - 1); dy <= REIHEN - 1; dy++) {
                boolean passt = true;
                int gutC = 0, belohnungC = 0;
                int[] erstes = null, zweites = null;
                List<int[]> andere = new ArrayList<>();
                for (int[] f : superFormGemerkt) {
                    int sp = f[0] + dx, re = f[1] + dy;
                    if (sp < 0 || sp >= SPALTEN || re < 0 || re >= REIHEN) { passt = false; break; }
                    int[] g = zelleIn(gut, sp, re);
                    if (g == null) { andere.add(new int[]{sp, re}); continue; }
                    gutC++;
                    if (g.length < 3 || g[2] == 0) belohnungC++;
                    if (erstes == null) erstes = new int[]{sp, re};
                    else if (zweites == null) zweites = new int[]{sp, re};
                }
                if (!passt || gutC == 0) continue;
                if (belohnungC == 0 && !nurSuperHaemmer) continue;   // nur der Stab: dafuer kein blauer Hammer
                if (zweites == null) zweites = andere.get(0);
                int[][] paar = {erstes, zweites};
                if (paareVersucht.contains(paarSchluessel(paar))) continue;
                if (gutC > besteGut || (gutC == besteGut && belohnungC > besteBelohnung)) {
                    ziel = paar;
                    besteGut = gutC;
                    besteBelohnung = belohnungC;
                }
            }
        }
        letzteSuperGut = besteGut;
        return ziel;
    }

    private static int[] zelleIn(List<int[]> zellen, int sp, int re) {
        for (int[] z : zellen) if (z[0] == sp && z[1] == re) return z;
        return null;
    }

    /**
     * Die gelbe Form liegt: so schieben, dass sie beide Items trifft (das
     * dritte Feld moeglichst auf ein noch geschlossenes Fass). Geht das
     * nicht -- rotes X, abbrechen (Autor).
     */
    private Outcome superhammerLegen(Mat screen, BotContext ctx, RunTracker runs) {
        superUseUm = 0;
        Raster r = rasterHolen(screen, ctx);
        List<int[]> form = r == null ? List.of() : formFelder(screen, r, GELB_VON, GELB_BIS);
        if (superNurAnsehen) {
            if (form.size() == 3) {
                // Nur angesehen: Form merken, rotes X -- es wird nichts verbraucht.
                superNurAnsehen = false;
                superAnsehenBilder = 0;
                superFormGemerkt = new ArrayList<>(form);
                superOhneForm = 0;   // es gibt noch blaue Haemmer
                Log.info("Event", Sprache.t("Blue hammer: shape looked at and memorised (" + formName(form)
                        + ") -- cancelled, nothing used up.",
                        "Blauer Hammer: Form angesehen und gemerkt (" + formName(form)
                        + ") -- abgebrochen, nichts verbraucht."));
                zielPaar = null;
                zuegeAnDerForm = 0;
                paarPruefen = true;
                formOffen = false;   // ohne erkannte Form: nicht fuer eine Lupen-Form halten (Test 02:48: 5,5 s verloren)
                if (ctx.clickIfVisibleIfKnown(screen, T_FORM_X, Sprache.t("Blue hammer: only looked at -- cancel (red X)", "Blauer Hammer: nur angesehen -- abbrechen (rotes X)"))) {
                    ctx.sleepUntilChanged(900);
                    return Outcome.ACTED;
                }
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            // Autor 27.10. (Idee 1): die gelbe Form ist nach "Use!" oft erst nach einem Moment fertig gezeichnet.
            // Nicht gleich abbrechen -- ein paar Bilder lang geduldig nachlesen (Form bleibt offen, kostet keinen Hammer).
            if (++superAnsehenBilder <= SUPER_ANSEHEN_BILDER) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            superNurAnsehen = false;
            superAnsehenBilder = 0;
            // Autor 29.10. 22:20: "Der Bot hat keine Super-Haemmer mehr" -- beim Ansehen kam dann gar keine Form (0 Felder),
            // viermal je Feld, ~5 s jedes Mal. Zweimal hintereinander ohne jede Form = keine blauen Haemmer mehr.
            // Autor 22:36: "es sind noch zwei Superhaemmer da" -- "keine Form" heisst also nicht sicher "keine Haemmer".
            // Foto behalten (hoechstens 5 je Lauf), damit die Ursache beim naechsten Event zu sehen ist.
            if (form.isEmpty()) superOhneFormFoto(ctx);
            if (form.isEmpty() && ++superOhneForm >= 2 && !superLeer) {
                superLeer = true;
                Log.info("Event", Sprache.t("Blue hammer: no shape at all twice in a row -- either no blue hammers left or the shape "
                                + "is not recognised. Normal hammers only for now.",
                        "Blauer Hammer: zweimal hintereinander gar keine Form -- entweder keine blauen Haemmer mehr oder die Form "
                                + "wird nicht erkannt. Vorerst nur normale Haemmer."));
            }
            Log.info("Event", Sprache.t("Blue hammer: shape not cleanly recognised while looking (" + form.size()
                    + " cells instead of 3, even after waiting) -- trying again later.",
                    "Blauer Hammer: Form beim Ansehen nicht sauber erkannt (" + form.size()
                    + " Felder statt 3, auch nach dem Warten) -- spaeter noch einmal."));
            zielPaar = null;
            zuegeAnDerForm = 0;
            paarPruefen = true;
            formOffen = false;
            if (ctx.clickIfVisibleIfKnown(screen, T_FORM_X, Sprache.t("Blue hammer: only looked at -- cancel (red X)", "Blauer Hammer: nur angesehen -- abbrechen (rotes X)"))) {
                ctx.sleepUntilChanged(900);
                return Outcome.ACTED;
            }
            ctx.sleep(tempo.ms(300));
            return Outcome.WAITING;
        }
        if (zielPaar == null || r == null) {
            return superAbbrechen(screen, ctx,
                    Sprache.t("A yellow shape is lying there, but the bot did not pick the blue hammer itself",
                            "Eine gelbe Form liegt, aber der Bot hat den blauen Hammer nicht selbst gewaehlt"));
        }
        if (form.size() != 3) {
            // Autor 27.10. (Idee 1): auch beim echten Einsatz geduldig nachlesen statt sofort abbrechen.
            if (++superAnsehenBilder <= SUPER_ANSEHEN_BILDER) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            superAnsehenBilder = 0;
            return superAbbrechen(screen, ctx,
                    Sprache.t("The yellow shape was not cleanly recognised (" + form.size() + " cells instead of 3, even after waiting)",
                            "Die gelbe Form wurde nicht sauber erkannt (" + form.size() + " Felder statt 3, auch nach dem Warten)"));
        }
        superAnsehenBilder = 0;
        superFormGemerkt = new ArrayList<>(form);   // bleibt gleich, bis sie benutzt wird
        int[] v;
        if (superTestLaeuft) {
            // TEST: die Form so schieben, dass ihr linkes oberes Feld in der Ecke liegt.
            int minSp = SPALTEN, minRe = REIHEN;
            for (int[] f : form) { minSp = Math.min(minSp, f[0]); minRe = Math.min(minRe, f[1]); }
            v = new int[]{-minSp, -minRe};
        } else {
            v = superStelle(form, zielPaar, zuVorForm != null ? zuVorForm : geschlossenesRaster(screen, ctx, r));
        }
        if (v == null) {
            return superAbbrechen(screen, ctx, Sprache.t("The shape " + formName(form)
                    + " does not fit both items",
                    "Die Form " + formName(form)
                    + " passt nicht auf beide Items"));
        }
        if (v[0] == 0 && v[1] == 0) {
            if (hakenKlicken(screen, ctx, superTestLaeuft ? Sprache.t("TEST blue hammer: shape lies in the top left corner", "TEST Blauer Hammer: Form liegt in der linken oberen Ecke")
                    : Sprache.t("Confirm blue hammer (" + feldText(zielPaar[0]) + ", " + feldText(zielPaar[1]) + ")",
                            "Blauer Hammer bestaetigen (" + feldText(zielPaar[0]) + ", " + feldText(zielPaar[1]) + ")"))) {
                if (superTestLaeuft) {
                    Log.info("Event", Sprache.t("TEST blue hammer: used, shape " + formName(form) + " confirmed in the corner.",
                            "TEST blauer Hammer: benutzt, Form " + formName(form) + " in der Ecke bestaetigt."));
                    superTestErledigt();
                }
                superBenutzt++;
                superOhneZiel = 0;
                superBenutztUm = System.currentTimeMillis();
                // Nacht 28.10. (Vorlagen-Pruefung): der blaue Hammer holt die Kisten unter der Form ab -- danach sieht die
                // Kisten-Vorlage das offene Feld noch (z. B. Schatz gold 0,761), und der Bot klickte die abgeholte Kiste
                // bis zu 4x an ("Reward claimed!"-Fenster, 9x zwischen 22:23 und 22:39, ~5-6 min). Darum gelten alle
                // Felder unter der Form ab jetzt als abgeholt.
                for (int[] f : form) {
                    int[] st = stelleMerken(r.mitteX(f[0]), r.mitteY(f[1]));
                    st[2] = 99;
                    st[3] = 1;
                }
                for (int[] f : zielPaar) {
                    int[] st = stelleMerken(r.mitteX(f[0]), r.mitteY(f[1]));
                    st[2] = 99;
                    st[3] = 1;
                }
                superFormGemerkt = null;   // benutzt: die naechste Form ist eine andere
                superOhneForm = 0;
                formOffen = false;
                zuVorForm = null;
                paareVersucht.add(paarSchluessel(zielPaar));
                zielPaar = null;
                zuegeAnDerForm = 0;
                wirkungslos = 0;
                eintritte = 0;
                paarPruefen = true;
                vasenVorKlick = -1;
                runs.progress(Sprache.t("Blue hammer used", "Blauer Hammer benutzt"));
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            ctx.sleep(tempo.ms(300));
            return Outcome.WAITING;
        }
        if (zuegeAnDerForm >= 2) {
            return superAbbrechen(screen, ctx, Sprache.t("The shape could not be dragged to the spot", "Die Form liess sich nicht an die Stelle ziehen"));
        }
        int[] griff = mittigstesFeld(form);
        Log.action("Event", Sprache.t("Blue hammer: move the shape by " + v[0] + "/" + v[1] + " cells.",
                "Blauer Hammer: Form um " + v[0] + "/" + v[1] + " Felder verschieben."));
        zuegeAnDerForm++;
        ctx.input().dragSchnell(r.mitteX(griff[0]), r.mitteY(griff[1]),
                r.mitteX(griff[0] + v[0]), r.mitteY(griff[1] + v[1]));
        runs.progress(Sprache.t("Shape moved", "Form verschoben"));
        ctx.sleep(tempo.ms(150));
        return Outcome.ACTED;
    }

    /** Rotes X: der blaue Hammer bleibt, die Items holt der Bot einzeln. */
    private Outcome superAbbrechen(Mat screen, BotContext ctx, String grund) {
        if (superTestLaeuft) {
            Log.warn("Event", Sprache.t("TEST blue hammer did not work: ", "TEST blauer Hammer ging nicht: ") + grund);
            superTestErledigt();
        }
        if (zielPaar != null) paareVersucht.add(paarSchluessel(zielPaar));
        zielPaar = null;
        zuegeAnDerForm = 0;
        paarPruefen = true;
        superAbbruecheFeld++;
        Log.info("Event", grund + Sprache.t(" -- cancelled, the items are collected one by one.", " -- abgebrochen, die Items werden einzeln geholt."));
        if (ctx.clickIfVisibleIfKnown(screen, T_FORM_X, Sprache.t("Cancel blue hammer (red X)", "Blauer Hammer abbrechen (rotes X)"))) {
            formOffen = false;
            ctx.sleepUntilChanged(900);
            return Outcome.ACTED;
        }
        ctx.sleep(tempo.ms(300));
        return Outcome.WAITING;
    }

    /**
     * Verschiebung {dx, dy}, bei der die Form beide Felder des Paares trifft;
     * bei mehreren die mit den meisten geschlossenen Faessern auf dem dritten
     * Feld, dann die kuerzeste. null = geht nicht (die Form dreht sich nicht).
     */
    static int[] superStelle(List<int[]> form, int[][] paar, boolean[][] zu) {
        int[] beste = null;
        int besterWert = -1, besterWeg = Integer.MAX_VALUE;
        for (int dx = -(SPALTEN - 1); dx <= SPALTEN - 1; dx++) {
            for (int dy = -(REIHEN - 1); dy <= REIHEN - 1; dy++) {
                boolean passt = true, a = false, b = false;
                int wert = 0;
                for (int[] f : form) {
                    int sp = f[0] + dx, re = f[1] + dy;
                    if (sp < 0 || sp >= SPALTEN || re < 0 || re >= REIHEN) { passt = false; break; }
                    if (sp == paar[0][0] && re == paar[0][1]) a = true;
                    else if (sp == paar[1][0] && re == paar[1][1]) b = true;
                    else if (zu[sp][re]) wert++;
                }
                if (!passt || !a || !b) continue;
                int weg = Math.abs(dx) + Math.abs(dy);
                if (wert > besterWert || (wert == besterWert && weg < besterWeg)) {
                    beste = new int[]{dx, dy};
                    besterWert = wert;
                    besterWeg = weg;
                }
            }
        }
        return beste;
    }

    private static String paarSchluessel(int[][] p) {
        String a = p[0][0] + "," + p[0][1], b = p[1][0] + "," + p[1][1];
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static String feldText(int[] f) {
        return Sprache.t("column " + (f[0] + 1) + "/row " + (f[1] + 1), "Spalte " + (f[0] + 1) + "/Reihe " + (f[1] + 1));
    }

    // ------------------------------------------------------------------
    //  Welche Formen kommen -- und in welcher Reihenfolge?
    // ------------------------------------------------------------------

    /**
     * Jede erkannte Form bekommt einen Namen, und die Reihenfolge wird
     * mitgeschrieben.
     *
     * WOZU. Autors Frage nach drei Durchgaengen: "kann der bot mal
     * mitschauen welche formen nacheinander erscheinen, vielleicht gibt es
     * eine feste reihenfolge."
     *
     * Wenn es eine gibt, waere das viel wert: Der Bot koennte die naechste
     * Form schon einrechnen, statt jede fuer sich zu legen. Aus fuenfzehn
     * aufgedeckten Vasen wuerden dann vielleicht die letzten Ecken sauber
     * ausgefuellt.
     *
     * ABER ERST WISSEN, DANN BAUEN. Ob es eine Reihenfolge gibt, weiss
     * heute niemand -- also wird sie zuerst aufgeschrieben. Steht im
     * Bericht dreimal dieselbe Kette, ist die Sache klar. Steht dort
     * Durcheinander, hat sich die Arbeit erspart.
     *
     * Es gibt genau sechs Dreier-Formen: zwei gerade und vier Winkel. Die
     * Winkel sind danach benannt, welche Ecke ihres Zweier-Kastens FEHLT --
     * "ohne-ru" heisst: rechts unten ist frei.
     */
    private final List<String> formenGesehen = new ArrayList<>();

    /** So viele Formen bleiben im Protokoll stehen. */
    private static final int FORMEN_GEDAECHTNIS = 24;

    private void formMitschreiben(List<int[]> form) {
        String name = formName(form);
        formenGesehen.add(name);
        while (formenGesehen.size() > FORMEN_GEDAECHTNIS) formenGesehen.remove(0);

        Log.info("Event", Sprache.t("Shape " + formenGesehen.size() + ": " + name
                + "   order so far: " + String.join(" ", formenGesehen),
                "Form " + formenGesehen.size() + ": " + name
                + "   Reihenfolge bisher: " + String.join(" ", formenGesehen)));
        // Dauerhaft in eine Datei (Autor, 28.09.: "gibt es eine Reihenfolge?"):
        // je Zeile Zeit, Feld, Lupe, Form -- zum spaeteren Auswerten.
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("event-formen.txt"),
                    java.time.LocalDateTime.now().withNano(0) + " Feld " + (felderGeschafft + 1)
                            + " Lupe " + lupenBenutzt + " " + name + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug("Event", "Form nicht mitgeschrieben: " + e.getMessage());
        }
    }

    /**
     * Der Name einer Dreier-Form, unabhaengig davon, wo sie liegt.
     *
     * Dazu wird die Form in die linke obere Ecke geschoben und nachgesehen,
     * welche der vier Ecken eines Zweier-Kastens fehlt.
     */
    static String formName(List<int[]> form) {
        if (form.size() != 3) return "unbekannt(" + form.size() + ")";

        int minS = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int[] f : form) {
            minS = Math.min(minS, f[0]);
            minZ = Math.min(minZ, f[1]);
        }

        boolean[][] kasten = new boolean[3][3];
        for (int[] f : form) {
            int s = f[0] - minS, z = f[1] - minZ;
            if (s > 2 || z > 2) return "seltsam";
            kasten[s][z] = true;
        }

        // Die beiden geraden.
        if (kasten[0][0] && kasten[1][0] && kasten[2][0]) return "quer";
        if (kasten[0][0] && kasten[0][1] && kasten[0][2]) return "hoch";

        // Die vier Winkel, benannt nach der fehlenden Ecke.
        if (kasten[0][0] && kasten[1][0] && kasten[0][1]) return "ohne-ru";
        if (kasten[0][0] && kasten[1][0] && kasten[1][1]) return "ohne-lu";
        if (kasten[0][0] && kasten[0][1] && kasten[1][1]) return "ohne-ro";
        if (kasten[1][0] && kasten[0][1] && kasten[1][1]) return "ohne-lo";

        return "seltsam";
    }

    /**
     * Das Feld der Form, das dem Schwerpunkt am naechsten liegt.
     *
     * Bei einer geraden Dreierreihe ist das die mittlere Vase, bei einem
     * Winkel die in der Ecke -- immer der Punkt, an dem sich die Form am
     * ehesten "anfassen" laesst.
     */
    private static int[] mittigstesFeld(List<int[]> form) {
        double mx = 0, my = 0;
        for (int[] f : form) { mx += f[0]; my += f[1]; }
        mx /= form.size();
        my /= form.size();

        int[] bestes = form.get(0);
        double besteEntfernung = Double.MAX_VALUE;
        for (int[] f : form) {
            double dx = f[0] - mx, dy = f[1] - my;
            double entfernung = dx * dx + dy * dy;
            if (entfernung < besteEntfernung) {
                besteEntfernung = entfernung;
                bestes = f;
            }
        }
        return bestes;
    }

    /**
     * Den gruenen Haken druecken -- mit zwei Rueckfaellen (Testlauf 28.09.):
     * erst die Vorlage wie sonst, dann in Graustufen mit milderer Schwelle
     * (der Hintergrund hinter dem Haken wechselt mit der Lage der Form), und
     * zuletzt ueber dem roten X: der Haken steht immer direkt darueber.
     */
    private boolean hakenKlicken(Mat screen, BotContext ctx, String grund) {
        var haken = hakenImFeld(screen, ctx);
        if (haken.isPresent()) {
            Matcher.Hit h = haken.get();
            Log.action("Klick", grund + Sprache.t("  [" + T_OK + " at " + h.centerX() + "," + h.centerY()
                    + ", match " + String.format("%.3f", h.score()) + "]",
                    "  [" + T_OK + " bei " + h.centerX() + "," + h.centerY()
                    + ", Uebereinstimmung " + String.format("%.3f", h.score()) + "]"));
            ctx.input().click(h);
            return true;
        }
        if (ctx.matcher().has(T_OK)) {
            // Nur auf dem Spielfeld: die milde Graustufen-Schwelle soll nie etwas
            // anderes treffen (Knoepfe rechts, Kisten, Angebote).
            List<Matcher.Hit> grau = ctx.matcher().findAllGrau(screen, T_OK, 0.70, 1);
            if (!grau.isEmpty() && aufDemFeld(grau.get(0), ctx)) {
                Matcher.Hit h = grau.get(0);
                Log.action("Klick", grund + Sprache.t(" (tick in greyscale, " + String.format("%.3f", h.score()) + ")",
                        " (Haken in Graustufen, " + String.format("%.3f", h.score()) + ")"));
                ctx.input().click(h);
                return true;
            }
        }
        {
            var x = formKreuz(screen, ctx);
            if (x.isPresent()) {
                Matcher.Hit h = x.get();
                int y = h.centerY() - (int) Math.round(h.height() * 1.38);
                Log.action("Klick", grund + Sprache.t(" (tick above the red X at " + h.centerX() + "," + y + ")",
                        " (Haken ueber dem roten X bei " + h.centerX() + "," + y + ")"));
                ctx.input().click(h.centerX(), y, 6);
                return true;
            }
        }
        return false;
    }

    /**
     * Das rote X unter dem Haken -- nur, wenn es auf dem Spielfeld liegt. Das
     * runde Schliessen-X oben rechts im Event-Fenster ist auch rot; das darf
     * nie als "Form liegt" gelten.
     */
    private java.util.Optional<Matcher.Hit> formKreuz(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_FORM_X)) return java.util.Optional.empty();
        var x = raster != null
                ? ctx.matcher().findImBereich(screen, T_FORM_X, formBereich(), Matcher.DEFAULT_THRESHOLD)
                : ctx.matcher().find(screen, T_FORM_X);
        if (x.isEmpty()) return x;
        return aufDemFeld(x.get(), ctx) ? x : java.util.Optional.empty();
    }

    /** Liegt der Treffer auf dem Spielfeld (mit etwas Rand fuer Haken und X der Form)? */
    private boolean aufDemFeld(Matcher.Hit h, BotContext ctx) {
        if (raster != null) {
            return h.centerX() > raster.x0() - raster.schrittX() && h.centerX() < raster.mitteX(SPALTEN) + raster.schrittX()
                    && h.centerY() > raster.y0() - raster.schrittY() && h.centerY() < raster.mitteY(REIHEN);
        }
        return h.centerX() < ctx.width() * 0.85 && h.centerY() > ctx.height() * 0.25;
    }

    /** Drueckt den gruenen Haken. */
    private Outcome bestaetigen(Mat screen, BotContext ctx, RunTracker runs) {
        if (hakenKlicken(screen, ctx, Sprache.t("Confirm the spot -- uncover three vases", "Stelle bestaetigen -- drei Vasen aufdecken"))) {
            formOffen = false;
            zuVorForm = null;
            lupeBestaetigtUm = System.currentTimeMillis();
            lupeGeklicktUm = 0;
            geplanteZellen = formZellenJetzt;   // Selbstkontrolle vor der naechsten Lupe
            geplantBild = formZellenJetzt != null ? ctx.frame() : null;
            formZellenJetzt = null;
            wirkungslos = 0;
            zuegeAnDerForm = 0;
            eintritte = 0;           // wir sind angekommen und tun etwas
            vasenVorKlick = -1;      // die Zahl der zuen Vasen aendert sich jetzt
            paarPruefen = true;      // die Lupe kann zwei gute Items nebeneinander aufdecken
            runs.progress(Sprache.t("Vases uncovered", "Vasen aufgedeckt"));
            nachLupeHinsehen(ctx);
            return Outcome.ACTED;
        }
        // DIE LUPE HAT NICHT GEGRIFFEN (Test 28.09., 23:05): Kam 1,8 s nach dem
        // Klick auf "See through" weder Haken noch rotes X, hat das Spiel den
        // Klick verschluckt (er kam kurz nach dem Wegtippen des Belohnungs-
        // Texts). Bisher wartete der Bot 8 s und zaehlte die Lupe trotzdem --
        // pro Feld eine Lupe weniger, also mehr Haemmer. Jetzt zaehlt sie nicht,
        // und es gibt gleich eine neue (hoechstens dreimal je Feld).
        if (formOffen && zielPaar == null && lupeGeklicktUm > 0
                && System.currentTimeMillis() - lupeGeklicktUm > 1800 && formKreuz(screen, ctx).isEmpty()) {
            // Autor 24.10.: die erste Lupe jeder Stage galt als "nicht gegriffen" und wurde noch einmal gedrueckt --
            // 6 statt 5 Lupen. Ist die Zahl ueber "See through" gesunken, IST sie verbraucht: die Form kommt nur spaeter.
            java.awt.image.BufferedImage zahlNach = lupenZahlBild(screen, ctx);
            boolean zahlAnders = zahlVorKlick != null && zahlNach != null && zahlGleich(zahlVorKlick, zahlNach) < ZAHL_GLEICH;
            if (!lupeVerlaengert && zahlAnders) {
                lupeVerlaengert = true;
                lupeGeklicktUm = System.currentTimeMillis();
                Log.info("Event", Sprache.t("The magnifier number changed -- it is used up, the shape is just slow. Waiting, not pressing again.",
                        "Die Lupen-Zahl hat sich geaendert -- die Lupe ist verbraucht, die Form kommt nur langsam. Warten, nicht noch einmal druecken."));
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            // Zweimal gedrueckt, Zahl unveraendert, keine Form: es sind keine Lupen mehr da -- das Zahlenbild merken.
            // NICHT in der Ausnahme "alle Lupen" (Nacht 28.10., 22:34): Autor hat ~2900 Lupen; zweimal "nicht gegriffen"
            // kam von einem Fenster im Weg, und das gemerkte Zahlenbild "2927" glich danach jeder Zahl ("2919": >= 0,97)
            // -- vier Stages lang "keine Lupen", Faesser mit Haemmern und Super-Haemmern zerschlagen.
            if (!de.hustlebot.core.Eventautomatik.alleLupen()
                    && !zahlAnders && zahlNach != null && lupeNichtGegriffen >= 1) {
                lupenLeerBild = zahlNach;
                felderSeitLeer = 0;
                lupenGesperrt = true;
                Log.info("Event", Sprache.t("The magnifier did not take twice and the number stays the same -- no magnifiers left. Remembered; the next fields go without them.",
                        "Die Lupe hat zweimal nicht gegriffen und die Zahl bleibt gleich -- keine Lupen mehr. Gemerkt; die naechsten Felder gehen ohne."));
                formOffen = false;
                lupeGeklicktUm = 0;
                return Outcome.ACTED;
            }
            formOffen = false;
            lupeGeklicktUm = 0;
            stageFehler++;
            // Ausnahme "alle Lupen": bis zu 6 Versuche je Feld -- ohne Lupe gingen sonst Haemmer auf geschlossene Faesser.
            if (++lupeNichtGegriffen <= (de.hustlebot.core.Eventautomatik.alleLupen() ? 6 : 3)) {
                lupenBenutzt = Math.max(0, lupenBenutzt - 1);
                Log.info("Event", Sprache.t("The magnifier did not take -- no shape appeared. It does not count, "
                        + "trying again right away.",
                        "Die Lupe hat nicht gegriffen -- es kam keine Form. Sie zaehlt nicht, "
                        + "gleich noch einmal."));
            } else {
                lupenGesperrt = true;
                Log.info("Event", Sprache.t("The magnifier does not take on this field -- carrying on without it.",
                        "Die Lupe greift auf diesem Feld nicht -- es geht ohne Lupe weiter."));
            }
            return Outcome.ACTED;
        }
        // Kein Haken, kein rotes X: vielleicht kam gar keine Form (keine Lupe
        // mehr). Nach ein paar Versuchen nicht mehr darauf warten.
        if (formOffen && ++formOhneHaken > 8) {
            formOffen = false;
            Log.info("Event", Sprache.t("No shape found to confirm -- carrying on without.",
                    "Keine Form zum Bestaetigen gefunden -- es geht ohne weiter."));
        }
        ctx.sleep(tempo.ms(300));
        return Outcome.WAITING;
    }

    /** So lange nach dem Bestaetigen einer Lupe hinsehen, bevor die naechste kommt. */
    private static final long LUPE_HINSEHEN_MS = 950;   // Autor, 29.09.: "wartet immer so lange" (Lupe -> Lupe im Mittel 1,6-1,9 s)
    /** Wann zuletzt "Ja, neu mischen" gedrueckt wurde (danach kommt der Belohnungs-Text). */
    private long neuGemischtUm = 0;
    /** Wann der Belohnungs-Text weggetippt wurde -- kurz danach verschluckt das Spiel Klicks. */
    private long textWegUm = 0;
    /** Nach dem "Ja": so lange auf den Belohnungs-Text warten, bevor auf dem Feld etwas passiert (Nacht 28.10.). Fest, nicht
     *  mit dem Tempo verkuerzt -- das ist die Zeit des Spiels, nicht des Bots. */
    static final long FRISCH_WARTEN_MS = 2500;
    /** Wann zuletzt "See through" gedrueckt wurde (0 = Form bestaetigt); verschluckte Lupen je Feld. */
    private long lupeGeklicktUm = 0;
    private int lupeNichtGegriffen = 0;
    /** So lange schnelle Fotos nach dem Bestaetigen; danach laeuft der normale Schritt. */
    private static final long LUPE_FOTOZYKLUS_MS = 800;
    /** Lupenknopf fehlt seit ... (0 = war zuletzt da); je Feld nur einmal darauf warten. */
    private long lupeFehltSeit = 0;
    private boolean lupeFehltGewartet = false;
    /** Selbstkontrolle: die Felder der zuletzt bestaetigten Form, und das Bild davon. */
    private List<int[]> formZellenJetzt = null, geplanteZellen = null;
    private java.awt.image.BufferedImage geplantBild = null;
    private int kontrollFotos = 0;

    /**
     * Das Spielfeld mit Rand fuer Haken und rotes X der Form -- dieselben
     * Grenzen wie aufDemFeld(). Nur hier werden die beiden gesucht (Tempo).
     */
    private org.opencv.core.Rect formBereich() {
        return new org.opencv.core.Rect(raster.x0() - raster.schrittX(), raster.y0() - raster.schrittY(),
                raster.schrittX() * (SPALTEN + 2), raster.schrittY() * (REIHEN + 1));
    }

    /** Der gruene Haken der Form -- mit bekanntem Raster nur im Feld gesucht. */
    private java.util.Optional<Matcher.Hit> hakenImFeld(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_OK)) return java.util.Optional.empty();
        return raster != null
                ? ctx.matcher().findImBereich(screen, T_OK, formBereich(), Matcher.DEFAULT_THRESHOLD)
                : ctx.find(screen, T_OK);
    }

    /**
     * FOTO-ZYKLUS NACH DER LUPE (Tempo, 28.09.). Statt einfach zu warten und
     * dann den ganzen Schritt (Angebot, Stoerfenster, ...) mehrmals laufen zu
     * lassen, sieht der Bot bis LUPE_HINSEHEN_MS nach dem Bestaetigen in
     * schneller Folge NUR aufs Feld: Taucht der Stab oder eine Kiste auf, geht
     * es sofort zurueck in den Schritt (der sie in der richtigen Reihenfolge
     * holt). Ein Angebot bricht ebenfalls sofort ab -- es wird im Schritt
     * weggedrueckt. Die naechste Lupe kommt nie, bevor hingesehen wurde.
     */
    private void nachLupeHinsehen(BotContext ctx) {
        int bilder = 0;
        // Nur bis LUPE_FOTOZYKLUS_MS: danach laeuft der normale Schritt mit der
        // teuren Paar-Suche fuer den blauen Hammer schon, waehrend die Sperre bis
        // LUPE_HINSEHEN_MS noch laeuft (Test 23:05: sonst 2,35 s bis zur Lupe).
        // Kein Blick aufs Angebot noetig: hier wird nichts angeklickt, und der
        // Schritt danach prueft es wie immer zuerst.
        while (System.currentTimeMillis() - lupeBestaetigtUm < LUPE_FOTOZYKLUS_MS) {
            ctx.sleep(tempo.ms(60));
            Mat bild = ctx.refresh();
            if (bild == null) return;
            if (++bilder < 3) continue;   // die ersten Bilder zeigen noch die Form
            if (stabImFeld(bild, ctx).isPresent() || neueKisteImFeld(bild, ctx)) {
                Log.debug("Event", "Nach der Lupe: Stab oder Kiste zu sehen ("
                        + (System.currentTimeMillis() - lupeBestaetigtUm) + " ms).");
                return;
            }
        }
    }

    /** Eine Kiste im Feld, die noch nicht als "laesst sich nicht mitnehmen" gilt. */
    private boolean neueKisteImFeld(Mat bild, BotContext ctx) {
        if (feldBereich() == null) return false;
        for (Matcher.Hit t : belohnungenImFeld(bild, ctx)) {
            int tx = t.centerX(), ty = t.centerY();
            boolean gesperrt = false;
            for (int[] st : kistenStellen) {
                if (Math.abs(tx - st[0]) < 20 && Math.abs(ty - st[1]) < 20 && st[2] > 3) gesperrt = true;
            }
            if (!gesperrt) return true;
        }
        return false;
    }

    /**
     * VORAUSKLICK "ANNEHMEN" (Tempo, 28.09.): Nach Hammerschlag oder Kiste
     * kommt "Claim" immer an derselben Stelle. Statt fest zu warten, in
     * schneller Folge nachsehen und ihn im selben Moment druecken, in dem er
     * erscheint. Hoechstens 2 s (Kiste 2,6 s); ein Angebot bricht sofort ab.
     */
    private boolean annehmenAbwarten(BotContext ctx, RunTracker runs, long hoechstensMs) {
        long ende = System.currentTimeMillis() + hoechstensMs;
        ctx.sleep(tempo.ms(250));
        while (System.currentTimeMillis() < ende) {
            Mat bild = ctx.refresh();
            if (bild == null) return false;
            if (angebotDa(bild, ctx)) return false;
            // Autor 24.10. (B10/B11): "Not enough hammers?" statt "Claim" -- nicht 2 s auf einen Claim warten, der nie kommt;
            // der naechste Schritt beendet das Event.
            if (zuWenigHaemmerFenster(ctx.matcher(), bild)) return false;
            if (ctx.clickIfVisibleIfKnown(bild, T_CLAIM, Sprache.t("Claim reward (right away)", "Belohnung annehmen (sofort)"))) {
                kisteAbgeholt();
                wirkungslos = 0;
                runs.progress(Sprache.t("Reward claimed", "Belohnung angenommen"));
                ctx.sleepUntilChanged(900);
                return true;
            }
            ctx.sleep(tempo.ms(50));
        }
        return false;
    }

    /**
     * SELBSTKONTROLLE (Autor, 28.09.: "manche Formen legt er in die Mitte statt
     * in die Ecke"). Vor der naechsten Lupe: Sind die Felder der letzten Form
     * wirklich aufgegangen? Sind noch zwei oder mehr davon zu, lag die Form
     * woanders, als der Bot glaubte -- dann steht es im Protokoll, und das Bild
     * von der liegenden Form kommt nach logs\\lupe-daneben-*.png (hoechstens 8).
     */
    private void formKontrolle(boolean[][] zu) {
        List<int[]> geplant = geplanteZellen;
        java.awt.image.BufferedImage bild = geplantBild;
        geplanteZellen = null;
        geplantBild = null;
        if (geplant == null || zu == null) return;
        int nochZu = 0;
        StringBuilder felder = new StringBuilder();
        for (int[] z : geplant) {
            if (z[0] < 0 || z[0] >= SPALTEN || z[1] < 0 || z[1] >= REIHEN) continue;
            if (zu[z[0]][z[1]]) nochZu++;
            felder.append(felder.length() > 0 ? " " : "").append(feldText(z));
        }
        if (nochZu < 2) return;
        String datei = "";
        if (bild != null && kontrollFotos < 8) {
            kontrollFotos++;
            try {
                java.nio.file.Path pfad = java.nio.file.Path.of("logs", "lupe-daneben-"
                        + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
                if (de.hustlebot.core.Beleg.schreiben(bild, "png", pfad.toFile())) datei = Sprache.t(" Photo: ", " Foto: ") + pfad;
            } catch (Exception e) {
                Log.debug("Event", "Foto nicht gespeichert: " + e.getMessage());
            }
        }
        Log.warn("Event", Sprache.t("Self-check: the last magnifier should have uncovered " + felder + ", "
                + nochZu + " of those are still closed -- the shape probably lay somewhere else." + datei,
                "Selbstkontrolle: Die letzte Lupe sollte " + felder + " aufdecken, davon sind "
                + nochZu + " noch zu -- die Form lag wohl woanders." + datei));
    }

    /** Wann zuletzt nach dem Update-Knopf, dem "Ja" bzw. den Stoerfenstern gesucht wurde (Tempo). */
    private long afkGeprueftUm = 0, jaGeprueftUm = 0, stoerGeprueftUm = 0;

    /** Rauswurf, Spielfehler, Belohnungsfenster. null = nichts davon da. */
    private Outcome stoerfensterPruefen(Mat screen, BotContext ctx, RunTracker runs) {
        switch (Stoerfenster.anderesGeraet(screen, ctx, "Event")) {
            case ANHALTEN -> { return Outcome.PAUSE; }
            case GEKLICKT -> {
                runs.progress(Sprache.t("Logged in again after being kicked", "Nach Rauswurf neu angemeldet"));
                return Outcome.ACTED;
            }
            case NICHTS -> { /* weiter */ }
        }
        if (Stoerfenster.belohnungWegklicken(screen, ctx, "Event")) {
            runs.progress(Sprache.t("Pop-up closed", "Stoerfenster geschlossen"));
            return Outcome.ACTED;
        }
        return null;
    }

    /** Wann zuletzt eine Lupe bestaetigt wurde -- danach erst in Ruhe hinsehen. */
    private long lupeBestaetigtUm = 0;
    /** Wie oft der Haken fuer die jetzige Lupe schon gedrueckt wurde. */
    private int hakenGedrueckt = 0;

    // --- Autor 28.10.: Lupe nicht zu frueh abbrechen, Schlussphase, Super-Haemmer -----------------------------------
    /** Wie oft die aktuelle Lupe "blind" bestaetigt wurde (ohne Umriss / nach zwei Zuegen), und seit wann. */
    private int blindZaehler = 0;
    private long blindSeitUm = 0;
    /** Erst nach so langem blinden Bestaetigen gilt die Lupe als haengend (Bericht #9P2L: endlos bestaetigt) ... */
    private static final long LUPE_BLIND_GEDULD_MS = 6000;
    /** ... oder nach so vielen blinden Haken, egal wie schnell. */
    private static final int LUPE_BLIND_HOECHSTENS = 15;
    /** Bilder, die nach dem Lupen-Klick auf den blauen Umriss gewartet wird, bevor die Lupe blind liegen bleibt. */
    private int umrissWarteBilder = 0;
    private static final int UMRISS_WARTE_BILDER = 6;

    private void blindBestaetigtMerken() {
        if (blindZaehler++ == 0) blindSeitUm = System.currentTimeMillis();
    }

    /** Haengt die Lupe beim blinden Bestaetigen fest? Erst nach 6 s (mindestens 3 Haken) oder nach 15 Haken. */
    private boolean blindHaengtFest() {
        if (blindZaehler <= 0) return false;
        return blindZaehler >= LUPE_BLIND_HOECHSTENS
                || (blindZaehler >= 3 && System.currentTimeMillis() - blindSeitUm >= LUPE_BLIND_GEDULD_MS);
    }

    /** Schlussphase des Events (Zeit oben links rot), hoechstens alle 30 s gemessen. */
    private boolean schlussphase = false;
    private long schlussGeprueftUm = 0;
    /** Wie oft hintereinander die Zeit oben links gemessen wurde und NICHT rot war (nur gueltige Messungen zaehlen). */
    private int schlussNichtRotFolge = 0;
    /** So viele "nicht rot"-Messungen hintereinander, bevor die Schlussphase als vorbei gilt. */
    static final int SCHLUSS_NICHT_ROT_FOLGE = 2;
    /** Endstopp beschlossen: nichts mehr spielen, nur noch ein offenes "Not enough hammers?"-Fenster schliessen, dann anhalten. */
    private boolean schlussStopp = false;

    /**
     * Gilt die Schlussphase nach dieser Messung? Rot: sofort ja. Nicht rot: erst nach {@link #SCHLUSS_NICHT_ROT_FOLGE} Messungen
     * hintereinander wieder nein -- eine einzelne Null kann auch ein Fenster oder eine Animation ueber der Zeit sein.
     * @param nichtRotFolge Zahl der "nicht rot"-Messungen hintereinander, die jetzige eingerechnet
     */
    static boolean schlussphaseGilt(boolean bisher, boolean rot, int nichtRotFolge) {
        if (rot) return true;
        return bisher && nichtRotFolge < SCHLUSS_NICHT_ROT_FOLGE;
    }

    /** Schlussphase und keine normalen Haemmer mehr: die blauen Super-Haemmer ersetzen sie. */
    private boolean nurSuperHaemmer = false;
    private boolean schlussGemeldet = false;
    /** Bilder hintereinander, in denen der blaue Hammer (als Ersatz fuer den normalen) kein Ziel fand. */
    private int superOhneZiel = 0;
    private static final int SUPER_OHNE_ZIEL_MAX = 40;
    /** So viele gute Felder trifft die zuletzt gefundene Super-Hammer-Stelle (fuers Protokoll). */
    private int letzteSuperGut = 0;
    /** Einmal je Gang das Kisten-Fenster fotografieren, auf das ev_open nicht passte. */
    private boolean materialFotoGemacht = false;
    /** Gleich nach einem Kisten-Klick genuegt fuer den "Open"-Knopf diese Schwelle (Material-Kiste). */
    private static final double OPEN_MILD = 0.68;
    /** Die roten Haemmer als Belohnung (Vorlagen vom 29.09.) -- sie bringen Haemmer zurueck. */
    private static final List<String> HAMMER_BELOHNUNGEN = List.of(
            "ev_kiste_extra_hammer2_feld",   // zwei rote Haemmer, von Autors Bilderseite (28.10.)
            "ev_kiste_extra_9", "ev_kiste_extra_5_feld", "ev_kiste_extra_5", "ev_kiste_extra_3");
    /** So viele kraeftig rote Bildpunkte oben links = die Zeit steht rot da (Schlussphase). */
    private static final int SCHLUSS_ROT_PIXEL = 300;

    /**
     * Steht die Event-Zeit oben links ROT da? Normal: weiss "Event ends in:" und gelb "5h 54s" (Bot-Foto 28.10., 04:59);
     * Schlussphase: rot "Argonius leaves in: 1d 19h 25m" (Autors Screenshot 28.10.). Gezaehlt werden kraeftig rote
     * Bildpunkte im Feld oben links -- Gelb, Orange und das Pink der Figuren zaehlen nicht.
     */
    private boolean schlussphaseJetzt(Mat screen, BotContext ctx) {
        int w = (int) (screen.cols() * 0.20), h = (int) (screen.rows() * 0.12);
        if (w < 20 || h < 20) return schlussphase;
        Mat teil = new Mat(screen, new Rect(0, 0, w, h));
        Mat hsv = new Mat(), rot1 = new Mat(), rot2 = new Mat();
        try {
            Imgproc.cvtColor(teil, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(0, 140, 140), new Scalar(6, 255, 255), rot1);
            Core.inRange(hsv, new Scalar(174, 140, 140), new Scalar(180, 255, 255), rot2);
            int rot = Core.countNonZero(rot1) + Core.countNonZero(rot2);
            Log.debug("Event", "Zeit oben links: " + rot + " rote Bildpunkte (Schlussphase ab " + SCHLUSS_ROT_PIXEL + ")");
            return rot >= SCHLUSS_ROT_PIXEL;
        } catch (Exception e) {
            return schlussphase;   // im Zweifel beim alten Stand bleiben
        } finally {
            teil.release();
            hsv.release();
            rot1.release();
            rot2.release();
        }
    }

    /** Schlussphase, alles verbraucht: anhalten (wie das Portal bei leerer Tasche) und Bescheid sagen. */
    private Outcome schlussphaseFertig(Mat screen, BotContext ctx) {
        schlussStopp = true;   // ab jetzt gibt step() ganz oben nur noch dieses Anhalten zurueck
        if (!schlussGemeldet) {
            schlussGemeldet = true;
            haemmerAlle = true;
            Log.warn("Event", Sprache.t("Final phase of the Hammer Event: the hammers and the super hammers are used up. The bot "
                    + "stops -- there is nothing left to play here. ", "Schlussphase des Hammer-Events: Haemmer und Super-Haemmer "
                    + "sind verbraucht. Der Bot haelt an -- hier gibt es nichts mehr zu spielen. ") + berichten());
        }
        if (zuWenigHaemmerFenster(ctx.matcher(), screen) && infoFensterSchliessen(screen, ctx)) return Outcome.ACTED;
        return Outcome.PAUSE;
    }
    /** Auf diesem Feld keine Lupe mehr (das Spiel nahm sie nicht an). */
    private boolean lupenGesperrt = false;
    /** Autor 24.10.: Tempo wie beim Stigmas-Loeschen -- gelernt je Stage, gemerkt in event-tempo.txt. */
    // Autor 28.10. 22:31: in der Ausnahme "alle Lupen" eigener Tempo-Modus -- je fehlerfreier Stage 1 % schneller bis zum
    // Hoechstwert (halbe Pausen), beim ersten Fehler eine Stufe zurueck und fest. Eigene Datei: das normale Event-Tempo
    // (event-tempo.txt, 0,1 %) bleibt davon unberuehrt.
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Eventautomatik.alleLupen()
            ? de.hustlebot.core.Tempolerner.fuer("event-ausnahme-tempo.txt", "Event", 0.99)
            : de.hustlebot.core.Tempolerner.fuer("event-tempo.txt", "Event");
    /** Fehler in dieser Stage, die am Tempo liegen koennen (Klick ohne Wirkung, Lupe verschluckt, Stab-Klick verschluckt). */
    private int stageFehler;
    /** Autor 24.10.: Abbrueche des blauen Hammers in diesem Feld (Form nicht erkannt); ab 2 nur normale Haemmer. */
    private int superAbbruecheFeld;
    private static final int SUPER_ABBRUECHE_JE_FELD = 2;
    /** Autor 24.10.: das Zahlenfeld ueber "See through" vor dem letzten Klick, und wie es bei "keine Lupen" aussah. */
    private java.awt.image.BufferedImage zahlVorKlick, lupenLeerBild;
    private boolean lupeVerlaengert;
    /** Nach so vielen Feldern wird "keine Lupen" noch einmal geprueft (Gold-Pass bringt neue). */
    private int felderSeitLeer;
    private static final double ZAHL_GLEICH = 0.97;

    /** Lupen-Abbrueche auf diesem Feld (Ausnahme "alle Lupen": erst nach so vielen keine Lupe mehr). */
    private int lupeAbbruecheFeld = 0;
    private static final int LUPE_ABBRUECHE_JE_FELD = 3;

    /** Rotes X: die Lupe zuruecknehmen. */
    private Outcome lupeAbbrechen(Mat screen, BotContext ctx, String grund) {
        // Nacht 28.10. 23:03: in der Ausnahme "alle Lupen" sperrte EIN Abbruch (Umriss nicht gefunden, Spiel nahm die
        // Form "wo sie liegt" nicht an) die Lupe fuer das ganze Feld -- 4 Faesser blieben zu und der Bot zerschlug
        // eins mit dem Hammer. Jetzt: bis zu 3 Abbrueche je Feld, danach erst sperren (die naechste Form ist eine andere).
        lupeAbbruecheFeld++;
        boolean nochmal = de.hustlebot.core.Eventautomatik.alleLupen() && lupeAbbruecheFeld < LUPE_ABBRUECHE_JE_FELD;
        lupenGesperrt = !nochmal;
        formOffen = false;
        zuVorForm = null;
        zuegeAnDerForm = 0;
        Log.info("Event", grund + (nochmal
                ? Sprache.t(" -- magnifier cancelled, trying a new one (" + lupeAbbruecheFeld + ". cancel on this field).",
                        " -- Lupe abgebrochen, gleich eine neue (" + lupeAbbruecheFeld + ". Abbruch auf diesem Feld).")
                : Sprache.t(" -- magnifier cancelled, no further one on this field.", " -- Lupe abgebrochen, auf diesem Feld keine weitere.")));
        var x = formKreuz(screen, ctx);
        if (x.isPresent()) {
            ctx.input().click(x.get());
            ctx.sleepUntilChanged(900);
            return Outcome.ACTED;
        }
        ctx.sleep(tempo.ms(300));
        return Outcome.WAITING;
    }

    /**
     * Der Zauberstab AUF DEM SPIELFELD -- etwas nachsichtiger (0,75), aber nie
     * das Stab-Symbol links ("Magic Wand x1") oder oben in der Leiste.
     * Testlauf 28.09., 15:49: der Stab lag offen, der Bot sah ihn nicht.
     */
    private java.util.Optional<Matcher.Hit> stabImFeld(Mat screen, BotContext ctx) {
        org.opencv.core.Rect feld = feldBereich();
        java.util.Optional<Matcher.Hit> h = java.util.Optional.empty();
        // 24.10. 05:50 live: der Stab unter einer durchsichtigen Vase ("See through") passte nur zu 0,85 -- das Event
        // endete mit "kein Fass mehr, Stab nicht geholt". Darum alle Varianten (ev_stab_kern, ev_stab_kern_glas, ...).
        for (String name : ctx.matcher().serie(T_STAB_KERN)) {
            h = feld != null ? ctx.matcher().findImBereich(screen, name, feld, 0.85)
                             : ctx.matcher().find(screen, name, 0.85);
            if (h.isPresent()) break;
        }
        if (h.isEmpty() && ctx.matcher().has(T_STAB)) {
            h = feld != null ? ctx.matcher().findImBereich(screen, T_STAB, feld, 0.75)
                             : ctx.matcher().find(screen, T_STAB, 0.75);
        }
        if (h.isEmpty()) return h;
        Matcher.Hit t = h.get();
        boolean imFeld;
        if (raster != null) {
            imFeld = t.centerX() > raster.x0() - raster.schrittX() / 2 && t.centerX() < raster.mitteX(SPALTEN - 1) + raster.schrittX() / 2
                    && t.centerY() > raster.y0() - raster.schrittY() / 2 && t.centerY() < raster.mitteY(REIHEN - 1) + raster.schrittY() / 2;
        } else {
            imFeld = t.centerX() > ctx.width() * 0.25 && t.centerX() < ctx.width() * 0.78
                    && t.centerY() > ctx.height() * 0.28 && t.centerY() < ctx.height() * 0.80;
        }
        return imFeld ? h : java.util.Optional.empty();
    }

    /**
     * Das Spielfeld mit einem Feld Rand ringsum -- dort liegen Kisten und Stab,
     * und dort steht auch das Kisten-Fenster nach dem Anklicken. null = Lage
     * noch unbekannt (dann wird wie frueher im ganzen Bild gesucht).
     */
    private org.opencv.core.Rect feldBereich() {
        if (raster == null) return null;
        return new org.opencv.core.Rect(raster.x0() - raster.schrittX(), raster.y0() - raster.schrittY(),
                raster.schrittX() * (SPALTEN + 1), raster.schrittY() * (REIHEN + 1));
    }

    /** Der Zauberstab dieses Feldes ist geholt -- jetzt nur noch neu mischen. */
    private boolean stabGeholt = false;
    private long stabGeholtUm = 0;

    /** Liegt gerade eine Form (Lupe oder blauer Hammer), die noch bestaetigt werden muss? */
    private boolean formOffen = false;
    /** Welche Faesser zu waren, BEVOR die Form kam -- damit wird die Stelle geplant. */
    private boolean[][] zuVorForm = null;
    private int formOhneHaken = 0;

    // ------------------------------------------------------------------
    //  Das Raster
    // ------------------------------------------------------------------

    /**
     * Wo die zwanzig Vasen stehen.
     *
     * Gerechnet wird mit Mittelpunkten: linke obere Vase und der Abstand
     * von Vase zu Vase. Daraus laesst sich jedes Feld ausrechnen.
     */
    record Raster(int x0, int y0, int schrittX, int schrittY) {
        int mitteX(int spalte) { return x0 + spalte * schrittX; }
        int mitteY(int reihe)  { return y0 + reihe  * schrittY; }
    }

    /**
     * Vermisst das Feld -- und merkt sich das Ergebnis.
     *
     * Vermessen wird nur, wenn fast alle Vasen noch zu sind. Das ist genau
     * nach dem Neumischen der Fall. Spaeter sind einige Glas und faellen
     * aus der Suche heraus; dann waere der aeussere Rand falsch und das
     * ganze Raster verschoben. Deshalb: einmal ordentlich messen, dann
     * behalten, bis das Feld neu gemischt wird.
     */
    private Raster rasterHolen(Mat screen, BotContext ctx) {
        if (raster != null) return raster;
        Raster gemessen = rasterMessen(screen, ctx);
        if (gemessen != null) {
            rasterMerken(ctx, gemessen);
            return gemessen;
        }
        // Zu wenige Faesser zu (Testlauf 28.09.: nur noch 9 -- "Das Spielfeld
        // liess sich nicht vermessen", die Lupe wurde nie verschoben). Das Feld
        // liegt aber immer gleich: der Bot dockt das Fenster fest an. Also gilt
        // die zuletzt gemessene Lage fuer diese Fenstergroesse.
        Raster gemerkt = rasterGemerkt(ctx);
        if (gemerkt != null) {
            raster = gemerkt;
            Log.info("Event", Sprache.t("Too few barrels to measure -- using the remembered layout: top left vase at "
                    + gemerkt.x0() + "," + gemerkt.y0() + ", spacing " + gemerkt.schrittX() + "/" + gemerkt.schrittY() + ".",
                    "Zu wenige Faesser zum Vermessen -- es gilt die gemerkte Lage: linke obere Vase bei "
                    + gemerkt.x0() + "," + gemerkt.y0() + ", Abstand " + gemerkt.schrittX() + "/" + gemerkt.schrittY() + "."));
        }
        return raster;
    }

    /** Die zuletzt gemessene Lage des Feldes je Fenstergroesse (Datei event-raster.txt). */
    private static final java.nio.file.Path RASTER_DATEI = java.nio.file.Path.of("event-raster.txt");

    private static void rasterMerken(BotContext ctx, Raster r) {
        try {
            java.nio.file.Files.writeString(RASTER_DATEI, Sprache.t("# width height x0 y0 stepX stepY (measured by the bot)\n",
                    "# Breite Hoehe x0 y0 schrittX schrittY (vom Bot gemessen)\n")
                    + ctx.width() + " " + ctx.height() + " " + r.x0() + " " + r.y0() + " " + r.schrittX() + " " + r.schrittY() + "\n");
        } catch (Exception e) {
            Log.debug("Event", "Raster nicht gespeichert: " + e.getMessage());
        }
    }

    private static Raster rasterGemerkt(BotContext ctx) {
        try {
            if (java.nio.file.Files.exists(RASTER_DATEI)) {
                for (String z : java.nio.file.Files.readAllLines(RASTER_DATEI)) {
                    String[] t = z.trim().split("\\s+");
                    if (z.startsWith("#") || t.length != 6) continue;
                    if (Integer.parseInt(t[0]) == ctx.width() && Integer.parseInt(t[1]) == ctx.height()) {
                        return new Raster(Integer.parseInt(t[2]), Integer.parseInt(t[3]),
                                Integer.parseInt(t[4]), Integer.parseInt(t[5]));
                    }
                }
            }
        } catch (Exception e) {
            Log.debug("Event", "Gemerktes Raster nicht lesbar: " + e.getMessage());
        }
        // Gemessen im Testlauf 28.09. bei der festen Fenstergroesse.
        if (ctx.width() == 1159 && ctx.height() == 770) return new Raster(386, 271, 96, 79);
        return null;
    }

    private Raster rasterMessen(Mat screen, BotContext ctx) {
        if (raster != null) return raster;

        List<Matcher.Hit> vasen = geschlosseneVasen(screen, ctx);
        if (vasen.size() < RASTER_MINDESTENS) {
            // Nur einmal je Feld ins Protokoll. Seit in JEDEM Bild vermessen
            // wird, kaeme diese Zeile sonst mehrmals je Sekunde -- und auf
            // einem Feld, das nie zusammenkommt (Wiedereinstieg nach dem
            // Clan-Krieg), deckt sie das ganze Protokoll zu.
            if (!rasterGemeldet) {
                rasterGemeldet = true;
                Log.debug("Event", "Nur " + vasen.size() + " geschlossene Vasen gefunden, "
                        + "zum Vermessen sind " + RASTER_MINDESTENS + " noetig.");
            }
            return null;
        }

        // ERST DIE SPALTEN UND REIHEN ZAEHLEN, DANN RECHNEN.
        //
        // Hier stand frueher: den linkesten und den rechtesten Treffer
        // nehmen, die Strecke dazwischen durch vier teilen, fertig. Das
        // stimmt nur, wenn wirklich alle fuenf Spalten gefunden wurden.
        //
        // Sechzehn Treffer reichten aber schon zum Vermessen -- und
        // sechzehn koennen genau eine fehlende Spalte bedeuten. Dann liegt
        // zwischen dem linkesten und dem rechtesten Treffer nur DREI mal der
        // Abstand statt vier, und der Bot rechnet mit einem Drittel zu viel.
        // Danach sitzt jede Vase im falschen Feld, die Karte von offen und
        // zu ist Unsinn, und der Bot legt eine Form auf die andere -- genau
        // das, was Autor am 7. September gesehen hat.
        //
        // Deshalb jetzt: Die Fundstellen werden zu Spalten und Reihen
        // zusammengefasst, und es MUESSEN fuenf mal vier sein. Sonst wird
        // gar nicht vermessen und beim naechsten Bild neu versucht -- das
        // kostet nichts, ein frisches Feld hat immer alle zwanzig.
        List<Integer> spalten = zuGruppen(vasen, true);
        List<Integer> reihen  = zuGruppen(vasen, false);

        if (spalten.size() != SPALTEN || reihen.size() != REIHEN) {
            if (!rasterGemeldet) {
                rasterGemeldet = true;
                Log.debug("Event", "Aus " + vasen.size() + " Vasen wurden " + spalten.size()
                        + " Spalten und " + reihen.size() + " Reihen -- gebraucht werden "
                        + SPALTEN + " mal " + REIHEN + ". Es wird noch nicht vermessen.");
            }
            return null;
        }

        int x0 = spalten.get(0);
        int y0 = reihen.get(0);
        int schrittX = (spalten.get(SPALTEN - 1) - x0) / (SPALTEN - 1);
        int schrittY = (reihen.get(REIHEN - 1) - y0) / (REIHEN - 1);

        if (schrittX < 10 || schrittY < 10) {
            Log.debug("Event", "Die gefundenen Vasen liegen zu dicht beieinander "
                    + "(" + schrittX + "/" + schrittY + " Pixel). Kein Raster.");
            return null;
        }

        raster = new Raster(x0, y0, schrittX, schrittY);
        Log.info("Event", Sprache.t("Playing field measured: top left vase at " + x0 + ","
                + y0 + ", spacing " + schrittX + "/" + schrittY + " pixels, "
                + "from " + vasen.size() + " vases in " + SPALTEN + " by " + REIHEN
                + " cells.",
                "Spielfeld vermessen: linke obere Vase bei " + x0 + ","
                + y0 + ", Abstand " + schrittX + "/" + schrittY + " Pixel, "
                + "aus " + vasen.size() + " Vasen in " + SPALTEN + " mal " + REIHEN
                + " Feldern."));
        return raster;
    }

    /**
     * Fasst die Fundstellen zu Spalten (oder Reihen) zusammen und gibt deren
     * Mittelpunkte zurueck, von links nach rechts.
     *
     * Zwei Vasen gehoeren zur selben Spalte, wenn ihre Mittelpunkte dicht
     * beieinander liegen. Was "dicht" heisst, ergibt sich aus dem Feld
     * selbst: Die ganze Breite geteilt durch die Zahl der Zwischenraeume ist
     * der Abstand von Spalte zu Spalte; die Haelfte davon trennt sie
     * sicher.
     *
     * Fehlt eine Spalte, kommt dabei eine zu grosse Schaetzung heraus -- und
     * dann zaehlt der Aufrufer eben vier Spalten statt fuenf und verwirft
     * das Ergebnis. Genau so soll es sein.
     */
    private static List<Integer> zuGruppen(List<Matcher.Hit> vasen, boolean waagerecht) {
        List<Integer> werte = new ArrayList<>();
        for (Matcher.Hit v : vasen) {
            werte.add(waagerecht ? v.centerX() : v.centerY());
        }
        java.util.Collections.sort(werte);

        int spanne = werte.get(werte.size() - 1) - werte.get(0);
        int felder = waagerecht ? SPALTEN : REIHEN;
        int grenze = Math.max(6, spanne / (felder - 1) / 2);

        List<Integer> mitten = new ArrayList<>();
        int summe = werte.get(0);
        int anzahl = 1;

        for (int i = 1; i < werte.size(); i++) {
            if (werte.get(i) - werte.get(i - 1) > grenze) {
                mitten.add(summe / anzahl);
                summe = 0;
                anzahl = 0;
            }
            summe += werte.get(i);
            anzahl++;
        }
        mitten.add(summe / anzahl);
        return mitten;
    }

    /**
     * Welche Felder noch eine geschlossene Vase tragen.
     *
     * GEMESSEN, NICHT GERATEN. Am 7. September abends habe ich ein echtes
     * Bild aus dem Spiel Feld fuer Feld ausgewertet. Das Ergebnis war
     * eindeutig und hat meine Annahme vom Nachmittag widerlegt:
     *
     *   nach der Vorlage ev_vase_zu        Wirklichkeit
     *          ####.                          ####.
     *          #....                          #....
     *          #..#.                          #..##
     *          #####                          #####
     *
     * Neunzehn von zwanzig Feldern richtig. Das eine verfehlte lag unter dem
     * weissen Pfeil, mit dem das Spiel das Verschieben anzeigt.
     *
     * Die Vorlage taugt also sehr wohl. Ich hatte sie ersetzt, weil eine
     * aufgedeckte Vase ihren goldenen Sockel behaelt und ich daraus schloss,
     * die Vorlage muesse auf beide passen. Sie tut es nicht -- und die Farbe,
     * die ich stattdessen genommen hatte, taugt aus einem Grund nicht, den
     * man erst an echten Zahlen sieht: Das Glas faerbt sich nach dem, was
     * drin liegt. Bei einem gruenen Fundstueck ist gar kein Tuerkis messbar,
     * und goldene Muenzen in einer Glasvase bringen mehr Gold aufs Feld als
     * die geschlossene Vase daneben.
     */
    boolean[][] geschlossenesRaster(Mat screen, BotContext ctx, Raster r) {
        boolean[][] zu = geschlossenesRasterNachVorlage(screen, ctx, r);

        // Die Karte gehoert ins Protokoll. Legt der Bot doch einmal daneben,
        // steht dort schwarz auf weiss, was er zu sehen geglaubt hat --
        // "#" ist zu, "." ist auf.
        StringBuilder karte = new StringBuilder();
        int offene = 0;
        for (int reihe = 0; reihe < REIHEN; reihe++) {
            for (int spalte = 0; spalte < SPALTEN; spalte++) {
                boolean offen = !zu[spalte][reihe];
                if (offen) offene++;
                karte.append(offen ? '.' : '#');
            }
            if (reihe < REIHEN - 1) karte.append(" / ");
        }
        Log.debug("Event", "Feld: " + karte + "   (" + offene + " von "
                + (SPALTEN * REIHEN) + " aufgedeckt)");
        return zu;
    }

    /** Die Fundstellen der Vorlage auf die zwanzig Felder verteilen. */
    private boolean[][] geschlossenesRasterNachVorlage(Mat screen, BotContext ctx, Raster r) {
        boolean[][] zu = new boolean[SPALTEN][REIHEN];
        for (Matcher.Hit v : geschlosseneVasen(screen, ctx)) {
            int spalte = (int) Math.round((v.centerX() - r.x0()) / (double) r.schrittX());
            int reihe  = (int) Math.round((v.centerY() - r.y0()) / (double) r.schrittY());
            if (spalte >= 0 && spalte < SPALTEN && reihe >= 0 && reihe < REIHEN) {
                zu[spalte][reihe] = true;
            }
        }
        return zu;
    }

    /**
     * Sucht die beste Stelle fuer die Form.
     *
     * DREI REGELN, IN DIESER REIHENFOLGE.
     *
     * 1. Moeglichst viele noch GESCHLOSSENE Vasen treffen. Eine Lupe, die
     *    auf schon aufgedecktes Glas faellt, ist verschenkt.
     *
     * 2. Bei Gleichstand: moeglichst weit AN DEN RAND DESSEN, WAS NOCH ZU
     *    IST -- also weit weg vom Schwerpunkt der geschlossenen Vasen.
     *
     *    Der Gedanke dahinter ist Autors: "meistens sind es ecken, das fuer
     *    die naechsten formen mehr platz ist." Er hat recht, und der Grund
     *    liegt in Regel 1: Auf einem frischen Feld sind ALLE zwanzig Vasen
     *    zu, also trifft jede Stelle drei von drei. Regel 1 sagt dann gar
     *    nichts mehr, und frueher entschied der kuerzeste Weg -- die Form
     *    blieb liegen, wo sie erschien, also in der Mitte. Die zweite Lupe
     *    fand ein Feld mit einem Loch in der Mitte vor, und in ein Loch
     *    passt kein Dreier mehr sauber hinein.
     *
     *    Aussen gepackt bleibt der Rest dagegen ein ZUSAMMENHAENGENDER
     *    Block, und in einen Block passt jede der vier Formen.
     *
     *    WARUM NICHT MEHR "LINKS OBEN". Genau das stand hier bis zum
     *    8. September, und es war der Grund fuer Autors haeufigste
     *    Beschwerde: "die unterste reihe an vasen bleibt immernoch oft
     *    geschlossen". Eine feste Ecke ist auf einem vollen Feld nie
     *    strittig, also entschied sie praktisch jede Lupe -- und fuenf
     *    Lupen decken 5 mal 3 = 15 Felder auf, genau so viele, wie in die
     *    oberen drei Reihen passen. Die unterste Reihe, genau fuenf Vasen,
     *    konnte rechnerisch gar nicht drankommen. Das ist auch die Fuenf
     *    aus seinem letzten Wunsch ("wenn noch 5 vasen zu sind").
     *
     *    Der Schwerpunkt wandert dagegen mit: Ist oben links aufgedeckt,
     *    liegt er weiter unten rechts, und die naechste Form wird nach
     *    unten rechts gedrueckt. Auf dem frischen Feld liegt er in der
     *    Mitte, dort heisst "weit weg" wie bisher: in die Ecke.
     *
     * 3. Erst wenn auch das gleich ist: der kuerzere Weg. Jeder Zug ist
     *    eine Gelegenheit, sich zu vertun.
     *
     * @return {verschiebungX, verschiebungY, getroffeneGeschlosseneVasen}
     */
    int[] besteStelle(List<int[]> form, boolean[][] zu) {
        int besteX = 0, besteY = 0;
        int bestesErgebnis = -1;
        int besteReste = Integer.MAX_VALUE;
        int bestePlatz = -1;
        double besteFerne = -1;
        int besteEntfernung = Integer.MAX_VALUE;

        // Der Schwerpunkt dessen, was noch zu ist. Von ihm weg wird
        // gedrueckt (Regel 2). Ist gar nichts mehr zu, tut es die Mitte
        // des Feldes -- dann ist ohnehin nichts mehr zu gewinnen.
        double mitteS = (SPALTEN - 1) / 2.0, mitteZ = (REIHEN - 1) / 2.0;
        double summeS = 0, summeZ = 0;
        int offene = 0;
        for (int s = 0; s < SPALTEN; s++) {
            for (int z = 0; z < REIHEN; z++) {
                if (!zu[s][z]) continue;
                summeS += s;
                summeZ += z;
                offene++;
            }
        }
        if (offene > 0) {
            mitteS = summeS / offene;
            mitteZ = summeZ / offene;
        }

        for (int dx = -(SPALTEN - 1); dx <= SPALTEN - 1; dx++) {
            for (int dy = -(REIHEN - 1); dy <= REIHEN - 1; dy++) {

                int treffer = 0;
                double ferne = 0;
                boolean passt = true;
                for (int[] feld : form) {
                    int s = feld[0] + dx;
                    int z = feld[1] + dy;
                    if (s < 0 || s >= SPALTEN || z < 0 || z >= REIHEN) {
                        passt = false;
                        break;
                    }
                    if (zu[s][z]) treffer++;
                    // Wie weit das Feld vom Schwerpunkt des noch
                    // Geschlossenen weg ist. Mehr ist besser.
                    ferne += (s - mitteS) * (s - mitteS)
                           + (z - mitteZ) * (z - mitteZ);
                }
                if (!passt) continue;

                int entfernung = Math.abs(dx) + Math.abs(dy);
                int reste = unbrauchbareReste(zu, form, dx, dy);
                int platz = platzDanach(zu, form, dx, dy);

                // Erstens:  moeglichst viele noch geschlossene Vasen treffen.
                // Zweitens: moeglichst wenige unbrauchbare Reste hinterlassen.
                // Drittens: moeglichst weit an den Rand des noch Zuen.
                // Viertens: der kuerzere Weg.
                // Neu (28.09., Autor: "Platz fuer die naechsten Formen"): zwischen
                // Zweitens und Drittens -- moeglichst viele gute Plaetze, auf die
                // danach noch IRGENDEINE der sechs Formen voll passt.
                boolean besser =
                        treffer > bestesErgebnis
                     || (treffer == bestesErgebnis && reste < besteReste)
                     || (treffer == bestesErgebnis && reste == besteReste && platz > bestePlatz)
                     || (treffer == bestesErgebnis && reste == besteReste && platz == bestePlatz
                         && ferne > besteFerne + 1e-9)
                     || (treffer == bestesErgebnis && reste == besteReste && platz == bestePlatz
                         && Math.abs(ferne - besteFerne) < 1e-9
                         && entfernung < besteEntfernung);

                if (besser) {
                    bestesErgebnis = treffer;
                    besteReste = reste;
                    bestePlatz = platz;
                    besteFerne = ferne;
                    besteEntfernung = entfernung;
                    besteX = dx;
                    besteY = dy;
                }
            }
        }
        return new int[]{besteX, besteY, Math.max(0, bestesErgebnis)};
    }

    /** Die sechs Dreier-Formen (Spalte, Reihe), jeweils links oben angesetzt. */
    private static final int[][][] ALLE_FORMEN = {
            {{0, 0}, {1, 0}, {2, 0}}, {{0, 0}, {0, 1}, {0, 2}},
            {{0, 0}, {1, 0}, {0, 1}}, {{0, 0}, {1, 0}, {1, 1}},
            {{0, 0}, {0, 1}, {1, 1}}, {{1, 0}, {0, 1}, {1, 1}}};

    /**
     * Wie viele Plaetze es nach diesem Zug noch gibt, auf die eine der sechs
     * Formen mit allen drei Feldern auf geschlossene Vasen passt. Je mehr,
     * desto besser laesst sich die naechste Lupe legen -- egal welche Form kommt.
     */
    static int platzDanach(boolean[][] zu, List<int[]> form, int dx, int dy) {
        boolean[][] rest = new boolean[SPALTEN][REIHEN];
        for (int sp = 0; sp < SPALTEN; sp++) rest[sp] = zu[sp].clone();
        for (int[] f : form) {
            int sp = f[0] + dx, re = f[1] + dy;
            if (sp >= 0 && sp < SPALTEN && re >= 0 && re < REIHEN) rest[sp][re] = false;
        }
        int platz = 0;
        for (int[][] fo : ALLE_FORMEN) {
            for (int ox = 0; ox < SPALTEN; ox++) {
                for (int oy = 0; oy < REIHEN; oy++) {
                    boolean alle = true;
                    for (int[] z : fo) {
                        int sp = z[0] + ox, re = z[1] + oy;
                        if (sp >= SPALTEN || re >= REIHEN || !rest[sp][re]) { alle = false; break; }
                    }
                    if (alle) platz++;
                }
            }
        }
        return platz;
    }

    /**
     * Wie viele geschlossene Vasen nach diesem Zug in einer Ecke sitzen, in
     * die keine Dreier-Form mehr passt.
     *
     * DIE EIGENTLICHE FRAGE BEIM LEGEN. Jede Form deckt drei Vasen auf; das
     * schafft fast jede Stelle. Der Unterschied liegt darin, was sie
     * UEBRIGLAESST.
     *
     * Legt man mitten aufs Feld, zerfaellt der Rest in Einzelstuecke: hier
     * eine Vase allein, dort zwei nebeneinander. In so ein Stueck passt
     * keine Dreier-Form mehr, und die naechste Lupe faellt zwangslaeufig
     * teilweise auf schon aufgedecktes Glas. Genau das hat Autor nach drei
     * Durchgaengen beschrieben: "wenn der bot die ersten 2 lupen in die
     * mitte stellt, ist ja fast kein platz mehr fuer die anderen."
     *
     * Gezaehlt wird deshalb, wie viele geschlossene Vasen in einem
     * zusammenhaengenden Stueck von weniger als drei liegen. Die sind fuer
     * jede kuenftige Form verloren -- egal welche kommt. Je weniger davon,
     * desto besser der Zug.
     *
     * Das ist auch die Antwort auf "der Bot soll selber checken": Er
     * probiert alle Stellen durch und rechnet fuer jede aus, wie brauchbar
     * das Feld danach noch ist. Er braucht dafuer nicht zu wissen, welche
     * Form als naechstes kommt.
     */
    private static int unbrauchbareReste(boolean[][] zu, List<int[]> form, int dx, int dy) {
        boolean[][] danach = new boolean[SPALTEN][REIHEN];
        for (int s = 0; s < SPALTEN; s++) {
            System.arraycopy(zu[s], 0, danach[s], 0, REIHEN);
        }
        for (int[] feld : form) {
            int s = feld[0] + dx, z = feld[1] + dy;
            if (s >= 0 && s < SPALTEN && z >= 0 && z < REIHEN) danach[s][z] = false;
        }

        boolean[][] gezaehlt = new boolean[SPALTEN][REIHEN];
        int verloren = 0;

        for (int s = 0; s < SPALTEN; s++) {
            for (int z = 0; z < REIHEN; z++) {
                if (!danach[s][z] || gezaehlt[s][z]) continue;

                // Das zusammenhaengende Stueck ausmessen, in dem diese Vase
                // liegt. Nachbarn sind oben, unten, links, rechts -- schraeg
                // zaehlt nicht, denn schraeg kann keine Form liegen.
                int groesse = 0;
                java.util.ArrayDeque<int[]> offen = new java.util.ArrayDeque<>();
                offen.add(new int[]{s, z});
                gezaehlt[s][z] = true;

                while (!offen.isEmpty()) {
                    int[] p = offen.poll();
                    groesse++;
                    int[][] nachbarn = {
                            {p[0] - 1, p[1]}, {p[0] + 1, p[1]},
                            {p[0], p[1] - 1}, {p[0], p[1] + 1}
                    };
                    for (int[] n : nachbarn) {
                        if (n[0] < 0 || n[0] >= SPALTEN || n[1] < 0 || n[1] >= REIHEN) continue;
                        if (!danach[n[0]][n[1]] || gezaehlt[n[0]][n[1]]) continue;
                        gezaehlt[n[0]][n[1]] = true;
                        offen.add(n);
                    }
                }

                if (groesse < 3) verloren += groesse;
            }
        }
        return verloren;
    }

    // ------------------------------------------------------------------
    //  Den blauen Umriss lesen
    // ------------------------------------------------------------------

    /**
     * Die Farbe des Umrisses in HSV.
     *
     * Warum HSV und nicht die ueblichen Farbkanaele: Der Umriss leuchtet
     * ueber Vasen in allen moeglichen Farben und wird dabei mal heller, mal
     * dunkler. Der FARBTON bleibt aber gleich -- ein kraeftiges Hellblau.
     * In HSV steht der Farbton allein in einem Wert und laesst sich sauber
     * abgrenzen; in Rot/Gruen/Blau haengen alle drei zusammen.
     *
     * OpenCV zaehlt den Farbton von 0 bis 179. Hellblau liegt bei etwa 95
     * bis 105.
     */
    private static final Scalar UMRISS_VON  = new Scalar(88, 90, 160);
    private static final Scalar UMRISS_BIS  = new Scalar(116, 255, 255);   // Autor 28.10.: vorher 112 -- gemessen oft 113

    /**
     * Wie breit der Stempel ist, mit dem die gefuellten Flaechen
     * herausgesiebt werden.
     *
     * Muss deutlich breiter sein als der Umriss (vier Bildpunkte) und
     * deutlich schmaler als eine Vase (rund sechzig). Neun liegt bequem
     * dazwischen.
     */
    private static final int STRICH_STEMPEL = 9;

    /**
     * Welche Felder unter der blauen Form liegen.
     *
     * Der Umriss ist eine geschlossene Linie um die drei Felder. Ob ein
     * Feld drin liegt, laesst sich mit einem alten Trick feststellen: Man
     * schiesst von der Feldmitte einen Strahl nach links aus dem Feld
     * hinaus und zaehlt, wie oft man die Linie kreuzt. Ungerade heisst
     * drinnen, gerade heisst draussen.
     *
     * Gezaehlt wird auf drei Hoehen und der Mehrheit gefolgt -- laeuft der
     * Strahl einmal genau auf der Linie entlang, verzaehlt er sich, und die
     * anderen beiden retten das Ergebnis.
     */
    List<int[]> formFelder(Mat screen, Raster r) {
        return formFelder(screen, r, UMRISS_VON, UMRISS_BIS);
    }

    /** Dasselbe fuer eine andere Umrissfarbe -- der blaue Hammer ist gelb umrandet. */
    List<int[]> formFelder(Mat screen, Raster r, Scalar von, Scalar bis) {
        List<int[]> felder = new ArrayList<>();

        Mat hsv = new Mat();
        Mat maske = new Mat();
        try {
            // Nur das Spielfeld ansehen. Der Rest des Bildschirms ist voll
            // von Blau -- die Reiter oben, der Knopf "See through", der
            // Hintergrund. Wuerde man alles durchsuchen, zaehlte der Strahl
            // fremde Linien mit.
            int rand = Math.max(r.schrittX(), r.schrittY());
            int x1 = Math.max(0, r.x0() - rand);
            int y1 = Math.max(0, r.y0() - rand);
            int x2 = Math.min(screen.width(),  r.mitteX(SPALTEN - 1) + rand);
            int y2 = Math.min(screen.height(), r.mitteY(REIHEN - 1) + rand);
            if (x2 - x1 < 20 || y2 - y1 < 20) return felder;

            Mat feld = new Mat(screen, new Rect(x1, y1, x2 - x1, y2 - y1));
            try {
                Imgproc.cvtColor(feld, hsv, Imgproc.COLOR_BGR2HSV);
                Core.inRange(hsv, von, bis, maske);
            } finally {
                feld.release();
            }

            // GROSSE TUERKISE FLAECHEN WEGNEHMEN.
            //
            // Das war der Fehler vom 7. September. Der Umriss wurde sehr wohl
            // gefunden -- aber es kamen "4, 13, 9, 7 Felder statt drei"
            // heraus, also Unsinn, und der Bot legte die Form immer dort ab,
            // wo sie erschien.
            //
            // Der Grund steht in Autors Bildschirmfoto: Eine AUFGEDECKTE Vase
            // ist selbst tuerkis. Und tuerkis ist auch der Knopf "See
            // through" und das Bestaetigungsfenster. Der Strahl, der die
            // Kreuzungen zaehlt, lief also durch ein halbes Dutzend Raender,
            // die gar nicht zur Form gehoeren.
            //
            // Der Unterschied ist nicht die Farbe, sondern die FORM: Der
            // Umriss ist ein duenner Strich, vier Bildpunkte breit. Die
            // Glasvasen sind ausgefuellte Flaechen von sechzig mal sechzig.
            //
            // Genau das trennt ein "Oeffnen" in der Bildverarbeitung: Es
            // laesst nur stehen, was breiter ist als der Stempel. Mit einem
            // Stempel von neun Bildpunkten ueberlebt jede Flaeche und kein
            // Strich. Was uebrigbleibt, sind die Flaechen -- und die zieht
            // man von der Maske ab. Zurueck bleiben nur noch die Striche.
            Mat stempel = null;
            Mat flaechen = new Mat();
            try {
                stempel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                        new org.opencv.core.Size(STRICH_STEMPEL, STRICH_STEMPEL));
                Imgproc.morphologyEx(maske, flaechen, Imgproc.MORPH_OPEN, stempel);
                Core.subtract(maske, flaechen, maske);
            } finally {
                flaechen.release();
                if (stempel != null) stempel.release();
            }

            // NUR DEN GROESSTEN ZUSAMMENHAENGENDEN STRICH BEHALTEN.
            //
            // Das Abziehen der Flaechen oben ist nicht sauber: Eine Glasvase
            // ist RUND, das Oeffnen arbeitet aber mit einem eckigen Stempel.
            // An den Rundungen bleibt deshalb ein duenner Ring stehen -- und
            // so ein Ring schliesst genau eine Vase ein. Der Strahl zaehlt
            // ihn wie den echten Umriss und meldet die Vase als belegt.
            //
            // Im Betrieb sah das am 7. September so aus:
            //
            //   Der blaue Umriss ergab 5 Felder statt drei.
            //   Der blaue Umriss ergab 5 Felder statt drei.
            //
            // Immer fuenf: die drei echten und zwei Glasvasen mit Ring.
            //
            // Der Umriss der Form ist aber EIN einziger, langer Strich -- er
            // laeuft um drei Felder herum, gut dreitausend Bildpunkte. Ein
            // Ringstueck einer einzelnen Vase ist ein Bruchteil davon. Es
            // reicht also, den groessten zusammenhaengenden Strich zu
            // behalten und alles andere wegzuwerfen. Das ist unabhaengig
            // davon, wie die Reste zustande kommen -- und deshalb die
            // haltbarere Loesung als noch ein Stempel in noch einer Groesse.
            groesstenStrichBehalten(maske);

            int blau = Core.countNonZero(maske);
            if (blau < 50) {
                // Selbst nachsehen, WELCHE Farbe da statt der erwarteten liegt.
                //
                // Der Farbton des Umrisses ist aus Autors Video geschaetzt.
                // Liegt er in Wirklichkeit daneben, findet der Bot nie einen
                // Umriss und legt die Form immer dort ab, wo sie erscheint --
                // ohne dass irgendwo staende, warum. Deshalb schreibt er hier
                // hin, welcher kraeftige Farbton auf dem Feld am haeufigsten
                // vorkommt. Damit ist die Zahl zum Nachstellen im Bericht,
                // statt dass jemand raten muss.
                Log.info("Event", Sprache.t("No blue outline found in the playing field ("
                        + blau + " pixels). Expected is a hue between "
                        + (int) UMRISS_VON.val[0] + " and " + (int) UMRISS_BIS.val[0]
                        + "; the most frequent one there is " + haeufigsterFarbton(hsv) + ".",
                        "Kein blauer Umriss im Spielfeld gefunden ("
                        + blau + " Bildpunkte). Erwartet wird ein Farbton zwischen "
                        + (int) UMRISS_VON.val[0] + " und " + (int) UMRISS_BIS.val[0]
                        + "; am haeufigsten liegt dort " + haeufigsterFarbton(hsv) + "."));
                return felder;
            }

            // VON AUSSEN FLUTEN STATT STRAHLEN ZAEHLEN.
            //
            // Bis zum 7. September wurde von jeder Feldmitte ein Strahl nach
            // links geschossen und gezaehlt, wie oft er den Umriss kreuzt --
            // ungerade heisst drinnen. Das ist ein alter Trick, aber er
            // haengt an jedem einzelnen Bildpunkt: Eine haarfeine Luecke im
            // Umriss, eine Ecke, die der Strahl streift, und die Zaehlung
            // kippt. Im Betrieb kam deshalb immer wieder "ergab 2 Felder
            // statt drei" oder "ergab 4" heraus -- und dann blieb die Form
            // liegen, wo sie war. Autor hat es mit zwei Bildern gezeigt: die
            // Form ueber zwei schon aufgedeckte Vasen gelegt, nur eine neue
            // aufgedeckt, obwohl daneben drei geschlossene lagen.
            //
            // Robuster ist der umgekehrte Weg: von einer Ecke des
            // Ausschnitts aus alles fluten, was AUSSERHALB liegt. Was danach
            // weder geflutet noch Umriss ist, liegt innen. Das haengt nicht
            // an einzelnen Bildpunkten, sondern nur daran, dass der Umriss
            // geschlossen ist -- und um kleine Luecken darin zu schliessen,
            // wird er vorher um einen Bildpunkt verbreitert.
            Mat innen = null;
            try {
                innen = innenFlaeche(maske);

                int[][] stimmen = new int[SPALTEN][REIHEN];

                for (int spalte = 0; spalte < SPALTEN; spalte++) {
                    for (int reihe = 0; reihe < REIHEN; reihe++) {
                        int mx = r.mitteX(spalte) - x1;
                        int my = r.mitteY(reihe) - y1;
                        if (mx < 0 || mx >= innen.width() || my < 0 || my >= innen.height()) {
                            continue;
                        }
                        // Fuenf Punkte in der Feldmitte abfragen, nicht nur
                        // einen: Liegt zufaellig ein Umrissstrich genau auf
                        // dem Mittelpunkt, entscheidet die Mehrheit.
                        int dafuer = 0;
                        int vx = Math.max(2, r.schrittX() / 5);
                        int vy = Math.max(2, r.schrittY() / 5);
                        int[][] proben = {{0, 0}, {-vx, 0}, {vx, 0}, {0, -vy}, {0, vy}};
                        for (int[] p : proben) {
                            int px = Math.min(innen.width() - 1, Math.max(0, mx + p[0]));
                            int py = Math.min(innen.height() - 1, Math.max(0, my + p[1]));
                            if (innen.get(py, px)[0] > 0) dafuer++;
                        }
                        stimmen[spalte][reihe] = dafuer;
                        if (dafuer >= 3) felder.add(new int[]{spalte, reihe});
                    }
                }

                if (felder.size() != 3) {
                    // NICHT GENAU DREI -- DIE DEUTLICHSTEN DREI NEHMEN.
                    //
                    // Es sind IMMER genau drei Felder. Kommt etwas anderes
                    // heraus, lag ein Feld knapp an der Grenze. Statt alles
                    // zu verwerfen -- und damit die Form liegenzulassen --
                    // werden die drei mit den meisten Stimmen genommen und
                    // geprueft, ob sie ueberhaupt eine gueltige Dreier-Form
                    // ergeben: hoechstens drei mal drei gross, und ueber
                    // Kanten zusammenhaengend.
                    List<int[]> beste = dreiMitDenMeistenStimmen(stimmen);

                    if (beste != null) {
                        Log.debug("Event", "Der blaue Umriss ergab " + felder.size()
                                + " Felder. Die drei deutlichsten werden genommen: "
                                + beste.get(0)[0] + "," + beste.get(0)[1] + " "
                                + beste.get(1)[0] + "," + beste.get(1)[1] + " "
                                + beste.get(2)[0] + "," + beste.get(2)[1]);
                        return beste;
                    }

                    Log.debug("Event", "Der blaue Umriss ergab " + felder.size()
                            + " Felder statt drei, und auch die deutlichsten drei ergeben "
                            + "keine gueltige Form. Die Form wird bestaetigt, wo sie liegt.");
                    felder.clear();
                }
                return felder;

            } finally {
                if (innen != null) innen.release();
            }

        } catch (Exception e) {
            Log.debug("Event", "Umriss konnte nicht gelesen werden: " + e.getMessage());
            return List.of();
        } finally {
            hsv.release();
            maske.release();
        }
    }

    /**
     * Welcher kraeftige Farbton auf dem Feld am haeufigsten vorkommt.
     *
     * Nur fuer die Fehlersuche. Gezaehlt werden ausschliesslich kraeftige,
     * helle Farben -- die blassen Vasen und der dunkle Hintergrund wuerden
     * das Ergebnis sonst zudecken.
     */
    private static String haeufigsterFarbton(Mat hsv) {
        try {
            int[] eimer = new int[180];
            byte[] zeile = new byte[hsv.cols() * 3];
            for (int y = 0; y < hsv.rows(); y += 2) {
                hsv.get(y, 0, zeile);
                for (int x = 0; x < zeile.length; x += 6) {   // jeder zweite Punkt
                    int s = zeile[x + 1] & 0xFF;
                    int v = zeile[x + 2] & 0xFF;
                    if (s < 90 || v < 150) continue;          // blass oder dunkel
                    eimer[(zeile[x] & 0xFF) % 180]++;
                }
            }
            int besterTon = -1, besteZahl = 0;
            for (int i = 0; i < eimer.length; i++) {
                if (eimer[i] > besteZahl) { besteZahl = eimer[i]; besterTon = i; }
            }
            return besterTon < 0 ? Sprache.t("no strong colour at all", "gar keine kraeftige Farbe")
                                 : Sprache.t(besterTon + " (" + besteZahl + " pixels)", besterTon + " (" + besteZahl + " Bildpunkte)");
        } catch (Exception e) {
            return Sprache.t("not measurable", "nicht messbar");
        }
    }

    /**
     * Wirft alles aus der Maske, was nicht zum groessten zusammenhaengenden
     * Strich gehoert.
     *
     * Danach steht in der Maske nur noch der Umriss der Form -- und der
     * Strahl kann nichts anderes mehr kreuzen.
     */
    private static void groesstenStrichBehalten(Mat maske) {
        Mat marken = new Mat();
        Mat werte = new Mat();
        Mat mitten = new Mat();
        try {
            int anzahl = Imgproc.connectedComponentsWithStats(maske, marken, werte, mitten,
                    8, org.opencv.core.CvType.CV_32S);

            // Marke 0 ist der Hintergrund, die zaehlt nicht mit.
            int beste = -1;
            double besteFlaeche = 0;
            for (int i = 1; i < anzahl; i++) {
                double flaeche = werte.get(i, Imgproc.CC_STAT_AREA)[0];
                if (flaeche > besteFlaeche) {
                    besteFlaeche = flaeche;
                    beste = i;
                }
            }
            if (beste < 0) return;

            Core.compare(marken, new Scalar(beste), maske, Core.CMP_EQ);

        } catch (Exception e) {
            Log.debug("Event", "Der groesste Strich liess sich nicht bestimmen: "
                    + e.getMessage() + " -- es wird mit der ganzen Maske gerechnet.");
        } finally {
            marken.release();
            werte.release();
            mitten.release();
        }
    }

    /**
     * Malt aus, was INNERHALB des Umrisses liegt.
     *
     * Der Weg dorthin geht ueber das Aussen: Von der linken oberen Ecke des
     * Ausschnitts -- die liegt garantiert draussen -- wird alles geflutet,
     * was zusammenhaengend erreichbar ist. Uebrig bleibt, was der Umriss
     * einschliesst.
     *
     * Vorher wird der Umriss um einen Bildpunkt verbreitert. Die Linie im
     * Spiel ist an den Ecken stellenweise nur ein, zwei Punkte dick und hat
     * durch die Kantenglaettung feine Luecken; durch eine einzige davon
     * wuerde die Flut nach innen laufen und alles zunichtemachen.
     *
     * @return ein Bild, in dem die eingeschlossene Flaeche weiss ist
     */
    private static Mat innenFlaeche(Mat umriss) {
        Mat dicker = new Mat();
        Mat hilfsmaske = new Mat();
        Mat stempel = null;
        try {
            stempel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new org.opencv.core.Size(3, 3));
            Imgproc.dilate(umriss, dicker, stempel);

            // WO ANGEFANGEN WIRD, MUSS HINTERGRUND SEIN.
            //
            // Liegt auf dem Startpunkt ausnahmsweise selbst Umriss -- ein
            // Kachelrand, Farbrauschen, eine Form ganz am aeusseren Rand --,
            // dann flutet die Flut die LINIE statt des Aussenraums. Danach
            // gilt die gesamte Aussenflaeche als "innen": alle zwanzig Felder
            // bekommen volle Stimmenzahl, und die drei ersten davon ergeben
            // zufaellig eine gueltige Form. Der Bot zoege die Lupe dann nach
            // einem frei erfundenen Umriss, und im Protokoll saehe alles
            // richtig aus. Deshalb werden alle vier Ecken durchprobiert.
            org.opencv.core.Point start = null;
            int[][] ecken = {
                    {0, 0},
                    {dicker.cols() - 1, 0},
                    {0, dicker.rows() - 1},
                    {dicker.cols() - 1, dicker.rows() - 1}
            };
            for (int[] e : ecken) {
                if (dicker.get(e[1], e[0])[0] == 0) {
                    start = new org.opencv.core.Point(e[0], e[1]);
                    break;
                }
            }
            if (start == null) {
                Log.debug("Event", "Alle vier Ecken des Ausschnitts sind blau -- da "
                        + "laesst sich nicht von aussen fluten. Die Form wird "
                        + "bestaetigt, wo sie liegt.");
                return Mat.zeros(dicker.size(), org.opencv.core.CvType.CV_8UC1);
            }

            // floodFill braucht eine Maske, die zwei Punkte groesser ist.
            hilfsmaske = Mat.zeros(dicker.rows() + 2, dicker.cols() + 2,
                    org.opencv.core.CvType.CV_8UC1);
            Imgproc.floodFill(dicker, hilfsmaske, start, new Scalar(128));

            // Jetzt gilt: 128 = aussen, 255 = der Umriss selbst, 0 = innen.
            Mat innen = new Mat();
            Core.inRange(dicker, new Scalar(0), new Scalar(0), innen);
            return innen;

        } finally {
            dicker.release();
            hilfsmaske.release();
            if (stempel != null) stempel.release();
        }
    }

    /**
     * Die drei Felder mit den meisten Stimmen -- wenn sie zusammen eine
     * gueltige Dreier-Form ergeben.
     *
     * Gueltig heisst: Sie passen in einen Kasten von hoechstens drei mal
     * drei UND haengen zusammen (ueber Kanten, nicht ueber Ecken). Das
     * schliesst zufaellige Anhaeufungen quer ueber das Feld aus.
     *
     * ALLE DREIERGRUPPEN WERDEN DURCHPROBIERT, die stimmenstaerkste zuerst.
     * Frueher wurden starr die drei obersten der Sortierung genommen -- und
     * bei Gleichstand entschied die Reihenfolge im Feld, also der Zufall.
     * Vier Felder mit je fuenf Stimmen (die Form liegt leicht versetzt und
     * streift einen Nachbarn) konnten so zu (0,0) (0,2) (1,0) fuehren: nicht
     * zusammenhaengend, also aufgegeben -- obwohl aus denselben vier Feldern
     * eine saubere Ecke zu bilden gewesen waere. Zwanzig Felder ergeben
     * hoechstens 1140 Dreiergruppen, das kostet nichts.
     *
     * @return die drei Felder, oder null wenn sich keine gueltige Form
     *         ergibt
     */
    private static List<int[]> dreiMitDenMeistenStimmen(int[][] stimmen) {
        List<int[]> alle = new ArrayList<>();
        for (int s = 0; s < SPALTEN; s++) {
            for (int z = 0; z < REIHEN; z++) {
                if (stimmen[s][z] > 0) alle.add(new int[]{s, z, stimmen[s][z]});
            }
        }
        if (alle.size() < 3) return null;

        alle.sort((a, b) -> Integer.compare(b[2], a[2]));

        List<int[]> beste = null;
        int besteStimmen = -1;
        for (int i = 0; i < alle.size() - 2; i++) {
            for (int j = i + 1; j < alle.size() - 1; j++) {
                for (int k = j + 1; k < alle.size(); k++) {
                    int summe = alle.get(i)[2] + alle.get(j)[2] + alle.get(k)[2];
                    if (summe <= besteStimmen) continue;

                    List<int[]> drei = List.of(
                            new int[]{alle.get(i)[0], alle.get(i)[1]},
                            new int[]{alle.get(j)[0], alle.get(j)[1]},
                            new int[]{alle.get(k)[0], alle.get(k)[1]});
                    if (!gueltigeDreierform(drei)) continue;

                    beste = new ArrayList<>(drei);
                    besteStimmen = summe;
                }
            }
        }
        return beste;
    }

    /**
     * Bilden diese drei Felder eine der Formen, die es im Spiel gibt?
     *
     * Sie muessen in einen Kasten von hoechstens drei mal drei passen und
     * ueber Kanten zusammenhaengen -- nicht ueber Ecken.
     */
    private static boolean gueltigeDreierform(List<int[]> drei) {
        int minS = Integer.MAX_VALUE, maxS = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] f : drei) {
            minS = Math.min(minS, f[0]); maxS = Math.max(maxS, f[0]);
            minZ = Math.min(minZ, f[1]); maxZ = Math.max(maxZ, f[1]);
        }
        if (maxS - minS > 2 || maxZ - minZ > 2) return false;

        // Jedes Feld braucht mindestens einen Nachbarn ueber eine Kante, und
        // alle drei muessen EIN Stueck bilden. Eine Dreierreihe hat vier
        // solche Nachbarschaften, ein Winkel ebenfalls.
        int verbunden = 0;
        for (int[] a : drei) {
            for (int[] b : drei) {
                if (a == b) continue;
                if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) == 1) verbunden++;
            }
        }
        return verbunden >= 4;
    }

}
