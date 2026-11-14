package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.List;

/**
 * Das Tutorial mit der Mini-Eule Pip (Autor 15.10.: "Ein kleiner Helfer erscheint, zaubert, markiert den Punkt im
 * Bot-Programm, erklaert kurz, worum es geht -- Verstanden/Weiter oder Ueberspringen; Einstellungen gleich ausfuellen.
 * Beim ersten Start von selbst, sonst ueber einen Knopf. Auf Deutsch und Englisch.")
 *
 * Ablauf je Schritt: Reiter umschalten, Ziel im Steuerfenster markieren (Steuerfenster.markieren), Pip zaubert
 * (Funken-Animation), Text in der eingestellten Sprache. Fertig oder abgebrochen: "tutorial.gesehen" wird gespeichert.
 */
final class Tutorial {

    /** Ein Schritt: welcher Reiter, welches Ziel (Schluessel in Steuerfenster.ziel), Pose, Titel und Text DE/EN. */
    record Schritt(int reiter, String ziel, PipBild.Pose pose, String titelEn, String titelDe, String textEn, String textDe) {
        String titel() { return Sprache.t(titelEn, titelDe); }
        String text() { return Sprache.t(textEn, textDe); }
    }

    static final String GESEHEN = "tutorial.gesehen";

    private static final int S = Steuerfenster.REITER_STEUERUNG, E = Steuerfenster.REITER_EINSTELLUNGEN,
            K = Steuerfenster.REITER_KUERZEL, W = Steuerfenster.REITER_WERKZEUGE, A = Steuerfenster.REITER_ANZEIGEN,
            P = Steuerfenster.REITER_PROFILE, U = Steuerfenster.REITER_SUPPORT;

