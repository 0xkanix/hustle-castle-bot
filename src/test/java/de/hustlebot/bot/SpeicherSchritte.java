package de.hustlebot.bot;

import de.hustlebot.core.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Fuer SpeicherTest: je Einstellung "setzen" (mit einem Wert, der nicht die Vorgabe ist) und "lesen" -- ueber
 * dieselben Wege, die der Bot benutzt. SpeicherTest laedt diese Klasse jedes Mal in einem frischen Klassenlader
 * (= Bot neu gestartet) und vergleicht, was vor und nach dem "Neustart" gelesen wird.
 */
public final class SpeicherSchritte {

    private SpeicherSchritte() {}

    /** Alle geprueften Einstellungen. */
    public static final List<String> NAMEN = List.of(
            "bezahlen", "nahrungslimit", "lager", "aufstellung", "leuchtturm", "kolosseum", "kolosseum-grenze", "titanturm", "titanturm-max", "kriegsplan", "kiste", "eventautomatik", "eventautomatik-runs", "eventautomatik-titan", "pass", "lupen", "nahrungskisten-je-event", "meldungen", "zauberstaebe", "tipp", "tipp-ping", "clanladen", "nahrungskisten-krieg", "notaus", "arenareserve-tickets", "arenabericht", "sprache", "stigmas", "dauerwerte", "steuerprogramm", "tastenkuerzel", "profile");

    public static String setzen(String name) throws Exception {
        switch (name) {
            case "bezahlen" -> Bezahlung.setStandard("dungeon", Bezahlung.Art.ALLES);
            case "nahrungslimit" -> Bezahlung.setLimit("arena", 1_234_000L);
            case "lager" -> Bezahlung.setLager(1_234_000L);
            case "aufstellung" -> Aufstellung.setzen("arena", Aufstellung.wahlAusText(Aufstellung.istPrevious("arena") ? "best" : "previous"));
            case "leuchtturm" -> LighthouseMode.LEUCHTTURM.plan().setAn(!LighthouseMode.LEUCHTTURM.plan().istAn());
            case "kolosseum" -> LighthouseMode.KOLOSSEUM.plan().setAn(!LighthouseMode.KOLOSSEUM.plan().istAn());
            case "kolosseum-grenze" -> LighthouseMode.setKolosseumGrenze(321_000L);
            case "titanturm" -> Turmplan.setAn(!Turmplan.istAn());
            case "titanturm-max" -> Turmplan.setMaximum("hard", 7);
            case "kriegsplan" -> { Kriegsplan k = new Kriegsplan(); k.setSelbsttaetig(!k.istSelbsttaetig()); }
            case "kiste" -> Eventkisten.setzen("holz", Eventkisten.beschreibung().contains("holz aus"));
            case "eventautomatik" -> Eventautomatik.setAn(!Eventautomatik.istAn());
            case "eventautomatik-runs" -> Eventautomatik.setVerliesRuns(17);
            case "eventautomatik-titan" -> Eventautomatik.setTitan(!Eventautomatik.titan());
            case "pass" -> Eventautomatik.setPass(Eventautomatik.pass() == Eventautomatik.Pass.SILBER ? Eventautomatik.Pass.GOLD : Eventautomatik.Pass.SILBER);
            case "lupen" -> Eventautomatik.setMaxLupen(Eventautomatik.maxLupen() == 7 ? 8 : 7);
            case "nahrungskisten-je-event" -> Eventautomatik.setNahrungskisten(33);
            case "meldungen" -> Meldetakt.setTakt(5);   // Freunde: nur 1, 5 oder 10
            case "zauberstaebe" -> Zauberstabziel.setZiel(4321);
            case "tipp" -> Tagestipp.setAn(!Tagestipp.istAn());
            case "tipp-ping" -> Tagestipp.setPing("123456789012345678");
            case "clanladen" -> Ladenplan.setAn(!Ladenplan.istAn());
            case "nahrungskisten-krieg" -> Nahrungskisten.setAn(!Nahrungskisten.istAn());
            case "notaus" -> {
                // Wie !settings notaus F8: schreibt notaus.taste in config.properties; Main liest es beim Start.
                Method m = de.hustlebot.discord.DiscordBridge.class.getDeclaredMethod("schreibeConfigWert",
                        Path.class, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, Path.of("config.properties"), "notaus.taste", "F8");
            }
            case "arenareserve-tickets" -> ArenaMode.setTicketReserve(13);
            case "arenabericht" -> ArenaMode.BERICHT.setzen(!ArenaMode.BERICHT.lesen(), "Speicher-Pruefung");
            case "sprache" -> Sprache.speichern(Sprache.get() == Sprache.Lang.DE ? Sprache.Lang.EN : Sprache.Lang.DE);
            case "stigmas" -> Stigmaplan.setzen(null, null, null, null, java.util.Set.of("wolf"));
            case "dauerwerte" -> {
                Dauerwerte.schreiben(Dauerwerte.STATS, 42);
                Dauerwerte.schreiben(Dauerwerte.ENDE_UHRZEIT, "06:30");
                Dauerwerte.schreiben(Dauerwerte.ENDE_UM, 4_102_444_800_000L);
                Dauerwerte.schreiben(Dauerwerte.AUS_UHRZEIT, "06:00");
                Dauerwerte.schreiben(Dauerwerte.AUS_UM, 4_102_441_200_000L);
            }
            case "steuerprogramm" -> {
                var e = new de.hustlebot.steuer.Steuereinstellungen(Path.of("steuerprogramm.properties"));
                e.setzen(de.hustlebot.steuer.Steuereinstellungen.AUSSEHEN, "dunkel-probe");
                e.setzen(de.hustlebot.steuer.Steuereinstellungen.MINILEISTE_X, 123);
                e.setzen(de.hustlebot.steuer.Steuereinstellungen.WINDOWS_MELDUNGEN, false);
            }
            case "tastenkuerzel" -> {
                var t = new de.hustlebot.steuer.Tastenkuerzel(Path.of("tastenkuerzel.properties"));
                t.setAlleAn(false);
                t.setzen(de.hustlebot.steuer.Tastenkuerzel.Aktion.values()[0], "Strg+Alt+Q");
            }
            case "nur-im-speicher" -> Sprache.set(Sprache.Lang.DE);   // Gegenprobe: ohne Datei
            case "profile" -> new de.hustlebot.steuer.Profile(Path.of("profile.txt"))
                    .setzen("Probe", List.of("!settings meldungen 3", "!mute"));
            default -> throw new IllegalArgumentException("unbekannt: " + name);
        }
        return lesen(name);
    }

