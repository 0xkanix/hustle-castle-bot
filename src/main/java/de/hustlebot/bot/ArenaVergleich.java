package de.hustlebot.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Autor 30.10.: "Wenn er gegen einen Gegner kaempft, den er noch nie angegriffen hat, kannst du ihn mit anderen Gegnern vergleichen,
 * die ungefaehr die gleiche Siegchance haben, und so einschaetzen, wie viele Punkte er geben wird." Grundlage ist arena-lernen.txt
 * (Spalten: Zeit, Gegner, Clan, SeineKraft, MeineKraft, Punkte, Messlatte, Abstand, Chance%, Top3, Ergebnis, Dauer_s, ArenaPunkte, Runde).
 * Gemessen 30.10. an 800 Siegen: 225er mit 99 % 221 Punkte, mit 90-97 % 218, mit 60-79 % 215; 150er mit 99 % 150, mit 60-79 % 147.
 * Reichen die Daten nicht: 220 und 149, also etwas weniger als das Maximum (Autors Regel; seine 145 lagen laut Rueckrechnung an
 * 544 Kaempfen 4 Punkte zu tief, die 220 passten genau).
 */
final class ArenaVergleich {

    private ArenaVergleich() {}

    /** So viele aehnliche Siege braucht der Vergleich. */
    static final int MINDESTENS = 8;
    /** So viele der neuesten zaehlen hoechstens. */
    static final int HOECHSTENS = 30;
    /** So weit darf die Siegchance abweichen (Prozentpunkte). */
    static final int BREITE = 5;
    /** Ohne Vergleich etwas weniger als das Maximum: 220 statt 225, 149 statt 150. */
    static double ohneVergleich(double lohn) {
        return Math.max(0, lohn - (lohn > 150 ? 5 : 1));
    }

    /**
     * Die Kampfdauern frueherer Siege mit derselben Belohnungsstufe und aehnlicher Siegchance, die neuesten zuletzt.
     * Leer, wenn es weniger als {@link #MINDESTENS} sind.
     */
    static List<Integer> dauern(List<String> lernZeilen, long lohn, int chanceProzent) {
        List<Integer> treffer = new ArrayList<>();
        for (String z : lernZeilen) {
            if (z.startsWith("#")) continue;
            String[] t = z.split("\t", -1);
            if (t.length < 13 || !t[10].equals("S")) continue;
            try {
                int chance = Integer.parseInt(t[8].trim());
                int dauer = Integer.parseInt(t[11].trim());
                int punkte = Integer.parseInt(t[12].trim());
                if (dauer <= 0 || dauer > 40 || punkte <= 0) continue;
                if ((punkte > 150) != (lohn > 150) || Math.abs(chance - chanceProzent) > BREITE) continue;
                treffer.add(dauer);
            } catch (NumberFormatException ignored) { }
        }
        if (treffer.size() < MINDESTENS) return List.of();
        return new ArrayList<>(treffer.subList(Math.max(0, treffer.size() - HOECHSTENS), treffer.size()));
    }
}
