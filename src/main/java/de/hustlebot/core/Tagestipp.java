package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Tipp des Tages (Autor, 30.09.): einmal am Tag stellt der Bot in Discord einen
 * Befehl aus !help / !help all vor, mit kurzer Erklaerung. Er kommt bei jedem Start
 * des Bots und um 12:00 (wenn er an dem Tag noch nicht kam); um 02:00 wechselt er zum
 * naechsten. Die wichtigsten Befehle zuerst, am Ende der Liste wieder von vorn.
 * Abschaltbar: !settings tipp aus.
 *
 * Dieselbe Datei liegt im Hauptbot und in der Freunde-Fassung; dort steht
 * FREUNDE auf true. Jeder Tipp sagt, in welcher Fassung es den Befehl gibt --
 * die Texte sind gegen die !help-all-Listen beider Fassungen geprueft (30.09.).
 *
 * Als gezeigt gilt ein Tipp erst, wenn Discord ihn angenommen hat: heute() und
 * faellig() sehen nur nach, gepostet() vermerkt es.
 */
public final class Tagestipp {

    private Tagestipp() {}

    /** true in der Freunde-Fassung. */
    static final boolean FREUNDE = true;

    private static final Path DATEI = Path.of("tagestipp.txt");

    private enum Ort { BEIDE, HAUPT, NUR_FREUNDE }

    /** Ein Tipp: Ueberschrift (der Befehl), Erklaerung deutsch und englisch. */
    private record Tipp(String befehl, String de, String en, Ort ort) {}

    private static Tipp t(String befehl, String de, String en) {
        return new Tipp(befehl, de, en, Ort.BEIDE);
    }

    private static Tipp haupt(String befehl, String de, String en) {
        return new Tipp(befehl, de, en, Ort.HAUPT);
    }

    private static Tipp freunde(String befehl, String de, String en) {
        return new Tipp(befehl, de, en, Ort.NUR_FREUNDE);
    }

