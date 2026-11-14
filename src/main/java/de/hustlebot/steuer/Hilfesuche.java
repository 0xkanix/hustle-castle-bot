package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * Autor 24.10. (Ideenliste F32): "Wo finde ich ...?" -- ein Suchfeld oben im Bot-Programm. Wer "Arena" tippt, bekommt die
 * Stellen genannt (Modus, Einstellung, Aufnahme-Knopf); ein Klick darauf schaltet den Reiter um und Pip markiert die Stelle mit
 * seinem leuchtenden Rahmen (Steuerfenster.markieren -- dieselbe Technik wie im Tutorial).
 *
 * Die Ziele sind dieselben Schluessel wie im Tutorial (Steuerfenster.ziel): "modus", "start", "Nahrung", "Arena" ...
 * Stichwoerter gelten in beiden Sprachen; Gross/Klein und Umlaute spielen keine Rolle (Einstellungssuche).
 */
final class Hilfesuche {

    private Hilfesuche() {
    }

    /** Eine Stelle im Programm: Anzeige, Reiter, Ziel-Schluessel. */
    record Treffer(String titel, int reiter, String ziel) {
        /** "Arena: Gegner-Listen   -   Reiter Einstellungen". */
        String anzeige() {
            return titel + "   –   " + Sprache.t("tab ", "Reiter ") + reiterName(reiter);
        }
    }

    private record Eintrag(String stichwoerter, String titelEn, String titelDe, int reiter, String ziel) {
        String titel() { return Sprache.t(titelEn, titelDe); }
    }

    private static final int S = Steuerfenster.REITER_STEUERUNG, A = Steuerfenster.REITER_ANZEIGEN, E = Steuerfenster.REITER_EINSTELLUNGEN,
            K = Steuerfenster.REITER_KUERZEL, P = Steuerfenster.REITER_PROFILE, W = Steuerfenster.REITER_WERKZEUGE,
            U = Steuerfenster.REITER_SUPPORT;

    static String reiterName(int reiter) {
        return switch (reiter) {
            case Steuerfenster.REITER_STEUERUNG -> Sprache.t("Control", "Steuerung");
            case Steuerfenster.REITER_ANZEIGEN -> Sprache.t("Windows", "Anzeigen");
            case Steuerfenster.REITER_EINSTELLUNGEN -> Sprache.t("Settings", "Einstellungen");
            case Steuerfenster.REITER_KUERZEL -> Sprache.t("Shortcuts", "Tastenkürzel");
            case Steuerfenster.REITER_PROFILE -> Sprache.t("Profiles", "Profile");
            case Steuerfenster.REITER_WERKZEUGE -> Sprache.t("Tools", "Werkzeuge");
            default -> "Support";
        };
    }

    private static Eintrag e(String stichwoerter, String en, String de, int reiter, String ziel) {
        return new Eintrag(stichwoerter, en, de, reiter, ziel);
    }

