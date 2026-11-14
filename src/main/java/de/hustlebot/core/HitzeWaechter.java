package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Hitze-Fruehwarnung (Autor 29.10.): "frueh erkennen, wenn die CPU immer heisser wird, nicht erst bei 85 Grad -- dann
 * beginnt ein Rennen gegen die Zeit, der PC faehrt nach 2 min runter. Bau Schritte bei bestimmten Grad-Angaben ein, wo du
 * checkst, ob alles gut laeuft, rausfindest, wieso es so heiss wird, und frueh validierst, ob die Hitze dauerhaft bleibt
 * oder nur 1-3 min da ist."
 *
 * Anlass: 28.10. 23:17 rechnete ein PaddleOCR-Training neben dem Bot -- 87 Grad, nach 2 min ging der Rechner aus, die
 * Arena-Nacht war vorbei. Vorher gab es keine einzige Meldung.
 *
 * STUFEN: 75 Grad = Hinweis, 80 = Warnung (der Bot macht langsamer), 83 = letzte Vorwarnung. Die Abschaltung bei 90 Grad (bis 29.10. 85)
 * (2 Minuten) bleibt unveraendert in BotEngine -- sie hoeher zu stellen entscheidet nur Autor.
 *
 * JEDE MELDUNG SAGT: wie heiss, Verlauf (Grad je Minute ueber die letzten 2 Minuten), seit wann ueber 75, ob es nach
 * einer kurzen Spitze aussieht (faellt schon) oder dauerhaft ist (3 Minuten und mehr, faellt nicht), bei steigender
 * Temperatur wann 85 erreicht waere -- und welche Programme gerade am meisten rechnen (die Ursache).
 * Nach 3 Minuten auf einer Stufe kommt EINE Bewertung "dauerhaft"; beim Abkuehlen eine Entwarnung mit Dauer und
 * Hoechstwert ("war nur eine Spitze"). Abwaerts erst 4 Grad unter der Stufe -- sonst pendelt es um die Grenze.
 *
 * Die aktuelle Stufe steht zusaetzlich in hitze-stufe.txt im Bot-Ordner (Bauten und Helfer warten ab Stufe 2).
 */
public final class HitzeWaechter {

    /** Grenzen der Stufen 1-3 in Grad. */
    static final double[] STUFEN = {75, 80, 83};
    /** Ab hier faehrt BotEngine nach 2 Minuten herunter. */
    static final double ABSCHALTUNG = 90;   // Autor 29.10. abends (vorher 85)
    /** Abwaerts erst so viele Grad unter der Stufe. */
    static final double ZURUECK = 4;
    /** Nach so langer Zeit ueber 75 Grad gilt die Hitze als dauerhaft. */
    static final long DAUERHAFT_MS = 3 * 60_000L;
    /** Ueber diesen Zeitraum wird der Verlauf (Grad je Minute) gerechnet. */
    static final long TREND_MS = 2 * 60_000L;

    /**
     * Was der Aufrufer melden soll: neue Stufe, ob es aufwaerts ging, der Text, ob man Autor wecken sollte und ob es nach Discord geht.
     * Autor 01.11.: "kannst du aufhoeren, mich so voll zu spammen" -- kurze Spitzen stehen nur noch im Protokoll. Nach Discord gehen
     * Stufe 3, die Bewertung "dauerhaft" und die Entwarnung nur dann, wenn vorher auch etwas gemeldet wurde.
     */
    public record Meldung(int stufe, boolean aufwaerts, String text, boolean wecken, boolean melden) { }

    private final Deque<double[]> verlauf = new ArrayDeque<>();   // {Zeit ms, Grad}, hoechstens 10 Minuten
    private int stufe = 0;
    private long ueber75Seit = 0;
    private double hoechst = 0;
    private boolean dauerhaftGemeldet = false;
    private boolean nachDiscordGemeldet = false;
    private int geschriebeneStufe = -1;
    private final Path datei;

    public HitzeWaechter(Path datei) {
        this.datei = datei;
    }

    public static HitzeWaechter imBotOrdner() {
        return new HitzeWaechter(Ordner.bei("hitze-stufe.txt"));
    }

    public synchronized int stufe() {
        return stufe;
    }

    /**
     * Eine neue Messung (etwa alle 15 s). Liefert eine Meldung, wenn sich etwas Berichtenswertes getan hat.
     *
     * @param ursache wird nur bei einer Meldung gefragt (die Messung der Programme kostet etwas)
     */
    public synchronized Optional<Meldung> messen(long jetzt, double grad, Supplier<String> ursache) {
        if (grad <= 0) return Optional.empty();
        verlauf.addLast(new double[]{jetzt, grad});
        while (!verlauf.isEmpty() && jetzt - verlauf.peekFirst()[0] > 10 * 60_000L) verlauf.removeFirst();

        if (grad >= STUFEN[0]) {
            if (ueber75Seit == 0) {
                ueber75Seit = jetzt;
                hoechst = grad;
            }
            hoechst = Math.max(hoechst, grad);
        }

        int ziel = stufeFuer(grad);
        Meldung m = null;
        if (ziel > stufe) {
            stufe = ziel;
            dauerhaftGemeldet = false;
            m = new Meldung(stufe, true, text(jetzt, grad, ursache.get(), false), stufe >= 3, stufe >= 3);
            nachDiscordGemeldet |= m.melden();
        } else {
            int alt = stufe;
            while (stufe > 0 && grad < STUFEN[stufe - 1] - ZURUECK) stufe--;
            if (stufe == 0 && alt > 0) {
                long dauer = ueber75Seit == 0 ? 0 : jetzt - ueber75Seit;
                m = new Meldung(0, false, entwarnung(grad, dauer), false, nachDiscordGemeldet);
                ueber75Seit = 0;
                hoechst = 0;
                dauerhaftGemeldet = false;
                nachDiscordGemeldet = false;
            } else if (stufe > 0 && !dauerhaftGemeldet && ueber75Seit > 0 && jetzt - ueber75Seit >= DAUERHAFT_MS
                    && trend(jetzt) > -0.5) {
                dauerhaftGemeldet = true;
                nachDiscordGemeldet = true;
                m = new Meldung(stufe, false, text(jetzt, grad, ursache.get(), true), stufe >= 2, true);
            }
        }
        // Die Datei immer aktuell halten, solange es warm ist oder sich die Stufe geaendert hat (Leser pruefen "um":
        // aelter als 1 Minute = der Bot misst nicht mehr, Angabe veraltet).
        if (stufe > 0 || stufe != geschriebeneStufe) {
            schreiben(jetzt, grad);
            geschriebeneStufe = stufe;
        }
        return Optional.ofNullable(m);
    }

