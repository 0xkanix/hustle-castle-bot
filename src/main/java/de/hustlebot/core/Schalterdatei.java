package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ein An/Aus-Schalter, der einen Neustart ueberlebt: eine kleine Textdatei
 * im Bot-Ordner mit "an" oder "aus" (Kommentarzeilen mit # werden
 * ueberlesen). Benutzt von Leuchtturm/Kolosseum (Energieplan) und der
 * Bildschirmaufnahme -- vorher stand derselbe Code zweimal da und verstand
 * jeweils andere Schreibweisen (Code-Pruefung 26.09.).
 */
public final class Schalterdatei {

    private final Path datei;
    private final String name;
    private final boolean standard;

    /**
     * @param datei    Datei im Bot-Ordner, z. B. "leuchtturm.txt"
     * @param name     Name fuers Protokoll
     * @param standard Wert, wenn es die Datei (noch) nicht gibt
     */
    public Schalterdatei(Path datei, String name, boolean standard) {
        this.datei = datei;
        this.name = name;
        this.standard = standard;
    }

    /** Liest den Schalter; bei fehlender oder unlesbarer Datei der Standard. */
    public boolean lesen() {
        try {
            if (!Files.exists(datei)) return standard;
            for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                // 28.10.: Windows-Kennung (BOM) am Dateianfang ueberspringen -- sonst galt "# Kommentar" nicht als Kommentar
                // und der Schalter blieb aus (event-lupen-alle.txt, mit PowerShell geschrieben).
                String z = zeile.replace("﻿", "").trim().toLowerCase();
                if (z.isEmpty() || z.startsWith("#")) continue;
                if (istAn(z)) return true;
                if (istAus(z)) return false;
                Log.warn(name, Sprache.t(datei + ": \"" + zeile.trim() + "\" is neither on nor off -- " + (standard ? "on" : "off") + " applies.",
                        datei + ": \"" + zeile.trim() + "\" ist weder an noch aus -- es gilt "
                        + (standard ? "an" : "aus") + "."));
                return standard;
            }
        } catch (Exception e) {
            Log.warn(name, Sprache.t(datei + " could not be read, " + (standard ? "on" : "off") + " applies.",
                    datei + " liess sich nicht lesen, es gilt " + (standard ? "an" : "aus") + "."));
        }
        return standard;
    }

    /** Schreibt den Schalter; die Kommentarzeile erklaert, wer ihn setzt. */
    public void setzen(boolean an, String kommentar) {
        try {
            Files.writeString(datei, "# " + kommentar + "\n" + (an ? "an" : "aus") + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn(name, Sprache.t("Switch could not be saved: " + e.getMessage(),
                    "Schalter liess sich nicht speichern: " + e.getMessage()));
        }
    }

    public static boolean istAn(String wort) {
        return switch (wort) {
            case "an", "on", "ja", "yes", "true", "1", "ein" -> true;
            default -> false;
        };
    }

    public static boolean istAus(String wort) {
        return switch (wort) {
            case "aus", "off", "nein", "no", "false", "0" -> true;
            default -> false;
        };
    }
}
