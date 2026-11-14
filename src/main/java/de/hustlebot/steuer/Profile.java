package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import de.hustlebot.core.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Profile auf Knopfdruck (Autor 11.10.): Einstellungen als Profil speichern
 * ("Nacht", "Event", "Arena") und mit einem Klick oder Tastenkuerzel wechseln.
 *
 * Ein Profil ist eine Liste von Befehlen, genau so, wie man sie in Discord
 * tippen wuerde ("!settings meldungen 10", "!mute"). Anwenden heisst: die
 * Befehle der Reihe nach ueber den einen Befehlsweg schicken. Damit werden die
 * Einstellungen nur ueber die vorhandenen Klassen geschrieben -- das Profil
 * selbst fasst keine Einstellungsdatei an.
 *
 * Datei profile.txt neben config.properties:
 *
 *     [Nacht]
 *     !settings meldungen 10
 *     !mute
 *
 *     [Tag]
 *     !unmute
 *
 * Zeilen mit # sind Kommentare. Die Reihenfolge der Profile bleibt erhalten;
 * die Tastenkuerzel "Profil 1/2/3" meinen die ersten drei.
 */
public final class Profile {

    private final Path datei;
    private final Map<String, List<String>> profile = new LinkedHashMap<>();

    public Profile(Path datei) {
        this.datei = datei;
        laden();
    }

    /** Die Namen in Dateireihenfolge. */
    public synchronized List<String> namen() {
        return new ArrayList<>(profile.keySet());
    }

    /** Die Befehle eines Profils (Kopie), leer wenn unbekannt. */
    public synchronized List<String> befehle(String name) {
        List<String> l = profile.get(schluessel(name));
        return l == null ? List.of() : new ArrayList<>(l);
    }

    /** Das n-te Profil (1 = erstes), oder null. Fuer die Tastenkuerzel. */
    public synchronized String name(int nummer) {
        List<String> n = namen();
        return nummer >= 1 && nummer <= n.size() ? n.get(nummer - 1) : null;
    }

    /**
     * Legt ein Profil an oder ersetzt es. Leere Zeilen und Kommentare fallen weg; jede
     * Zeile muss mit "!" anfangen (ein Befehl), sonst wird sie mit "!" ergaenzt.
     * Liefert false bei leerem Namen.
     */
    public synchronized boolean setzen(String name, List<String> zeilen) {
        String n = schluessel(name);
        if (n.isEmpty() || n.startsWith("[")) return false;
        List<String> sauber = new ArrayList<>();
        for (String z : zeilen == null ? List.<String>of() : zeilen) {
            String t = z == null ? "" : z.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!t.startsWith("!")) t = "!" + t;
            sauber.add(t);
        }
        profile.put(n, sauber);
        speichern();
        return true;
    }

    public synchronized boolean loeschen(String name) {
        boolean weg = profile.remove(schluessel(name)) != null;
        if (weg) speichern();
        return weg;
    }

    /**
     * Wendet ein Profil an: schickt jeden Befehl ueber den Befehlsweg. Liefert, wie viele es
     * waren (0, wenn das Profil fehlt oder leer ist).
     */
    public int anwenden(String name, Consumer<String> befehlsweg) {
        List<String> b = befehle(name);
        for (String z : b) {
            try {
                befehlsweg.accept(z);
            } catch (Exception e) {
                Log.warn("Profile", Sprache.t("Command from profile " + name + " failed: " + z + " (" + e.getMessage() + ")",
                        "Befehl aus Profil " + name + " ging nicht: " + z + " (" + e.getMessage() + ")"));
            }
        }
        if (!b.isEmpty()) Log.info("Profile", de.hustlebot.core.Sprache.t("Profile \"" + name + "\" applied (" + b.size() + " commands).", "Profil \"" + name + "\" angewendet (" + b.size() + " Befehle)."));
        return b.size();
    }

    /** Profil-Text wie in der Datei (eine Zeile je Befehl) -- fuers Bearbeiten im Fenster. */
    public String alsText(String name) {
        return String.join("\n", befehle(name));
    }

    private static String schluessel(String name) {
        return name == null ? "" : name.trim();
    }

    // ------------------------------------------------------------------
    //  Datei
    // ------------------------------------------------------------------

    private void laden() {
        profile.clear();
        if (!Files.exists(datei)) {
            beispiele();
            return;
        }
        try {
            String aktuell = null;
            // Code-Review 15.10.: Mit Editor in ANSI gespeichert (Umlaut) brach UTF-8 ab -- die Liste blieb leer,
            // und das naechste Speichern hat Autors Profile ueberschrieben. Dann eben als Windows-1252 lesen.
            List<String> zeilen;
            try {
                zeilen = Files.readAllLines(datei, StandardCharsets.UTF_8);
            } catch (java.nio.charset.CharacterCodingException e) {
                zeilen = Files.readAllLines(datei, java.nio.charset.Charset.forName("windows-1252"));
            }
            for (String zeile : zeilen) {
                String z = zeile.trim();
                if (z.isEmpty() || z.startsWith("#")) continue;
                if (z.startsWith("[") && z.endsWith("]") && z.length() > 2) {
                    aktuell = z.substring(1, z.length() - 1).trim();
                    profile.putIfAbsent(aktuell, new ArrayList<>());
                } else if (aktuell != null) {
                    profile.get(aktuell).add(z.startsWith("!") ? z : "!" + z);
                }
            }
        } catch (Exception e) {
            Log.warn("Profile", Sprache.t(datei + " could not be read: " + e.getMessage(),
                    datei + " liess sich nicht lesen: " + e.getMessage()));
        }
    }

    /**
     * Beim ersten Start ein paar Beispiele, damit die Liste nicht leer ist. Alles davon
     * sind Befehle, die es in Discord gibt -- Autor aendert sie im Fenster nach Bedarf.
     */
    private void beispiele() {
        profile.put(Sprache.t("Night", "Nacht"), new ArrayList<>(List.of("!mute", "!settings reports 10")));
        profile.put(Sprache.t("Day", "Tag"), new ArrayList<>(List.of("!unmute", "!settings reports 1")));
        profile.put("Event", new ArrayList<>(List.of("!settings automation on", "!settings pass gold", "!settings magnifiers 5")));
        profile.put("Arena", new ArrayList<>(List.of("!arenareserve 10", "!arenareport on")));
        speichern();
    }

    private void speichern() {
        StringBuilder b = new StringBuilder();
        b.append("# Profile des Steuerprogramms. Ein Profil = Befehle wie in Discord, der Reihe nach.\n");
        b.append("# Anwenden im Steuerprogramm (Reiter Profile) oder per Tastenkuerzel \"Profil 1/2/3\" (die ersten drei).\n\n");
        for (var e : profile.entrySet()) {
            b.append('[').append(e.getKey()).append("]\n");
            for (String z : e.getValue()) b.append(z).append('\n');
            b.append('\n');
        }
        try {
            Files.writeString(datei, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Profile", Sprache.t(datei + " could not be saved: " + e.getMessage(),
                    datei + " liess sich nicht speichern: " + e.getMessage()));
        }
    }
}