    static int stufeFuer(double grad) {
        int s = 0;
        for (int i = 0; i < STUFEN.length; i++) if (grad >= STUFEN[i]) s = i + 1;
        return s;
    }

    /** Grad je Minute ueber die letzten 2 Minuten (Ausgleichsgerade); 0 bei weniger als 3 Messungen. */
    synchronized double trend(long jetzt) {
        double n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (double[] p : verlauf) {
            if (jetzt - p[0] > TREND_MS) continue;
            double x = (p[0] - jetzt) / 60_000.0, y = p[1];
            n++;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        if (n < 3) return 0;
        double nenner = n * sxx - sx * sx;
        return nenner == 0 ? 0 : (n * sxy - sx * sy) / nenner;
    }

    private String text(long jetzt, double grad, String ursache, boolean bewertung) {
        double t = trend(jetzt);
        long minuten = ueber75Seit == 0 ? 0 : (jetzt - ueber75Seit) / 60_000L;
        String art;
        if (t <= -0.5) {
            art = Sprache.t("falling already -- looks like a short spike", "faellt schon -- sieht nach einer kurzen Spitze aus");
        } else if (ueber75Seit > 0 && jetzt - ueber75Seit >= DAUERHAFT_MS) {
            art = Sprache.t("LASTING (" + minuten + " min above 75)", "DAUERHAFT (seit " + minuten + " min ueber 75)");
        } else {
            art = Sprache.t("not clear yet (" + minuten + " min above 75, verdict after 3 min)",
                    "noch unklar (seit " + minuten + " min ueber 75, Bewertung nach 3 min)");
        }
        String bis85 = "";
        if (t > 0.2 && grad < ABSCHALTUNG) {
            long min = Math.round((ABSCHALTUNG - grad) / t);
            bis85 = Sprache.t(" At this pace " + (int) ABSCHALTUNG + " degrees in about " + min + " min (then the PC shuts down after 2 min).",
                    " Bei diesem Tempo in etwa " + min + " min " + (int) ABSCHALTUNG + " Grad (dann faehrt der PC nach 2 min herunter).");
        }
        String folge = switch (stufe) {
            case 1 -> Sprache.t(" Just a note.", " Nur ein Hinweis.");
            case 2 -> Sprache.t(" The bot slows down; builds and helpers pause.", " Der Bot macht langsamer; Bauten und Helfer pausieren.");
            default -> Sprache.t(" Last warning before the shutdown limit.", " Letzte Warnung vor der Abschaltgrenze.");
        };
        String kopf = bewertung
                ? Sprache.t("Heat verdict: ", "Hitze-Bewertung: ")
                : Sprache.t("Heat level " + stufe + " of 3: ", "Hitze-Stufe " + stufe + " von 3: ");
        return kopf + Sprache.t("processor " + Math.round(grad) + " degrees, ", "Prozessor " + Math.round(grad) + " Grad, ")
                + String.format(Locale.GERMANY, "%+.1f", t) + Sprache.t(" degrees/min, ", " Grad/min, ") + art + "." + bis85
                + Sprache.t(" Busiest programs: ", " Rechnet am meisten: ") + ursache + "." + folge;
    }

    private String entwarnung(double grad, long dauerMs) {
        long min = dauerMs / 60_000L;
        String art = dauerMs < DAUERHAFT_MS
                ? Sprache.t("it was only a short spike", "es war nur eine kurze Spitze")
                : Sprache.t("it lasted " + min + " min", "sie hielt " + min + " min an");
        return Sprache.t("Heat over: processor back at " + Math.round(grad) + " degrees (highest " + Math.round(hoechst)
                        + "), " + art + ".",
                "Hitze vorbei: Prozessor wieder bei " + Math.round(grad) + " Grad (hoechstens " + Math.round(hoechst)
                        + "), " + art + ".");
    }

    private void schreiben(long jetzt, double grad) {
        if (datei == null) return;
        try {
            Files.writeString(datei, "# Hitze-Stufe (0 = gut, 1 = ab 75, 2 = ab 80 -> Bauten/Helfer pausieren, 3 = ab 83)\n"
                    + "stufe=" + stufe + "\ngrad=" + Math.round(grad) + "\num=" + jetzt + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Rechner", "hitze-stufe.txt: " + e.getMessage());
        }
    }
}
