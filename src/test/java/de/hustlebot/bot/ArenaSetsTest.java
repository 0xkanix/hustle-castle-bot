package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verzerrt gelesene Set-Namen aus Autors Arena (Protokolle bis 11.10.) muessen trotzdem erkannt werden. */
class ArenaSetsTest {

    private static void set(String gelesen, String name, char stufe) {
        ArenaSets.Treffer t = ArenaSets.erkennen(gelesen);
        assertEquals(stufe, t.stufe(), gelesen);
        assertEquals(name, t.set(), gelesen);
    }

    /** Autor 22.10.: eigene Punkte je Set (Zahl statt Stufe in arena-sets.txt). */
    private static void set(String gelesen, String name, int punkte) {
        ArenaSets.Treffer t = ArenaSets.erkennen(gelesen);
        assertEquals(punkte, t.punkte(), gelesen);
        assertEquals(name, t.set(), gelesen);
    }

    @Test
    void verzerrteNamen() {
        set("Armor Breaker's Plate Mail", "Armor Breaker", 5);
        set("vøø•rmor øreaxer Mail", "Armor Breaker", 5);
        set("mo Breaxer•s nate Mail", "Armor Breaker", 5);
        set("uetenaer ot Nature•s Outfit", "Defender of Nature", 5);
        set("veTenaer o Outfit", "Defender of Nature", 5);
        set("vevenaer o Outfit", "Defender of Nature", 5);
        set(">ervant OT Outfit", "Servant of the Night", 3);
        set("•ervanr OT Outfit", "Servant of the Night", 3);
        set("execut•oner OT uarxness Plate Mail", "Executioner of Darkness", 4);
    }

    @Test
    void keinSet() {
        set("Equipped by character: Deadlv Liahtnina", "kein Set", '-');
        set("•naestrucume Monoutn plate mail", "kein Set", '-');
        set("Magic Inside Outfit", "kein Set", '-');
        assertEquals(-2, ArenaSets.erkennen("Magic Inside Outfit").punkte(), "Autor 22.10.: setlose Ruestung -2");
    }

    @Test
    void sauberGelesen() {
        assertEquals(true, ArenaPruefer.sauberGelesen("Armor Breaker's Plate Mail"));
        assertEquals(false, ArenaPruefer.sauberGelesen("vøø•rmor øreaxer Mail"));
        assertEquals(false, ArenaPruefer.sauberGelesen("veTenaer o Outfit"));
    }
}
