package de.hustlebot.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Raeumt beim Start weg, was der Bot nicht mehr braucht (Autor 01.11.: "Du musst keine Obergrenze einfuehren. Du sollst das
 * Problem loesen. Am besten soll gar nichts wachsen."). Bis 01.11. hielt diese Klasse je Art 150 Fotos und 100 MB -- das
 * hielt das Problem nur aus. Neue Beweisfotos entstehen seither nicht mehr (siehe {@link Beleg}); hier verschwindet der Rest:
 *
 * - Fotos in logs (auch Unterordner), ausser den Ergebnissen und Sammlungen in {@link #behalten}. Eine frische Fotoserie
 *   (!fotoserie) bleibt {@value #FOTOSERIE_TAGE} Tage, logs\arena-fotos {@value #ARENA_FOTOS_TAGE} Tage. Mit beweisfotos.txt
 *   "an" bleiben die Fotos liegen -- sonst waeren sie beim naechsten Start weg, bevor jemand sie angesehen hat.
 * - Protokolle bot_*.log, aelter als {@value #PROTOKOLL_TAGE} Tage; mit protokolle-behalten.txt "an" bleiben sie (Autors
 *   Arena-Auswertung liest die alten).
 * - held-N.png in arena-aussehen: die las nie jemand.
 * - Das Aussehen von Gegnern in arena-aussehen, die {@value #AUSSEHEN_TAGE} Tage nicht mehr da waren (Stempel: info.txt, siehe
 *   {@link #aussehenAltAufraeumen}). Das gilt immer, auch mit beweisfotos.txt "an" -- es sind keine Beweisfotos.
 * - Reste des Bots im Temp-Ordner, aelter als {@value #TEMP_STUNDEN} Stunden, und nur die.
 *
 * Gesperrte Dateien sind kein Fehler -- sie kommen beim naechsten Start dran.
 */
public final class Fotobremse {

    static final int FOTOSERIE_TAGE = 3;
    static final int ARENA_FOTOS_TAGE = 14;
    static final int PROTOKOLL_TAGE = 7;
    static final int TEMP_STUNDEN = 6;
    static final String PROTOKOLLE_BEHALTEN = "protokolle-behalten.txt";
    static final int AUSSEHEN_TAGE = 30;
    /** Liegt in arena-aussehen: seit wann der Bot dort "zuletzt gesehen" vermerkt (ein Zeitpunkt, z. B. 2026-11-01T13:00:00Z). */
    static final String AUSSEHEN_STEMPEL_SEIT = "stempel-seit.txt";
    /**
     * Ordnername des eigenen Teams in arena-aussehen (= ArenaAussehen.ordner(ArenaMode.EIGEN_SCHLUESSEL), ein Test haelt das
     * fest): das sind keine Gegner, es bleibt auch nach Wochen ohne Arena.
     */
    public static final String AUSSEHEN_EIGENES = "_eigenes_team_-";

    private static final long STUNDE_MS = 3600_000L, TAG_MS = 24 * STUNDE_MS;
    private static final Pattern PROTOKOLL = Pattern.compile("bot_.*\\.log");
    private static final Pattern HELD_BILD = Pattern.compile("held-\\d+\\.png");
    /** Nur was der Bot selbst im Temp-Ordner hinterlaesst. */
    private static final Pattern TEMP_REST = Pattern.compile(
            "onnxruntime-java.*|opencv_openpnp.*|hustle-chat-.*\\.png|hustle-ocr-.*\\.txt|imageio.*\\.tmp");

    private Fotobremse() { }

    private interface Schritt {
        void machen() throws IOException;
    }

    /** Im Hintergrund aufraeumen (beim Start). */
    public static void imHintergrund() {
        Thread t = new Thread(() -> {
            // ImageIO legt beim Schreiben in den Speicher (Discord, Fehlerbericht) sonst erst eine imageio*.tmp an.
            javax.imageio.ImageIO.setUseCache(false);
            long jetzt = System.currentTimeMillis();
            Path logs = Ordner.bei("logs");
            schritt("Fotos", () -> fotosAufraeumen(logs, jetzt, Beleg.an()));
            schritt("Protokolle", () -> {
                if (new Schalterdatei(Ordner.bei(PROTOKOLLE_BEHALTEN), "Protokolle", false).lesen()) return;
                // Fehlt die Platzliste der Arena, holt sie der Bot aus den alten Protokollen nach -- also vor dem Loeschen.
                ArenaPlaetze.lesen();
                protokolleAufraeumen(logs, jetzt);
            });
            schritt("Held-Bilder", () -> heldBilderAufraeumen(Ordner.bei("arena-aussehen")));
            schritt("Aussehen", () -> aussehenAltAufraeumen(Ordner.bei("arena-aussehen"), jetzt));
            schritt("Temp", () -> tempAufraeumen(Path.of(System.getProperty("java.io.tmpdir")), jetzt));
        }, "fotobremse");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void schritt(String was, Schritt s) {
        try {
            s.machen();
        } catch (Exception e) {
            Log.debug("Fotobremse", was + " nicht aufgeraeumt: " + e.getMessage());
        }
    }

    /** Fotos in logs wegraeumen. Mit beweisfotosBleiben (beweisfotos.txt "an") gelten nur die Fristen der Fotoserie und der Arena-Fotos. */
    static void fotosAufraeumen(Path logs, long jetzt, boolean beweisfotosBleiben) throws IOException {
        if (!Files.isDirectory(logs)) return;
        List<Path> weg = new ArrayList<>();
        Files.walkFileTree(logs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                if (a.isRegularFile() && istBild(p)
                        && mussWeg(logs.relativize(p), a.lastModifiedTime().toMillis(), jetzt, beweisfotosBleiben)) weg.add(p);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException e) {
                return FileVisitResult.CONTINUE;   // gesperrt: beim naechsten Start
            }
        });
        Set<Path> ordner = new HashSet<>();
        int anzahl = 0;
        long bytes = 0;
        for (Path p : weg) {
            long g = groesse(p);
            if (!loeschen(p)) continue;
            anzahl++;
            bytes += g;
            ordner.add(p.getParent());
        }
        for (Path o : ordner) leereOrdnerWeg(o, logs);
        if (anzahl > 0) {
            Log.info("Fotobremse", Sprache.t(anzahl + " old evidence photos deleted from logs (" + bytes / (1024 * 1024) + " MB).",
                    anzahl + " alte Beweisfotos in logs geloescht (" + bytes / (1024 * 1024) + " MB)."));
        }
    }

    /** Muss dieses Foto weg? rel = Pfad unterhalb von logs. */
    static boolean mussWeg(Path rel, long geaendert, long jetzt, boolean beweisfotosBleiben) {
        if (behalten(rel)) return false;
        String erster = rel.getNameCount() > 1 ? rel.getName(0).toString() : "";
        // Autor 25.10.: die Fotoserie (!fotoserie) ist gewollt -- beim Neustart (Einspielen 3.94) loeschte die Bremse
        // ~3 000 Bilder einer laufenden Stunde. Fotoserien-Bilder bleiben 3 Tage, unabhaengig von Anzahl und Groesse.
        if (erster.equalsIgnoreCase("fotoserie")) return jetzt - geaendert > FOTOSERIE_TAGE * TAG_MS;
        if (erster.equalsIgnoreCase("arena-fotos")) return jetzt - geaendert > ARENA_FOTOS_TAGE * TAG_MS;
        return !beweisfotosBleiben;
    }

    /** Ergebnis-Fotos und Sammlungen, die die Bremse nie anfasst (Ordner "gute", Dateien "uebersicht*", Arena-Boegen, OCR-Training). */
    static boolean behalten(Path rel) {
        for (Path teil : rel) if (teil.toString().equalsIgnoreCase("gute")) return true;
        // Autor 22.10.: "noch mehr noch nicht bekannte Bilder von allen Sachen" -- die Ausschnitte fuer die Arena-Boegen
        // (Held, Schirmherrschaften, Pals) sind Sammlung, keine Beweisfotos. Begrenzt sind sie selbst (je Gegner ein Bild).
        // 01.11.: ocr-training und schmuckschrift gehoeren zu je einer Liste (labels.txt, liste.tsv), die ohne Bild wertlos wird.
        String erster = rel.getNameCount() > 1 ? rel.getName(0).toString() : "";
        if (erster.equalsIgnoreCase("arena-helden") || erster.equalsIgnoreCase("arena-bogen")
                || erster.equalsIgnoreCase("ocr-training") || erster.equalsIgnoreCase("schmuckschrift")) return true;
        return rel.getFileName() != null
                && rel.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("uebersicht");
    }

    /** bot_*.log direkt in logs, aelter als {@value #PROTOKOLL_TAGE} Tage. */
    static void protokolleAufraeumen(Path logs, long jetzt) throws IOException {
        if (!Files.isDirectory(logs)) return;
        List<Path> kandidaten;
        try (Stream<Path> s = Files.list(logs)) {
            kandidaten = s.filter(p -> PROTOKOLL.matcher(p.getFileName().toString()).matches()).toList();
        }
        int anzahl = 0;
        for (Path p : kandidaten) {
            if (Files.isRegularFile(p) && jetzt - zeit(p) > PROTOKOLL_TAGE * TAG_MS && loeschen(p)) anzahl++;
        }
        if (anzahl > 0) {
            Log.info("Fotobremse", Sprache.t(anzahl + " logs older than " + PROTOKOLL_TAGE + " days deleted.",
                    anzahl + " Protokolle, aelter als " + PROTOKOLL_TAGE + " Tage, geloescht."));
        }
    }

    /** Die Bilder held-N.png je Gegner in arena-aussehen; der Bot liest sie nicht und legt seit 01.11. keine neuen mehr an. */
    static void heldBilderAufraeumen(Path aussehen) throws IOException {
        if (!Files.isDirectory(aussehen)) return;
        List<Path> gegner;
        try (Stream<Path> s = Files.list(aussehen)) {
            gegner = s.filter(Files::isDirectory).toList();
        }
        for (Path o : gegner) {
            try (Stream<Path> s = Files.list(o)) {
                s.filter(p -> HELD_BILD.matcher(p.getFileName().toString()).matches()).toList().forEach(Fotobremse::loeschen);
            } catch (IOException | RuntimeException e) {
                // gesperrt: beim naechsten Start
            }
        }
    }

    /**
     * Autor 01.11.: Aussehen von Gegnern, die seit {@value #AUSSEHEN_TAGE} Tagen nicht mehr da waren, geht weg -- sonst waechst
     * arena-aussehen mit jedem neuen Gegner ohne Ende (je Gegner rund 0,6 MB). "Zuletzt gesehen" ist der Zeitstempel von
     * info.txt: die Arena schreibt sie bei jedem vollen Ansehen neu und fasst sie bei jedem Nachschlagen an, ohne ein Bild zu
     * schreiben (ArenaAussehen.gesehen) -- ein Gegner kann monatelang gleich aussehen und trotzdem jede Woche kommen.
     *
     * Vor dieser Fassung gab es keinen Stempel: info.txt zeigt beim Altbestand nur, wann zuletzt voll angesehen wurde, nicht ob
     * der Gegner seither kam. Darum legt der erste Lauf {@value #AUSSEHEN_STEMPEL_SEIT} an, und geloescht wird erst, wenn seit
     * diesem Zeitpunkt {@value #AUSSEHEN_TAGE} Tage um sind -- bis dahin hat jeder Gegner, der kam, einen echten Stempel. Fehlt die
     * Datei oder ist sie kaputt oder liegt in der Zukunft, beginnt die Frist neu (nie zu frueh). info.txt geht zuerst (dann gilt der
     * Gegner sofort als unbekannt); ist danach eine Datei gesperrt, bleibt sie liegen und der Ordner wird beim naechsten Start
     * fertig -- ohne info.txt zaehlt seine neueste Datei. Das eigene Team ({@link #AUSSEHEN_EIGENES}) bleibt.
     */
    public static void aussehenAltAufraeumen(Path aussehen, long jetzt) throws IOException {
        if (!Files.isDirectory(aussehen)) return;
        long seit = aussehenStempelSeit(aussehen, jetzt);
        if (jetzt - seit <= AUSSEHEN_TAGE * TAG_MS) return;
        List<Path> gegner;
        try (Stream<Path> s = Files.list(aussehen)) {
            gegner = s.filter(Files::isDirectory).toList();
        }
        int anzahl = 0, geblieben = 0;
        long bytes = 0;
        for (Path o : gegner) {
            try {
                if (o.getFileName().toString().startsWith(AUSSEHEN_EIGENES) || !aussehenZuAlt(o, jetzt)) continue;
                long g = aussehenOrdnerWeg(o);
                if (g < 0) { geblieben++; continue; }
                anzahl++;
                bytes += g;
            } catch (RuntimeException e) {
                geblieben++;   // ein Ordner, der Aerger macht, haelt die anderen nicht auf
            }
        }
        if (anzahl > 0) {
            Log.info("Fotobremse", Sprache.t("Looks of " + anzahl + " opponents not seen for " + AUSSEHEN_TAGE + " days deleted ("
                            + bytes / (1024 * 1024) + " MB).",
                    "Aussehen von " + anzahl + " Gegnern, seit " + AUSSEHEN_TAGE + " Tagen nicht mehr da, geloescht ("
                            + bytes / (1024 * 1024) + " MB)."));
        }
        if (geblieben > 0) Log.debug("Fotobremse", geblieben + " alte Aussehen-Ordner gesperrt, beim naechsten Start.");
    }

    /** Seit wann es "zuletzt gesehen" gibt (stempel-seit.txt). Fehlt die Datei, ist sie kaputt oder liegt in der Zukunft: jetzt, und neu angelegt. */
    static long aussehenStempelSeit(Path aussehen, long jetzt) {
        Path datei = aussehen.resolve(AUSSEHEN_STEMPEL_SEIT);
        try {
            long seit = Instant.parse(Files.readString(datei, StandardCharsets.UTF_8).trim()).toEpochMilli();
            if (seit <= jetzt) return seit;
        } catch (IOException | RuntimeException e) {
            // fehlt, gesperrt oder kaputt: die Frist beginnt neu
        }
        try {
            Files.writeString(datei, Instant.ofEpochMilli(jetzt) + "\n", StandardCharsets.UTF_8);
            Log.debug("Fotobremse", "Aussehen: Stempel angelegt, frueheste Loeschung in " + AUSSEHEN_TAGE + " Tagen.");
        } catch (IOException | RuntimeException e) {
            // nicht schreibbar: bleibt bei "jetzt", an diesem Start wird nichts geloescht
        }
        return jetzt;
    }

    /** War der Gegner seit {@value #AUSSEHEN_TAGE} Tagen nicht mehr da? Nicht lesbar zaehlt nie als alt. */
    private static boolean aussehenZuAlt(Path o, long jetzt) {
        return jetzt - aussehenGesehen(o) > AUSSEHEN_TAGE * TAG_MS;
    }

    /**
     * Der Stempel "zuletzt gesehen": info.txt. Ohne info.txt (halb abgelegt, oder beim Loeschen haengen geblieben) die neueste
     * Datei im Ordner -- nicht der Ordner selbst, den macht jedes Loeschen frisch. Long.MAX_VALUE = nicht lesbar.
     */
    static long aussehenGesehen(Path o) {
        Path info = o.resolve("info.txt");
        if (Files.exists(info)) return zeit(info);
        try (Stream<Path> s = Files.list(o)) {
            long neueste = s.mapToLong(Fotobremse::zeit).max().orElse(-1);
            return neueste >= 0 ? neueste : zeit(o);
        } catch (IOException | RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Einen Gegner-Ordner loeschen, info.txt zuerst (dann gilt er sofort als unbekannt). Bytes, oder -1 wenn nicht alles wegging. */
    private static long aussehenOrdnerWeg(Path o) {
        List<Path> dateien;
        try (Stream<Path> s = Files.list(o)) {
            dateien = s.sorted(Comparator.comparing((Path p) -> !p.getFileName().toString().equals("info.txt"))).toList();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        long bytes = 0;
        boolean ganz = true;
        for (Path p : dateien) {
            long g = groesse(p);
            if (loeschen(p)) bytes += g;
            else ganz = false;
        }
        return ganz && loeschen(o) ? bytes : -1;
    }

    /** Reste des Bots im Temp-Ordner, aelter als {@value #TEMP_STUNDEN} Stunden. Alles andere bleibt unangetastet. */
    static void tempAufraeumen(Path temp, long jetzt) throws IOException {
        if (!Files.isDirectory(temp)) return;
        List<Path> kandidaten;
        try (Stream<Path> s = Files.list(temp)) {
            kandidaten = s.filter(p -> istTempRest(p.getFileName().toString())).toList();
        }
        int anzahl = 0;
        for (Path p : kandidaten) {
            if (jetzt - zeit(p) <= TEMP_STUNDEN * STUNDE_MS) continue;
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? ordnerWeg(p) : loeschen(p)) anzahl++;
        }
        if (anzahl > 0) {
            Log.debug("Fotobremse", anzahl + " alte Reste im Temp-Ordner geloescht.");
        }
    }

    static boolean istTempRest(String name) {
        return TEMP_REST.matcher(name).matches();
    }

    /**
     * Einen Temp-Ordner ganz oder gar nicht: laeuft noch ein Bot aus ihm (DLL geladen), verweigert Windows schon das
     * Umbenennen -- dann bleibt alles liegen, statt dem laufenden Bot die uebrigen Dateien wegzunehmen.
     */
    private static boolean ordnerWeg(Path ordner) {
        Path ziel = ordner;
        try {
            String name = ordner.getFileName().toString();
            if (!name.endsWith(".weg")) {
                ziel = ordner.resolveSibling(name + ".weg");
                Files.move(ordner, ziel);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        List<Path> alles;
        try (Stream<Path> s = Files.walk(ziel)) {
            alles = s.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        boolean ganz = true;
        for (Path p : alles) ganz &= loeschen(p);
        return ganz;
    }

    /** Vom Ordner aufwaerts bis unter logs: leere Ordner entfernen, damit keine Geister-Ordner stehen bleiben. */
    private static void leereOrdnerWeg(Path ordner, Path logs) {
        Path o = ordner;
        while (o != null && !o.equals(logs) && o.startsWith(logs)) {
            try {
                Files.delete(o);
            } catch (IOException | RuntimeException e) {
                return;   // nicht leer oder in Benutzung
            }
            o = o.getParent();
        }
    }

    private static boolean loeschen(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;   // gerade in Benutzung -- beim naechsten Start
        }
    }

    private static boolean istBild(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg");
    }

    private static long zeit(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;   // unbekannt: gilt nicht als alt
        }
    }

    private static long groesse(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
