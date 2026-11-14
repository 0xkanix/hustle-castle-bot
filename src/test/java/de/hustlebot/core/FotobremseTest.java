package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 01.11.: "Am besten soll gar nichts wachsen" -- was die Bremse beim Start wegraeumt und was sie liegen laesst. */
class FotobremseTest {

    private static final long STUNDE = 3600_000L, TAG = 24 * STUNDE;

    @TempDir
    Path tmp;

    private final long jetzt = System.currentTimeMillis();

    private Path datei(Path wo, long alterMs) throws Exception {
        Files.createDirectories(wo.getParent());
        Files.writeString(wo, "x");
        Files.setLastModifiedTime(wo, FileTime.fromMillis(jetzt - alterMs));
        return wo;
    }

    private Path ordner(Path wo, long alterMs) throws Exception {
        Files.createDirectories(wo);
        Files.setLastModifiedTime(wo, FileTime.fromMillis(jetzt - alterMs));
        return wo;
    }

    @Test
    void beweisfotosGehenAuchInUnterordnern() throws Exception {
        Path logs = tmp.resolve("logs");
        Path a = datei(logs.resolve("event-stab-120000.png"), STUNDE);
        Path b = datei(logs.resolve("etagen").resolve("1030-120000_x.jpg"), 0);
        Path c = datei(logs.resolve("fotoserie-1917").resolve("f_001.png"), STUNDE);
        Fotobremse.fotosAufraeumen(logs, jetzt, false);
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(c));
        assertFalse(Files.exists(logs.resolve("etagen")), "leere Ordner sollen nicht stehen bleiben");
        assertTrue(Files.isDirectory(logs));
    }

    @Test
    void ergebnisseUndSammlungenBleiben() throws Exception {
        Path logs = tmp.resolve("logs");
        long alt = 100 * TAG;
        Path[] bleiben = {
                datei(logs.resolve("stigmas").resolve("gute").resolve("a.png"), alt),
                datei(logs.resolve("uebersicht-mind.png"), alt),
                datei(logs.resolve("arena-helden").resolve("X-haupt.png"), alt),
                datei(logs.resolve("arena-bogen").resolve("pals").resolve("X.png"), alt),
                datei(logs.resolve("ocr-training").resolve("000001_ab.png"), alt),
                datei(logs.resolve("schmuckschrift").resolve("ab.png"), alt),
        };
        Fotobremse.fotosAufraeumen(logs, jetzt, false);
        for (Path p : bleiben) assertTrue(Files.exists(p), p.toString());
    }

    @Test
    void nurBilderWerdenAngefasst() throws Exception {
        Path logs = tmp.resolve("logs");
        Path text = datei(logs.resolve("arena-schlecht").resolve("t1.txt"), 100 * TAG);
        Path marke = datei(logs.resolve("titan-rangliste.txt"), 100 * TAG);
        Fotobremse.fotosAufraeumen(logs, jetzt, false);
        assertTrue(Files.exists(text));
        assertTrue(Files.exists(marke));
    }

    @Test
    void fotoserieBleibtDreiTageArenaFotosVierzehn() throws Exception {
        Path logs = tmp.resolve("logs");
        Path serieFrisch = datei(logs.resolve("fotoserie").resolve("f_001.png"), 2 * TAG);
        Path serieAlt = datei(logs.resolve("fotoserie").resolve("f_002.png"), 4 * TAG);
        Path arenaFrisch = datei(logs.resolve("arena-fotos").resolve("2026-10-25").resolve("a.jpg"), 13 * TAG);
        Path arenaAlt = datei(logs.resolve("arena-fotos").resolve("2026-10-01").resolve("b.png"), 15 * TAG);
        Fotobremse.fotosAufraeumen(logs, jetzt, false);
        assertTrue(Files.exists(serieFrisch));
        assertFalse(Files.exists(serieAlt));
        assertTrue(Files.exists(arenaFrisch));
        assertFalse(Files.exists(arenaAlt));
        assertFalse(Files.exists(logs.resolve("arena-fotos").resolve("2026-10-01")));
    }

    @Test
    void mitBeweisfotosBleibenSieLiegen() throws Exception {
        Path logs = tmp.resolve("logs");
        Path foto = datei(logs.resolve("team").resolve("a.png"), 100 * TAG);
        Path serieAlt = datei(logs.resolve("fotoserie").resolve("f.png"), 4 * TAG);
        Fotobremse.fotosAufraeumen(logs, jetzt, true);
        assertTrue(Files.exists(foto), "sonst waere das Foto beim naechsten Neustart weg, bevor jemand es sieht");
        assertFalse(Files.exists(serieAlt));
    }

    @Test
    void protokolleNachSiebenTagen() throws Exception {
        Path logs = tmp.resolve("logs");
        Path alt = datei(logs.resolve("bot_2026-10-01_12-00-00.log"), 8 * TAG);
        Path neu = datei(logs.resolve("bot_2026-10-30_12-00-00.log"), 6 * TAG);
        Path wache = datei(logs.resolve("fernwache.log"), 100 * TAG);
        Path bericht = datei(logs.resolve("bericht_2026-10-01.txt"), 100 * TAG);
        Path tiefer = datei(logs.resolve("berichte").resolve("bot_alt.log"), 100 * TAG);
        Fotobremse.protokolleAufraeumen(logs, jetzt);
        assertFalse(Files.exists(alt));
        assertTrue(Files.exists(neu));
        assertTrue(Files.exists(wache));
        assertTrue(Files.exists(bericht));
        assertTrue(Files.exists(tiefer), "nur direkt in logs");
    }

    @Test
    void heldBilderGehenDerRestDesAussehensBleibt() throws Exception {
        Path o = tmp.resolve("arena-aussehen").resolve("gegner-1a");
        Path held = datei(o.resolve("held-0.png"), 0);
        Path text = datei(o.resolve("held.txt"), 0);
        Path feld = datei(o.resolve("e1-0.png"), 0);
        Path figur = datei(o.resolve("e1-m4.png"), 0);
        Fotobremse.heldBilderAufraeumen(tmp.resolve("arena-aussehen"));
        assertFalse(Files.exists(held));
        assertTrue(Files.exists(text));
        assertTrue(Files.exists(feld));
        assertTrue(Files.exists(figur));
    }

    @Test
    void tempNurBotResteUndNurAlte() throws Exception {
        Path temp = tmp.resolve("temp");
        long alt = 7 * STUNDE, frisch = 5 * STUNDE;
        Path[] weg = {
                datei(temp.resolve("hustle-chat-1.png"), alt),
                datei(temp.resolve("hustle-ocr-1.txt"), alt),
                datei(temp.resolve("imageio123.tmp"), alt),
        };
        Path[] bleiben = {
                datei(temp.resolve("hustle-chat-2.png"), frisch),
                datei(temp.resolve("imageio456.tmp"), frisch),
                // Namen, die nur aehnlich sind, und fremde Dateien
                datei(temp.resolve("hustle-chat-1.txt"), alt),
                datei(temp.resolve("imageio.png"), alt),
                datei(temp.resolve("fremd.png"), alt),
                datei(temp.resolve("hustlebot-paddleocr").resolve("rec.onnx"), alt),
                datei(temp.resolve("mein-opencv_openpnp").resolve("a.dll"), alt),
        };
        // Ordner zuletzt alt stellen: das Anlegen der Datei darin hat sie frisch gemacht.
        datei(temp.resolve("opencv_openpnp77").resolve("x.dll"), alt);
        datei(temp.resolve("onnxruntime-java88").resolve("sub").resolve("y.dll"), alt);
        datei(temp.resolve("onnxruntime-java99").resolve("z.dll"), alt);
        ordner(temp.resolve("opencv_openpnp77"), alt);
        ordner(temp.resolve("onnxruntime-java88"), alt);
        ordner(temp.resolve("onnxruntime-java99"), frisch);
        ordner(temp.resolve("hustlebot-paddleocr"), alt);
        ordner(temp.resolve("mein-opencv_openpnp"), alt);

        Fotobremse.tempAufraeumen(temp, jetzt);

        for (Path p : weg) assertFalse(Files.exists(p), p.toString());
        for (Path p : bleiben) assertTrue(Files.exists(p), p.toString());
        assertFalse(Files.exists(temp.resolve("opencv_openpnp77")));
        assertFalse(Files.exists(temp.resolve("onnxruntime-java88")));
        assertFalse(Files.exists(temp.resolve("onnxruntime-java88.weg")), "auch der Zwischenname darf nicht liegen bleiben");
        assertTrue(Files.exists(temp.resolve("onnxruntime-java99").resolve("z.dll")), "frisch: bleibt");
    }

    @Test
    void ordnerEinesLaufendenBotsBleibtGanz() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "die Sperre gibt es nur unter Windows");
        Path temp = tmp.resolve("temp");
        Path dll = datei(temp.resolve("opencv_openpnp55").resolve("opencv.dll"), 7 * STUNDE);
        Path andere = datei(temp.resolve("opencv_openpnp55").resolve("andere.dll"), 7 * STUNDE);
        ordner(temp.resolve("opencv_openpnp55"), 7 * STUNDE);
        try (FileInputStream offen = new FileInputStream(dll.toFile())) {   // wie eine geladene DLL: gesperrt
            assertEquals('x', offen.read());
            Fotobremse.tempAufraeumen(temp, jetzt);
            assertTrue(Files.exists(dll));
            assertTrue(Files.exists(andere), "dem laufenden Bot darf nichts aus dem Ordner verschwinden");
        }
    }

    @Test
    void tempNamen() {
        assertTrue(Fotobremse.istTempRest("onnxruntime-java1234567"));
        assertTrue(Fotobremse.istTempRest("opencv_openpnp1234567"));
        assertTrue(Fotobremse.istTempRest("hustle-chat-99.png"));
        assertTrue(Fotobremse.istTempRest("hustle-ocr-99.txt"));
        assertTrue(Fotobremse.istTempRest("imageio99.tmp"));
        assertFalse(Fotobremse.istTempRest("hustlebot-paddleocr"));
        assertFalse(Fotobremse.istTempRest("imageio99.png"));
        assertFalse(Fotobremse.istTempRest("x-onnxruntime-java1"));
    }

    // ---- Aussehen von Gegnern, die 30 Tage nicht mehr da waren (Autor 01.11.) ----

    /** Ein Gegner in arena-aussehen: info.txt (der Stempel "zuletzt gesehen") und zwei Bilder, alles gleich alt. */
    private Path gegner(Path aussehen, String name, long gesehenVorMs) throws Exception {
        Path o = aussehen.resolve(name);
        datei(o.resolve("e1-0.png"), gesehenVorMs);
        datei(o.resolve("e1-m4.png"), gesehenVorMs);
        datei(o.resolve("info.txt"), gesehenVorMs);
        return o;
    }

    private void stempelSeit(Path aussehen, long vorMs) throws Exception {
        Files.createDirectories(aussehen);
        Files.writeString(aussehen.resolve(Fotobremse.AUSSEHEN_STEMPEL_SEIT), Instant.ofEpochMilli(jetzt - vorMs) + "\n");
    }

    private boolean ganzDa(Path gegner) {
        return Files.exists(gegner.resolve("info.txt")) && Files.exists(gegner.resolve("e1-0.png")) && Files.exists(gegner.resolve("e1-m4.png"));
    }

    @Test
    void aussehenGehtErstNachDreissigTagenOhneBesuch() throws Exception {
        Path aussehen = tmp.resolve("arena-aussehen");
        stempelSeit(aussehen, 40 * TAG);
        Path alt = gegner(aussehen, "alt-1", 31 * TAG);
        Path grenzeUeber = gegner(aussehen, "grenze-ueber-2", 30 * TAG + STUNDE);
        Path grenzeUnter = gegner(aussehen, "grenze-unter-3", 30 * TAG - STUNDE);
        Path besucht = gegner(aussehen, "besucht-4", 2 * TAG);
        Path heute = gegner(aussehen, "heute-5", 0);
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertFalse(Files.exists(alt), "31 Tage nicht gesehen: samt Ordner weg");
        assertFalse(Files.exists(grenzeUeber));
        assertTrue(ganzDa(grenzeUnter), "knapp unter 30 Tagen bleibt");
        assertTrue(ganzDa(besucht));
        assertTrue(ganzDa(heute));
        assertTrue(Files.exists(aussehen.resolve(Fotobremse.AUSSEHEN_STEMPEL_SEIT)), "die Stempel-Datei ist kein Gegner");
    }

    @Test
    void frischerStempelRettetEinenAltenGegner() throws Exception {
        // Die Bilder sind uralt (nur beim vollen Ansehen neu geschrieben), aber der Gegner kam gestern: info.txt wurde angefasst.
        Path aussehen = tmp.resolve("arena-aussehen");
        stempelSeit(aussehen, 40 * TAG);
        Path o = gegner(aussehen, "kommt-jede-woche", 200 * TAG);
        Files.setLastModifiedTime(o.resolve("info.txt"), FileTime.fromMillis(jetzt - TAG));
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(o));
    }

    @Test
    void ersterLaufLoeschtNichtsUndSpaeterNurWasNichtBesuchtWurde() throws Exception {
        Path aussehen = tmp.resolve("arena-aussehen");
        Path uralt = gegner(aussehen, "uralt-1", 400 * TAG);
        Path alt = gegner(aussehen, "alt-2", 100 * TAG);
        Path kommt = gegner(aussehen, "kommt-3", 100 * TAG);
        // Ohne Stempel-Datei sieht jeder Altbestand alt aus -- aber nur, weil nie gestempelt wurde.
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(uralt));
        assertTrue(ganzDa(alt));
        assertTrue(ganzDa(kommt));
        Path stempel = aussehen.resolve(Fotobremse.AUSSEHEN_STEMPEL_SEIT);
        assertTrue(Files.exists(stempel), "der erste Lauf legt den Startpunkt fest");
        assertEquals(jetzt, Instant.parse(Files.readString(stempel).trim()).toEpochMilli(), 1000.0);
        // Spaeter laeuft die Bremse wieder: erst nach 30 Tagen darf etwas gehen.
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt + 29 * TAG);
        assertTrue(ganzDa(uralt), "29 Tage nach dem ersten Stempel: noch nichts");
        assertTrue(ganzDa(alt));
        Files.setLastModifiedTime(kommt.resolve("info.txt"), FileTime.fromMillis(jetzt + 20 * TAG));   // kam am Tag 20
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt + 31 * TAG);
        assertFalse(Files.exists(uralt));
        assertFalse(Files.exists(alt));
        assertTrue(ganzDa(kommt), "der Gegner, der zwischendurch kam, bleibt");
    }

    @Test
    void stempelUnterDreissigTagenLoeschtNichts() throws Exception {
        Path aussehen = tmp.resolve("arena-aussehen");
        stempelSeit(aussehen, 10 * TAG);
        Path alt = gegner(aussehen, "alt-1", 100 * TAG);
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(alt));
    }

    @Test
    void kaputterOderZukuenftigerStempelBeginntDieFristNeu() throws Exception {
        Path aussehen = tmp.resolve("arena-aussehen");
        Path alt = gegner(aussehen, "alt-1", 100 * TAG);
        Path stempel = aussehen.resolve(Fotobremse.AUSSEHEN_STEMPEL_SEIT);
        Files.writeString(stempel, "kein Zeitpunkt\n");
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(alt), "kaputt: nie zu frueh loeschen");
        assertEquals(jetzt, Instant.parse(Files.readString(stempel).trim()).toEpochMilli(), 1000.0, "und neu angelegt");
        Files.writeString(stempel, Instant.ofEpochMilli(jetzt + 5 * TAG) + "\n");
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(alt), "Zukunft (falsche Uhr): auch nicht loeschen");
        assertEquals(jetzt, Instant.parse(Files.readString(stempel).trim()).toEpochMilli(), 1000.0, "die Zukunft wird nicht mitgeschleppt");
    }

    @Test
    void eigenesTeamBleibtAuchNachWochenOhneArena() throws Exception {
        Path aussehen = tmp.resolve("arena-aussehen");
        stempelSeit(aussehen, 40 * TAG);
        Path ich = gegner(aussehen, Fotobremse.AUSSEHEN_EIGENES + "1a2b3c", 100 * TAG);
        Path anderer = gegner(aussehen, "eigenes-team-fremd", 100 * TAG);
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(ich));
        assertFalse(Files.exists(anderer));
    }

    @Test
    void ordnerOhneInfoGiltNachSeinerNeuestenDatei() throws Exception {
        // Halb abgelegt, oder ein frueherer Lauf kam an einer gesperrten Datei nicht weiter.
        Path aussehen = tmp.resolve("arena-aussehen");
        stempelSeit(aussehen, 40 * TAG);
        Path alt = datei(aussehen.resolve("halb-alt-1").resolve("e1-0.png"), 50 * TAG).getParent();
        datei(alt.resolve("e1-1.png"), 60 * TAG);
        ordner(alt, 0);   // den Ordner selbst macht jedes Loeschen frisch -- er zaehlt nicht
        Path frisch = datei(aussehen.resolve("halb-frisch-2").resolve("e1-0.png"), 50 * TAG).getParent();
        datei(frisch.resolve("e1-1.png"), 0);
        Path leer = ordner(aussehen.resolve("leer-alt-3"), 50 * TAG);
        Path leerNeu = ordner(aussehen.resolve("leer-neu-4"), 0);
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertFalse(Files.exists(alt));
        assertTrue(Files.exists(frisch.resolve("e1-0.png")), "eine frische Datei hat der Ordner gerade bekommen");
        assertFalse(Files.exists(leer));
        assertTrue(Files.exists(leerNeu));
    }

    @Test
    void gesperrteDateiHaeltDieAnderenNichtAufUndWirdSpaeterFertig() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "die Sperre gibt es nur unter Windows");
        Path aussehen = tmp.resolve("arena-aussehen");
        stempelSeit(aussehen, 40 * TAG);
        Path gesperrt = gegner(aussehen, "gesperrt-1", 50 * TAG);
        Path anderer = gegner(aussehen, "anderer-2", 50 * TAG);
        try (FileInputStream offen = new FileInputStream(gesperrt.resolve("e1-0.png").toFile())) {
            assertEquals('x', offen.read());
            Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);   // wirft nichts
            assertFalse(Files.exists(anderer), "der zweite Ordner geht trotzdem");
            assertTrue(Files.exists(gesperrt.resolve("e1-0.png")));
            assertFalse(Files.exists(gesperrt.resolve("info.txt")), "info.txt geht zuerst: der Gegner gilt sofort als unbekannt");
        }
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);   // naechster Start: nichts mehr gesperrt
        assertFalse(Files.exists(gesperrt));
    }

    @Test
    void unlesbarerStempelUndFehlenderOrdnerBrechenNichtsAb() throws Exception {
        Fotobremse.aussehenAltAufraeumen(tmp.resolve("gibt-es-nicht"), jetzt);   // wirft nichts
        assertFalse(Files.exists(tmp.resolve("gibt-es-nicht")), "und legt auch nichts an");
        Path datei = datei(tmp.resolve("keine-ordner"), 0);
        Fotobremse.aussehenAltAufraeumen(datei, jetzt);
        // Die Stempel-Datei ist ein Ordner (nicht lesbar, nicht schreibbar): nichts wird geloescht, nichts bricht ab.
        Path aussehen = tmp.resolve("arena-aussehen");
        Path alt = gegner(aussehen, "alt-1", 100 * TAG);
        Files.createDirectories(aussehen.resolve(Fotobremse.AUSSEHEN_STEMPEL_SEIT));
        Fotobremse.aussehenAltAufraeumen(aussehen, jetzt);
        assertTrue(ganzDa(alt));
    }
}
