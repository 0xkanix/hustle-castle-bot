package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Findet heraus, wo der Bot eigentlich liegt.
 *
 * DAS PROBLEM. Klickt man eine .exe doppelt an, ist das Arbeitsverzeichnis
 * nicht ihr eigener Ordner, sondern das, wo Windows gerade steht. Startet
 * jemand den Bot ueber eine Verknuepfung auf dem Desktop, sucht er
 * config.properties dann auf dem Desktop -- und findet nichts.
 *
 * Genau das ist beim ersten Versuch passiert: "config.properties not found",
 * obwohl sie direkt neben der .exe lag.
 *
 * DIE LOESUNG. jpackage legt beim Start eine Angabe ab, wo die .exe liegt.
 * Daraus ergibt sich der richtige Ordner. Laeuft der Bot dagegen ganz normal
 * aus der .jar, gilt weiterhin das Arbeitsverzeichnis -- dann stimmt es
 * ohnehin.
 *
 * Zusaetzlich wird user.dir umgesetzt. Das faengt all die Dateien mit, die
 * anderswo im Programm ohne Pfad angelegt werden: kriegsplan-an.txt,
 * laufzeiten.txt, lizenz.dat.
 */
public final class Ordner {

    private static Path basis = null;

    private Ordner() {
    }

    /** Der Ordner, in dem der Bot liegt. */
    public static synchronized Path basis() {
        if (basis != null) return basis;

        basis = ermitteln();

        // Damit auch alles greift, was relative Pfade ohne Umweg benutzt.
        System.setProperty("user.dir", basis.toString());

        return basis;
    }

    /** Kurzform fuer basis().resolve(name). */
    public static Path bei(String name) {
        return basis().resolve(name);
    }

    private static Path ermitteln() {
        // jpackage sagt uns, wo die .exe liegt.
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null && !exe.isBlank()) {
            try {
                Path p = Path.of(exe).toAbsolutePath().getParent();
                if (p != null && Files.isDirectory(p)) return p;
            } catch (Exception ignored) {
                // dann eben das Arbeitsverzeichnis
            }
        }
        return Path.of("").toAbsolutePath();
    }
}
