package de.hustlebot.bot;

import de.hustlebot.bot.ArenaListenRegel.Entscheidung;
import de.hustlebot.bot.ArenaListenRegel.Kampf;
import de.hustlebot.bot.ArenaListenRegel.Liste;
import de.hustlebot.bot.ArenaListenRegel.Stand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Listen-Regel nach einem Arena-Kampf (Autor 29.10.): gilt fuer die Kaempfe des Bots UND fuer Autors eigene
 * (Beobachter), gezaehlt wird gemeinsam. Grenze 7 Sekunden fuer alle.
 */
class ArenaListenRegelTest {

    /** Niederlage; die Zahlen enthalten diesen Kampf schon. */
    private static Entscheidung verloren(long dauer, int siegeGesamt, int kaempfeGesamt, int niederlagenNotiz) {
        return ArenaListenRegel.entscheide(new Kampf(false, dauer, false, siegeGesamt, kaempfeGesamt, niederlagenNotiz, siegeGesamt),
                Stand.LEER);
    }

    private static Entscheidung gewonnen(long dauer, int siegeNotiz) {
        return ArenaListenRegel.entscheide(new Kampf(true, dauer, false, siegeNotiz, siegeNotiz, 0, siegeNotiz), Stand.LEER);
    }

    // ---- Autors Beispiele ------------------------------------------------------------------------------------

    @Test
    void botZweimalVerlorenUndAutorEinmal_niemals() {
        // "Wenn er sieht, dass ich gegen einen Gegner verliere, gegen den er schon 2 mal verloren hat" -- 3 Kaempfe, kein Sieg.
        Entscheidung e = verloren(12, 0, 3, 3);
        assertEquals(Liste.NIEMALS, e.liste());
        assertTrue(e.dreiOhneSieg());
    }

    @Test
    void dreiNiederlagenOhneSieg_niemalsEgalWieLange() {
        for (long dauer : new long[]{0, 6, 10, 18, 25, 38}) {
            Entscheidung e = verloren(dauer, 0, 3, 3);
            assertEquals(Liste.NIEMALS, e.liste(), "Dauer " + dauer);
            assertTrue(e.dreiOhneSieg());
        }
    }

    @Test
    void autorDreimalAngegriffenDreimalVerloren_niemals() {
        assertEquals(Liste.NIEMALS, verloren(15, 0, 3, 3).liste());
    }

    @Test
    void einSiegImVerlaufBremstDieDreiOhneSiegRegel() {
        // 2 Niederlagen + 1 Sieg + diese Niederlage (16 s): kein "niemals", erst die 2. Niederlage -> meiden
        Entscheidung e = verloren(16, 1, 4, 3);
        assertEquals(Liste.MEIDEN, e.liste());
        assertFalse(e.dreiOhneSieg());
    }

    // ---- Niederlage nach Dauer (Grenze 7 s) ---------------------------------------------------------------------

    @Test
    void niederlageSechsSekunden_niemals() {
        assertEquals(Liste.NIEMALS, verloren(6, 1, 2, 1).liste());
    }

    @Test
    void niederlageSiebenSekunden_nichtMehrNiemals() {
        assertEquals(7, ArenaListenRegel.NIEDERLAGE_NIEMALS_S);
        assertEquals(Liste.MEIDEN, verloren(7, 1, 2, 1).liste());
    }

    @Test
    void niederlageZehnSekunden_meiden() {
        assertEquals(Liste.MEIDEN, verloren(10, 1, 2, 1).liste());
        assertEquals(Liste.MEIDEN, verloren(14, 1, 2, 1).liste());
    }

    @Test
    void niederlageFuenfundzwanzigSekunden_keineListe() {
        assertEquals(Liste.KEINE, verloren(25, 1, 2, 1).liste());
        assertEquals(Liste.KEINE, verloren(20, 1, 2, 1).liste());
    }

    @Test
    void niederlageVierzehnBisZwanzig_meidenErstAbDerZweiten() {
        assertEquals(Liste.KEINE, verloren(17, 1, 2, 1).liste());
        assertEquals(Liste.MEIDEN, verloren(17, 1, 3, 2).liste());
    }

    @Test
    void niederlageDauerUnbekannt_meidenErstAbDerDritten() {
        assertEquals(Liste.KEINE, verloren(-1, 1, 3, 2).liste());
        assertEquals(Liste.MEIDEN, verloren(-1, 1, 4, 3).liste());
    }

    @Test
    void vielStaerkerMeidenAusserUeberZwanzigSekunden() {
        Kampf kurz = new Kampf(false, 10, true, 1, 2, 1, 1), lang = new Kampf(false, 25, true, 1, 2, 1, 1);
        assertEquals(Liste.MEIDEN, ArenaListenRegel.entscheide(kurz, Stand.LEER).liste());
        assertEquals(Liste.KEINE, ArenaListenRegel.entscheide(lang, Stand.LEER).liste());
        // ... aber unter 7 s bleibt es "niemals"
        assertEquals(Liste.NIEMALS, ArenaListenRegel.entscheide(new Kampf(false, 5, true, 1, 2, 1, 1), Stand.LEER).liste());
    }

    @Test
    void stehtSchonAufMeiden_nichtNochEinmal() {
        Stand meiden = new Stand(false, true, false, false);
        assertEquals(Liste.KEINE, ArenaListenRegel.entscheide(new Kampf(false, 10, false, 1, 2, 1, 1), meiden).liste());
        // "niemals" geht trotzdem
        assertEquals(Liste.NIEMALS, ArenaListenRegel.entscheide(new Kampf(false, 5, false, 1, 2, 1, 1), meiden).liste());
    }

