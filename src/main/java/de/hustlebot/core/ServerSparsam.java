package de.hustlebot.core;

/**
 * Autor 30.10.: "Kannst du den Bot auf dem Server so prozess-sparend wie moeglich bauen?" Schalter server-sparsam.txt im Bot-Ordner, "an"
 * oder "aus", Standard aus. Liegt nur auf dem Server: kein sichtbares Fenster und keine Fenster-Zeitgeber, keine Notfall-Wache als zweiter
 * Prozess, keine Maus- und Tastenwache, keine Abfrage des Hardware-Monitors. Die Befehlsdatei befehl.txt laeuft weiter.
 */
public final class ServerSparsam {

    private ServerSparsam() {}

    private static final boolean AN = new Schalterdatei(Ordner.bei("server-sparsam.txt"), "Server-sparsam", false).lesen();

    /** Gelesen einmal beim Start. */
    public static boolean an() {
        return AN;
    }

    /**
     * Autor 30.10.: "nicht so viele Bilder laden". Der Server spielt nur Clankrieg; diese Ordner gehoeren anderen Modi, und keine Klasse auf dem
     * Weg des Clankriegs nennt eines ihrer Bilder (148 von 817, nachgeprueft; gelernt/team bleibt, 4 seiner Bilder werden genannt). Sie
     * bleiben im Ordner, nur nicht im Speicher. Das spart Startzeit und Speicher; gegen die Prozessorlast hilft es kaum, dort rechnet zu 75 %
     * das Spiel.
     */
    private static final java.util.List<String> NICHT_LADEN =
            java.util.List.of("gelernt/titanen/", "gelernt/portal/", "gelernt/stigma/");

    /** Soll der Bot dieses Bild laden? */
    public static boolean bildLaden(java.nio.file.Path bild) {
        return !AN || !nichtNoetig(bild.toString());
    }

    static boolean nichtNoetig(String pfad) {
        String p = pfad.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        for (String ordner : NICHT_LADEN) {
            if (p.startsWith(ordner) || p.contains("/" + ordner)) return true;
        }
        return false;
    }
}
