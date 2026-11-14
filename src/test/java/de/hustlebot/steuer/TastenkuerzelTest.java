package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Kuerzel lesen, speichern, wieder laden -- alles ohne Bildschirm (Auftrag 1, 11.10.). */
class TastenkuerzelTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    @TempDir
    Path tmp;

    private Tastenkuerzel neu() {
        return new Tastenkuerzel(tmp.resolve("tastenkuerzel-" + System.nanoTime() + ".properties"));
    }

    @Test
    void lesen_buchstabenkombination() {
        Tastenkuerzel.Kombination k = Tastenkuerzel.lesen("o+a");
        assertNotNull(k);
        assertEquals(List.of((int) 'O', (int) 'A'), k.codes());
        assertEquals("O+A", k.anzeige());
    }

    @Test
    void lesen_zusatztastenUndFunktionstaste() {
        Tastenkuerzel.Kombination k = Tastenkuerzel.lesen(" strg + ALT + p ");
        assertNotNull(k);
        assertEquals(List.of(0x11, 0x12, (int) 'P'), k.codes());
        assertEquals("Strg+Alt+P", k.anzeige());

        Tastenkuerzel.Kombination f = Tastenkuerzel.lesen("F7");
        assertNotNull(f);
        assertEquals(List.of(0x76), f.codes());
    }

    @Test
    void lesen_unbekanntOderLeerIstNull() {
        assertNull(Tastenkuerzel.lesen(""));
        assertNull(Tastenkuerzel.lesen("   "));
        assertNull(Tastenkuerzel.lesen("O+"));
        assertNull(Tastenkuerzel.lesen("Blubb"));
        assertNull(Tastenkuerzel.lesen("A+B+C+D+E"));
        assertNull(Tastenkuerzel.lesen(null));
    }

    @Test
    void lesen_doppelteTasteZaehltEinmal() {
        Tastenkuerzel.Kombination k = Tastenkuerzel.lesen("A+a");
        assertNotNull(k);
        assertEquals(1, k.codes().size());
    }

    @Test
    void gueltig_leerIstErlaubt() {
        assertTrue(Tastenkuerzel.gueltig(""));
        assertTrue(Tastenkuerzel.gueltig("O+A"));
        assertFalse(Tastenkuerzel.gueltig("Murks"));
    }

    @Test
    void imAlltagMoeglich_nurOhneZusatzOderFunktionstaste() {
        assertTrue(Tastenkuerzel.imAlltagMoeglich(Tastenkuerzel.lesen("O+A")));
        assertFalse(Tastenkuerzel.imAlltagMoeglich(Tastenkuerzel.lesen("Strg+O")));
        assertFalse(Tastenkuerzel.imAlltagMoeglich(Tastenkuerzel.lesen("F7")));
        assertFalse(Tastenkuerzel.imAlltagMoeglich(null));
    }

    @Test
    void vorgaben_sindGesetztUndWirksam() {
        Tastenkuerzel t = neu();
        assertEquals("Strg+Alt+A", t.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).kombination().anzeige());
        assertTrue(t.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).wirksam());
        // Profile haben keine Vorgabe und sind damit aus.
        assertFalse(t.eintrag(Tastenkuerzel.Aktion.PROFIL_1).wirksam());
        assertTrue(t.alleAn());
        assertEquals(Tastenkuerzel.Aktion.PROTOKOLL, t.aktionFuer(Tastenkuerzel.lesen("a+alt+strg")));
    }

    @Test
    void setzen_speichertUndLaedtWieder() {
        Path datei = tmp.resolve("k.properties");
        Tastenkuerzel t = new Tastenkuerzel(datei);
        assertTrue(t.setzen(Tastenkuerzel.Aktion.PROTOKOLL, "strg+alt+p"));
        t.setAn(Tastenkuerzel.Aktion.NOTIZBLOCK, false);
        assertTrue(t.setzen(Tastenkuerzel.Aktion.PROFIL_1, "F8"));

        Tastenkuerzel neu = new Tastenkuerzel(datei);
        assertEquals("Strg+Alt+P", neu.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).kombination().anzeige());
        assertFalse(neu.eintrag(Tastenkuerzel.Aktion.NOTIZBLOCK).an());
        assertFalse(neu.eintrag(Tastenkuerzel.Aktion.NOTIZBLOCK).wirksam());
        assertEquals("F8", neu.eintrag(Tastenkuerzel.Aktion.PROFIL_1).kombination().anzeige());
        assertTrue(neu.eintrag(Tastenkuerzel.Aktion.PROFIL_1).wirksam());
    }

    @Test
    void setzen_lehntDoppelteUndUnbekannteAb() {
        Tastenkuerzel t = neu();
        // Strg+Alt+N gehoert schon dem Notizblock.
        assertFalse(t.setzen(Tastenkuerzel.Aktion.PROTOKOLL, "Alt+Strg+N"));
        assertEquals("Strg+Alt+A", t.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).kombination().anzeige());
        assertFalse(t.setzen(Tastenkuerzel.Aktion.PROTOKOLL, "Quatsch"));
        // Leer schaltet ab.
        assertTrue(t.setzen(Tastenkuerzel.Aktion.PROTOKOLL, ""));
        assertFalse(t.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).wirksam());
    }

    @Test
    void alleAus_machtAlleUnwirksam() {
        Tastenkuerzel t = neu();
        assertFalse(t.wirksame().isEmpty());
        t.setAlleAn(false);
        assertTrue(t.wirksame().isEmpty());
        assertNull(t.aktionFuer(Tastenkuerzel.lesen("Strg+Alt+A")));
        t.setAlleAn(true);
        assertFalse(t.wirksame().isEmpty());
    }

    @Test
    void vorgaben_setztAllesZurueck() {
        Tastenkuerzel t = neu();
        assertTrue(t.setzen(Tastenkuerzel.Aktion.PROTOKOLL, "F6"));
        t.setAlleAn(false);
        t.vorgaben();
        assertEquals("Strg+Alt+A", t.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).kombination().anzeige());
        assertTrue(t.alleAn());
    }

    @Test
    void alteVorgabeInDerDatei_wirdZurNeuen() throws Exception {
        // Autor 11.10.: O+Buchstabe loeste beim Tippen aus. Steht die alte Vorgabe noch in der Datei, gilt Strg+Alt.
        Path datei = tmp.resolve("alt.properties");
        java.nio.file.Files.writeString(datei, String.join("\n", "alle=an", "protokoll=O+A", "statistik=O+S", "status=F6"));
        Tastenkuerzel t = new Tastenkuerzel(datei);
        assertEquals("Strg+Alt+A", t.eintrag(Tastenkuerzel.Aktion.PROTOKOLL).kombination().anzeige());
        assertEquals("Strg+Alt+S", t.eintrag(Tastenkuerzel.Aktion.STATISTIK).kombination().anzeige());
        // Selbst gewaehlte bleiben.
        assertEquals("F6", t.eintrag(Tastenkuerzel.Aktion.STATUS).kombination().anzeige());
    }

    @Test
    void vorgaben_wirkenNichtBeimTippen() {
        for (Tastenkuerzel.Aktion a : Tastenkuerzel.Aktion.values()) {
            Tastenkuerzel.Kombination k = Tastenkuerzel.lesen(a.vorgabe);
            if (k != null) assertFalse(Tastenkuerzel.imAlltagMoeglich(k), a + " " + a.vorgabe);
        }
        // AltGr = Strg+Alt: Strg+Alt+E schreibt das Euro-Zeichen.
        assertTrue(Tastenkuerzel.imAlltagMoeglich(Tastenkuerzel.lesen("Strg+Alt+E")));
        assertTrue(Tastenkuerzel.imAlltagMoeglich(Tastenkuerzel.lesen("Strg+Alt+Q")));
        assertFalse(Tastenkuerzel.imAlltagMoeglich(Tastenkuerzel.lesen("Strg+Alt+A")));
    }
}