    /** Autor 24.10.: "Pip soll alle Modi, alle Einstellungen, das Aufnahme-Programm, die Statistik -- alles abdecken." */
    static final List<Schritt> SCHRITTE = List.of(
            new Schritt(S, null, PipBild.Pose.HALLO, "Hello, I am Pip!", "Hallo, ich bin Pip!",
                    "I'll show you the bot program once, step by step. Each time I mark the spot I'm talking about. "
                    + "\"Got it\" goes on, \"Skip\" jumps over a step.",
                    "Ich zeige dir das Bot-Programm einmal Schritt für Schritt. Ich markiere jedes Mal die Stelle, um die es "
                    + "geht. „Verstanden“ geht weiter, „Überspringen“ lässt einen Schritt aus."),
            // ---- Reiter Steuerung ----
            new Schritt(S, "modus", PipBild.Pose.ERKLAERT, "What to play", "Was gespielt wird",
                    "Choose the mode: Cathedral, Dungeon, Arena, Portal, Titan excavation, Titan tower, Lighthouse, Colosseum, "
                    + "Clan war or the Hammer event. Stigma jobs and your own profiles are in this list too.",
                    "Wähle den Modus: Kathedrale, Verlies, Arena, Portal, Titan-Grabung, Titanen-Turm, Leuchtturm, Kolosseum, "
                    + "Clan-Krieg oder das Hammer-Event. Auch die Stigma-Aufträge und deine Profile stehen in dieser Liste."),
            new Schritt(S, "anzahl", PipBild.Pose.ERKLAERT, "How many runs", "Wie oft",
                    "How many runs the bot plays (Arena: tournaments, Hammer event: fields). Lighthouse, Colosseum and "
                    + "Clan War always run once.",
                    "Wie viele Durchläufe der Bot spielt (Arena: Turniere, Hammer-Event: Felder). Leuchtturm, Kolosseum und "
                    + "Clan-Krieg laufen immer genau einmal."),
            new Schritt(S, "weg", PipBild.Pose.ERKLAERT, "Cathedral route", "Weg in der Kathedrale",
                    "c = turbo, as fast as possible. b = takes every waypoint, slower but collects more.",
                    "c = Turbo, so schnell wie möglich. b = nimmt jeden Wegpunkt mit, langsamer, sammelt aber mehr."),
            new Schritt(S, "freund", PipBild.Pose.ERKLAERT, "Dungeon teammates", "Mitspieler im Verlies",
                    "Click into the field: all names you entered before appear for ticking. Empty = random players.",
                    "Klick ins Feld: Alle Namen, die du schon einmal eingetragen hast, erscheinen zum Anhaken. Leer = zufällige Spieler."),
            new Schritt(S, "titan", PipBild.Pose.ERKLAERT, "Stigmas", "Stigmas",
                    "For stigma jobs choose the titan here. Delete = only 1 star + level 1-79 of the sets you tick. "
                    + "Filter = looks at new Bull stigmas and equips better ones. Level = brings the equipped ones up.",
                    "Für Stigma-Aufträge wählst du hier den Titan. Löschen = nur 1 Stern + Level 1-79 der Sets, die du anhakst. "
                    + "Filtern = sieht neue Bull-Stigmas an und setzt bessere ein. Leveln = levelt die eingesetzten hoch."),
            new Schritt(S, "portal", PipBild.Pose.ERKLAERT, "Portal", "Portal",
                    "Portal plays level by level up to \"up to level\" (it watches the \"Start at level 80?\" video once a week). "
                    + "Then it farms up to 2 levels in auto battle: enter each level and the most food it may use. Food "
                    + "chests come only from your bag -- never diamonds.",
                    "Portal spielt Level für Level bis „bis Level“ (das Video „Start at level 80?“ schaut er einmal je Woche). "
                    + "Danach farmt er bis zu 2 Level im Autokampf: je Level die Nummer und die höchste Nahrung eintragen. "
                    + "Nahrungskisten nur aus der Tasche -- nie Diamanten."),
            new Schritt(S, "start", PipBild.Pose.ERKLAERT, "Start", "Start",
                    "One button for everything: starts what is chosen above. From then on the mouse belongs to the bot.",
                    "Ein Knopf für alles: startet, was oben gewählt ist. Ab dann gehört die Maus dem Bot."),
            new Schritt(S, "danach", PipBild.Pose.ERKLAERT, "Add afterwards", "Danach anhängen",
                    "Plans a second mode that starts by itself when the first one is done. First Start, then choose the "
                    + "next mode and press this button. As many as you like.",
                    "Plant einen zweiten Modus, der von selbst startet, wenn der erste fertig ist. Erst Start, dann den "
                    + "nächsten Modus wählen und diesen Knopf drücken. Beliebig viele hintereinander."),
            new Schritt(S, "kreislauf", PipBild.Pose.ERKLAERT, "Loop (event routine)", "Kreislauf (Event-Ablauf)",
                    "The routine without an end: Cathedral, and every N cathedrals the event (hammer or titan, see Settings), "
                    + "uses all hammers, then carries on. On the side Lighthouse, Colosseum, Titan tower -- and the Clan War "
                    + "at the war times always comes first. Perfect for the night.",
                    "Der Ablauf ohne Ende: Kathedrale, und alle N Kathedralen das Event (Hammer oder Titan, siehe Einstellungen) "
                    + "mit allen Hämmern, dann weiter. Nebenbei Leuchtturm, Kolosseum, Titanen-Turm -- und der Clan-Krieg geht "
                    + "zu den Kriegszeiten immer vor. Perfekt für die Nacht."),
            new Schritt(S, "kreislaufOhne", PipBild.Pose.ERKLAERT, "Loop (no event)", "Kreislauf ohne Event",
                    "The same routine without the event: Cathedral without end, on the side Lighthouse, Colosseum, Titan tower "
                    + "and Clan War.",
                    "Derselbe Ablauf ohne Event: Kathedrale ohne Ende, nebenbei Leuchtturm, Kolosseum, Titanen-Turm und Clan-Krieg."),
            new Schritt(S, "tasten", PipBild.Pose.ERKLAERT, "Stop and pause", "Anhalten und Pause",
                    "While the bot plays, use keys: emergency stop F9, pause/resume F10. Important: if you move the mouse, "
                    + "the bot stops by itself, so it never fights with you for the mouse -- then just start the next mode. "
                    + "You can switch that off in the settings (Mouse). Right after Start, keep the mouse still for a few seconds.",
                    "Während der Bot spielt, gehen Tasten: Not-Aus F9, Pause/Weiter F10. Wichtig: Bewegst du die Maus, "
                    + "stoppt der Bot von selbst, damit ihr euch nicht um die Maus streitet -- dann einfach den nächsten Modus starten. "
                    + "Abschaltbar in den Einstellungen (Maus). Direkt nach „Start“ die Maus ein paar Sekunden ruhig lassen."),
            new Schritt(S, "nachsehen", PipBild.Pose.ERKLAERT, "Look things up", "Nachsehen",
                    "Status, Statistics, To the castle, Screenshot, Help -- and this tutorial again. The answer opens in a "
                    + "small window.",
                    "Status, Statistik, In die Burg, Bildschirmfoto, Hilfe -- und dieses Tutorial noch einmal. Die Antwort "
                    + "geht in einem kleinen Fenster auf."),
            new Schritt(S, "befehl", PipBild.Pose.ERKLAERT, "Command like in Discord", "Befehl wie in Discord",
                    "Every Discord command also works here, without Discord: e.g. !status, !queue, !settings hammerevent 250 "
                    + "or !start arena 5. Enter = send.",
                    "Jeder Discord-Befehl geht auch hier, ohne Discord: z. B. !status, !queue, !settings hammerevent 250 "
                    + "oder !start arena 5. Enter = schicken."),
            // ---- Reiter Anzeigen ----
            new Schritt(A, "anzeigen", PipBild.Pose.ERKLAERT, "Windows", "Anzeigen",
                    "Show or hide the small windows: Status, Statistics, Resources (castle stock), Log, Notebook, Mini bar "
                    + "and Messages -- also by shortcut. \"Always on top\" keeps them in front of the game.",
                    "Kleine Fenster zeigen oder verstecken: Status, Statistik, Vorrat (Burg-Stand), Protokoll, Notizblock, "
                    + "Mini-Leiste und Meldungen -- auch per Tastenkürzel. „Immer im Vordergrund“ hält sie vor dem Spiel."),
            new Schritt(A, "anzeigen", PipBild.Pose.ERKLAERT, "Statistics", "Statistik",
                    "The Statistics window (Ctrl+Alt+S) shows the runs, runs per hour, arena places (top 1/2/3, per day) "
                    + "and your food, with three small charts. Cathedral times: !times -- Discord shows a card every 10 runs.",
                    "Das Statistik-Fenster (Strg+Alt+S) zeigt die Durchläufe, Runs pro Stunde, Arena-Plätze (Platz 1/2/3, je "
                    + "Tag) und deine Nahrung, mit drei kleinen Diagrammen. Kathedralen-Zeiten: !zeiten -- in Discord alle 10 Runs eine Karte."),
            // ---- Reiter Einstellungen ----
            new Schritt(E, "Nahrung", PipBild.Pose.ERKLAERT, "Food", "Nahrung",
                    "Fill this in now: may the bot pay with food? \"Food + chests\" also opens food chests from your bag. "
                    + "The food limit applies to all modes. Enter your storage size (at most 2,000,000).",
                    "Jetzt gleich ausfüllen: Darf der Bot mit Nahrung bezahlen? „Nahrung + Kisten“ öffnet auch Nahrungskisten "
                    + "aus der Tasche. Das Limit gilt für alle Modi. Trag deine Lagergröße ein (höchstens 2.000.000)."),
            new Schritt(E, "Nebenbei", PipBild.Pose.ERKLAERT, "On the side", "Nebenbei",
                    "What the bot does in between: Lighthouse and Colosseum (when the energy is full), Titan Tower "
                    + "(02:00 and 12:00) and setting up the Clan War by itself. On = yes.",
                    "Was der Bot zwischendurch erledigt: Leuchtturm und Kolosseum (wenn die Energie voll ist), Titanen-Turm "
                    + "(02:00 und 12:00) und den Clan-Krieg selbst aufstellen. An = ja."),
            new Schritt(E, "Truppe", PipBild.Pose.ERKLAERT, "Squad and Clan War", "Truppe und Clan-Krieg",
                    "\"Deploy the best\" or \"Previous squad\" per mode. Restart the game every 2 hours keeps it fast. "
                    + "Clan War at " + de.hustlebot.core.Kriegsplan.zeitenText(", ") + ": the bot pauses what it does, deploys both squads (fills a "
                    + "missing unit with your strongest) and carries on. Optional: a hero set before and after the war.",
                    "„Deploy the best“ oder „Previous squad“ je Modus. Spiel-Neustart alle 2 Stunden hält es schnell. "
                    + "Clan-Krieg um " + de.hustlebot.core.Kriegsplan.zeitenText(", ") + ": Der Bot unterbricht, was er tut, stellt beide Trupps auf "
                    + "(füllt eine fehlende Einheit mit deinen stärksten auf) und macht dann weiter. Auf Wunsch ein Helden-Set "
                    + "vor und nach dem Krieg."),
            new Schritt(E, "Event-Automatik", PipBild.Pose.ERKLAERT, "Event automation", "Event-Automatik",
                    "On/off for the event in the loop and which event: hammer or titan.",
                    "An/aus für das Event im Kreislauf und welches Event: Hammer oder Titan."),
            new Schritt(E, "Hammer-Event", PipBild.Pose.ERKLAERT, "Hammer event", "Hammer-Event",
                    "After how many cathedrals the bot goes into the hammer event (1 to 500), at most how many stages per "
                    + "visit (0 = all hammers), your event pass (gold, silver, none) and magnifiers per field.",
                    "Nach wie vielen Kathedralen der Bot ins Hammer-Event geht (1 bis 500), höchstens wie viele Stages je "
                    + "Gang (0 = alle Hämmer), dein Event-Pass (Gold, Silber, kein) und Lupen je Feld."),
            new Schritt(E, "Titan-Event", PipBild.Pose.ERKLAERT, "Titan event", "Titan-Event",
                    "After how many cathedrals the bot goes digging (1 to 500), whether it digs out all HC rewards, uses the "
                    + "drills and at which depth it stops.",
                    "Nach wie vielen Kathedralen der Bot graben geht (1 bis 500), ob er alle HC-Belohnungen ausgräbt, die "
                    + "Drills benutzt und bei welcher Tiefe er aufhört."),
            new Schritt(E, "Zeitzone", PipBild.Pose.ERKLAERT, "Time zone", "Zeitzone",
                    "All schedules (Clan War, PC off) follow this clock. Choose a city or type your own zone.",
                    "Alle Zeitpläne (Clan-Krieg, PC aus) gehen nach dieser Uhr. Wähle eine Stadt oder tipp deine Zone ein."),
            new Schritt(E, "Eigenes Team", PipBild.Pose.ERKLAERT, "Your team", "Dein Team",
                    "\"Scan now\" lets the bot look at your barracks once (takes a moment). It repeats that every 3 days "
                    + "by itself and reads your player name, too. Sacrifice units: names the arena may sacrifice.",
                    "„Jetzt ansehen“ lässt den Bot einmal deine Kaserne ansehen (dauert etwas). Das wiederholt er alle "
                    + "3 Tage von selbst und liest dabei deinen Spielernamen. Opfer-Einheiten: Namen, die die Arena opfern darf."),
            new Schritt(E, "Sprache", PipBild.Pose.ERKLAERT, "Language", "Sprache",
                    "In which language the bot writes its messages: English or German.",
                    "In welcher Sprache der Bot seine Meldungen schreibt: Deutsch oder Englisch."),
            new Schritt(E, "Ton", PipBild.Pose.ERKLAERT, "Sound", "Ton",
                    "Sound of the Discord notifications on or off (mute).",
                    "Ton der Discord-Benachrichtigungen an oder aus (stumm)."),
            new Schritt(E, "Autostart", PipBild.Pose.ERKLAERT, "Autostart", "Autostart",
                    "On = the bot starts by itself when Windows starts.",
                    "An = der Bot startet von selbst, wenn Windows hochfährt."),
            new Schritt(E, "Discord", PipBild.Pose.ERKLAERT, "Discord (optional)", "Discord (freiwillig)",
                    "With Discord you control the bot from your phone. \"Open guide\" explains token and channel. "
                    + "Everything works without Discord too.",
                    "Mit Discord steuerst du den Bot vom Handy aus. „Anleitung öffnen“ erklärt Token und Kanal. "
                    + "Ohne Discord geht alles hier genauso."),
            new Schritt(E, "Maus", PipBild.Pose.ERKLAERT, "Mouse", "Maus",
                    "On = the bot stops as soon as you move the mouse, so you can always take over.",
                    "An = der Bot hält an, sobald du die Maus bewegst -- so kannst du jederzeit übernehmen."),
            new Schritt(E, "Arena", PipBild.Pose.ERKLAERT, "Arena", "Arena",
                    "The bot can watch your own arena fights: it counts wins and losses and never clicks. The buttons open "
                    + "your opponent lists (Safe, Doable, Avoid, Never, Clans) -- the same as \"My pictures\\Arena lists\" in "
                    + "your bot folder.",
                    "Der Bot kann deine eigenen Arena-Kämpfe beobachten: Er zählt Siege und Niederlagen und klickt nie. Die "
                    + "Knöpfe öffnen deine Gegner-Listen (Sicher, Machbar, Ungern, Niemals, Clans) -- dieselben wie der Ordner "
                    + "„My pictures\\Arena lists“ in deinem Bot-Ordner."),
            new Schritt(E, "Spielkonten", PipBild.Pose.ERKLAERT, "Game accounts", "Spielkonten",
                    "Several game accounts on one PC: the bot recognises them by the player name.",
                    "Mehrere Spielkonten auf einem PC: Der Bot erkennt sie am Spielernamen."),
            new Schritt(E, "Mehr", PipBild.Pose.ERKLAERT, "More", "Mehr",
                    "\"Show all settings\" lists everything at once, \"What's new\" shows the changes of the last update.",
                    "„Alle Einstellungen zeigen“ listet alles auf einmal, „Was ist neu“ zeigt die Änderungen des letzten Updates."),
            // ---- Reiter Tastenkuerzel, Profile, Werkzeuge, Support ----
            new Schritt(K, "Anhalten", PipBild.Pose.ERKLAERT, "Keys", "Tasten",
                    "Here you can change the emergency key and all shortcuts -- any key you like. Ctrl+Alt+B shows or hides "
                    + "this bot program.",
                    "Hier änderst du die Not-Aus-Taste und alle Kürzel – jede Taste, die du willst. Strg+Alt+B zeigt oder "
                    + "versteckt dieses Bot-Programm."),
            new Schritt(P, "profile", PipBild.Pose.ERKLAERT, "Profiles", "Profile",
                    "Save your favourite starts as a profile (e.g. \"Night\"): name on the right, commands below, Save. "
                    + "\"Apply\" sends them one after another; profiles also appear in the mode list.",
                    "Speichere deine Lieblings-Starts als Profil (z. B. „Nacht“): rechts der Name, darunter die Befehle, "
                    + "Speichern. „Anwenden“ schickt sie nacheinander; Profile stehen auch in der Modus-Liste."),
            new Schritt(W, "werkzeuge", PipBild.Pose.ERKLAERT, "Tools", "Werkzeuge",
                    "Capture program for pictures, PC check (is the PC fit for the bot?) and the PC cleaner.",
                    "Aufnahme-Programm für Bilder, PC-Check (passt der Rechner?) und der PC-Reiniger."),
            new Schritt(W, "werkzeuge", PipBild.Pose.ERKLAERT, "Capture program", "Aufnahme-Programm",
                    "Capture-Images: pick in the list what you want to teach the bot -- \"Arena: your name\", a dungeon "
                    + "teammate, a button, or \"Own event item 1-10\" (rewards the bot should take). \"Take picture\", drag a "
                    + "tight frame, save, restart the bot. Your pictures land in the folder \"My pictures\" -- Autor's "
                    + "own pictures stay hidden.",
                    "Capture-Images: In der Liste wählen, was der Bot lernen soll -- „Arena: dein Name“, einen Mitspieler "
                    + "fürs Verlies, einen Knopf oder „Eigenes Event-Motiv 1-10“ (Belohnungen, die er nehmen soll). „Bild "
                    + "aufnehmen“, eng umrahmen, speichern, Bot neu starten. Deine Bilder landen im Ordner „My pictures“ "
                    + "-- Autors eigene Bilder bleiben versteckt."),
            new Schritt(U, "support", PipBild.Pose.ERKLAERT, "Support", "Support",
                    "If something goes wrong: \"Save error report (ZIP)\" saves the log, a picture of the game and your "
                    + "settings as a ZIP on this PC -- never your token. \"Create error report\" puts it into a folder.",
                    "Wenn etwas schiefgeht: „Fehlerbericht speichern (ZIP)“ speichert Protokoll, ein Bild vom Spiel und "
                    + "deine Einstellungen als ZIP auf diesem PC -- nie den Token. „Fehlerbericht erstellen“ legt alles in einen Ordner."),
            new Schritt(S, null, PipBild.Pose.GESCHAFFT, "Done!", "Geschafft!",
                    "That's it! You can open me again any time with the \"Tutorial\" button. Can't see the bot program? "
                    + "Ctrl+Alt+B or double-click the bot icon at the bottom right (next to the clock). Have fun!",
                    "Das war's! Du kannst mich jederzeit über den Knopf „Tutorial“ wieder holen. Bot-Programm nicht zu sehen? "
                    + "Strg+Alt+B oder Doppelklick auf das Bot-Symbol unten rechts (neben der Uhr). Viel Spaß!"));

