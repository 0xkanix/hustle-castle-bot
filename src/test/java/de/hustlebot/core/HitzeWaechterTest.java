package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Hitze-Fruehwarnung (Autor 29.10.): Stufen 75/80/83, Verlauf, Spitze oder dauerhaft, Ursache. */
class HitzeWaechterTest {

    @TempDir
    Path ordner;

    private static final long S = 15_000;   // der Bot misst alle 15 s

    /** Spielt Messungen ab (eine je 15 s) und sammelt die Meldungen. */
    private List<HitzeWaechter.Meldung> abspielen(HitzeWaechter w, long start, double... grad) {
        List<HitzeWaechter.Meldung> l = new ArrayList<>();
        for (int i = 0; i < grad.length; i++) {
            Optional<HitzeWaechter.Meldung> m = w.messen(start + i * S, grad[i], () -> "python.exe 310 %");
            m.ifPresent(l::add);
        }
        return l;
    }

    @Test
    void kurzeSpitzeGibtHinweisUndEntwarnung() {
        HitzeWaechter w = new HitzeWaechter(ordner.resolve("hitze-stufe.txt"));
        var l = abspielen(w, 0, 60, 66, 72, 77, 76, 72, 68, 65);
        assertEquals(2, l.size(), l.toString());
        assertEquals(1, l.get(0).stufe());
        assertTrue(l.get(0).aufwaerts());
        assertFalse(l.get(0).wecken());
        assertTrue(l.get(0).text().contains("python.exe 310 %"), "die Ursache steht in der Meldung");
        assertEquals(0, l.get(1).stufe());
        assertTrue(l.get(1).text().matches("(?s).*(Spitze|spike).*"), l.get(1).text());
        // Autor 01.11.: kein Discord-Spam -- die kurze Spitze bleibt im Protokoll
        assertFalse(l.get(0).melden());
        assertFalse(l.get(1).melden());
    }

    @Test
    void kurzeSpitzeAufStufeZweiGehtAuchNichtNachDiscord() {
        HitzeWaechter w = new HitzeWaechter(ordner.resolve("hitze-stufe.txt"));
        var l = abspielen(w, 0, 70, 81, 78, 72, 68);
        assertTrue(l.stream().noneMatch(HitzeWaechter.Meldung::melden), l.toString());
    }

    @Test
    void stufeDreiUndDauerhaftGehenNachDiscordMitEntwarnung() {
        HitzeWaechter w = new HitzeWaechter(ordner.resolve("hitze-stufe.txt"));
        var drei = abspielen(w, 0, 70, 84);
        assertTrue(drei.stream().anyMatch(m -> m.stufe() == 3 && m.melden() && m.wecken()), drei.toString());
        var ende = abspielen(w, 2 * S, 60);
        assertEquals(1, ende.size());
        assertTrue(ende.get(0).melden(), "es wurde gemeldet, also auch die Entwarnung");

        HitzeWaechter d = new HitzeWaechter(ordner.resolve("hitze-stufe2.txt"));
        double[] warm = new double[16];
        java.util.Arrays.fill(warm, 77);
        var lang = abspielen(d, 0, warm);
        assertTrue(lang.stream().anyMatch(m -> !m.aufwaerts() && m.melden()), "nach 3 Minuten die Bewertung: " + lang);
        var kalt = abspielen(d, 16 * S, 60);
        assertTrue(kalt.get(0).melden());
    }

    @Test
    void anstiegMeldetJedeStufeMitZeitBisZurAbschaltung() {
        HitzeWaechter w = new HitzeWaechter(ordner.resolve("hitze-stufe.txt"));
        // 1 Grad je 15 s = 4 Grad je Minute
        var l = abspielen(w, 0, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83);
        assertEquals(3, l.size(), l.toString());
        assertEquals(List.of(1, 2, 3), l.stream().map(HitzeWaechter.Meldung::stufe).toList());
        assertTrue(l.get(1).text().contains("90"), "bei steigender Temperatur: wann die Abschaltgrenze (90) erreicht waere");
        assertTrue(l.get(2).wecken(), "Stufe 3 (83 Grad) darf wecken");
        assertFalse(l.get(1).wecken());
        assertEquals(4.0, w.trend(11 * S), 0.3);
    }

    @Test
    void dauerhafteHitzeWirdEinmalBewertet() {
        HitzeWaechter w = new HitzeWaechter(ordner.resolve("hitze-stufe.txt"));
        double[] werte = new double[20];
        java.util.Arrays.fill(werte, 81);
        var l = abspielen(w, 0, werte);   // 5 Minuten bei 81 Grad
        assertEquals(2, l.size(), l.toString());
        assertEquals(2, l.get(0).stufe());
        assertFalse(l.get(1).aufwaerts());
        assertTrue(l.get(1).text().matches("(?s).*(DAUERHAFT|LASTING).*"), l.get(1).text());
    }

    @Test
    void pendelnUmDieGrenzeMeldetNichtStaendig() {
        HitzeWaechter w = new HitzeWaechter(ordner.resolve("hitze-stufe.txt"));
        var l = abspielen(w, 0, 80, 79, 80, 79.5, 80.2, 78, 80.1, 77);
        assertEquals(1, l.size(), "nur einmal Stufe 2, kein Hin und Her: " + l);
        assertEquals(2, w.stufe());
        abspielen(w, 8 * S, 75.5);   // unter 80-4 -> Stufe 1, ohne Meldung
        assertEquals(1, w.stufe());
    }

    @Test
    void dateiZeigtDieStufe() throws Exception {
        Path d = ordner.resolve("hitze-stufe.txt");
        HitzeWaechter w = new HitzeWaechter(d);
        abspielen(w, 0, 70, 81);
        assertTrue(Files.readString(d).contains("stufe=2"));
        abspielen(w, 2 * S, 70);
        assertTrue(Files.readString(d).contains("stufe=0"));
    }

    @Test
    void rechenlastZaehltGleicheProgrammeZusammen() {
        var l = Rechenlast.auswerten(Map.of("python.exe", 46_500L, "HustleBot.exe", 6_750L, "svchost.exe", 300L), 15_000, 4);
        assertEquals("python.exe", l.get(0).name());
        assertEquals(310, l.get(0).prozent());
        assertEquals(45, l.get(1).prozent());
        assertEquals(2, l.size(), "unter 5 % faellt weg");
        assertEquals("python.exe 310 %, HustleBot.exe 45 %", Rechenlast.text(l));
    }
}
