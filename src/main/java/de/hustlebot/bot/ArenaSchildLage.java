package de.hustlebot.bot;

import de.hustlebot.core.NamensVergleich;
import java.util.List;
import java.util.function.Supplier;

/**
 * Autor 01.11. (Pruefung 3.137, Abweichung 2/4): Name und Clan eines Schilds nach LAGE, nicht nach Text. Auf dem Schild steht oben der Spielername, darunter in
 * kleinerer Schrift der Clan (links davon das Wappen), darunter die Punkte. Die Texterkennung ueber das ganze Schild liefert nur Zeilen OHNE Lage und laesst
 * dabei oft eine weg: bei "~ЦИРИ~ / Myth Gods / 375" kam ["Myth Gods", "375"] -- der unlesbare Name verschwand, der Clan rutschte auf Platz 1 und galt als Name
 * (der Notizblock fuehrte ihn als "Myth Gods", spaeter "V Myth Gods", und bekam die Bilanz eines anderen Spielers).
 *
 * Darum werden Name und Clan getrennt aus ihren festen Baendern gelesen (Namensband = der weisse Namenskasten, Clanband = die Zeile darunter). Die Zeilen des
 * ganzen Schilds dienen nur noch als Zusatz: Steht die erste Zeile im Clanband, IST sie der Clan -- der Name ist dann unlesbar, und der Clan rueckt nie nach oben.
 */
final class ArenaSchildLage {

    private ArenaSchildLage() { }

    /** Name und Clan; nameAusBand = der Name stammt aus dem Namensband (dann ist er ein echter Name, auch wenn er wie ein Clan aussieht). */
    record Ergebnis(String name, String clan, boolean nameAusBand) { }

    /**
     * @param zeilen    die Zeilen der Texterkennung ueber das ganze Schild (ohne Lage); {@code zeilen.size() >= 3} gilt als [Name, Clan, Punkte]
     * @param nameBand  die Lesung des Namensbandes (nur wenn gebraucht abgefragt), null/leer = nichts Lesbares
     * @param clanBand  die Lesung des Clanbandes, null/leer = nichts Lesbares (oder ohne Clan: "Not in clan" wird vorher aussortiert)
     */
    static Ergebnis teilen(List<String> zeilen, Supplier<String> nameBand, String clanBand) {
        String z0 = zeilen.isEmpty() ? "" : zeilen.get(0).strip();
        boolean mehrdeutig = zeilen.size() <= 2;   // [Name, Punkte], [Clan, Punkte], [x]: wer die Zeile ist, sagt erst das Band
        String c = lesbar(clanBand) ? clanBand.strip() : null;

        // Clan: aus seinem Band; nur wenn das nichts liefert und drei Zeilen da sind (Name, Clan, Punkte), die zweite.
        String clan = c != null ? c : zeilen.size() >= 3 && !ArenaMode.pokalZeile(zeilen.get(1)) ? zeilen.get(1).strip() : "";

        // Steht im Namensband und im Clanband dasselbe, trug der Clan nur den Namen mit (FLIMSY): der Name bleibt ein echter Name, der Clan fehlt.
        boolean z0IstClan = c != null && !z0.isBlank() && NamensVergleich.gleicherName(z0, c);
        String b = null;
        if (z0IstClan || mehrdeutig || z0.isBlank() || ArenaMode.unleserlich(z0)) {
            String t = nameBand.get();
            if (lesbar(t)) b = t.strip();
        }
        if (b != null && c != null && gleicheZeichen(b, c)) return new Ergebnis(b, "", true);

        // Die erste Zeile steht im Clanband: es ist der Clan, der Name ist unlesbar -- der Clan rueckt nie nach oben.
        if (z0IstClan) return new Ergebnis(b != null ? b : "", clan, b != null);
        if (z0.isBlank() || ArenaMode.unleserlich(z0)) return new Ergebnis(b != null ? b : z0, clan, b != null);
        if (mehrdeutig && b != null && !NamensVergleich.gleicherName(z0, b)) {
            // Weder Clan noch Name aus dem Namensband: wie bisher war die einzige Zeile der Clan, der Name steht im Band.
            return new Ergebnis(b, clan.isBlank() && c == null ? z0 : clan, true);
        }
        return new Ergebnis(z0, clan, false);
    }

    /**
     * Dasselbe fuers Vergleichsfenster: Name und Clan stehen dort in zwei Zeilen untereinander (Name rechts vom Stufen-Abzeichen, Clan eine Zeile tiefer rechts
     * vom kleinen Wappen). Gelesen wird je Zeile ihr festes Band; die Texterkennung ueber den ganzen Ausschnitt (ohne Lage, meist nur die erste Zeile) wird nur
     * gefragt, wenn das Namensband nichts Lesbares ergibt -- und ihre Zeile ist der Clan, wenn sie im Clanband steht ("Not in clan" wurde so zum Namen).
     *
     * @param ganz die Zeilen ueber den ganzen Ausschnitt (nur bei Bedarf abgefragt)
     */
    static Ergebnis teilenFenster(Supplier<List<String>> ganz, String nameBand, String clanBand) {
        String c = lesbar(clanBand) ? clanBand.strip() : null;
        String b = lesbar(nameBand) ? nameBand.strip() : null;
        if (b != null && c != null && gleicheZeichen(b, c)) return new Ergebnis(b, "", true);   // trug der Clan nur den Namen mit (FLIMSY): Name echt, Clan fehlt
        if (b != null) return new Ergebnis(b, c != null ? c : "", true);
        List<String> z = ganz.get();
        String z0 = z.isEmpty() ? "" : z.get(0).strip();
        if (z0.isBlank()) return new Ergebnis("", c != null ? c : "", false);
        if (c != null && NamensVergleich.gleicherName(z0, c)) return new Ergebnis("", c, false);   // die Zeile steht im Clanband: der Name ist unlesbar
        return new Ergebnis(z0, c != null ? c : z.size() > 1 ? z.get(1).strip() : "", false);
    }

    private static boolean lesbar(String t) {
        return t != null && !t.isBlank();
    }

    /** Gleiche Buchstaben und Ziffern (Gross-/Kleinschreibung und Zeichen dazwischen egal). */
    private static boolean gleicheZeichen(String a, String b) {
        String x = NamensVergleich.form(a);
        return x.length() >= 3 && x.equals(NamensVergleich.form(b));
    }
}