    /** Was der Bot nach dem Start fuer diese Einstellung weiss. */
    public static String lesen(String name) throws Exception {
        return switch (name) {
            case "bezahlen" -> String.valueOf(Bezahlung.standard("dungeon"));
            case "nahrungslimit" -> String.valueOf(Bezahlung.gespeichertesLimit("arena"));
            case "lager" -> String.valueOf(Bezahlung.lager());
            case "aufstellung" -> {   // Main laedt sie beim Start
                Aufstellung.konfigurieren(Path.of("aufstellung.properties"));
                yield String.valueOf(Aufstellung.istPrevious("arena"));
            }
            case "leuchtturm" -> String.valueOf(LighthouseMode.LEUCHTTURM.plan().istAn());
            case "kolosseum" -> String.valueOf(LighthouseMode.KOLOSSEUM.plan().istAn());
            case "kolosseum-grenze" -> String.valueOf(LighthouseMode.kolosseumGrenze());
            case "titanturm" -> String.valueOf(Turmplan.istAn());
            case "titanturm-max" -> String.valueOf(Turmplan.maximum("hard"));
            case "kriegsplan" -> String.valueOf(new Kriegsplan().istSelbsttaetig());
            case "kiste" -> Eventkisten.beschreibung();
            case "eventautomatik" -> String.valueOf(Eventautomatik.istAn());
            case "eventautomatik-runs" -> String.valueOf(Eventautomatik.verliesRuns());
            case "eventautomatik-titan" -> String.valueOf(Eventautomatik.titan());
            case "pass" -> String.valueOf(Eventautomatik.pass());
            case "lupen" -> String.valueOf(Eventautomatik.maxLupen());
            case "nahrungskisten-je-event" -> String.valueOf(Eventautomatik.nahrungskistenJeEvent());
            case "meldungen" -> String.valueOf(Meldetakt.takt());
            case "zauberstaebe" -> String.valueOf(Zauberstabziel.ziel());
            case "tipp" -> String.valueOf(Tagestipp.istAn());
            case "tipp-ping" -> Tagestipp.ping();
            case "clanladen" -> String.valueOf(Ladenplan.istAn());
            case "nahrungskisten-krieg" -> String.valueOf(Nahrungskisten.istAn());
            case "notaus" -> {
                Properties p = new Properties();
                if (Files.exists(Path.of("config.properties"))) try (var in = Files.newBufferedReader(Path.of("config.properties"))) { p.load(in); }
                yield p.getProperty("notaus.taste", "F9");
            }
            case "arenareserve-tickets" -> String.valueOf(ArenaMode.ticketReserve());
            case "arenabericht" -> String.valueOf(ArenaMode.BERICHT.lesen());
            case "sprache" -> String.valueOf(Sprache.laden());   // wie Main beim Start
            case "stigmas" -> String.valueOf(new java.util.TreeSet<>(Stigmaplan.behalten()));
            case "dauerwerte" -> Dauerwerte.zahl(Dauerwerte.STATS, 0) + "/" + Dauerwerte.lesen(Dauerwerte.ENDE_UHRZEIT, "") + "/"
                    + Dauerwerte.zahl(Dauerwerte.ENDE_UM, 0L) + "/" + Dauerwerte.lesen(Dauerwerte.AUS_UHRZEIT, "") + "/" + Dauerwerte.zahl(Dauerwerte.AUS_UM, 0L);
            case "steuerprogramm" -> {
                var e = new de.hustlebot.steuer.Steuereinstellungen(Path.of("steuerprogramm.properties"));
                yield e.text(de.hustlebot.steuer.Steuereinstellungen.AUSSEHEN, "") + "/"
                        + e.zahl(de.hustlebot.steuer.Steuereinstellungen.MINILEISTE_X, -1) + "/"
                        + e.an(de.hustlebot.steuer.Steuereinstellungen.WINDOWS_MELDUNGEN, true);
            }
            case "tastenkuerzel" -> {
                var t = new de.hustlebot.steuer.Tastenkuerzel(Path.of("tastenkuerzel.properties"));
                yield t.alleAn() + "/" + t.eintrag(de.hustlebot.steuer.Tastenkuerzel.Aktion.values()[0]).kombination().anzeige();
            }
            case "nur-im-speicher" -> String.valueOf(Sprache.get());
            case "profile" -> String.valueOf(new de.hustlebot.steuer.Profile(Path.of("profile.txt")).befehle("Probe"));
            default -> throw new IllegalArgumentException("unbekannt: " + name);
        };
    }
}
