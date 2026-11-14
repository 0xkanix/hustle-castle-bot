package de.hustlebot.steuer;

import de.hustlebot.bot.BotEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F31, F32): das Tutorial in zwei Stufen und "Wo finde ich ...?".
 */
class HilfesucheTutorialTest {

    // ---------------------------------------------------------------- Hilfesuche (F32)

    @Test
    void arenaFindetModusEinstellungUndAufnahmeProgramm() {
        Set<String> ziele = new HashSet<>();
        for (Hilfesuche.Treffer t : Hilfesuche.suche("Arena")) ziele.add(t.ziel());
        assertTrue(ziele.contains("modus"), ziele.toString());
        assertTrue(ziele.contains("Arena"), ziele.toString());
        assertTrue(ziele.contains("werkzeuge"), ziele.toString());
    }

    @Test
    void sucheIstZweisprachigUndEgalBeiGrossKleinUndUmlauten() {
        assertFalse(Hilfesuche.suche("nahrung").isEmpty());
        assertFalse(Hilfesuche.suche("FOOD").isEmpty());
        assertFalse(Hilfesuche.suche("kathedrale").isEmpty());
        assertFalse(Hilfesuche.suche("cathedral").isEmpty());
        assertFalse(Hilfesuche.suche("tastenkürzel").isEmpty());
        assertFalse(Hilfesuche.suche("tastenkuerzel").isEmpty());
        assertTrue(Hilfesuche.suche("").isEmpty());
        assertTrue(Hilfesuche.suche("   ").isEmpty());
        assertTrue(Hilfesuche.suche("xyzzyx").isEmpty());
        assertTrue(Hilfesuche.suche("arena").size() <= 8);
    }

    @Test
    void trefferZeigenReiterUndZiel() {
        Hilfesuche.Treffer t = Hilfesuche.suche("zeitzone").get(0);
        assertEquals("Zeitzone", t.ziel());
        assertEquals(Steuerfenster.REITER_EINSTELLUNGEN, t.reiter());
        assertTrue(t.anzeige().contains(Hilfesuche.reiterName(Steuerfenster.REITER_EINSTELLUNGEN)));
    }

    /** Jedes Ziel im Index und jedes Ziel der Tutorials gibt es im gebauten Fenster -- sonst zeigte Pip ins Leere. */
    @Test
    void alleZieleGibtEsImFenster(@TempDir Path ordner) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm");
        Steuerprogramm steuer = Steuerprogramm.vorschau(ordner);
        steuer.vorschauZustand(BotEngine.State.BEREIT, "");
        List<String> fehlen = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                steuer.fensterBauen();
                var feld = Steuerprogramm.class.getDeclaredField("steuerfenster");
                feld.setAccessible(true);
                Steuerfenster f = (Steuerfenster) feld.get(steuer);
                for (String z : Hilfesuche.alleZiele()) if (f.ziel(z) == null) fehlen.add("Hilfesuche: " + z);
                for (Tutorial.Schritt s : Tutorial.SCHRITTE) if (s.ziel() != null && f.ziel(s.ziel()) == null) fehlen.add("Tutorial: " + s.ziel());
                for (Tutorial.Schritt s : Tutorial.KURZ) if (s.ziel() != null && f.ziel(s.ziel()) == null) fehlen.add("Kurzfassung: " + s.ziel());
                for (java.awt.Window w : java.awt.Window.getWindows()) w.dispose();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(fehlen.isEmpty(), fehlen.toString());
    }

    // ---------------------------------------------------------------- Tutorial in zwei Stufen (F31)

    @Test
    void kurzfassungHatFuenfSchritte() {
        assertEquals(5, Tutorial.KURZ.size());
        assertEquals(Tutorial.PipBild.Pose.HALLO, Tutorial.KURZ.get(0).pose());
        assertEquals(Tutorial.PipBild.Pose.GESCHAFFT, Tutorial.KURZ.get(4).pose());
        for (Tutorial.Schritt s : Tutorial.KURZ) {
            assertFalse(s.titelEn().isBlank() || s.titelDe().isBlank() || s.textEn().isBlank() || s.textDe().isBlank());
        }
    }

    @Test
    void kapitelTeilenDieLangeTourLuckenlosAuf() {
        List<Tutorial.Kapitel> k = Tutorial.KAPITEL;
        assertTrue(k.size() >= 4, "mindestens Steuerung, Einstellungen, Werkzeuge, Support: " + k);
        assertEquals(0, k.get(0).von());
        assertEquals(Tutorial.SCHRITTE.size() - 1, k.get(k.size() - 1).bis());
        for (int i = 1; i < k.size(); i++) {
            assertEquals(k.get(i - 1).bis() + 1, k.get(i).von(), "Kapitel " + i + " schliesst nicht an");
        }
        int summe = 0;
        for (Tutorial.Kapitel x : k) {
            assertTrue(x.groesse() >= 1);
            summe += x.groesse();
        }
        assertEquals(Tutorial.SCHRITTE.size(), summe);
        for (int i = 0; i < Tutorial.SCHRITTE.size(); i++) assertNotNull(Tutorial.kapitelVon(i), "Stelle " + i);
        assertNull(Tutorial.kapitelVon(-1));
        assertNull(Tutorial.kapitelVon(Tutorial.SCHRITTE.size()));
    }
}
