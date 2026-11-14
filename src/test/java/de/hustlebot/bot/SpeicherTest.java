package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor, 12.10. (WICHTIGSTE SACHE): jede Einstellung muss nach Schliessen und Neu-Oeffnen von Programm und Bot
 * noch genauso gelten. Je Einstellung: Wert setzen -> Bot "neu starten" (frischer Klassenlader, alle statischen
 * Werte weg, alles wird wieder aus den Dateien gelesen) -> Wert lesen.
 *
 * Laeuft in einem eigenen Ordner (target/speicher-pruefung, siehe pom.xml) -- die echten Einstellungs-Dateien
 * des Bots werden nie angefasst.
 */
class SpeicherTest {

    /** Laedt die Klassen des Bots neu (eigene statische Werte), Bibliotheken kommen vom normalen Lader. */
    private static final class Neustart extends URLClassLoader {
        Neustart(URL[] urls, ClassLoader eltern) {
            super(urls, eltern);
        }

        @Override
        protected Class<?> loadClass(String name, boolean aufloesen) throws ClassNotFoundException {
            if (!name.startsWith("de.hustlebot.")) return super.loadClass(name, aufloesen);
            synchronized (getClassLoadingLock(name)) {
                Class<?> k = findLoadedClass(name);
                if (k == null) k = findClass(name);
                if (aufloesen) resolveClass(k);
                return k;
            }
        }
    }

    private static Object schritt(String methode, String name) throws Exception {
        URL klassen = de.hustlebot.core.Dauerwerte.class.getProtectionDomain().getCodeSource().getLocation();
        URL testKlassen = SpeicherSchritte.class.getProtectionDomain().getCodeSource().getLocation();
        try (Neustart lader = new Neustart(new URL[]{klassen, testKlassen}, SpeicherTest.class.getClassLoader())) {
            Class<?> s = lader.loadClass(SpeicherSchritte.class.getName());
            return s.getMethod(methode, String.class).invoke(null, name);
        }
    }

    @Test
    void laeuftImEigenenOrdner() {
        // Sonst wuerde der Test Autors Einstellungen ueberschreiben.
        assertTrue(Path.of("").toAbsolutePath().toString().replace('\\', '/').endsWith("target/speicher-pruefung"),
                "SpeicherTest muss in target/speicher-pruefung laufen (pom.xml), nicht in " + Path.of("").toAbsolutePath());
        assertFalse(Files.exists(Path.of("pom.xml")));
    }

    @Test
    void neustartVergisstWasNurImSpeicherLiegt() throws Exception {
        // Gegenprobe: sonst wuerde der Neustart nichts beweisen.
        assertEquals("DE", schritt("setzen", "nur-im-speicher"));
        assertEquals("EN", schritt("lesen", "nur-im-speicher"));
    }

    @Test
    void jedeEinstellungUeberlebtEinenNeustart() throws Exception {
        Path ordner = Path.of("").toAbsolutePath();
        assertTrue(ordner.toString().replace('\\', '/').endsWith("target/speicher-pruefung"));
        // Jeder Lauf beginnt leer -- wie ein frisch ausgepackter Bot.
        try (var alt = Files.walk(ordner)) {
            for (Path p : alt.sorted(java.util.Comparator.reverseOrder()).toList()) if (!p.equals(ordner)) Files.delete(p);
        }
        List<String> fehler = new ArrayList<>();
        for (String name : SpeicherSchritte.NAMEN) {
            try {
                Object vorher = schritt("lesen", name);       // frisch gestartet, wie es gerade steht
                Object gesetzt = schritt("setzen", name);     // Knopf gedrueckt / Befehl geschickt
                Object danach = schritt("lesen", name);       // Bot neu gestartet
                if (String.valueOf(vorher).equals(String.valueOf(gesetzt))) {
                    fehler.add(name + ": Setzen hat nichts geaendert (" + vorher + ")");
                } else if (!String.valueOf(gesetzt).equals(String.valueOf(danach))) {
                    fehler.add(name + ": gesetzt " + gesetzt + ", nach dem Neustart " + danach);
                }
            } catch (Exception e) {
                Throwable t = e.getCause() != null ? e.getCause() : e;
                fehler.add(name + ": " + t);
            }
        }
        assertEquals(List.of(), fehler);
    }
}
