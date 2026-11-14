package de.hustlebot.steuer;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Function;

/**
 * Autor 24.10. (Ideenliste F24, F32): Suchen in Texten -- fuer das Suchfeld der Einstellungen und die Hilfe "Wo finde ich ...?".
 * Gross und klein, Umlaute (ae = a) und Formatierung (html) spielen keine Rolle; jedes getippte Wort muss irgendwo im Text
 * vorkommen (Reihenfolge egal).
 */
final class Einstellungssuche {

    private Einstellungssuche() {
    }

    /** Text ohne html-Zeichen, ohne Akzente (Umlaute werden a/o/u), in Kleinbuchstaben, mit einfachen Leerzeichen. */
    static String normal(String text) {
        if (text == null) return "";
        String t = text.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&");
        t = t.toLowerCase(Locale.ROOT).replace("ß", "ss");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.replaceAll("\\s+", " ").strip();
    }

    /** Zusaetzlich ae, oe, ue wie a, o, u -- damit "zurueck" und "zurück" sich finden, in beide Richtungen. */
    private static String kurz(String normal) {
        return normal.replace("ae", "a").replace("oe", "o").replace("ue", "u");
    }

    /** Kommen alle Woerter der Suche im Text vor? Eine leere Suche passt immer. */
    static boolean passt(String suche, String text) {
        String s = normal(suche);
        if (s.isEmpty()) return true;
        String t = normal(text);
        String tk = kurz(t);
        for (String wort : s.split(" ")) {
            if (!t.contains(wort) && !tk.contains(kurz(wort))) return false;
        }
        return true;
    }

    /**
     * Alle Texte, die man auf einer Zeile sieht: Beschriftungen und Knoepfe samt ihren Tooltips -- und, wenn die Zeile
     * einen "infoTitel" traegt, dessen Erklaerung (damit "Kisten" auch dort findet, wo es nur in der Erklaerung steht).
     */
    static String textVon(Component c, Function<String, String> erklaerung) {
        StringBuilder b = new StringBuilder();
        sammeln(c, b, erklaerung);
        return b.toString();
    }

    private static void sammeln(Component c, StringBuilder b, Function<String, String> erklaerung) {
        if (c instanceof JLabel l) anhaengen(b, l.getText());
        if (c instanceof AbstractButton a) anhaengen(b, a.getText());
        if (c instanceof JComponent j) {
            anhaengen(b, j.getToolTipText());
            Object info = j.getClientProperty("infoTitel");
            if (info instanceof String titel && erklaerung != null) {
                anhaengen(b, titel);
                anhaengen(b, erklaerung.apply(titel));
            }
        }
        if (c instanceof Container k) {
            for (Component x : k.getComponents()) sammeln(x, b, erklaerung);
        }
    }

    private static void anhaengen(StringBuilder b, String text) {
        if (text != null && !text.isBlank()) b.append(' ').append(text);
    }
}
