package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Konto-Profile (Autor 21.10., Mia mit mehreren Spielkonten). */
class KontenTest {

    @TempDir
    Path bot;

    private Path templates() throws Exception {
        return Files.createDirectories(bot.resolve("templates"));
    }

    private void datei(Path p, String inhalt) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, inhalt);
    }

    @Test
    void ohneSchalterPassiertNichts() {
        assertEquals(Optional.empty(), Konten.erkennen("Mia", bot, false));
        assertFalse(Files.exists(bot.resolve("konto-aktiv.txt")));
    }

    @Test
    void erstesKontoWirdNurVermerktZweitesBrauchtZweiLesungen() {
        assertEquals(Optional.empty(), Konten.erkennen("Mia", bot, true));
        assertEquals("Mia", Konten.aktiv(bot));
        // Lesefehler am selben Namen: kein Wechsel
        assertEquals(Optional.empty(), Konten.erkennen("mia", bot, true));
        // neuer Name: erst beim zweiten gleichen Lesen
        assertEquals(Optional.empty(), Konten.erkennen("MiaZwei", bot, true));
        assertEquals(Optional.of("MiaZwei"), Konten.erkennen("MiaZwei", bot, true));
    }

    @Test
    void wechselnTauschtDateienUndListen() throws Exception {
        Path t = templates();
        Konten.erkennen("Mia", bot, true);
        datei(bot.resolve("arena-ich.txt"), "punkte=40");
        datei(bot.resolve("bezahlung.txt"), "arena=food+chests\nlager=900000");
        datei(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki_1019-0049.png"), "x");
        datei(bot.resolve("config.properties"), "bleibt");

        assertTrue(Konten.wechseln("Zweitkonto", bot, t));
        assertEquals("Zweitkonto", Konten.aktiv(bot));
        // das alte Konto ruht in konten\mia
        assertEquals("punkte=40", Files.readString(bot.resolve("konten/mia/arena-ich.txt")));
        assertTrue(Files.exists(bot.resolve("konten/mia/gegner/sicher/ar_sicher_Loki_1019-0049.png")));
        // das neue Konto faengt ohne Team-Scan und ohne Listen an, die Bezahlart kommt mit
        assertFalse(Files.exists(bot.resolve("arena-ich.txt")));
        assertFalse(Files.exists(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki_1019-0049.png")));
        assertTrue(Files.readString(bot.resolve("bezahlung.txt")).contains("food+chests"));
        // config.properties gehoert keinem Konto
        assertEquals("bleibt", Files.readString(bot.resolve("config.properties")));

        datei(bot.resolve("arena-ich.txt"), "punkte=90");
        assertTrue(Konten.wechseln("Mia", bot, t));
        assertEquals("punkte=40", Files.readString(bot.resolve("arena-ich.txt")));
        assertTrue(Files.exists(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki_1019-0049.png")));
        assertEquals("punkte=90", Files.readString(bot.resolve("konten/zweitkonto/arena-ich.txt")));
        // bekannte Konten: beide, das aktive zuerst
        assertEquals(java.util.List.of("Mia", "Zweitkonto"), Konten.bekannt(bot));
        // zurueck zum bekannten Zweitkonto: sofort, ohne zweite Lesung
        assertEquals(Optional.of("Zweitkonto"), Konten.erkennen("Zweitkonto", bot, true));
    }

    // ---- Autor 21.11.: Kaempferbilder, Spieler-ID und Namensbild je Konto ----------------------------------------------

    @Test
    void kontoVorlagenSindDieEigenenKaempfer() {
        assertTrue(Konten.kontoVorlage("unit_1.png"));
        assertTrue(Konten.kontoVorlage("unit_4_b.png"));
        assertTrue(Konten.kontoVorlage("name_2.png"));
        assertTrue(Konten.kontoVorlage("unit_sacrifice.png"));
        assertTrue(Konten.kontoVorlage("name_sacrifice.png"));
        assertTrue(Konten.kontoVorlage("nameplate_me.png"));
        assertFalse(Konten.kontoVorlage("unit_dead.png"));
        assertFalse(Konten.kontoVorlage("unit_dead_bar.png"));
        assertFalse(Konten.kontoVorlage("ar_name_Loki.png"));
        assertFalse(Konten.kontoVorlage("unit_1.txt"));
        // Autor 22.11.: die Team-Scan-Bilder (Kaserne dieses Kontos) wandern mit
        assertTrue(Konten.kontoVorlage("unit_auto_1.png"));
        assertTrue(Konten.kontoVorlage("name_auto_opfer.png"));
    }

    /** Autor 22.11.: auch Kaempferbilder in "My pictures" und die Team-Scan-Liste einheiten-auto.txt gehoeren dem Konto. */
    @Test
    void wechselnNimmtEigeneBilderUndTeamScanBilderMit() throws Exception {
        Path t = templates();
        Konten.ersteVermerken("Autor", bot);
        datei(bot.resolve("My pictures/Dungeon/unit_2_b.png"), "eigen2");
        datei(bot.resolve("My pictures/Dungeon/dg_irgendwas.png"), "bleibt");
        datei(bot.resolve("gelernt/dungeon/einheiten/auto/unit_auto_1.png"), "auto1");
        datei(bot.resolve("einheiten-auto.txt"), "1 | Bernd | 900");

        assertTrue(Konten.wechseln("Robin Zwei", bot, t));
        assertEquals("eigen2", Files.readString(bot.resolve("konten/autor/vorlagen-eigene/Dungeon/unit_2_b.png")));
        assertEquals("auto1", Files.readString(bot.resolve("konten/autor/vorlagen-gelernt/dungeon/einheiten/auto/unit_auto_1.png")));
        assertEquals("1 | Bernd | 900", Files.readString(bot.resolve("konten/autor/einheiten-auto.txt")));
        assertFalse(Files.exists(bot.resolve("My pictures/Dungeon/unit_2_b.png")));
        assertFalse(Files.exists(bot.resolve("einheiten-auto.txt")));
        assertEquals("bleibt", Files.readString(bot.resolve("My pictures/Dungeon/dg_irgendwas.png")));
        assertTrue(Konten.brauchtEinheitenBilder(t, bot.resolve("gelernt"), bot.resolve("My pictures")));

        assertTrue(Konten.wechseln("Autor", bot, t));
        assertEquals("eigen2", Files.readString(bot.resolve("My pictures/Dungeon/unit_2_b.png")));
        assertEquals("auto1", Files.readString(bot.resolve("gelernt/dungeon/einheiten/auto/unit_auto_1.png")));
        assertEquals("1 | Bernd | 900", Files.readString(bot.resolve("einheiten-auto.txt")));
    }

    /** Autor 22.11.: der Team-Scan darf fuer ein umbenanntes Konto oder "Name #ID" speichern, fuer ein fremdes nicht. */
    @Test
    void passtZumAktivenKenntSchreibweisenUndIdNamen() throws Exception {
        assertTrue(Konten.passtZumAktiven("Drittkonto", bot), "ohne aktives Konto wie bisher");
        Konten.ersteVermerken("Robin #50017349", bot);
        assertEquals("Robin", Konten.kern("Robin #50017349"));
        assertEquals("", Konten.kern("ID 50017349"));
        assertTrue(Konten.passtZumAktiven("Robin", bot));
        assertFalse(Konten.passtZumAktiven("Drittkonto", bot));
        assertTrue(Konten.passtZumAktiven("", bot), "Name nicht lesbar: wie bisher");
        datei(bot.resolve(Konten.NAMEN_DATEI), "Grim Robin");
        assertTrue(Konten.passtZumAktiven("Grim Robin", bot), "bestaetigte Schreibweise (umbenannt)");
    }

    @Test
    void wechselnNimmtDieKaempferbilderMit() throws Exception {
        Path t = templates();
        Konten.ersteVermerken("Mia", bot);
        datei(t.resolve("dungeon/unit_1.png"), "u1");
        datei(t.resolve("dungeon/unit_2.png"), "u2");
        datei(t.resolve("dungeon/name_2.png"), "n2");
        datei(t.resolve("dungeon/unit_dead.png"), "tot");
        datei(t.resolve("gelernt/dungeon/unit_3.png"), "u3");
        datei(t.resolve("gelernt/eigen/nameplate_me.png"), "me");
        datei(bot.resolve("gelernt/dungeon/unit_4.png"), "u4");
        datei(t.resolve("arena/gegner/sicher/ar_sicher_Loki.png"), "x");
        assertFalse(Konten.brauchtEinheitenBilder(t, bot.resolve("gelernt")));

        assertTrue(Konten.wechseln("Zweitkonto", bot, t));
        // weg zum alten Konto, die Todesleiste bleibt (fuer alle gleich)
        assertEquals("u1", Files.readString(bot.resolve("konten/mia/vorlagen/dungeon/unit_1.png")));
        assertEquals("u3", Files.readString(bot.resolve("konten/mia/vorlagen/gelernt/dungeon/unit_3.png")));
        assertEquals("me", Files.readString(bot.resolve("konten/mia/vorlagen/gelernt/eigen/nameplate_me.png")));
        assertEquals("u4", Files.readString(bot.resolve("konten/mia/vorlagen-gelernt/dungeon/unit_4.png")));
        assertFalse(Files.exists(t.resolve("dungeon/unit_1.png")));
        assertFalse(Files.exists(t.resolve("dungeon/name_2.png")));
        assertTrue(Files.exists(t.resolve("dungeon/unit_dead.png")));
        assertTrue(Konten.brauchtEinheitenBilder(t, bot.resolve("gelernt")));

        // das Zweitkonto nimmt eigene auf, dann zurueck zu Mia
        datei(t.resolve("dungeon/unit_1.png"), "zweit1");
        assertTrue(Konten.wechseln("Mia", bot, t));
        assertEquals("u1", Files.readString(t.resolve("dungeon/unit_1.png")));
        assertEquals("u3", Files.readString(t.resolve("gelernt/dungeon/unit_3.png")));
        assertEquals("u4", Files.readString(bot.resolve("gelernt/dungeon/unit_4.png")));
        assertEquals("zweit1", Files.readString(bot.resolve("konten/zweitkonto/vorlagen/dungeon/unit_1.png")));
        assertFalse(Konten.brauchtEinheitenBilder(t, bot.resolve("gelernt")));
    }

    /** Ein kleines Namensbild: ein Block an Stelle {@code x} (verschiedene x = verschiedene Namen). */
    private static boolean[][] bild(int x) {
        boolean[][] m = new boolean[16][60];
        for (int y = 2; y < 14; y++) for (int xx = x; xx < x + 10; xx++) m[y][xx] = true;
        for (int y = 2; y < 5; y++) for (int xx = 0; xx < 60; xx++) m[y][xx] = true;
        return m;
    }

    @Test
    void nachNameBildVorText() throws Exception {
        assertEquals(Konten.Urteil.ERSTES, Konten.nachName("Autor", null, bot).urteil());
        Konten.ersteVermerken("Autor", bot);
        assertEquals(Konten.Urteil.AKTIV, Konten.nachName("Autor", null, bot).urteil());
        Konten.merken("", java.util.List.of("Autor"), bild(5), bot);
        // Lesefehler "Autori", aber das Namensbild ist dasselbe -> bleibt beim aktiven Konto
        assertEquals(Konten.Urteil.AKTIV, Konten.nachName("Autori", bild(5), bot).urteil());
        // unbekannter Name, anderes Bild -> Profil ansehen
        assertEquals(Konten.Urteil.UNBEKANNT, Konten.nachName("Zweiter", bild(40), bot).urteil());
        assertEquals(Konten.Urteil.UNLESBAR, Konten.nachName("", bild(40), bot).urteil());

        // ein zweites, bekanntes Konto: am Bild erkannt, auch wenn der Text Unsinn ist
        assertTrue(Konten.wechseln("Zweiter", bot, templates()));
        Konten.merken("", java.util.List.of("Zweiter"), bild(40), bot);
        assertTrue(Konten.wechseln("Autor", bot, templates()));
        assertEquals(new Konten.Befund(Konten.Urteil.ANDERES, "Zweiter"), Konten.nachName("Zw3it3r", bild(40), bot));
        assertEquals(new Konten.Befund(Konten.Urteil.ANDERES, "Zweiter"), Konten.nachName("Zweiter", null, bot));
        assertEquals(Konten.Urteil.AKTIV, Konten.nachName("Zweiter", bild(5), bot).urteil());
    }

    @Test
    void nachIdEntscheidetDieId() throws Exception {
        Konten.ersteVermerken("Autor", bot);
        // erste Pruefung nach dem Update: das aktive Konto hat noch keine ID, der Name passt -> seine ID
        assertEquals(new Konten.Befund(Konten.Urteil.AKTIV, "Autor"), Konten.nachId("111", java.util.List.of("Autor"), null, bot));
        Konten.merken("111", java.util.List.of("Autor"), bild(5), bot);
        assertEquals("111", Konten.idVon(bot));
        Konten.merken("999", java.util.List.of(), null, bot);
        assertEquals("111", Konten.idVon(bot), "eine gespeicherte ID wird nie ueberschrieben");

        // gleiche ID, anderer Name (umbenannt oder Lesefehler): dasselbe Konto
        assertEquals(new Konten.Befund(Konten.Urteil.AKTIV, "Autor"), Konten.nachId("111", java.util.List.of("Kobold"), null, bot));
        // unbekannte ID -> neues Konto mit dem gelesenen Namen (Profil zuerst)
        assertEquals(new Konten.Befund(Konten.Urteil.NEU, "Zweiter"), Konten.nachId("222", java.util.List.of("Zweiter", "Zweiten"), null, bot));
        // unbekannte ID, gleicher Name wie ein bekanntes Konto -> eigener Name mit ID
        assertEquals(new Konten.Befund(Konten.Urteil.NEU, "Autor #222"), Konten.nachId("222", java.util.List.of("Autor"), null, bot));
        // ohne lesbaren Namen
        assertEquals(new Konten.Befund(Konten.Urteil.NEU, "ID 222"), Konten.nachId("222", java.util.List.of(""), null, bot));
        assertEquals(Konten.Urteil.UNLESBAR, Konten.nachId("", java.util.List.of("Autor"), null, bot).urteil());

        // neues Konto anlegen, ID merken, zurueck: die ID fuehrt wieder hin
        assertTrue(Konten.wechseln("Zweiter", bot, templates()));
        Konten.merken("222", java.util.List.of("Zweiter"), bild(40), bot);
        assertTrue(Konten.wechseln("Autor", bot, templates()));
        assertEquals("222", Konten.idVon(bot.resolve("konten/zweiter")));
        assertEquals(new Konten.Befund(Konten.Urteil.ANDERES, "Zweiter"), Konten.nachId("222", java.util.List.of("Umbenannt"), null, bot));
        assertEquals("Zweiter", Konten.kontoMitId(bot, "222"));
        assertEquals("Autor", Konten.kontoMitId(bot, "111"));
    }

    /** Autor 22.11.: eine falsch gelesene Ziffer legt kein neues Konto an, wenn Name oder Namensbild passen. */
    @Test
    void eineFalscheZifferIstKeinNeuesKonto() throws Exception {
        Konten.ersteVermerken("Autor", bot);
        Konten.merken("50017349", java.util.List.of("Autor"), bild(5), bot);
        assertEquals(new Konten.Befund(Konten.Urteil.AKTIV, "Autor"), Konten.nachId("50017346", java.util.List.of("Autor"), null, bot));
        assertEquals(new Konten.Befund(Konten.Urteil.AKTIV, "Autor"), Konten.nachId("50017346", java.util.List.of(""), bild(5), bot));
        assertEquals("50017349", Konten.idVon(bot));
        // anderer Name und anderes Bild: wirklich ein anderes Konto (IDs koennen nah beieinander liegen)
        assertEquals(new Konten.Befund(Konten.Urteil.NEU, "Fremder"), Konten.nachId("50017348", java.util.List.of("Fremder"), bild(40), bot));
        assertTrue(Konten.eineZifferAnders("123", "124"));
        assertFalse(Konten.eineZifferAnders("123", "123"));
        assertFalse(Konten.eineZifferAnders("123", "1234"));
        assertFalse(Konten.eineZifferAnders("", "1"));
    }

    @Test
    void bekanntesKontoOhneIdBekommtSie() throws Exception {
        Konten.ersteVermerken("Autor", bot);
        Konten.merken("111", java.util.List.of(), null, bot);
        assertTrue(Konten.wechseln("Alt", bot, templates()));   // ein Konto von frueher, noch ohne ID
        assertTrue(Konten.wechseln("Autor", bot, templates()));
        assertEquals(new Konten.Befund(Konten.Urteil.ANDERES, "Alt"), Konten.nachId("333", java.util.List.of("Alt"), null, bot));
    }

    @Test
    void merkenSammeltSchreibweisenUndBilder() throws Exception {
        Konten.ersteVermerken("Autor", bot);
        assertFalse(Konten.namePasst(bot, "Autor", "Autorus"));
        Konten.merken("", java.util.List.of("Autor", "Autorus"), bild(5), bot);   // per ID/Bild bestaetigt
        assertTrue(Konten.namePasst(bot, "Autor", "Autorus"));
        Konten.merken("", java.util.List.of(), bild(5), bot);   // gleiches Bild: kein zweites
        assertFalse(Files.exists(bot.resolve("konto-namensbild-2.png")));
        Konten.merken("", java.util.List.of(), bild(30), bot);
        assertTrue(Files.exists(bot.resolve("konto-namensbild-2.png")));
        // die Merkmale wandern mit dem Konto
        assertTrue(Konten.wechseln("Zweiter", bot, templates()));
        assertTrue(Files.exists(bot.resolve("konten/autor/konto-namensbild.png")));
        assertTrue(Files.exists(bot.resolve("konten/autor/konto-namen.txt")));
        assertFalse(Files.exists(bot.resolve("konto-namensbild.png")));
    }
}
