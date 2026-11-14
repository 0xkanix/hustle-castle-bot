package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Welche Zusatz-Punkte in der Arena zaehlen (Autor, 01.10.): Schirmherrschaften, Stigmas, Pals,
 * Clan-Liste. Fuer Autors Testlaeufe einzeln schaltbar ueber {@code arena-extras.txt} im
 * Bot-Ordner -- Woerter darin: {@code schirm stigma pals clans} oder {@code alles}.
 * Ohne Datei ist alles an.
 */
public final class ArenaExtras {

    public static final Path DATEI = Path.of("arena-extras.txt");

    private static long gelesenUm;
    /** null = keine Datei = alles an. */
    private static Set<String> woerter;

    private ArenaExtras() { }

    /** Schirmherrschaften: +1 je Stueck, +3 je guter. */
    public static boolean schirm() { return an("schirm"); }
    /** Gute Stigmas auf den Schirmherrschaften: +2 je Stueck. */
    public static boolean stigma() { return an("stigma"); }
    /** Gute Pals: +5 je Stueck. */
    public static boolean pals()   { return an("pals"); }
    /** Spieler in einem Clan der Clan-Liste (ar_clan_): +8. */
    public static boolean clans()  { return an("clans"); }

    /**
     * Autor 11.10. (Auftrag 6, Punkt 6): das eigene Team (arena-ich.txt, vom Team-Scan) als Messlatte
     * in der Arena-Siegchance (Gegner-Team minus eigenes Team). Standard an; aus mit der Zeile
     * "eigenesteam aus" in arena-extras.txt -- dann gilt wie vorher der staerkste je geschlagene Gegner.
     */
    /** Autor 14.10.: "Team als Messlatte in der Arena soll immer an sein" -- kein Schalter mehr. */
    public static boolean eigenesTeam() { return true; }

    /** Schreibt "eigenesteam aus" in die Datei oder nimmt es wieder heraus (!team punkte an|aus). */
    public static synchronized void setEigenesTeam(boolean an) {
        try {
            java.util.List<String> zeilen = new java.util.ArrayList<>();
            if (Files.exists(DATEI)) {
                for (String z : Dateien.zeilen(DATEI)) {
                    if (istEigenesTeamAus(z)) continue;
                    zeilen.add(z);
                }
            } else {
                zeilen.add("# Arena-Zusatzpunkte (Autor): Woerter schirm stigma pals clans oder alles; kraftalt = alte Kraft-Rechnung.");
                zeilen.add("alles");
            }
            if (!an) zeilen.add("eigenesteam aus");
            Files.write(DATEI, zeilen, StandardCharsets.UTF_8);
            neuLesen();
        } catch (Exception e) {
            Log.warn("Arena", "arena-extras.txt liess sich nicht schreiben: " + e.getMessage());
        }
    }

    /** Die Zeile "eigenesteam aus" (auch "eigenesteam=aus", "eigenes team: aus")? */
    static boolean istEigenesTeamAus(String zeile) {
        String z = zeile == null ? "" : zeile.toLowerCase(Locale.ROOT).strip();
        if (z.startsWith("#")) return false;
        return z.replaceAll("[^a-z]", "").equals("eigenesteamaus");
    }

    /** "Schirmherrschaften an, Stigmas aus, ..." fuers Protokoll. */
    public static String text() {
        return Sprache.t("Patronages " + (schirm() ? "on" : "off") + ", stigmas " + (stigma() ? "on" : "off") + ", pals " + (pals() ? "on" : "off") + ", clan list " + (clans() ? "on" : "off"), "Schirmherrschaften " + (schirm() ? "an" : "aus") + ", Stigmas " + (stigma() ? "an" : "aus")
                + ", Pals " + (pals() ? "an" : "aus") + ", Clan-Liste " + (clans() ? "an" : "aus"));
    }

    static synchronized void neuLesen() {
        gelesenUm = 0;
    }

    private static synchronized boolean an(String was) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - gelesenUm > 5000) {
            gelesenUm = jetzt;
            woerter = laden();
        }
        return woerter == null || woerter.contains("alles") || woerter.contains(was);
    }

    private static Set<String> laden() {
        try {
            if (!Files.exists(DATEI)) return null;
            Set<String> s = new HashSet<>();
            for (String z : Dateien.zeilen(DATEI)) {
                if (z.strip().startsWith("#")) continue;
                for (String w : z.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                    if (w.isEmpty()) continue;
                    // "clan" und "pal" gelten auch
                    s.add(w.equals("clan") ? "clans" : w.equals("pal") ? "pals" : w.equals("stigmas") ? "stigma" : w);
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
