package de.hustlebot.bot;

import de.hustlebot.core.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Aus jedem Arena-Kampf lernen (Autor, 08.10.: "die ganzen Verbesserungen, die du siehst,
 * kannst du bitte alle umsetzen"). Je Kampf eine Zeile in arena-lernen.txt: was der Bot vorher
 * geschaetzt hat (Punkte, Messlatte, Abstand, Siegchance) und wie es ausging (Sieg, Dauer,
 * Arena-Punkte). Ab etwa 100 Zeilen laesst sich daraus die Chance-Kurve (ArenaMode.
 * chanceNachAbstand) aus echten Zahlen nachstellen statt aus Schaetzungen.
 *
 * Nur schreiben, nie lesen -- der Bot aendert sein Verhalten daran (noch) nicht selbst.
 */
final class ArenaLernen {
    private ArenaLernen() {}

    static final Path DATEI = Path.of("arena-lernen.txt");
    private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String KOPF = "# Jeder Arena-Kampf des Bots: Schaetzung vorher, Ergebnis nachher (Tab-getrennt).\n"
            + "# Zeit\tGegner\tClan\tSeineKraft\tMeineKraft\tPunkte\tMesslatte\tAbstand\tChance%\tTop3\tErgebnis\tDauer_s\tArenaPunkte\tRunde\tLohn\tRest_s\tPlatz\tStaerksteEinheit\tBauarbeiter\tSchnittKaempfer\n";

    /**
     * Haengt einen Kampf an. Unbekannte Zahlen kommen als -1 (Abstand/Punkte: leer).
     */
    static synchronized void eintragen(String gegner, String clan, long seineKraft, long meineKraft,
                                       Integer punkte, int messlatte, double chance, boolean top3,
                                       boolean sieg, long dauer, long arenaPunkte, int runde,
                                       long lohn, long restSekunden, int pyramidenPlatz, long[] einheiten) {
        try {
            if (!Files.exists(DATEI)) Files.writeString(DATEI, KOPF, StandardCharsets.UTF_8);
            String abstand = punkte == null || messlatte == Integer.MIN_VALUE ? "" : String.valueOf(punkte - messlatte);
            String zeile = String.join("\t",
                    LocalDateTime.now().format(STEMPEL),
                    sauber(gegner), sauber(clan),
                    String.valueOf(seineKraft), String.valueOf(meineKraft),
                    punkte == null ? "" : String.valueOf(punkte),
                    messlatte == Integer.MIN_VALUE ? "" : String.valueOf(messlatte),
                    abstand,
                    chance < 0 ? "" : String.valueOf(Math.round(chance * 100)),
                    top3 ? "ja" : "nein",
                    sieg ? "S" : "N",
                    String.valueOf(dauer), String.valueOf(arenaPunkte), String.valueOf(runde),
                    // Auswertung 30.10.: ohne den Lohn fehlte bei Niederlagen die Stufe, Rest und Platz fehlten fuer die Regeln ganz.
                    String.valueOf(lohn), String.valueOf(restSekunden), String.valueOf(pyramidenPlatz),
                    // Autor 31.10.: Kraft je Einheit. Die Rueckrechnung ergab noch keinen Grund fuer eine Regel; so laesst es sich in ein paar Wochen sauber pruefen.
                    einheiten == null ? "" : String.valueOf(einheiten[0]), einheiten == null ? "" : String.valueOf(einheiten[1]),
                    einheiten == null ? "" : String.valueOf(einheiten[2])) + "\n";
            Files.writeString(DATEI, zeile, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug("Arena", "arena-lernen.txt nicht geschrieben: " + e.getMessage());
        }
    }

    private static String sauber(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').strip();
    }
}