    @Test
    void sicherVerloren_wirdAbgestuftStattNiemals() {
        Entscheidung e = ArenaListenRegel.entscheide(new Kampf(false, 5, false, 3, 4, 1, 3), new Stand(false, false, true, false));
        assertEquals(Liste.KEINE, e.liste());
        assertTrue(e.abstufen());
    }

    @Test
    void gesperrterGegner_nichtsMehrTun() {
        Stand gesperrt = new Stand(true, false, false, false);
        assertEquals(Entscheidung.NICHTS, ArenaListenRegel.entscheide(new Kampf(false, 5, false, 0, 4, 4, 0), gesperrt));
        assertEquals(Entscheidung.NICHTS, ArenaListenRegel.entscheide(new Kampf(true, 5, false, 1, 5, 4, 1), gesperrt));
    }

    // ---- Sieg ---------------------------------------------------------------------------------------------------

    @Test
    void siegSechsSekunden_sicher() {
        assertEquals(Liste.SICHER, gewonnen(6, 1).liste());
    }

    @Test
    void siegSiebenSekunden_nurMachbar() {
        assertEquals(7, ArenaListenRegel.SIEG_SICHER_S);
        assertEquals(Liste.MACHBAR, gewonnen(7, 1).liste());
    }

    @Test
    void siegZehnSekunden_machbar() {
        assertEquals(Liste.MACHBAR, gewonnen(10, 1).liste());
        assertEquals(Liste.MACHBAR, gewonnen(14, 1).liste());
    }

    @Test
    void siegZwanzigSekunden_keineListe() {
        Entscheidung e = gewonnen(20, 1);
        assertEquals(Liste.KEINE, e.liste());
        assertFalse(e.dritterSieg());
    }

    /** Autor 30.10.: Spieler, die der Bot nicht kannte: erst nach 3 eigenen Kaempfen auf eine Liste. */
    @Test
    void selbstKaempfe_erstNachDreiKaempfen() {
        assertEquals(Liste.KEINE, ArenaListenRegel.nachSelbstKaempfen(null));
        assertEquals(Liste.KEINE, ArenaListenRegel.nachSelbstKaempfen(""));
        assertEquals(Liste.KEINE, ArenaListenRegel.nachSelbstKaempfen("S"));
        assertEquals(Liste.KEINE, ArenaListenRegel.nachSelbstKaempfen("SS"));
        assertEquals(Liste.KEINE, ArenaListenRegel.nachSelbstKaempfen("NN"));
    }

    @Test
    void selbstKaempfe_autorsVierFaelle() {
        assertEquals(Liste.SICHER, ArenaListenRegel.nachSelbstKaempfen("SSS"));
        assertEquals(Liste.MACHBAR, ArenaListenRegel.nachSelbstKaempfen("SSN"));
        assertEquals(Liste.MACHBAR, ArenaListenRegel.nachSelbstKaempfen("NSS"));
        assertEquals(Liste.MEIDEN, ArenaListenRegel.nachSelbstKaempfen("SNN"));
        assertEquals(Liste.MEIDEN, ArenaListenRegel.nachSelbstKaempfen("NNS"));
        assertEquals(Liste.NIEMALS, ArenaListenRegel.nachSelbstKaempfen("NNN"));
    }

    @Test
    void selbstKaempfe_danachZaehlenDieLetztenDrei() {
        assertEquals(Liste.MACHBAR, ArenaListenRegel.nachSelbstKaempfen("SSSN"));
        assertEquals(Liste.SICHER, ArenaListenRegel.nachSelbstKaempfen("NSSS"));
        assertEquals(Liste.NIEMALS, ArenaListenRegel.nachSelbstKaempfen("SSNNN"));
    }

    @Test
    void dritterSieg_sicherEgalWieLange() {
        Entscheidung e = gewonnen(20, 3);
        assertEquals(Liste.SICHER, e.liste());
        assertTrue(e.dritterSieg());
        assertEquals(Liste.SICHER, gewonnen(-1, 3).liste());
    }

    @Test
    void siegDauerUnbekannt_keineListe() {
        assertEquals(Liste.KEINE, gewonnen(-1, 1).liste());
    }

    @Test
    void siegBeiMachbarMachbar_nichtNochEinmal() {
        Stand machbar = new Stand(false, false, false, true);
        assertEquals(Liste.KEINE, ArenaListenRegel.entscheide(new Kampf(true, 10, false, 2, 2, 0, 2), machbar).liste());
        // ... schneller Sieg macht ihn aber zu "sicher"
        assertEquals(Liste.SICHER, ArenaListenRegel.entscheide(new Kampf(true, 5, false, 2, 2, 0, 2), machbar).liste());
    }

    @Test
    void siegBeiSicher_nichtsMehrTun() {
        assertEquals(Entscheidung.NICHTS,
                ArenaListenRegel.entscheide(new Kampf(true, 5, false, 4, 4, 0, 4), new Stand(false, false, true, false)));
    }

    // ---- automatische Liste vor dem Kampf -------------------------------------------------------------------------

    @Test
    void autoStufeVorDemKampf() {
        assertEquals(ArenaMode.AutoListe.SICHER, ArenaListenRegel.autoStufe(3, 3, 'S'));
        // letzter Kampf eine Niederlage: sofort eine Stufe runter
        assertEquals(ArenaMode.AutoListe.MACHBAR, ArenaListenRegel.autoStufe(3, 4, 'N'));
        assertNull(ArenaListenRegel.autoStufe(2, 3, 'N'));
        assertNull(ArenaListenRegel.autoStufe(0, 0, (char) 0));
        assertEquals(ArenaMode.AutoListe.MACHBAR, ArenaListenRegel.autoStufe(2, 2, 'S'));
    }
}
