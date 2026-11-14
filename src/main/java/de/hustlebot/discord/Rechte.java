package de.hustlebot.discord;

import de.hustlebot.core.Sprache;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wer darf die gefaehrlichen Befehle? (Sicherheit, 16.10.)
 *
 * Bisher durfte jeder im Bot-Kanal alles. Haengt ein Freund den Bot in einen
 * Clan-Server, koennte dann jedes Clan-Mitglied den Rechner herunterfahren oder
 * den Bildschirm ansehen. Ab jetzt gilt fuer die gefaehrlichen Befehle:
 *
 *   - der BESITZER des Discord-Servers darf (owner_id der Guild, beim Start
 *     von Discord geholt),
 *   - dazu die Nutzer-IDs aus config.properties, Schluessel
 *     discord.erlaubteNutzer (kommagetrennt),
 *   - der eigene Rechner (Steuerprogramm) darf immer.
 *
 * Ist der Besitzer (noch) nicht bekannt -- etwa weil die Abfrage bei Discord
 * fehlgeschlagen ist -- gilt der SICHERHEITSMODUS: Die gefaehrlichen Befehle
 * sind dann fuer alle gesperrt, ausser fuer die ausdruecklich erlaubten IDs.
 * Harmlose Befehle (!help, !status, Start/Stop der Modi) bleiben fuer alle.
 *
 * Die Besitzer sind je Server (Guild) gemerkt: Der Bot kann in mehreren
 * Servern sein, und der Besitzer des Clan-Servers ist nicht der des eigenen.
 */
public final class Rechte {

    /** Kennung fuer Befehle vom eigenen Rechner (Steuerprogramm) -- keine Discord-ID, die sind reine Zahlen. */
    public static final String EIGENER_RECHNER = "eigener-rechner";

    /** Die gefaehrlichen Befehle -- in der englischen Grundform aus {@link Befehlsnamen}. */
    private static final Set<String> GEFAEHRLICH = Set.of(
            "!pc", "!emergency", "!off", "!settings", "!clear", "!screenshot", "!live",
            // Code-Pruefung 16.10.: die neuen Knoepfe -- Bot neu starten, Autostart (legt eine Verknuepfung an),
            // Zeitzone (verschiebt alle Zeitplaene) -- darf in Discord nur der Besitzer.
            "!restart", "!autostart", "!timezone", "!team",
            // Autor 21.10.: !watch schickt dauernd Bilder vom Spiel in den Kanal -- wie !screenshot und !live.
            "!watch",
            // Autor 24.10.: Zeichen der eigenen Namens-Schrift ansehen und beschriften (aendert die Glyphen-Bibliothek)
            "!glyphs", "!glyph");

    /** Besitzer je Server: Guild-ID -> Nutzer-ID. Leer, solange die Abfrage nicht geklappt hat. */
    private final Map<String, String> besitzer = new ConcurrentHashMap<>();
    /** Zusaetzlich erlaubte Nutzer-IDs aus config.properties. */
    private final Set<String> erlaubte;

    public Rechte(Collection<String> erlaubteNutzer) {
        Set<String> s = new LinkedHashSet<>();
        if (erlaubteNutzer != null) {
            for (String id : erlaubteNutzer) {
                if (id != null && id.matches("\\d{5,25}")) s.add(id.trim());
            }
        }
        this.erlaubte = Collections.unmodifiableSet(s);
    }