    private static final List<Eintrag> INDEX = List.of(
            // ---- Steuerung ----
            e("cathedral kathedrale kathedralen modus mode spielen play", "Cathedral: choose the mode", "Kathedrale: Modus wählen", S, "modus"),
            e("route weg turbo wegpunkt waypoint", "Cathedral route (turbo or all waypoints)", "Weg in der Kathedrale (Turbo oder alle Wegpunkte)", S, "weg"),
            e("dungeon verlies mitspieler teammate friend freund", "Dungeon: mode and teammates", "Verlies: Modus und Mitspieler", S, "freund"),
            e("arena turnier tournament", "Arena: choose the mode", "Arena: Modus wählen", S, "modus"),
            e("portal level farmen farm autokampf", "Portal: levels and farming", "Portal: Level und Farmen", S, "portal"),
            e("stigma stigmas titan loeschen leveln delete level", "Stigmas: choose the titan", "Stigmas: Titan wählen", S, "titan"),
            e("anzahl oft many number runs durchlaeufe", "How many runs", "Wie oft gespielt wird", S, "anzahl"),
            e("start starten los begin", "Start button", "Start-Knopf", S, "start"),
            e("danach after warteschlange queue anhaengen", "Add afterwards (queue)", "Danach anhängen (Warteschlange)", S, "danach"),
            e("kreislauf loop routine nacht night event ablauf", "Loop (event routine)", "Kreislauf (Event-Ablauf)", S, "kreislauf"),
            e("kreislauf loop ohne event without", "Loop without event", "Kreislauf ohne Event", S, "kreislaufOhne"),
            e("anhalten stop pause notaus emergency f9 f10 taste key weiter resume", "Stop and pause keys", "Anhalten und Pause (Tasten)", S, "tasten"),
            e("naechste next zeit time uhr zeitleiste timeline termine plan", "Coming up (timeline)", "Als Nächstes (Zeitleiste)", S, "zeitleiste"),
            e("selbstcheck check ampel health gesundheit pruefen virenschutz antivirus norton skalierung scaling energiesparplan power plan fenstergroesse window size lizenz licence",
                    "Check-up before you start (traffic light)", "Selbstcheck vor dem Start (Ampel)", S, "gesundheit"),
            e("status statistik statistics burg castle screenshot bildschirmfoto hilfe help nachsehen look", "Look things up (status, statistics ...)", "Nachsehen (Status, Statistik ...)", S, "nachsehen"),
            e("tutorial pip anleitung guide erklaerung", "Tutorial with Pip", "Tutorial mit Pip", S, "nachsehen"),
            e("befehl command discord frei free", "Command like in Discord", "Befehl wie in Discord", S, "befehl"),
            // ---- Anzeigen ----
            e("fenster windows anzeigen show hide protokoll log notizblock notebook vorrat resources mini leiste bar", "Show and hide windows", "Fenster ein- und ausblenden", A, "anzeigen"),
            e("statistik statistics durchlauf runs tage days zeit time diagramm chart", "Statistics window", "Statistik-Fenster", A, "anzeigen"),
            // ---- Einstellungen ----
            e("nahrung food limit lager storage kisten chests bezahlen pay apfel apple tickets", "Food: use, limit, storage", "Nahrung: benutzen, Limit, Lager", E, "Nahrung"),
            e("leuchtturm lighthouse kolosseum colosseum titanturm titan tower nebenbei side", "Lighthouse, colosseum, titan tower", "Leuchtturm, Kolosseum, Titanen-Turm", E, "Nebenbei"),
            e("clan krieg war aufstellen deploy kriegsplan", "Clan war: deploy by itself", "Clan-Krieg selbst aufstellen", E, "Nebenbei"),
            e("heldenset hero set clan krieg war kleiderbuegel", "Hero set for the clan war", "Helden-Set im Clan-Krieg", E, "Truppe"),
            e("truppe squad previous best aufstellung neustart restart spiel game", "Squad and game restart", "Truppe und Spiel-Neustart", E, "Truppe"),
            e("event automatik automation kreislauf loop titan hammer", "Event automation", "Event-Automatik", E, "Event-Automatik"),
            e("hammer event haemmer lupen magnifier pass stages gold silber", "Hammer event: settings", "Hammer-Event: Einstellungen", E, "Hammer-Event"),
            e("titan event grabung excavation hacke pickaxe drill tiefe depth", "Titan event: settings", "Titan-Event: Einstellungen", E, "Titan-Event"),
            e("zeitzone time zone uhr clock berlin", "Time zone", "Zeitzone", E, "Zeitzone"),
            e("team kaserne barracks scan spielername player name opfer sacrifice", "Your team (barracks scan)", "Eigenes Team (Kaserne ansehen)", E, "Eigenes Team"),
            e("sprache language deutsch english englisch", "Language of the messages", "Sprache der Meldungen", E, "Sprache"),
            e("ton sound mute stumm laut", "Sound (mute)", "Ton (stumm)", E, "Ton"),
            e("autostart windows hochfahren boot", "Autostart with Windows", "Autostart mit Windows", E, "Autostart"),
            e("discord token kanal channel", "Discord (optional)", "Discord (freiwillig)", E, "Discord"),
            e("maus mouse stopp stop bewegung move", "Stop when the mouse moves", "Stopp bei Mausbewegung", E, "Maus"),
            e("arena gegner opponent liste list niemals never sicher safe machbar doable meiden avoid beobachter watcher", "Arena: opponent lists and watcher", "Arena: Gegner-Listen und Beobachter", E, "Arena"),
            e("konten accounts mehrere several spielkonto", "Several game accounts", "Mehrere Spielkonten", E, "Spielkonten"),
            e("update neu new alle einstellungen all settings", "What's new / all settings", "Was ist neu / alle Einstellungen", E, "Mehr"),
            e("suche search finden find einfach simple rueckgaengig undo standard default", "Search settings, undo, default", "Einstellungen suchen, rückgängig, Standard", E, "einstellungsleiste"),
            // ---- Kuerzel, Profile, Werkzeuge, Support ----
            e("kuerzel shortcut tastenkuerzel hotkey taste key", "Shortcuts", "Tastenkürzel", K, "Anhalten"),
            e("profil profile speichern save lieblingsstart", "Profiles", "Profile", P, "profile"),
            e("aufnahme capture bild bilder picture pictures image werkzeug tool name spielername arena event motiv", "Capture program (own pictures)", "Aufnahme-Programm (eigene Bilder)", W, "werkzeuge"),
            e("pc check reiniger cleaner aufraeumen", "PC check and PC cleaner", "PC-Check und PC-Reiniger", W, "werkzeuge"),
            e("support fehler error bericht report hilfe help autor problem", "Support and error report", "Support und Fehlerbericht", U, "support"));