    // ------------------------------------------------------------------------------------------------------------
    //  Autor 24.10. (Ideenliste F31): zwei Stufen
    //  1. Kurzfassung beim ersten Start: nur die 5 Dinge, die man zum Loslegen braucht.
    //  2. Die lange Tour in Kapiteln -- ueber den Knopf "Tutorial (Pip)": ein Kapitel waehlen oder "weiter, wo ich war".
    // ------------------------------------------------------------------------------------------------------------

    /** Die Kurzfassung: Modus, Anzahl, Start, Pause/Stopp, Hilfe -- fuenf Schritte, dieselben markierten Stellen wie in der Tour. */
    static final List<Schritt> KURZ = List.of(
            new Schritt(S, "modus", PipBild.Pose.HALLO, "Hello, I am Pip! 1: What to play", "Hallo, ich bin Pip! 1: Was gespielt wird",
                    "I'll show you the 5 most important things in a minute. First: here you choose what the bot plays -- "
                    + "Cathedral, Dungeon, Arena, Portal, Titan excavation ... Your own profiles are in this list too.",
                    "Ich zeige dir in einer Minute die 5 wichtigsten Dinge. Zuerst: Hier wählst du, was der Bot spielt -- "
                    + "Kathedrale, Verlies, Arena, Portal, Titan-Grabung ... Auch deine Profile stehen in dieser Liste."),
            new Schritt(S, "anzahl", PipBild.Pose.ERKLAERT, "2: How many runs", "2: Wie oft",
                    "How many runs the bot plays (Arena: tournaments, Hammer event: fields).",
                    "Wie viele Durchläufe der Bot spielt (Arena: Turniere, Hammer-Event: Felder)."),
            new Schritt(S, "start", PipBild.Pose.ERKLAERT, "3: Start", "3: Start",
                    "One button for everything: starts what is chosen above. From then on the mouse belongs to the bot -- "
                    + "keep it still for a few seconds.",
                    "Ein Knopf für alles: startet, was oben gewählt ist. Ab dann gehört die Maus dem Bot -- lass sie ein "
                    + "paar Sekunden in Ruhe."),
            new Schritt(S, "tasten", PipBild.Pose.ERKLAERT, "4: Stop and pause", "4: Anhalten und Pause",
                    "Emergency stop F9, pause/resume F10. If you move the mouse while the bot plays, it stops by itself -- "
                    + "then just start again.",
                    "Not-Aus F9, Pause/Weiter F10. Bewegst du die Maus, während der Bot spielt, hält er von selbst an -- "
                    + "dann einfach wieder starten."),
            new Schritt(U, "support", PipBild.Pose.GESCHAFFT, "5: Help", "5: Hilfe",
                    "If something goes wrong: \"Save error report (ZIP)\" here. And if you want to know everything: the "
                    + "button \"Tutorial (Pip)\" on the Control tab gives you the long tour in chapters -- and continues where "
                    + "you stopped. You can also type a word in the search field at the top: \"Where do I find ...?\" Have fun!",
                    "Wenn etwas schiefgeht: hier „Fehlerbericht speichern (ZIP)“. Und wenn du alles wissen willst: Der Knopf "
                    + "„Tutorial (Pip)“ auf der Steuerung gibt dir die lange Tour in Kapiteln -- und macht dort weiter, wo du "
                    + "aufgehört hast. Oben im Suchfeld findest du auch mit einem Wort: „Wo finde ich ...?“ Viel Spaß!"));