    /** Die wichtigsten zuerst. */
    private static final List<Tipp> ALLE = List.of(
        t("!start",
          "Startet einen Modus: `!start dungeon 5` spielt 5 Verlies-Runs, `!start cathedral 3` drei Kathedralen (Turbo), "
          + "`!start clanwar` stellt für den Clan-Krieg auf.",
          "Starts a mode: `!start dungeon 5` plays 5 dungeon runs, `!start cathedral 3` three cathedrals (turbo), "
          + "`!start clanwar` deploys for the clan war."),
        t("!stop",
          "Hält den Bot an und leert die Warteschlange. Weiter geht es mit einem neuen `!start ...`.",
          "Stops the bot and clears the queue. Carry on with a new `!start ...`."),
        t("!pause  /  !resume",
          "`!pause` hält an, ohne den Zähler zu verlieren – `!resume` macht genau dort weiter. "
          + "Gut, wenn du kurz selbst ins Spiel willst.",
          "`!pause` halts without losing the counter – `!resume` carries on right there. "
          + "Handy when you want to play yourself for a moment."),
        t("!status",
          "Zustand, Durchläufe und Laufzeit – was der Bot gerade macht.",
          "State, runs and uptime – what the bot is doing right now."),
        t("!stats",
          "Die ganze Statistik: Durchläufe, Tempo und alles Gesammelte. `!stats 10` schickt sie alle 10 Minuten, "
          + "`!stats off` beendet das.",
          "The full statistics: runs, pace and everything collected. `!stats 10` sends them every 10 minutes, "
          + "`!stats off` stops that."),
        t("!start eventablauf",
          "Der Kreislauf: Kathedrale (Schlüssel, Kisten) -> Hammer-Event -> Verlies-Runs, immer wieder.",
          "`!start eventloop` -- the loop: cathedral (keys, chests) -> hammer event -> dungeon runs, again and again."),
        t("!after",
          "Hängt einen Auftrag hinten an: `!after dungeon 20` spielt 20 Verlies-Runs, sobald das Laufende fertig ist.",
          "Adds a job to the end: `!after dungeon 20` plays 20 dungeon runs once the current job is done."),
        t("!queue",
          "Zeigt die Warteschlange mit Nummern. `!queue clear` leert sie (der laufende Modus bleibt).",
          "Shows the queue, numbered. `!queue clear` empties it (the running mode stays)."),
        t("!screenshot",
          "Schickt ein Bild vom Spiel – so siehst du von unterwegs, was gerade los ist.",
          "Sends a picture of the game – so you can see what is going on from anywhere."),
        t("!burg",
          "Spielt den laufenden Durchlauf zu Ende und geht zurück in die Burg. Danach wartet der Bot auf den nächsten Befehl.",
          "`!castle` finishes the current run and goes back to the castle. Then the bot waits for the next command."),
        t("!settings",
          "Alle Einstellungen auf einen Blick. Zum Beispiel je Modus, ob beim Aufstellen „Deploy the best“ "
          + "oder „Previous Squad“ gedrückt wird: `!settings dungeon previous`.",
          "All settings at a glance. For example, per mode, whether \"Deploy the best\" or \"Previous Squad\" "
          + "is pressed when deploying: `!settings dungeon previous`."),
        t("!clanwar on  /  off",
          "Mit `on` stellt der Bot für den Clan-Krieg von selbst auf (" + Kriegsplan.zeitenText(", ") + ") und spielt danach "
          + "da weiter, wo er war. Mit `off` lässt er den Krieg in Ruhe.",
          "With `on` the bot deploys for the clan war by itself (" + Kriegsplan.zeitenText(", ") + ") and then carries on "
          + "where it was. With `off` it leaves the war alone."),
        t("!log",
          "Schickt den Fehlerbericht als Datei – das Erste, was man bei einem Hänger ansieht.",
          "Sends the error report as a file – the first thing to look at when something hangs."),
        t("!ende 06:30",
          "Der Bot hört um 6:30 von selbst auf (der laufende Durchlauf wird noch zu Ende gespielt). Der Rechner bleibt an.",
          "`!end 06:30`: the bot stops by itself at 6:30 (the current run is finished first). The PC stays on."),
        t("!specs",
          "Temperatur, Lüfter und Stromverbrauch des Rechners. `!specs 10` schickt sie alle 10 Minuten.",
          "Temperature, fans and power draw of the PC. `!specs 10` sends them every 10 minutes."),
        t("!live",
          "Ein Bild vom Spiel, das sich 5 Minuten lang alle 2 Sekunden erneuert. `!live stop` beendet es.",
          "A picture of the game that refreshes every 2 seconds for 5 minutes. `!live stop` ends it."),
        t("!notaus",
          "Der Not-Aus: hält alles sofort an. Geht auch über eine Taste am Rechner (Voreinstellung F9), "
          + "umstellbar mit `!settings notaus F9`.",
          "`!emergency`: stops everything at once. Also works with a key on the PC (default F9), "
          + "change it with `!settings emergency F9`."),
        t("!pc",
          "`!pc off` fährt den Rechner herunter, `!pc restart` startet ihn neu, `!pc cancel` nimmt es zurück.",
          "`!pc off` shuts the PC down, `!pc restart` restarts it, `!pc cancel` takes it back."),
        t("!shortcut",
          "Eigener Kurzbefehl: ein kurzes Wort für einen langen Befehl, z. B. `!maus 25` für 25 Kathedralen-Runs.",
          "Your own short command: one short word for a long command, e.g. `!mouse 25` for 25 cathedral runs."),
        t("!mute  /  !unmute",
          "`!mute` schickt alle Meldungen ohne Benachrichtigung – `!unmute` stellt sie wieder an.",
          "`!mute` sends all messages without notification – `!unmute` turns them back on."),
        t("!clear",
          "Leert den Kanal (fragt vorher nach), damit alte Meldungen nicht im Weg stehen.",
          "Empties the channel (asks first) so old messages do not get in the way."),
        t("!update",
          "Zeigt, was in der neuesten Fassung des Bots neu ist – das Wichtigste zuerst.",
          "Shows what is new in the latest version of the bot – most important first."),
        t("!english  /  !deutsch",
          "Stellt die Sprache der Bot-Meldungen um.",
          "Switches the language of the bot's messages."),
        t("!help all",
          "Die ausführliche Liste: jeder Befehl mit allen Zusatzangaben.",
          "The full list: every command with all options."),
        t("!settings tipp",
          "Dieser Tipp des Tages: `!settings tipp aus` schaltet ihn ab, `an` wieder ein, `jetzt` zeigt ihn noch einmal, `weiter` springt zum nächsten. "
          + "`!settings tipp ping aus` lässt das @everyone weg.",
          "This daily tip: `!settings tip off` turns it off, `on` back on, `now` shows it again, `next` skips to the next one. "
          + "`!settings tip ping off` drops the @everyone."),

        // ---- nur Freunde-Fassung ---------------------------------------------------
        // (Beim Hauptbot schaltet !off die Steckdose -- dort kein Tipp dazu.)
        freunde("!off 06:30",
          "Fährt den PC zur angegebenen Zeit herunter (der Durchlauf wird noch zu Ende gespielt). "
          + "`!off now` sofort (zweimal tippen), `!off delete` nimmt es zurück.",
          "Shuts the PC down at the given time (the current run is finished first). "
          + "`!off now` right away (type twice), `!off delete` takes it back."),

        // ---- nur Hauptbot ----------------------------------------------------------
        haupt("!start arena 5",
          "Spielt fünf Arena-Turniere. In der Wartezeit jeder Stage sieht sich der Bot alle Gegner an "
          + "(Kraft, Sets, Artefakte) und greift erst in den letzten Sekunden an.",
          "Plays five arena tournaments. While waiting in each stage the bot inspects every opponent "
          + "(power, sets, artifacts) and attacks only in the last seconds."),
        haupt("!queue runs 2 80",
          "Die Warteschlange bearbeiten: `!queue remove 2` nimmt Eintrag 2 heraus, `!queue up 3` schiebt Eintrag 3 "
          + "einen Platz vor, `!queue runs 2 80` stellt Eintrag 2 auf 80 Runs.",
          "Editing the queue: `!queue remove 2` takes entry 2 out, `!queue up 3` moves entry 3 one place forward, "
          + "`!queue runs 2 80` sets entry 2 to 80 runs."),
        haupt("!screenshot 5",
          "Schickt jetzt ein Bild und dann alle 5 Minuten eins (`!screenshot 10` alle 10, `!screenshot aus` beendet es). "
          + "`!wach 5` macht dasselbe.",
          "Sends a picture now and then one every 5 minutes (`!screenshot 10` every 10, `!screenshot off` ends it). "
          + "`!watch 5` does the same."),
        haupt("F10  /  F11",
          "Tasten am Rechner: F10 pausiert den Bot sofort, F11 setzt ihn fort – egal, welches Fenster vorne ist. "
          + "F9 bleibt der Not-Aus.",
          "Keys on the PC: F10 pauses the bot at once, F11 resumes it – whatever window is in front. "
          + "F9 stays the emergency stop."),
        haupt("!settings nahrungskisten 25",
          "Nach so vielen Nahrungskisten verlässt der Bot das Hammer-Event und spielt wieder Kathedrale.",
          "After this many food chests the bot leaves the hammer event and plays the cathedral again."),
        haupt("!settings zauberstaebe 1000",
          "Der Bot spielt das Hammer-Event, bis du so viele Zauberstäbe hast – danach nur noch Kathedrale. "
          + "`!settings zauberstaebe aus` nimmt die Grenze weg.",
          "The bot plays the hammer event until you have this many wands – then cathedral only. "
          + "`!settings zauberstaebe off` removes the limit."),
        haupt("!settings lager 1200000",
          "Bis zu diesem Stand füllt der Bot die Nahrung mit Food-Kisten aus der Tasche auf.",
          "The bot tops up food with food chests from the bag up to this amount."),
        haupt("!spiel",
          "Startet Hustle Castle, ohne einen Modus zu starten.",
          "`!game` starts Hustle Castle without starting a mode."),
        haupt("!web",
          "Öffnet das Bedienfeld am Rechner – noch einmal tippen schließt es wieder.",
          "Opens the control panel on the PC – type it again to close it."),
        haupt("!autostart an",
          "Der Bot startet von selbst, wenn der Rechner hochfährt. `!autostart aus` stellt das wieder ab.",
          "`!autostart on`: the bot starts by itself when the PC boots. `!autostart off` turns that off."),
        haupt("!log an",
          "Öffnet am Rechner das Mitschau-Fenster mit dem laufenden Protokoll, neben dem Spiel. `!log aus` schließt es.",
          "`!log on` opens the live log window on the PC, next to the game. `!log off` closes it."),
        haupt("!notizen an",
          "Öffnet daneben den Arena-Notizblock: was der Bot über deine Gegner weiß (Kraft, Sets, Bilanz).",
          "`!notes on` opens the arena notebook beside it: what the bot knows about your opponents (power, sets, record)."),
        haupt("!chat  /  !antwort",
          "`!chat` zeigt den Clan-Chat, `!uebersetz` übersetzt ihn, `!antwort Text` schreibt hinein.",
          "`!chat` shows the clan chat, `!translate` translates it, `!answer text` writes into it.")
    );

