package de.hustlebot.bot;

import de.hustlebot.core.Sprache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reine Rechenregeln des Motors, ohne Spiel und ohne Bild. */
class BotEngineTest {

    private final Sprache.Lang vorher = Sprache.get();

    @AfterEach
    void spracheZurueck() {
        Sprache.set(vorher);
    }

    /**
     * Arena-Nachspiel (Pruefung 11.10.): Hat die Arena im Nachspiel KEIN Turnier gespielt
     * (Balken falsch gelesen, oder sie kam nicht weiter), sperrt der Motor zehn Minuten lang
     * jedes weitere Nachspiel -- sonst liefe er im Kreis. Nach einem gespielten Turnier keine Sperre.
     */
    @Test
    void nachspielOhneTurnierSperrtZehnMinuten() {
        long jetzt = 1_000_000L;
        assertEquals(jetzt + 10 * 60_000L, BotEngine.nachspielSperreBis(false, jetzt));
    }

    @Test
    void nachspielMitTurnierSperrtNicht() {
        assertEquals(0L, BotEngine.nachspielSperreBis(true, 1_000_000L));
    }

    /** Autor 18.11. (18:51): nach der Endzeit kein neues Arena-Nachspiel mehr (der Bot wollte nach dem Verlies in die Arena). */
    @Test
    void nachDerEndzeitKeinNachspiel() {
        assertTrue(BotEngine.nachspielErlaubt(0));
        assertFalse(BotEngine.nachspielErlaubt(1_000_000L));
    }

    /** Autor 18.11. (21:51): "!start burgen 10" brach nach 3 Angriffen ab -- da darf nicht "All 10 runs done" stehen. */
    @Test
    void vorzeitigesEndeWirdNichtAlsAllesErledigtGemeldet() {
        Sprache.set(Sprache.Lang.EN);
        assertEquals("1 of 10 runs, then stopped: Stopped (Weltkarte nicht erreicht) after 3 attack(s).",
                BotEngine.endeMeldung(1, 10, "Stopped (Weltkarte nicht erreicht) after 3 attack(s)."));
        assertEquals("All 10 runs done.", BotEngine.endeMeldung(10, 10, null));
        assertTrue(BotEngine.endeMeldung(3, 10, " ").startsWith("3 of 10 runs, then the mode stopped"));
        // "bis leer" (100 000 geplant): kein Abbruch, sondern einfach die Zahl.
        assertEquals("12 runs done (until empty).", BotEngine.endeMeldung(12, 100_000, null));

        Sprache.set(Sprache.Lang.DE);
        assertEquals("3 von 10 Durchlaeufen, dann abgebrochen: Weltkarte nicht erreicht",
                BotEngine.endeMeldung(3, 10, "Weltkarte nicht erreicht"));
        assertEquals("Alle 6 Durchlaeufe erledigt.", BotEngine.endeMeldung(6, 6, ""));
    }

    /** Konrad 20.11. (UJQ8): "Name doppelt vergeben" beim Laden der Vorlagen ist kein Grund, warum ein Modus aufhoert. */
    @Test
    void vorlagenWarnungIstKeinEndeGrund() {
        de.hustlebot.core.Log.warn("Vorlagen", "Name doppelt vergeben: confirm.");
        assertNull(de.hustlebot.core.Log.letzteWarnung(60_000, "Motor", "Vorlagen"));
        de.hustlebot.core.Log.warn("Motor", "Seit 20 s kein Fortschritt");
        assertNull(de.hustlebot.core.Log.letzteWarnung(60_000, "Motor", "Vorlagen"));
        de.hustlebot.core.Log.warn("Clan-Krieg", "Die Burg wurde nicht erreicht");
        assertEquals("Die Burg wurde nicht erreicht", de.hustlebot.core.Log.letzteWarnung(60_000, "Motor", "Vorlagen"));
        assertEquals("Die Burg wurde nicht erreicht", de.hustlebot.core.Log.letzteWarnung(60_000));
    }

    @Test
    void vorzeitigNurUnterDerGeplantenZahl() {
        assertTrue(BotEngine.vorzeitig(3, 10));
        assertFalse(BotEngine.vorzeitig(10, 10));
        assertFalse(BotEngine.vorzeitig(11, 10));
        assertFalse(BotEngine.vorzeitig(5, 100_000), "bis leer");
    }
}
