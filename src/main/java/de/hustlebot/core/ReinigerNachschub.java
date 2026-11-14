package de.hustlebot.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Legt den PC-Cleaner in den Bot-Ordner (Autor, 07.10.: "bring den Reiniger in jeder
 * Freunde-Bot-Fassung mit dazu ... und gib mir eine Update-Datei, die den PC-Cleaner
 * hinzufuegt").
 *
 * Wie bei den Vorlagen (VorlagenNachschub): Ein Freund aktualisiert nur die Jar. Deshalb
 * steckt der Reiniger in der Jar (Ordner "pc-reiniger") und wird beim Start nach
 * &lt;Bot-Ordner&gt;\PC-Cleaner geschrieben -- fehlt eine Datei oder ist sie aelter, wird sie
 * ersetzt. Es sind unsere eigenen Dateien, ueberschreiben ist hier also richtig.
 */
public final class ReinigerNachschub {

    private static final String LISTE = "/pc-reiniger/liste.txt";

    private ReinigerNachschub() {
    }

    public static int liefern() {
        int neu = 0;
        try (InputStream in = ReinigerNachschub.class.getResourceAsStream(LISTE)) {
            if (in == null) return 0;
            Path ziel = Ordner.bei("PC-Cleaner");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String zeile : text.split("\\R")) {
                // Set-Content -Encoding UTF8 schreibt ein BOM vor die erste Zeile.
                String name = zeile.replace("﻿", "").strip();
                if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                try (InputStream datei = ReinigerNachschub.class.getResourceAsStream("/pc-reiniger/" + name)) {
                    if (datei == null) continue;
                    byte[] inhalt = datei.readAllBytes();
                    Path p = ziel.resolve(name);
                    if (Files.exists(p) && Arrays.equals(Files.readAllBytes(p), inhalt)) continue;
                    Files.createDirectories(ziel);
                    Files.write(p, inhalt);
                    neu++;
                }
            }
            if (neu > 0) {
                Log.info("Start", Sprache.t("PC cleaner placed in the bot folder (" + neu + " files): " + ziel + " -- double-click PC-Cleaner.bat.",
                        "PC-Cleaner in den Bot-Ordner gelegt (" + neu + " Dateien): " + ziel
                        + " -- PC-Cleaner.bat doppelklicken."));
            }
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("PC cleaner could not be placed: " + e.getMessage(),
                    "PC-Cleaner liess sich nicht ablegen: " + e.getMessage()));
        }
        return neu;
    }
}