    private static final List<Tipp> LISTE = ALLE.stream()
            .filter(x -> x.ort() == Ort.BEIDE || (FREUNDE ? x.ort() == Ort.NUR_FREUNDE : x.ort() == Ort.HAUPT))
            .toList();

    // ---- Zustand (tagestipp.txt) ---------------------------------------------------
    private static boolean an = true;
    /** Welcher Tipp gerade "der Tipp des Tages" ist. */
    private static int index = 0;
    /** Fuer welchen Tipp-Tag index gilt. Der Tipp-Tag wechselt um 02:00 (wie der Tag im Spiel). */
    private static LocalDate tag = null;
    /** An welchem Tipp-Tag zuletzt gepostet bzw. mit Erwaehnung gepostet wurde. */
    private static LocalDate gepostetAm = null;
    private static LocalDate gepingtAm = null;
    /** Was vor dem Tipp erwaehnt wird: "@everyone", "<@Nutzer-ID>" oder leer (kein Ping). */
    private static String ping = "@everyone";

    static {
        laden();
    }

    /** Der Tipp-Tag geht von 02:00 bis 02:00 (Autor, 30.09.: "soll sich jeden Tag um 02:00 resetten"). */
    static LocalDate tippTag(LocalDateTime jetzt) {
        return jetzt.minusHours(2).toLocalDate();
    }

