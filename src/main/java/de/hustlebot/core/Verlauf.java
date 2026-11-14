package de.hustlebot.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Fuehrt Buch ueber einen Lauf: was der Rechner an Strom verbraucht hat und
 * wie weit die Werte dabei hochgegangen sind.
 *
 * Wozu das gut ist: Der laufende Bericht zeigt immer nur den Augenblick. Wer
 * morgens hinsieht, hat aber eine andere Frage -- war ueber Nacht irgendwas
 * los? Ein Rechner, der um drei Uhr kurz auf 78 Grad war und um acht wieder
 * bei 50 steht, sieht im Augenblicksbericht voellig unauffaellig aus.
 *
 * Der Strom wird laufend aufsummiert, nicht am Ende geschaetzt: Bei jedem
 * Messpunkt wird die Leistung mit der Zeit seit dem letzten Messpunkt
 * multipliziert. Das ist genauer als "Durchschnitt mal Laufzeit", weil der
 * Bot mal Vollgas gibt und mal wartet.
 *
 * Gezaehlt wird ab Programmstart. Wird der Bot neu gestartet, faengt die
 * Rechnung von vorne an -- das ist Absicht, denn genau dann will man wissen,
 * was der neue Lauf kostet.
 */
public final class Verlauf {

    /** Was eine Kilowattstunde kostet. Aus config.properties, sonst geschaetzt. */
    private static volatile double preisProKwh = 0.35;

    /** Bisher verbrauchte Kilowattstunden. */
    private static volatile double kwh = 0;

    /** Wann zuletzt gemessen wurde -- fuer die Zeitspanne bis zum naechsten Mal. */
    private static volatile long letzteMessung = 0;

    private static final long START = System.currentTimeMillis();

    private static volatile double maxTemp = -1;
    private static volatile String maxTempUm = "";
    private static volatile double maxWatt = -1;
    private static volatile int maxFan = -1;

    private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("dd.MM.' um 'HH:mm");

    private Verlauf() {
    }

    /**
     * Traegt den Strompreis ein. Wird einmal beim Start aufgerufen.
     *
     * @param preis Euro je Kilowattstunde. 0 oder weniger laesst den
     *              Standardwert stehen.
     */
    public static void configure(double preis) {
        if (preis > 0) preisProKwh = preis;
    }

    /**
     * Nimmt einen frischen Messpunkt auf.
     *
     * Wird von SystemMonitor aufgerufen, sobald neue Sensorwerte da sind --
     * also etwa alle 15 Sekunden. Fehlt der Hardware-Monitor, kommt hier
     * nichts an und alle Werte bleiben leer.
     */
    public static synchronized void note(SystemMonitor.Sensors s) {
        long jetzt = System.currentTimeMillis();
        double watt = s.totalWatt();

        // Strom aufsummieren. Beim allerersten Messpunkt gibt es noch keine
        // Zeitspanne, deshalb wird da nur der Zeitpunkt gemerkt.
        if (watt > 0) {
            if (letzteMessung > 0) {
                double stunden = (jetzt - letzteMessung) / 3_600_000.0;

                // Eine Luecke von mehr als fuenf Minuten heisst: der Rechner
                // hat geschlafen oder der Monitor war weg. Dann wird nicht
                // hochgerechnet, sonst stuende am Morgen eine Fantasiezahl da.
                if (stunden < 0.084) kwh += watt * stunden / 1000.0;
            }
            letzteMessung = jetzt;
        }

        if (s.cpuTemp() > maxTemp) {
            maxTemp = s.cpuTemp();
            maxTempUm = LocalDateTime.now().format(UHRZEIT);
        }
        if (watt > maxWatt) maxWatt = watt;
        if (s.fanRpm() > maxFan) maxFan = s.fanRpm();
    }

    /** Verbrauchte Kilowattstunden seit dem Start. */
    public static double kwh() {
        return kwh;
    }

    /** Was der Strom bisher gekostet hat, in Euro. */
    public static double euro() {
        return kwh * preisProKwh;
    }

    /** Die hoechste gemessene Prozessortemperatur, oder -1. */
    public static double maxTemp() {
        return maxTemp;
    }

    /** Wann diese Temperatur erreicht wurde, als "30.08. um 03:14". */
    public static String maxTempUm() {
        return maxTempUm;
    }

    /** Die hoechste gemessene Leistungsaufnahme, oder -1. */
    public static double maxWatt() {
        return maxWatt;
    }

    /** Die hoechste gemessene Luefterdrehzahl, oder -1. */
    public static int maxFan() {
        return maxFan;
    }

    /** Wie lange der Bot schon laeuft, in Minuten. */
    public static long laufzeitMinuten() {
        return (System.currentTimeMillis() - START) / 60_000;
    }

    /**
     * Die Zeile fuer den Bericht: "3,2 kWh, 1,12 EUR in 14 Stunden".
     *
     * Leer, solange nichts gemessen werden konnte -- dann faellt die Zeile im
     * Bericht einfach weg, statt dort mit Strichen herumzustehen.
     */
    public static String stromZeile() {
        if (kwh <= 0) return "";
        // Unter einer Kilowattstunde in Wattstunden. Sonst stuende in der
        // ersten Stunde jedes Mal "0,00 kWh" da -- eine Zahl, die nichts sagt.
        String menge = kwh >= 1
                ? String.format(java.util.Locale.GERMANY, "%.2f kWh", kwh)
                : String.format(java.util.Locale.GERMANY, "%.0f Wh", kwh * 1000);

        return String.format(java.util.Locale.GERMANY, "%s, %.2f EUR in %s",
                menge, euro(), CrashDetector.humanAge(laufzeitMinuten()));
    }

    /**
     * Die Zeile mit den Spitzenwerten: "68 Grad C um 03:14, 92 W, 2400 RPM".
     *
     * Auch hier: nichts gemessen, keine Zeile.
     */
    public static String spitzenZeile() {
        StringBuilder text = new StringBuilder();
        if (maxTemp > 0) {
            text.append(String.format(java.util.Locale.GERMANY, "%.0f Grad C", maxTemp));
            if (!maxTempUm.isEmpty()) text.append(" am ").append(maxTempUm);
        }
        if (maxWatt > 0) {
            if (text.length() > 0) text.append(", ");
            text.append(String.format(java.util.Locale.GERMANY, "%.0f W", maxWatt));
        }
        if (maxFan > 0) {
            if (text.length() > 0) text.append(", ");
            text.append(maxFan).append(" RPM");
        }
        return text.toString();
    }
}
