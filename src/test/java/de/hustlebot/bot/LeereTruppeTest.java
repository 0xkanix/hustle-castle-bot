package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Bugjagd 29.10.: Bericht UZ4S (ben, 28.10.): Leuchtturm-Fenster mit leerer Truppe und der Meldung "Move your troops to the
 * Barracks!". Die Texte sind echte Lesungen dieses Fotos (ganzes Bild und Ausschnitte, zwei Lesearten der Texterkennung).
 */
class LeereTruppeTest {

    @Test
    void echteLesungenDesFotosWerdenErkannt() {
        assertTrue(LeereTruppe.ausText("Liahthouse, Move your Barracks!, Your squad:, Refresh, Move your troops to the Barracks!, OKCaH, thecril"));
        assertTrue(LeereTruppe.ausText("Mov..yeur Barracks! Your squad:"));
        assertTrue(LeereTruppe.ausText("ysCaptBladeechvile oriLMove vour troops.to the Barracks! 3037692 855"));
        assertTrue(LeereTruppe.ausText("Moye y,our Barracks! Your squad: Refresh Move your troops to the Barracks! 4d 1-7h"));
        assertTrue(LeereTruppe.ausText("Move your troops to the Barracks!"));
    }

    @Test
    void anderesZaehltNicht() {
        assertFalse(LeereTruppe.ausText(null));
        assertFalse(LeereTruppe.ausText(""));
        assertFalse(LeereTruppe.ausText("Barracks (level 6) Info Upgrade"), "Raumname in der Burg / Kaserne-Fenster");
        assertFalse(LeereTruppe.ausText("Lighthouse Your squad: Refresh Quick Attack! Attack! Reward: 55 3"));
        assertFalse(LeereTruppe.ausText("Participate For keys For food"));
    }
}