    /** Ab 02:00 ist der naechste Tipp dran. */
    private static void tagWechseln(LocalDateTime jetzt) {
        LocalDate heute = tippTag(jetzt);
        if (heute.equals(tag)) return;
        if (tag != null && !LISTE.isEmpty()) index = (Math.floorMod(index, LISTE.size()) + 1) % LISTE.size();
        tag = heute;
        speichern();
    }

    public static synchronized boolean istAn() {
        return an;
    }

    public static synchronized void setAn(boolean neu) {
        an = neu;
        speichern();
        Log.info("Tipp", Sprache.t("Daily tip: " + (an ? "on" : "off") + ".",
                                   "Tipp des Tages: " + (an ? "an" : "aus") + "."));
    }

    /** Die Erwaehnung vor dem Tipp, leer = keine. */
    public static synchronized String ping() {
        return ping;
    }

    /** "everyone", eine Nutzer-ID / Erwaehnung, oder "aus". Liefert false bei Unverstaendlichem. */
    public static synchronized boolean setPing(String wort) {
        String w = wort == null ? "" : wort.trim().toLowerCase();
        String ziffern = w.replaceAll("[^0-9]", "");
        if (w.equals("aus") || w.equals("off") || w.equals("kein") || w.equals("none")) ping = "";
        else if (w.equals("everyone") || w.equals("@everyone") || w.equals("alle") || w.equals("all")) ping = "@everyone";
        else if (ziffern.length() >= 15 && ziffern.length() <= 22) ping = "<@" + ziffern + ">";
        else return false;
        speichern();
        Log.info("Tipp", Sprache.t("Daily tip: mention " + (ping.isEmpty() ? "off" : ping) + ".",
                                   "Tipp des Tages: Erwaehnung " + (ping.isEmpty() ? "aus" : ping) + "."));
        return true;
    }

