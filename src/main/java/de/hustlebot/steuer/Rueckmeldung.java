package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;
import de.hustlebot.core.Zeitzone;
import de.hustlebot.discord.DiscordClient;

import javax.swing.JLabel;
import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Die kleine Zeile hinter den Knoepfen in den Einstellungen: hat es geklappt? (Autor, 15.10.: "ich will immer wissen,
 * ob das jetzt geklappt hat, mit ja oder nein".)
 *
 * Grundlage ist die Antwort des Bots auf genau diesen Befehl: Das Steuerprogramm sammelt alle Meldungen, die der Bot
 * waehrend des Befehls im selben Faden ausgibt (Steuerprogramm.senden mit Rueckruf). Gruen = geklappt, rot = nicht
 * geklappt (mit Grund), gelb = Hinweis des Bots. Die ganze Antwort steht im Tooltip.
 */
public final class Rueckmeldung {

    public enum Art { WARTET, GEKLAPPT, STAND, HINWEIS, NICHT_GEKLAPPT }

    public record Ergebnis(Art art, String kurz, String lang) { }

    /** Woran eine Absage zu erkennen ist -- die Einstellungs-Karten kommen auch bei "Nicht verstanden" in Info-Farbe. */
    // Code-Review 15.10.: "unbekannt" allein traf auch Stand-Texte ("Kraft unbekannt") -- die wurden rot als
    // "Nicht geklappt" gezeigt. Jetzt nur der unbekannte BEFEHL.
    private static final Pattern NEIN = Pattern.compile("(?i)nicht verstanden|not understood|unbekannte[rs]? befehl|unknown command|"
            + "ung(ue|ü)ltig|invalid|geht nicht|ging nicht|fehlgeschlagen|failed|nicht erlaubt|not allowed|"
            + "kein(e)? (gueltig|gültig)|nicht gefunden|not found");

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm");
    private static final int KURZ = 60;

    private Rueckmeldung() { }

    /**
     * Wertet die Antworten des Bots aus.
     * @param antworten alle Meldungen, die der Bot waehrend des Befehls ausgegeben hat
     * @param fehler    Ausnahme beim Schicken (sonst null)
     * @param abfrage   true = nur nachsehen (Stand/Anzeigen), dann heisst es "Stand:" statt "Geklappt:"
     */
    public static Ergebnis auswerten(List<Meldung> antworten, Throwable fehler, boolean abfrage) {
        if (fehler != null) {
            String g = fehler.getMessage() == null ? fehler.getClass().getSimpleName() : fehler.getMessage();
            return new Ergebnis(Art.NICHT_GEKLAPPT, Sprache.t("Failed: " + kuerzen(g), "Nicht geklappt: " + kuerzen(g)), g);
        }
        if (antworten == null || antworten.isEmpty()) {
            return new Ergebnis(Art.NICHT_GEKLAPPT,
                    Sprache.t("No reply from the bot – please press again.", "Keine Antwort vom Bot – bitte noch einmal drücken."), "");
        }
        StringBuilder lang = new StringBuilder();
        for (Meldung m : antworten) {
            if (lang.length() > 0) lang.append("\n\n");
            if (!m.titelLesbar().isBlank()) lang.append(m.titelLesbar()).append('\n');
            lang.append(m.textLesbar());
        }
        for (Meldung m : antworten) {
            if (m.farbe() == DiscordClient.FARBE_FEHLER || NEIN.matcher(m.titel() + " " + m.text()).find()) {
                return new Ergebnis(Art.NICHT_GEKLAPPT, Sprache.t("Failed: " + ersteZeile(m), "Nicht geklappt: " + ersteZeile(m)),
                        lang.toString());
            }
        }
        for (Meldung m : antworten) {
            if (m.farbe() == DiscordClient.FARBE_WARNUNG) {
                return new Ergebnis(Art.HINWEIS, Sprache.t("Note: " + ersteZeile(m), "Hinweis: " + ersteZeile(m)), lang.toString());
            }
        }
        Meldung m = antworten.get(0);
        return abfrage ? new Ergebnis(Art.STAND, Sprache.t("Status: " + ersteZeile(m), "Stand: " + ersteZeile(m)), lang.toString())
                : new Ergebnis(Art.GEKLAPPT, Sprache.t("Done: " + ersteZeile(m), "Geklappt: " + ersteZeile(m)), lang.toString());
    }

    /** Titel und erste Textzeile, gekuerzt -- z. B. "Leuchtturm: an". */
    static String ersteZeile(Meldung m) {
        String titel = m.titelLesbar().strip();
        String zeile = "";
        for (String z : m.textLesbar().split("\\R")) {
            String t = z.strip().replaceFirst("^[•\\-*·]+\\s*", "");
            if (!t.isEmpty()) {
                zeile = t;
                break;
            }
        }
        String s = titel.isEmpty() ? zeile : zeile.isEmpty() ? titel : titel + ": " + zeile;
        return kuerzen(s);
    }

    private static String kuerzen(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= KURZ ? t : t.substring(0, KURZ - 1) + "…";
    }

    /** Die leere Zeile hinter den Knoepfen. */
    public static JLabel label() {
        JLabel l = Holzstil.hinweis(" ");
        l.setIconTextGap(4);
        return l;
    }

    /** Gleich nach dem Druecken: grau "wird geschickt ...". Nur im Swing-Faden aufrufen. */
    public static void warten(JLabel l) {
        zeigen(l, new Ergebnis(Art.WARTET, Sprache.t("Sending …", "Wird geschickt …"), ""));
    }

    /** Ergebnis in die Zeile schreiben. Nur im Swing-Faden aufrufen. */
    public static void zeigen(JLabel l, Ergebnis e) {
        if (l == null || e == null) return;
        Color farbe;
        Symbol.Art symbol;
        switch (e.art()) {
            case GEKLAPPT -> { farbe = Holzstil.GUT; symbol = Symbol.Art.HAKEN; }
            case STAND -> { farbe = Holzstil.TEXT; symbol = Symbol.Art.LUPE; }
            case HINWEIS -> { farbe = Holzstil.WARNUNG; symbol = Symbol.Art.PUNKT; }
            case NICHT_GEKLAPPT -> { farbe = Holzstil.FEHLER; symbol = Symbol.Art.KREUZ; }
            default -> { farbe = Holzstil.LEISE; symbol = null; }
        }
        String uhr = e.art() == Art.WARTET ? "" : Zeitzone.uhrzeit().format(UHR) + "  ";
        l.setForeground(farbe);
        l.setIcon(symbol == null ? null : new Symbol(symbol, 13));
        l.setText(uhr + e.kurz());
        l.setToolTipText(e.lang().isBlank() ? null
                : "<html>" + escape(e.lang().length() > 1500 ? e.lang().substring(0, 1500) + " …" : e.lang())
                        .replace("\n", "<br>") + "</html>");
        // 15.10. 10:30: Die Zeile ist anfangs unsichtbar (sie soll keinen Platz wegnehmen) -- hier sichtbar schalten,
        // sonst sah Autor nach dem Druecken gar nichts.
        l.setVisible(true);
        l.revalidate();
        if (l.getParent() != null) l.getParent().revalidate();
        l.repaint();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
