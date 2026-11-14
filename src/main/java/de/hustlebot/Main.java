package de.hustlebot;

import de.hustlebot.core.Sprache;

/**
 * Der Einstieg. Die eigentliche Startlogik liegt in Kern.
 *
 * Name und main bleiben: HustleBot.cfg nennt diese Klasse.
 */
public final class Main {
    // Windows-Skalierung (16.10.): siehe Kern. Muss vor dem ersten Grafik-Zugriff laufen -- auch das Einrichtungsfenster
    // im offenen Startteil braucht es, deshalb steht es hier noch einmal.
    static {
        System.setProperty("sun.java2d.uiScale", "1");
    }


    public static final String FASSUNG = "3.142";

    /**
     * Wann diese Jar gebaut wurde ("21.10. 23:05"), "?" wenn unbekannt. Autor 21.10.: Ben und Mia liefen tagelang auf
     * einem alten Bau -- in jedem Bericht stand nur "3.3", der Stand war nicht zu sehen. Gelesen wird die Zeit, die der
     * Bau in die Jar geschrieben hat (Eintrag dieser Klasse), sonst die Zeit der Datei.
     */
    public static String bau() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            long zeit = 0;
            if (java.nio.file.Files.isRegularFile(p)) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(p.toFile())) {
                    java.util.jar.JarEntry e = jar.getJarEntry(Main.class.getName().replace('.', '/') + ".class");
                    if (e != null) zeit = e.getTime();
                }
            }
            if (zeit <= 0) zeit = java.nio.file.Files.getLastModifiedTime(p).toMillis();
            return java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")
                    .format(java.time.Instant.ofEpochMilli(zeit).atZone(java.time.ZoneId.systemDefault()));
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Sprache aus sprache.txt ("en" oder "de"). Seit 2.1 umschaltbar (Autor,
     * 23.09.: ein Freund aus Grossbritannien braucht alles auf Englisch);
     * !english / !deutsch, Settings.exe und das Einrichtungs-Fenster schreiben
     * die Datei. Seit 3.1 (Autor 11.10.) ist ENGLISCH der Standard: ohne Datei
     * Englisch, Deutsch nur, wenn "de" darin steht. Die Logik liegt in core/Sprache.
     */
    public static Sprache.Lang spracheAusDatei() {
        return Sprache.laden();
    }

    public static void main(String[] args) {
        Kern.main(args);
    }
}