    /** Liest discord.erlaubteNutzer aus config.properties (nur lesen; fehlt der Schluessel oder die Datei: leer). */
    public static Rechte ausConfig(java.nio.file.Path configDatei) {
        String text = "";
        try {
            if (configDatei != null && java.nio.file.Files.isRegularFile(configDatei)) {
                java.util.Properties p = new java.util.Properties();
                try (java.io.Reader in = java.nio.file.Files.newBufferedReader(configDatei,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    p.load(in);
                }
                text = p.getProperty("discord.erlaubteNutzer", "");
            }
        } catch (Exception e) {
            de.hustlebot.core.Log.debug("Rechte", "discord.erlaubteNutzer nicht gelesen: " + e.getMessage());
        }
        return new Rechte(idsAus(text));
    }

    /** "123, 456,,789" -> die Zahlen; alles andere wird uebergangen. */
    public static Set<String> idsAus(String kommaListe) {
        Set<String> s = new LinkedHashSet<>();
        if (kommaListe == null) return s;
        for (String teil : kommaListe.split("[,;\\s]+")) {
            String t = teil.trim();
            if (t.matches("\\d{5,25}")) s.add(t);
        }
        return s;
    }

    /** Ist das ein gefaehrlicher Befehl? Jede Schreibweise ("!rechner", "!notaus", "!leeren") zaehlt. */
    public static boolean gefaehrlich(String verb) {
        if (verb == null || verb.isBlank()) return false;
        String v = verb.trim().toLowerCase(java.util.Locale.ROOT);
        return GEFAEHRLICH.contains(v) || GEFAEHRLICH.contains(Befehlsnamen.kanonisch(v));
    }

    public void besitzerSetzen(String guildId, String besitzerId) {
        if (guildId == null || guildId.isBlank() || besitzerId == null || !besitzerId.matches("\\d{5,25}")) return;
        besitzer.put(guildId.trim(), besitzerId.trim());
    }

    /** Der Besitzer des Servers, oder "" wenn unbekannt. Ohne Guild-ID: der einzige bekannte Server. */
    public String besitzer(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return besitzer.size() == 1 ? besitzer.values().iterator().next() : "";
        }
        return besitzer.getOrDefault(guildId.trim(), "");
    }

    public boolean besitzerBekannt(String guildId) {
        return !besitzer(guildId).isEmpty();
    }

    /** Ist irgendein Besitzer bekannt? */
    public boolean irgendeinBesitzerBekannt() {
        return !besitzer.isEmpty();
    }

    public Set<String> erlaubte() {
        return erlaubte;
    }

    /**
     * Darf dieser Nutzer die gefaehrlichen Befehle im Server guildId?
     * Unbekannter Besitzer heisst: nein (Sicherheitsmodus), ausser die ID steht in der Erlaubt-Liste.
     */
    public boolean darf(String guildId, String nutzerId) {
        if (nutzerId == null || nutzerId.isBlank()) return false;
        String n = nutzerId.trim();
        if (EIGENER_RECHNER.equals(n)) return true;
        if (erlaubte.contains(n)) return true;
        String b = besitzer(guildId);
        return !b.isEmpty() && b.equals(n);
    }

    /**
     * Der Grund, warum der Befehl nicht ausgefuehrt wird -- oder null, wenn er erlaubt ist.
     * Englisch/Deutsch nach der eingestellten Sprache.
     */
    public String sperrtext(String guildId, String nutzerId, String verb) {
        if (!gefaehrlich(verb) || darf(guildId, nutzerId)) return null;
        String b = "`" + (verb == null ? "" : verb.trim()) + "`";
        if (!besitzerBekannt(guildId)) {
            return Sprache.t("For safety " + b + " is locked right now: the bot could not find out who owns this "
                             + "Discord server. It tries again in the background -- please retry in a moment. "
                             + "(Extra users: `discord.erlaubteNutzer` in config.properties, then restart the bot.)",
                             "Aus Sicherheitsgruenden ist " + b + " gerade gesperrt: Der Bot konnte nicht feststellen, "
                             + "wem dieser Discord-Server gehoert. Er versucht es im Hintergrund noch einmal -- bitte "
                             + "gleich nochmal. (Weitere Nutzer: `discord.erlaubteNutzer` in config.properties, dann Bot neu starten.)");
        }
        return Sprache.t("Only the owner of this Discord server may use " + b + ". "
                         + "(Extra users: `discord.erlaubteNutzer` in config.properties, then restart the bot.)",
                         "Nur der Besitzer dieses Discord-Servers darf " + b + " benutzen. "
                         + "(Weitere Nutzer: `discord.erlaubteNutzer` in config.properties, dann Bot neu starten.)");
    }
}