    /** Ein Kapitel der langen Tour: von bis bis (Stellen in SCHRITTE, beide einschliesslich). */
    record Kapitel(String titelEn, String titelDe, int von, int bis) {
        String titel() { return Sprache.t(titelEn, titelDe); }
        int groesse() { return bis - von + 1; }
    }

    /** Die Kapitel nach Reitern: Steuerung, Fenster und Statistik, Einstellungen, Tasten/Profile/Werkzeuge, Support. */
    static final List<Kapitel> KAPITEL = kapitelBauen();

    private static int kapitelNummer(Schritt s, boolean letzter) {
        if (letzter) return 4;   // das "Geschafft" am Ende gehoert zum letzten Kapitel
        int r = s.reiter();
        if (r == S) return 0;
        if (r == A) return 1;
        if (r == E) return 2;
        if (r == U) return 4;
        return 3;                // Tasten, Profile, Werkzeuge
    }

    private static List<Kapitel> kapitelBauen() {
        String[][] namen = {
                {"Control: modes, start, loop, keys", "Steuerung: Modi, Start, Kreislauf, Tasten"},
                {"Windows and statistics", "Fenster und Statistik"},
                {"Settings", "Einstellungen"},
                {"Shortcuts, profiles and tools", "Tastenkürzel, Profile und Werkzeuge"},
                {"Support", "Support"}};
        int[] von = {-1, -1, -1, -1, -1}, bis = {-1, -1, -1, -1, -1};
        for (int i = 0; i < SCHRITTE.size(); i++) {
            int k = kapitelNummer(SCHRITTE.get(i), i == SCHRITTE.size() - 1);
            if (von[k] < 0) von[k] = i;
            bis[k] = i;
        }
        List<Kapitel> l = new java.util.ArrayList<>();
        for (int k = 0; k < namen.length; k++) {
            if (von[k] >= 0) l.add(new Kapitel(namen[k][0], namen[k][1], von[k], bis[k]));
        }
        return List.copyOf(l);
    }

