package de.hustlebot.steuer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Aussehen laesst sich ohne Bildschirm pruefen: Kontrast nach WCAG (Schrift mindestens 4,5:1,
 * grosse Schrift 3:1) in beiden Themen, das Mischen von Farben, die Zustandsfarben.
 */
class HolzstilTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    @AfterEach
    void zurueckAufHell() {
        Holzstil.thema(false);
    }

    @Test
    void kontrastRechnetWieWcag() {
        assertEquals(21.0, Holzstil.kontrast(Color.BLACK, Color.WHITE), 0.01);
        assertEquals(1.0, Holzstil.kontrast(Color.RED, Color.RED), 0.01);
        assertEquals(Holzstil.kontrast(Color.BLUE, Color.WHITE), Holzstil.kontrast(Color.WHITE, Color.BLUE), 0.0001);
    }

    @Test
    void schriftImHellenThemaIstLesbar() {
        Holzstil.thema(false);
        assertFalse(Holzstil.istDunkel());
        schriftPruefen();
    }

    @Test
    void schriftImDunklenThemaIstLesbar() {
        Holzstil.thema(true);
        assertTrue(Holzstil.istDunkel());
        schriftPruefen();
    }

    private static void schriftPruefen() {
        // Schrift auf den Flaechen
        mindestens(4.5, Holzstil.TEXT, Holzstil.GRUND, "Text auf Grund");
        mindestens(4.5, Holzstil.TEXT, Holzstil.HELL, "Text auf Karte");
        mindestens(4.5, Holzstil.TEXT, Holzstil.FELD, "Text auf Knopf");
        mindestens(4.5, Holzstil.TEXT, Holzstil.ZEBRA, "Text auf Zebra-Zeile");
        mindestens(4.5, Holzstil.LEISE, Holzstil.GRUND, "Hinweis auf Grund");
        mindestens(4.5, Holzstil.LEISE, Holzstil.HELL, "Hinweis auf Karte");
        mindestens(4.5, Holzstil.LEISE, Holzstil.FELD, "Tabellenkopf");
        mindestens(4.5, Holzstil.aufKopf(), Holzstil.KOPF, "Titel auf Kopfleiste");
        mindestens(4.5, Holzstil.leiseAufKopf(), Holzstil.KOPF, "Zeile auf Kopfleiste");
        // Signalfarben als Schrift (Kartentitel, Protokoll) auf Karte und Grund
        for (Color c : new Color[]{Holzstil.GUT, Holzstil.WARNUNG, Holzstil.FEHLER, Holzstil.KLICK}) {
            mindestens(4.5, c, Holzstil.HELL, "Signalfarbe " + c + " auf Karte");
            mindestens(4.5, c, Holzstil.GRUND, "Signalfarbe " + c + " auf Grund");
        }
        // Schrift auf Signal-Knoepfen
        for (Color c : new Color[]{Holzstil.GUT, Holzstil.WARNUNG, Holzstil.FEHLER}) {
            mindestens(4.5, Holzstil.AUF_SIGNAL, c, "Schrift auf Knopf " + c);
        }
        // Linien muessen sichtbar sein, aber nicht als Schrift lesbar (3:1 waere zu hart fuer feine Linien)
        assertTrue(Holzstil.kontrast(Holzstil.LINIE, Holzstil.HELL) >= 1.3, "Linie auf Karte sichtbar");
    }

    private static void mindestens(double soll, Color schrift, Color grund, String was) {
        double ist = Holzstil.kontrast(schrift, grund);
        assertTrue(ist >= soll, was + ": Kontrast " + String.format("%.2f", ist) + " < " + soll);
    }

    @Test
    void mischenGehtVonAnachB() {
        assertEquals(Color.BLACK, Holzstil.mischen(Color.BLACK, Color.WHITE, 0));
        assertEquals(Color.WHITE, Holzstil.mischen(Color.BLACK, Color.WHITE, 1));
        Color mitte = Holzstil.mischen(Color.BLACK, Color.WHITE, 0.5);
        assertEquals(128, mitte.getRed());
        assertEquals(Color.WHITE, Holzstil.mischen(Color.BLACK, Color.WHITE, 7), "ueber 1 wird abgeschnitten");
    }

    @Test
    void zustandsfarbenUndWoerter() {
        assertEquals(Holzstil.GUT, Holzstil.zustandsfarbe(de.hustlebot.bot.BotEngine.State.LAEUFT));
        assertEquals(Holzstil.WARNUNG, Holzstil.zustandsfarbe(de.hustlebot.bot.BotEngine.State.PAUSIERT));
        assertEquals(Holzstil.FEHLER, Holzstil.zustandsfarbe(de.hustlebot.bot.BotEngine.State.FEHLER));
        assertEquals(Holzstil.LEISE, Holzstil.zustandsfarbe(null));
        assertEquals("Spielt", Holzstil.zustandswort(de.hustlebot.bot.BotEngine.State.LAEUFT));
        assertEquals("Bereit", Holzstil.zustandswort(null));
    }

    @Test
    void themaTauschtDieFarben() {
        Holzstil.thema(false);
        Color hell = Holzstil.GRUND;
        Holzstil.thema(true);
        assertNotEquals(hell, Holzstil.GRUND);
        assertTrue(Holzstil.kontrast(Holzstil.GRUND, Color.BLACK) < Holzstil.kontrast(hell, Color.BLACK), "dunkel ist dunkler");
    }

    @Test
    void markdownUndZahlen() {
        assertEquals("Nahrung: 1 234", Holzstil.ohneMarkdown("**Nahrung:** `1 234`"));
        assertEquals("1.234.567", Holzstil.zahl(1234567));
        assertEquals("12", Holzstil.zahl(12));
    }
}