    /** Die Stellen zu einer Eingabe, die besten zuerst (Titel-Treffer vor Stichwort-Treffern), hoechstens 8. Leer = nichts getippt. */
    static List<Treffer> suche(String eingabe) {
        List<Treffer> titelTreffer = new ArrayList<>(), rest = new ArrayList<>();
        if (Einstellungssuche.normal(eingabe).isEmpty()) return titelTreffer;
        for (Eintrag e : INDEX) {
            Treffer t = new Treffer(e.titel(), e.reiter(), e.ziel());
            if (Einstellungssuche.passt(eingabe, e.titelEn() + " " + e.titelDe())) {
                if (!titelTreffer.contains(t)) titelTreffer.add(t);
            } else if (Einstellungssuche.passt(eingabe, e.stichwoerter())) {
                if (!rest.contains(t)) rest.add(t);
            }
        }
        titelTreffer.addAll(rest);
        return titelTreffer.size() > 8 ? new ArrayList<>(titelTreffer.subList(0, 8)) : titelTreffer;
    }

    /** Alle Ziel-Schluessel des Index (fuer die Pruefung, dass jedes Ziel im Fenster vorkommt). */
    static List<String> alleZiele() {
        List<String> l = new ArrayList<>();
        for (Eintrag e : INDEX) if (!l.contains(e.ziel())) l.add(e.ziel());
        return l;
    }

    /** Das Reiter-Ziel-Paar eines Eintrags (fuer die Pruefung). */
    static int reiterVon(String ziel) {
        for (Eintrag e : INDEX) if (e.ziel().equals(ziel)) return e.reiter();
        return -1;
    }

    /** Ein Suchfeld im Holzstil mit grauem Hinweis, solange es leer ist. */
    static JTextField feld(String hinweis, int spalten) {
        JTextField f = new JTextField(spalten) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Holzstil.LEISE);
                    g2.setFont(getFont().deriveFont(Font.ITALIC));
                    java.awt.Insets i = getInsets();
                    g2.drawString(hinweis, i.left, (getHeight() - g2.getFontMetrics().getHeight()) / 2 + g2.getFontMetrics().getAscent());
                    g2.dispose();
                }
            }
        };
        Holzstil.feldStil(f);
        return f;
    }
}