    /** Wo die lange Tour zuletzt stand (Stelle in SCHRITTE); fehlt der Eintrag oder ist die Tour fertig: -1. */
    static final String POSITION = "tutorial.position";

    /** Das Kapitel, in dem diese Stelle der langen Tour liegt (oder null). */
    static Kapitel kapitelVon(int stelle) {
        for (Kapitel k : KAPITEL) if (stelle >= k.von() && stelle <= k.bis()) return k;
        return null;
    }

    private final Steuerfenster fenster;
    private final Steuerprogramm steuer;
    /** Die Schritte dieser Tour (Kurzfassung, ein Kapitel oder die ganze lange Tour). */
    private final List<Schritt> schritte;
    /** Stelle des ersten Schritts in SCHRITTE, wenn dies ein Stueck der langen Tour ist; sonst -1 (Kurzfassung). */
    private final int ab;
    private final JDialog dialog;
    private final PipBild pip = new PipBild();
    private final JLabel titel = new JLabel();
    private final JTextArea text = Holzstil.lesebereich();
    private final JLabel zaehler = Holzstil.hinweis(" ");
    private final JButton weiter, zurueck, ueberspringen;
    private int nr;

    private static Tutorial offen;

    /** Der Knopf "Tutorial (Pip)": erst die Wahl -- Kurzfassung, ein Kapitel oder "weiter, wo ich war". */
    static void starten(Steuerfenster fenster, Steuerprogramm steuer) {
        if (offen != null) {
            offen.dialog.toFront();
            return;
        }
        waehlen(fenster, steuer);
    }

    /** Beim allerersten Start: gleich die Kurzfassung (5 Schritte). */
    static void erststart(Steuerfenster fenster, Steuerprogramm steuer) {
        if (offen != null) {
            offen.dialog.toFront();
            return;
        }
        los(fenster, steuer, KURZ, -1, 0);
    }

    /** Eine Tour beginnen: {@code schritte}, die ab Stelle {@code ab} in SCHRITTE liegen (-1 = Kurzfassung), bei Schritt {@code erster}. */
    private static void los(Steuerfenster fenster, Steuerprogramm steuer, List<Schritt> schritte, int ab, int erster) {
        offen = new Tutorial(fenster, steuer, schritte, ab);
        offen.zeigen(Math.max(0, Math.min(erster, schritte.size() - 1)));
        offen.dialog.setVisible(true);
    }