    /** Fuer die Einstellungs-Karte. */
    public static synchronized String beschreibung() {
        return Sprache.t("💡 **Daily tip:** ", "💡 **Tipp des Tages:** ") + (an ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                + Sprache.t(" – shown whenever the bot is started and at 12:00; a new tip every day at 02:00. Mention: ",
                            " – kommt bei jedem Start des Bots und um 12:00; jeden Tag um 02:00 ein neuer Tipp. Erwähnung: ")
                + (ping.isEmpty() ? Sprache.t("none", "keine") : "`" + ping + "`")
                + Sprache.t(". Today: tip ", ". Heute: Tipp ")
                + (Math.floorMod(index, Math.max(1, LISTE.size())) + 1) + Sprache.t(" of ", " von ") + LISTE.size() + "."
                + Sprache.t("\n_Change: `!settings tip on` / `off` / `now` / `next` · `!settings tip ping everyone` / `off` / user ID_",
                            "\n_Ändern: `!settings tipp an` / `aus` / `jetzt` / `weiter` · `!settings tipp ping everyone` / `aus` / Nutzer-ID_");
    }

    /** Der Tipp des (Tipp-)Tages als {Titel, Text}; wechselt dabei um 02:00 zum naechsten. */
    public static synchronized String[] heute(LocalDateTime jetzt) {
        if (LISTE.isEmpty()) return null;
        tagWechseln(jetzt);
        int nr = Math.floorMod(index, LISTE.size());
        Tipp tipp = LISTE.get(nr);
        String titel = Sprache.t("💡 Daily tip: ", "💡 Tipp des Tages: ") + "`" + tipp.befehl() + "`";
        String text = Sprache.t(tipp.en(), tipp.de())
                + "\n\n_" + Sprache.t("Tip ", "Tipp ") + (nr + 1) + Sprache.t(" of ", " von ") + LISTE.size()
                + Sprache.t(" · all commands: `!help` · turn off: `!settings tip off`_",
                            " · alle Befehle: `!help` · abschalten: `!settings tipp aus`_");
        return new String[]{titel, text};
    }

    /**
     * Fuer laufende Bots: um 12:00 (bis 12:30) den Tipp zeigen, wenn er an diesem Tipp-Tag
     * noch nicht kam -- etwa weil der Bot seit gestern durchlaeuft. Beim Start des Bots
     * zeigt ihn die Bruecke ohnehin (heute()). Aendert nichts; erst gepostet() vermerkt.
     */
    public static synchronized String[] faellig(LocalDateTime jetzt) {
        if (!an || LISTE.isEmpty()) return null;
        LocalTime zeit = jetzt.toLocalTime();
        if (zeit.isBefore(LocalTime.of(12, 0)) || !zeit.isBefore(LocalTime.of(12, 30))) return null;
        if (tippTag(jetzt).equals(gepostetAm)) return null;
        return heute(jetzt);
    }

    /** Mit Erwaehnung nur beim ersten Mal am Tipp-Tag -- spaetere Starts zeigen den Tipp still. */
    public static synchronized boolean pingFaellig(LocalDateTime jetzt) {
        return !ping.isEmpty() && !tippTag(jetzt).equals(gepingtAm);
    }

    /** Der Tipp ist in Discord angekommen. */
    public static synchronized void gepostet(LocalDateTime jetzt, boolean mitPing) {
        gepostetAm = tippTag(jetzt);
        if (mitPing) gepingtAm = gepostetAm;
        speichern();
        int nr = Math.floorMod(index, Math.max(1, LISTE.size())) + 1;
        Log.info("Tipp", Sprache.t("Daily tip " + nr + " of " + LISTE.size()
                + " shown" + (mitPing ? " (with mention)." : "."),
                "Tipp des Tages " + nr + " von " + LISTE.size()
                + " gezeigt" + (mitPing ? " (mit Erwaehnung)." : ".")));
    }

    /** Von Hand zum naechsten Tipp springen (!settings tipp weiter). */
    public static synchronized void weiter() {
        if (LISTE.isEmpty()) return;
        index = (Math.floorMod(index, LISTE.size()) + 1) % LISTE.size();
        speichern();
    }

    private static void laden() {
        try {
            if (!Files.exists(DATEI)) return;
            for (String z : Dateien.zeilen(DATEI)) {
                String s = z.trim();
                // Jede Zeile fuer sich: eine kaputte darf die anderen nicht mitreissen.
                try {
                    if (s.startsWith("an=")) an = !s.endsWith("false");
                    else if (s.startsWith("index=")) index = Integer.parseInt(s.substring(6).trim());
                    else if (s.startsWith("tag=") && s.length() > 4) tag = LocalDate.parse(s.substring(4).trim());
                    else if (s.startsWith("gepostet=") && s.length() > 9) gepostetAm = LocalDate.parse(s.substring(9).trim());
                    else if (s.startsWith("gepingt=") && s.length() > 8) gepingtAm = LocalDate.parse(s.substring(8).trim());
                    else if (s.startsWith("ping=")) ping = s.substring(5).trim();
                } catch (Exception e) {
                    Log.warn("Tipp", Sprache.t("tagestipp.txt, line not readable: " + s,
                                               "tagestipp.txt, Zeile nicht lesbar: " + s));
                }
            }
        } catch (Exception e) {
            Log.warn("Tipp", Sprache.t("tagestipp.txt not readable: " + e.getMessage(),
                                       "tagestipp.txt nicht lesbar: " + e.getMessage()));
        }
    }

    private static void speichern() {
        try {
            // Die Kopfzeile liest niemand zurueck (laden() kennt nur an=, index=, ...) -- darf zweisprachig sein.
            Files.writeString(DATEI, Sprache.t("# Daily tip -- !settings tip on|off|now|next, !settings tip ping everyone|off|<user ID>\n",
                                               "# Tipp des Tages -- !settings tipp an|aus|jetzt|weiter, !settings tipp ping everyone|aus|<Nutzer-ID>\n")
                    + "an=" + an + "\nindex=" + index
                    + "\ntag=" + (tag == null ? "" : tag)
                    + "\ngepostet=" + (gepostetAm == null ? "" : gepostetAm)
                    + "\ngepingt=" + (gepingtAm == null ? "" : gepingtAm)
                    + "\nping=" + ping + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Tipp", Sprache.t("tagestipp.txt not written: " + e.getMessage(),
                                       "tagestipp.txt nicht geschrieben: " + e.getMessage()));
        }
    }

    /** Zahl der Tipps in dieser Fassung (Selbsttest). */
    public static int anzahl() {
        return LISTE.size();
    }
}