    /** Die Wahl: Kurzfassung / weiter, wo ich war / ein Kapitel / alles von vorn. */
    private static void waehlen(Steuerfenster fenster, Steuerprogramm steuer) {
        JDialog d = new JDialog(fenster, Sprache.t("Tutorial with Pip", "Tutorial mit Pip"), true);
        JPanel p = Holzstil.grund(new GridBagLayout(), Holzstil.LUFT);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        int y = 0;
        c.gridy = y++;
        p.add(Holzstil.beschriftung(Sprache.t("What would you like to see?", "Was möchtest du sehen?")), c);
        int pos = steuer.einst.zahl(POSITION, -1);
        Kapitel dort = kapitelVon(pos);
        if (pos > 0 && dort != null && pos < SCHRITTE.size() - 1) {
            JButton weiter = Holzstil.knopf(Sprache.t("Continue where I was (" + dort.titel() + ", step " + (pos - dort.von() + 1) + " of " + dort.groesse() + ")",
                            "Weiter, wo ich war (" + dort.titel() + ", Schritt " + (pos - dort.von() + 1) + " von " + dort.groesse() + ")"),
                    Sprache.t("Picks up the long tour at the step where you stopped.", "Macht die lange Tour dort weiter, wo du aufgehört hast."),
                    Holzstil.GUT, Symbol.Art.START);
            weiter.addActionListener(e -> {
                d.dispose();
                los(fenster, steuer, SCHRITTE.subList(pos, SCHRITTE.size()), pos, 0);
            });
            c.gridy = y++;
            p.add(weiter, c);
        }
        JButton kurz = Holzstil.knopf(Sprache.t("Quick tour (5 steps)", "Kurzfassung (5 Schritte)"),
                Sprache.t("Mode, number, start, stop and help -- that's all you need to begin.",
                        "Modus, Anzahl, Start, Anhalten und Hilfe -- mehr brauchst du zum Loslegen nicht."), Symbol.Art.STERN);
        kurz.addActionListener(e -> {
            d.dispose();
            los(fenster, steuer, KURZ, -1, 0);
        });
        c.gridy = y++;
        p.add(kurz, c);
        c.gridy = y++;
        p.add(Holzstil.beschriftung(Sprache.t("The long tour in chapters:", "Die lange Tour in Kapiteln:")), c);
        for (Kapitel k : KAPITEL) {
            JButton b = Holzstil.kleinerKnopf(k.titel() + "  (" + k.groesse() + ")",
                    Sprache.t("Only this chapter.", "Nur dieses Kapitel."));
            b.addActionListener(e -> {
                d.dispose();
                los(fenster, steuer, SCHRITTE.subList(k.von(), k.bis() + 1), k.von(), 0);
            });
            c.gridy = y++;
            p.add(b, c);
        }
        JButton alles = Holzstil.kleinerKnopf(Sprache.t("Everything from the start", "Alles von vorn"),
                Sprache.t("All chapters one after another.", "Alle Kapitel nacheinander."));
        alles.addActionListener(e -> {
            d.dispose();
            los(fenster, steuer, SCHRITTE, 0, 0);
        });
        c.gridy = y++;
        p.add(alles, c);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(fenster);
        d.setVisible(true);
    }

    private Tutorial(Steuerfenster fenster, Steuerprogramm steuer, List<Schritt> schritte, int ab) {
        this.fenster = fenster;
        this.steuer = steuer;
        this.schritte = schritte;
        this.ab = ab;
        dialog = new JDialog(fenster, Sprache.t("Tutorial with Pip", "Tutorial mit Pip"), false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { ende(); }
        });
        titel.setFont(Holzstil.GROSS);
        titel.setForeground(Holzstil.TEXT);
        text.setFont(Holzstil.NORMAL);
        text.setOpaque(false);
        // Autor 15.10.: "kleiner, besser ausgeschnitten, transparent -- Pip ueberdeckt Knoepfe". Darum: kein Fensterrahmen,
        // Pip ohne Kasten, Text in einer Sprechblase, kleine Knoepfe, und bei jedem Schritt in die Ecke, die die
        // markierte Stelle frei laesst (mit der Maus verschiebbar).
        dialog.setUndecorated(true);
        try {
            dialog.setBackground(new Color(0, 0, 0, 0));   // durchsichtig, wo nichts gemalt ist
        } catch (UnsupportedOperationException ohneTransparenz) {
            // Ohne Transparenz (sehr alte Grafik): normaler Hintergrund, alles andere bleibt gleich.
        }
        weiter = Holzstil.kleinerKnopf(Sprache.t("Got it", "Verstanden"), Sprache.t("Next step", "Nächster Schritt"), Symbol.Art.HAKEN);
        zurueck = Holzstil.kleinerKnopf(Sprache.t("Back", "Zurück"), Sprache.t("Previous step", "Vorheriger Schritt"));
        ueberspringen = Holzstil.kleinerKnopf(Sprache.t("Skip", "Überspringen"), Sprache.t("Skip this step", "Diesen Schritt auslassen"));
        JButton beenden = Holzstil.kleinerKnopf(Sprache.t("End", "Ende"), Sprache.t("End the tutorial", "Tutorial beenden"), Symbol.Art.KREUZ);
        weiter.addActionListener(e -> { if (nr >= schritte.size() - 1) ende(); else zeigen(nr + 1); });
        ueberspringen.addActionListener(e -> { if (nr >= schritte.size() - 1) ende(); else zeigen(nr + 1); });
        zurueck.addActionListener(e -> zeigen(Math.max(0, nr - 1)));
        beenden.addActionListener(e -> ende());

        JPanel kopf = durchsichtig(new BorderLayout(Holzstil.LUFT_KLEIN, 0));
        kopf.add(titel, BorderLayout.CENTER);
        JPanel kopfRechts = durchsichtig(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0));
        kopfRechts.add(zaehler);
        kopfRechts.add(beenden);
        kopf.add(kopfRechts, BorderLayout.EAST);
        JPanel knoepfe = durchsichtig(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        knoepfe.add(weiter);
        knoepfe.add(ueberspringen);
        knoepfe.add(zurueck);
        JPanel rechts = new Sprechblase();
        rechts.add(kopf, BorderLayout.NORTH);
        rechts.add(text, BorderLayout.CENTER);
        rechts.add(knoepfe, BorderLayout.SOUTH);
        JPanel inhalt = durchsichtig(new BorderLayout(0, 0));
        inhalt.add(pip, BorderLayout.WEST);
        inhalt.add(rechts, BorderLayout.CENTER);
        dialog.setContentPane(inhalt);
        verschiebbar(inhalt);
        verschiebbar(rechts);
        verschiebbar(pip);
        dialog.setSize(BREITE, 170);
    }

    private static final int BREITE = 470;
    private static final int RAND = 10;
    /** Hat Autor Pip selbst verschoben, bleibt er dort (keine automatische Ecke mehr). */
    private boolean vonHandVerschoben;

    private static JPanel durchsichtig(LayoutManager l) {
        JPanel p = new JPanel(l);
        p.setOpaque(false);
        return p;
    }

    /** Mit der Maus ziehen = Pip verschieben. */
    private void verschiebbar(JComponent c) {
        java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter() {
            private Point start;
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                start = e.getLocationOnScreen();
                Point d = dialog.getLocation();
                start.translate(-d.x, -d.y);
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (start == null) return;
                Point p = e.getLocationOnScreen();
                dialog.setLocation(p.x - start.x, p.y - start.y);
                vonHandVerschoben = true;
            }
        };
        c.addMouseListener(m);
        c.addMouseMotionListener(m);
    }

    /** Hoehe passend zum Text, Platz in der Ecke des Steuerfensters, die das markierte Ziel nicht verdeckt. */
    private void platzieren(JComponent ziel) {
        int textBreite = BREITE - pip.getPreferredSize().width - 40;
        text.setSize(textBreite, Short.MAX_VALUE);
        int hoehe = Math.max(150, text.getPreferredSize().height + 92);
        dialog.setSize(BREITE, hoehe);
        if (vonHandVerschoben) return;
        Rectangle f = fenster.getBounds();
        Rectangle z = ziel != null && ziel.isShowing() ? new Rectangle(ziel.getLocationOnScreen(), ziel.getSize()) : null;
        int rechtsX = f.x + Math.max(0, f.width - BREITE - RAND), linksX = f.x + RAND;
        int untenY = f.y + Math.max(0, f.height - hoehe - RAND), obenY = f.y + Math.min(110, Math.max(0, f.height - hoehe));
        Point[] ecken = {new Point(rechtsX, untenY), new Point(linksX, untenY), new Point(rechtsX, obenY), new Point(linksX, obenY)};
        Point beste = ecken[0];
        long wenigsteUeberdeckung = Long.MAX_VALUE;
        for (Point p : ecken) {
            long ueber = 0;
            if (z != null) {
                Rectangle schnitt = new Rectangle(p.x, p.y, BREITE, hoehe).intersection(z);
                ueber = schnitt.isEmpty() ? 0 : (long) schnitt.width * schnitt.height;
            }
            if (ueber < wenigsteUeberdeckung) {
                wenigsteUeberdeckung = ueber;
                beste = p;
            }
            if (ueber == 0) break;
        }
        dialog.setLocation(beste);
    }

    /** Die Sprechblase rechts neben Pip: abgerundet, leicht durchscheinend, mit Spitze zu Pip. */
    private static final class Sprechblase extends JPanel {
        Sprechblase() {
            super(new BorderLayout(0, Holzstil.LUFT_KLEIN));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 12));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Holzstil.weich(g0);
            int spitze = 10, w = getWidth() - spitze - 2, h = getHeight() - 2;
            Color grund = Holzstil.GRUND;
            Shape blase = new RoundRectangle2D.Double(spitze, 1, w, h, 22, 22);
            Path2D zipfel = new Path2D.Double();
            zipfel.moveTo(spitze + 1, 46);
            zipfel.lineTo(0, 58);
            zipfel.lineTo(spitze + 1, 66);
            zipfel.closePath();
            g.setColor(new Color(grund.getRed(), grund.getGreen(), grund.getBlue(), 238));
            g.fill(blase);
            g.fill(zipfel);
            g.setColor(Holzstil.LINIE);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(blase);
            g.dispose();
        }
    }

    private void zeigen(int i) {
        nr = i;
        Schritt s = schritte.get(i);
        // F31: bei der langen Tour merken, wo man steht -- "weiter, wo ich war"
        if (ab >= 0) steuer.einst.setzen(POSITION, ab + i);
        fenster.reiterWaehlen(s.reiter());
        JComponent ziel = s.ziel() == null ? null : fenster.ziel(s.ziel());
        fenster.markieren(ziel);
        pip.zaubern(s.pose());
        titel.setText(s.titel());
        text.setText(s.text());
        zaehler.setText((i + 1) + " / " + schritte.size());
        boolean letzter = i == schritte.size() - 1;
        weiter.setText(letzter ? Sprache.t("Finish", "Fertig") : Sprache.t("Got it", "Verstanden"));
        ueberspringen.setEnabled(!letzter);
        zurueck.setEnabled(i > 0);
        // Erst nach dem Umschalten des Reiters steht das Ziel an seinem Platz.
        SwingUtilities.invokeLater(() -> platzieren(ziel));
    }

    private void ende() {
        fenster.markieren(null);
        pip.stoppen();
        steuer.einst.setzen(GESEHEN, true);
        // Die ganze lange Tour bis zum Schluss gesehen: nichts mehr zum Weitermachen. Sonst bleibt die Stelle stehen.
        if (ab >= 0 && ab + nr >= SCHRITTE.size() - 1) steuer.einst.setzen(POSITION, -1);
        dialog.dispose();
        offen = null;
    }

    // ==================================================================
    //  Pip -- mit Java2D gemalt (wie doku/helfer-pip.svg), mit Zauber-Funken
    // ==================================================================

    static final class PipBild extends JComponent {
        enum Pose { HALLO, ERKLAERT, GESCHAFFT }

        private Pose pose = Pose.HALLO;
        private long zauberSeit = 0;
        private final Timer takt = new Timer(40, e -> repaint());

        PipBild() {
            setPreferredSize(new Dimension(118, 150));
            setOpaque(false);
        }

        void zaubern(Pose p) {
            pose = p;
            zauberSeit = System.currentTimeMillis();
            if (!takt.isRunning()) takt.start();
            repaint();
        }

        void stoppen() {
            takt.stop();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Holzstil.weich(g0);
            // 15.10.: ohne Kasten -- Pip steht ausgeschnitten vor dem Steuerfenster.
            g.translate(getWidth() / 2.0 - 4, getHeight() / 2.0 + 16);
            g.scale(0.5, 0.5);
            long t = System.currentTimeMillis();
            // sanftes Schweben
            g.translate(0, Math.sin(t / 400.0) * 3);
            malePip(g);
            // Zauberstab-Spitze je Pose
            double sx = pose == Pose.GESCHAFFT ? 100 : 110, sy = pose == Pose.GESCHAFFT ? -150 : -86;
            double phase = (t - zauberSeit) / 1200.0;
            if (phase < 1) {
                for (int k = 0; k < 7; k++) {
                    double w = k * Math.PI * 2 / 7 + phase;
                    double r = 10 + phase * 55;
                    float a = (float) Math.max(0, 1 - phase);
                    stern(g, sx + Math.cos(w) * r, sy + Math.sin(w) * r, 7 + 3 * (1 - phase), a);
                }
            } else {
                stern(g, sx, sy, 5 + Math.sin(t / 200.0) * 2, 0.9f);
            }
            g.dispose();
        }

        private void malePip(Graphics2D g) {
            Color fell = new Color(0x6c7fb3), fellHell = new Color(0x9fb0dd);
            // Fluegel / Arme
            g.setStroke(new BasicStroke(18, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(fell);
            if (pose == Pose.GESCHAFFT) {
                g.draw(new QuadCurve2D.Double(-70, -5, -106, -40, -90, -84));
                g.draw(new QuadCurve2D.Double(70, -5, 106, -40, 90, -84));
            } else if (pose == Pose.HALLO) {
                g.fill(new Ellipse2D.Double(-112, -82, 34, 70));
                g.draw(new QuadCurve2D.Double(68, 10, 92, 2, 94, -18));
            } else {
                g.draw(new QuadCurve2D.Double(68, 6, 100, -10, 108, -40));
                g.draw(new QuadCurve2D.Double(-68, 8, -86, 22, -80, 44));
            }
            // Zauberstab
            g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0xf3e6d0));
            if (pose == Pose.GESCHAFFT) g.draw(new Line2D.Double(90, -84, 100, -150));
            else if (pose == Pose.HALLO) g.draw(new Line2D.Double(94, -16, 110, -86));
            else g.draw(new Line2D.Double(108, -38, 110, -86));
            // Federohren
            g.setColor(fell);
            g.fill(dreieck(-58, -40, -50, -84, -24, -58));
            g.fill(dreieck(58, -40, 50, -84, 24, -58));
            // Koerper mit Verlauf
            g.setPaint(new RadialGradientPaint(new Point2D.Double(-20, -30), 110, new float[]{0f, 1f}, new Color[]{fellHell, fell}));
            g.fill(new Ellipse2D.Double(-76, -80, 152, 160));
            g.setColor(new Color(0xe6ecf8));
            g.fill(new Ellipse2D.Double(-46, -12, 92, 84));
            // Baeckchen, Schnabel, Fuesse
            g.setColor(new Color(242, 167, 167, 170));
            g.fill(new Ellipse2D.Double(-69, -9, 22, 14));
            g.fill(new Ellipse2D.Double(47, -9, 22, 14));
            g.setColor(new Color(0xe0a12b));
            g.fill(dreieck(-8, 6, 8, 6, 0, 20));
            g.setColor(new Color(0xf0a52b));
            g.fill(new Ellipse2D.Double(-44, 72, 28, 14));
            g.fill(new Ellipse2D.Double(16, 72, 28, 14));
            // Augen
            if (pose == Pose.GESCHAFFT) {
                g.setColor(new Color(0x2b1d12));
                g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new QuadCurve2D.Double(-50, -20, -28, -42, -6, -20));
                g.draw(new QuadCurve2D.Double(6, -20, 28, -42, 50, -20));
                g.setColor(new Color(0xc0392b));
                g.fill(new Arc2D.Double(-14, 30, 28, 22, 180, 180, Arc2D.CHORD));
            } else {
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(-55, -49, 54, 54));
                g.fill(new Ellipse2D.Double(1, -49, 54, 54));
                g.setColor(new Color(0x2b1d12));
                g.fill(new Ellipse2D.Double(-38, -32, 26, 26));
                g.fill(new Ellipse2D.Double(12, -32, 26, 26));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(-26, -29, 9, 9));
                g.fill(new Ellipse2D.Double(24, -29, 9, 9));
                g.setColor(new Color(0x2b1d12));
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new QuadCurve2D.Double(-10, 44, 0, 53, 10, 44));
            }
            // Brille
            g.setColor(new Color(0xe0b62b));
            g.setStroke(new BasicStroke(5));
            g.draw(new Ellipse2D.Double(-58, -52, 60, 60));
            g.draw(new Ellipse2D.Double(-2, -52, 60, 60));
        }

        private static Shape dreieck(double x1, double y1, double x2, double y2, double x3, double y3) {
            Path2D p = new Path2D.Double();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            p.lineTo(x3, y3);
            p.closePath();
            return p;
        }

        private static void stern(Graphics2D g, double x, double y, double r, float alpha) {
            Path2D p = new Path2D.Double();
            for (int k = 0; k < 8; k++) {
                double w = k * Math.PI / 4, rr = k % 2 == 0 ? r : r * 0.32;
                double px = x + Math.cos(w) * rr, py = y + Math.sin(w) * rr;
                if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            p.closePath();
            Composite alt = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
            g.setColor(new Color(0xffd84a));
            g.fill(p);
            g.setComposite(alt);
        }
    }
}
