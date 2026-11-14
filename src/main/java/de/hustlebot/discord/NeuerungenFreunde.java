package de.hustlebot.discord;

import de.hustlebot.core.Sprache;

import java.util.Comparator;
import java.util.List;

/**
 * "!update" im Freunde-Bot: was die Fassung neu kann, wichtig zuerst.
 * Neueste Eintraege oben einfuegen. Wichtigkeit: 3 gross, 2 spuerbar, 1 klein.
 */
public final class NeuerungenFreunde {

    private record Eintrag(String fassung, int wichtig, String de, String en) { }

    private static final List<Eintrag> LISTE = List.of(
            // 3.3 (21.10.): Befehle aus dem Hauptbot.
            new Eintrag("3.3", 2, "Neue Befehle: `!wach 5` schickt alle 5 Minuten ein Bild vom Spiel (`!wach aus`), `!turm` und `!clanladen` starten Titanen-Turm bzw. Clan-Laden sofort, `!spiel` startet Hustle Castle, `!event rangliste` schickt waehrend des Hammer-Events ein Bild der Rangliste, `!arena Rina sicher` merkt einen Gegner fuer die Arena-Listen vor (auch machbar / meiden / niemals).",
                        "New commands: `!watch 5` sends a picture of the game every 5 minutes (`!watch off`), `!tower` and `!clanstore` start the Titan tower or the clan store right away, `!game` starts Hustle Castle, `!event ranking` sends a picture of the ranking during the hammer event, `!arena Rina safe` notes an opponent for the arena lists (also doable / avoid / never)."),
            // 3.3 (18.10.): Verlies-Leiter ohne Discord (Ben).
            new Eintrag("3.3", 3, "Verlies: Bist du Leiter, erkennt der Bot das jetzt auch an deinem Namen und waehlt selbst den Stuetzpunkt. Waehlt der Leiter um, folgt der Bot sofort.",
                        "Dungeon: if you are the leader, the bot now also recognises that by your name and picks the waypoint itself. If the leader changes the waypoint, the bot follows at once."),
            // 3.3 (18.10.): Schalter fuers Titan-Event.
            new Eintrag("3.3", 3, "Titan-Event: In den Einstellungen unter \"Event-Automatik\" gibt es jetzt Knoepfe fuer \"alle HC-Belohnungen ausgraben\" (sonst gerade runter) und \"auch die Drills benutzen\". Super-Spitzhacke und Dynamit landen ohne HC im Bild auf dem tiefsten offenen Feld statt fest in Spalte 3",
                        "Titan event: in the settings under \"Event automation\" there are now buttons for \"dig out all HC rewards\" (otherwise straight down) and \"also use the drills\". Without an HC in view, super pickaxe and dynamite go onto the deepest open block instead of always column 3"),
            // 3.3 (17.10.): Kathedralen-Zeitmesser.
            new Eintrag("3.3", 3, "Kathedralen-Zeitmesser: Nach jedem Run kommt eine Meldung mit der Zeit (wofuer sie draufging: Kaempfe, Segen, Wegwahl, Laden) und dem Schnitt der letzten 10 Runs. `!zeiten` zeigt die letzten 10 Runs mit schnellstem und langsamstem",
                        "Cathedral stopwatch: after every run a message shows the time (where it went: fights, blessings, path choice, loading) and the average of the last 10 runs. `!times` shows the last 10 runs with the fastest and slowest"),
            // 3.3 (17.10.): eigene Vorlagen.
            new Eintrag("3.3", 3, "Der Bot lernt deinen Bildschirm: Jeden Knopf, den er sehr sicher erkennt, nimmt er einmal von deinem Bildschirm auf und benutzt ab dann dieses Bild (templates\\gelernt\\eigen). Haengt er einmal 2 Minuten, nimmt er alle eigenen Bilder zurueck und arbeitet wieder mit Autors Bildern",
                        "The bot learns your screen: every button it recognizes very surely is captured once from your screen and used from then on (templates\\gelernt\\eigen). If it ever gets stuck for 2 minutes, it takes all own pictures back and works with Autor's pictures again"),
            // 3.3 (17.10.): Kathedrale robuster (Karla).
            new Eintrag("3.3", 3, "Kathedrale robuster bei anderer Windows-Skalierung oder zweitem Bildschirm: Findet der Bot \"Bestaetigen\", \"Deploy the best\" oder \"Quick Attack\" nicht, sucht er sie in anderer Groesse, tippt notfalls die bekannte Stelle und lernt den Knopf dann fuer deinen PC. Bewirkt ein Tipp nichts, probiert er andere Klickarten (laenger druecken usw.). Einmal nach dem Update schickt er Autor eine Fotoserie aus der Kathedrale",
                        "Cathedral more robust with a different Windows scaling or a second screen: if the bot cannot find \"Confirm\", \"Deploy the best\" or \"Quick Attack\", it looks for them in a different size, taps the known place if needed and then learns the button for your PC. If a tap does nothing, it tries other kinds of taps (longer press etc.). Once after the update it sends Autor a photo series from the cathedral"),
            // 3.3 (17.10.): Fehlerberichte automatisch.
            // 3.3 (17.10.): Titan-Event wie beim Hauptbot -- HC-Belohnungen gezielt ausgraben.
            new Eintrag("3.3", 3, "Titan-Event: Der Bot graebt nicht mehr nur gerade runter, sondern den kuerzesten Weg zu jeder HC-Belohnung im Bild -- mit Spitzhacken, Super-Spitzhacken, Dynamit und Drills (Drill nur auf Spalten, neben denen schon etwas offen ist). Ausschalten: `!titan hc aus`",
                        "Titan event: the bot no longer only digs straight down, it digs the shortest way to every HC reward it sees -- with pickaxes, super pickaxes, dynamite and drills (drill only on columns next to an open one). Switch off: `!titan hc off`"),
            // 3.3 (16.10.): Sicherheit und Fehlerbehebungen.
            new Eintrag("3.3", 3, "Neues Steuerprogramm wie beim Hauptbot: Tutorial mit der Eule Pip (erklaert jeden Knopf und jede Einstellung), ein Start-Knopf, Knopf \"Kreislauf\" fuer den Event-Ablauf, neue Einstellungen und Anzeige, Notizblock-Fenster.",
                        "New control window like Autor's own bot: tutorial with Pip the owl (explains every button and setting), one start button, a \"Loop\" button for the event loop, new settings and display, notebook window."),
            new Eintrag("3.3", 3, "Clan-Krieg, Titanen-Turm und Team-Scan rechnen jetzt nach der eingestellten Zeitzone (Standard Berlin) statt nach der Uhr des PCs.",
                        "Clan war, Titan Tower and team scan now follow the set time zone (default Berlin) instead of the PC clock."),
            new Eintrag("3.3", 2, "Fehler behoben: Stigmas loeschen/leveln nur noch mit frischem Befehl (kein versehentliches Loeschen nach einem Neustart); im Clan-Krieg werden keine Diamanten mehr fuer Aepfel ausgegeben; waehrend eines Werbevideos wird nichts mehr angeklickt; Startfehler (Lizenz, Einrichtung) bleiben sichtbar statt im minimierten Fenster.",
                        "Bugs fixed: deleting/levelling stigmas only runs with a fresh command (no accidental deleting after a restart); in clan war no diamonds are spent on apples any more; nothing is clicked during an ad video; start-up errors (licence, setup) stay visible instead of in a minimised window."),
            // 3.2 (Autor 11.10.): das Steuerprogramm aus dem Hauptbot, zweisprachig; zwei Arena-Verbesserungen.
            new Eintrag("3.2", 3, "Neu: das Steuerprogramm -- ein grosses Fenster links oben neben dem Spiel (nie darueber). Starten, Pause, Stopp, Not-Aus, Warteschlange, alle Einstellungen mit i-Erklaerung, Meldungen, Profile, Tastenkuerzel und Support. Alles geht damit auch ganz ohne Discord.",
                        "New: the control window -- a big window at the top left next to the game (never on top of it). Start, pause, stop, emergency, queue, all settings with an i explanation, messages, profiles, shortcuts and support. Everything also works without Discord."),
            new Eintrag("3.2", 2, "Arena: Gegner, die schon angegriffen wurden, sieht der Bot nicht mehr an -- die Zeit bleibt fuer die anderen. Das Gegenstand-Fenster geht auch zu, wenn das X nicht erkannt wird.",
                        "Arena: opponents that were already attacked are not looked at again -- the time is left for the others. The item window also closes when the X is not recognised."),
            // 3.1 (Autor 11.10. abends): alles Englisch, Arena-Neuerungen aus dem Hauptbot
            new Eintrag("3.1", 3, "Alles auf Englisch: Der Bot spricht von Anfang an Englisch -- Discord, alle Fenster, Protokoll. Deutsch mit `!deutsch` oder in Settings.exe oben rechts; die Wahl wird gespeichert und gilt nach jedem Neustart",
                        "Everything in English: the bot speaks English from the start -- Discord, all windows, log. German with `!deutsch` or at the top right of Settings.exe; the choice is saved and still applies after every restart"),
            new Eintrag("3.1", 3, "Arena: Ein Turnier wird immer zu Ende gespielt -- kein Kathedralen-Run mehr in der Wartezeit, Leuchtturm/Turm/Laden warten bis zum Turnierende; steht oben noch der Turnier-Balken, spielt der Bot erst die Arena zu Ende und geht dann heim",
                        "Arena: a tournament is always played to the end -- no cathedral run while waiting any more, lighthouse/tower/store wait until the tournament is over; if the tournament bar is still at the top, the bot finishes the arena first and then goes home"),
            new Eintrag("3.1", 3, "Arena: Namen in jeder Schrift (kyrillisch, chinesisch, japanisch, koreanisch) werden gelesen und so in den Notizblock geschrieben, wie sie im Spiel stehen; derselbe Spieler wird in beiden Schriften erkannt",
                        "Arena: names in every script (Cyrillic, Chinese, Japanese, Korean) are read and written into the notebook as they appear in the game; the same player is recognised in both scripts"),
            new Eintrag("3.1", 2, "Arena: Niederlage unter 8 s = niemals, 8-14 s = meiden, 15-19 s = erst bei der zweiten Niederlage meiden, ab 20 s nichts; gegen 20 Mio staerkere immer meiden. Sieg unter 8 s = sicher",
                        "Arena: defeat under 8 s = never, 8-14 s = avoid, 15-19 s = avoid only after the second defeat, 20 s or more nothing; against 20 million stronger always avoid. Win under 8 s = safe"),
            new Eintrag("3.1", 2, "Arena: Runde 1 immer der schwaechste bekannte Gegner; je Stage mindestens 5 Gegner neu ansehen; Platz 10 ab Runde 2 bevorzugt, untere Reihe etwas bevorzugt",
                        "Arena: round 1 always the weakest known opponent; at least 5 opponents inspected again per stage; place 10 preferred from round 2 on, bottom row slightly preferred"),
            new Eintrag("3.1", 2, "Arena: Set-Namen englisch nachgelesen, 39 Ausruestungen zaehlen als kein Set; Kraft ueber 6 Mio +1 / ueber 8 Mio +5 je Einheit, alle vier Allianz-Talente +8, gutes Emblem +3 (eigene Bilder `ar_emblem_gut_1` ...)",
                        "Arena: set names re-read in English, 39 pieces of equipment count as no set; power over 6 million +1 / over 8 million +5 per unit, all four alliance talents +8, good emblem +3 (your own pictures `ar_emblem_gut_1` ...)"),
            new Eintrag("3.1", 2, "Arena: Aufheben-Liste (arena-aufheben.txt: sehr gute Spieler erst in Stage 4-5), eigene Punkte beim Aufnehmen (Capture-Images fragt nach Punkten, Dauer, Aufheben), kein Name zweimal in einer Liste, lesbare Namen fuer Namensbilder (arena-namensbilder.txt)",
                        "Arena: save-for-later list (arena-aufheben.txt: very good players only in stage 4-5), your own points when capturing (Capture-Images asks for points, duration, save for later), no name twice in one list, readable names for name pictures (arena-namensbilder.txt)"),
            new Eintrag("3.1", 2, "Befehle: der englische Name steht vorn (`!castle`, `!resume`, `!cleaner`, `!end`, `!dig`, `!arenareport`, `!notes`, `!list`, `!emergency`), die deutschen Schreibweisen gehen weiter",
                        "Commands: the English name comes first (`!castle`, `!resume`, `!cleaner`, `!end`, `!dig`, `!arenareport`, `!notes`, `!list`, `!emergency`), the German spellings keep working"),
            // 3.0 = Arena dazu; die Eintraege der nie verteilten 2.6 laufen seit 11.10. als 3.0.
            new Eintrag("3.0", 3, "Arena: `!start arena 5` spielt fuenf Turniere (je ein Ticket), `!start arena 5 aepfel` mit Aepfeln. Der Bot waehlt je Runde den Gegner mit der besten Siegchance (Kraft, Sets, Artefakte, Steine, Schirmherrschaften, Pals) und merkt sich jeden Gegner. Vorher einmal in Capture-Images.exe \"Arena: dein Name\" aufnehmen. Der Held kommt spaeter",
                        "Arena: `!start arena 5` plays five tournaments (one ticket each), `!start arena 5 food` with apples. Each round the bot picks the opponent with the best chance to win (power, sets, artifacts, stones, patronages, pals) and remembers every opponent. First capture \"Arena: your name\" once in Capture-Images.exe. The hero comes later"),
            new Eintrag("3.0", 2, "Arena-Befehle: `!notizen` Notizblock als Datei, `!notiz Max hat 4 Bauarbeiter` eigene Notiz, `!liste` Gegner des Turniers, `!arenareserve 10` 10 Tickets aufheben, `!arenabericht aus`, `!settings bezahlen arena food`, `!settings arena previous`. Eigene Gegner-Listen (ar_block_1 ...) mit \"Anderes Bild...\" -- siehe templates\\arena\\gegner\\LIESMICH.txt",
                        "Arena commands: `!notes` notebook as a file, `!note Max has 4 builders` your own note, `!list` opponents of the tournament, `!arenareserve 10` keep 10 tickets, `!arenareport off`, `!settings pay arena food`, `!settings arena previous`. Own opponent lists (ar_block_1 ...) with \"Other picture...\" -- see templates\\arena\\gegner\\LIESMICH.txt"),
            new Eintrag("3.0", 2, "Titanen-Turm: laedt das Spiel neu (\"Update\"), sucht der Bot den Turm sofort wieder, statt bis zu 30 Minuten zu warten",
                        "Titan tower: if the game reloads (\"Update\"), the bot looks for the tower again right away instead of waiting up to 30 minutes"),
            new Eintrag("3.0",3, "Titan-Event (Titanic Excavation): `!start grabung` oder `!graben` -- ins Event, Boss mit X10, alle Spitzhacken gerade runter, danach Super-Spitzhacken und Dynamit. `!titan 5000` = hoechstens 5000 Spitzhacken, `!titan rangliste`, `!titan drill an`. Im Kreislauf (`!start eventablauf`) alle 100 Kathedralen-Runs, in den letzten 12 Stunden oefter. `!titan` erklaert alles",
                        "Titan event (Titanic Excavation): `!start grabung` or `!graben` -- into the event, boss with X10, all pickaxes straight down, then super pickaxes and dynamite. `!titan 5000` = at most 5000 pickaxes, `!titan ranking`, `!titan drill on`. In the loop (`!start eventloop`) every 100 cathedral runs, more often in the last 12 hours. `!titan` explains everything"),
            new Eintrag("3.0",3, "`!stigmas leveln`: beim Last Guardian alle Bull-Stigmas (Mind bis Tenacity) ansehen, bessere einsetzen, gute mit Power Rays hochleveln -- nach jeder Sorte ein Bild in Discord (blau = eingesetzt, gruen = gleich gut oder besser). Anderer Titan: `!stigmas leveln medusa` oder einfach den Namen dahinter (eigener Titan: bewertet nach deinen Einstellungen). Mit `100` dahinter danach Level 100 (Strahlen + Stones of Creation), sonst nie. `!stigmas leveln probe` schaut nur. Spiel auf Englisch",
                        "`!stigmas level`: at the Last Guardian, looks at all Bull stigmas (Mind to Tenacity), equips better ones, levels good ones with power rays -- a picture in Discord after each kind (blue = equipped, green = as good or better). Another titan: `!stigmas level medusa` or just its name after it (own titan: rated by your settings). With `100` after it, then level 100 (power rays + Stones of Creation), otherwise never. `!stigmas level test` only looks. Game in English"),
            new Eintrag("3.0",3, "`!stigmas einstellen`: Formular -- welche Sorten, wann hochleveln, wonach tauschen (Haupt- und Zweitwert), welche Sets beim Loeschen bleiben; dazu eine eigene Rangliste (gilt fuer den Last Guardian und eigene Titan-Namen). `!stigmas hundert`: die eingesetzten Stigmas so weit wie moeglich Richtung Level 100 (Strahlen + Stones of Creation, kein Probelauf)",
                        "`!stigmas settings`: form -- which kinds, when to level up, what to swap by (main and second stat), which sets stay when deleting; plus your own ranking (for the Last Guardian and your own titan names). `!stigmas 100`: the equipped stigmas as close to level 100 as possible (power rays + Stones of Creation, no dry run)"),
            new Eintrag("3.0",3, "`!stigmas loeschen`: zerlegt alle Stigmas mit 1 Stern und Level 1 -- ausser den behaltenen Sets (Standard Bull, Dragon, Wolf). `!stigmas loeschen probe` schaut nur nach. Spiel auf Englisch",
                        "`!stigmas delete`: disassembles all stigmas with 1 star and level 1 -- except the kept sets (standard Bull, Dragon, Wolf). `!stigmas delete test` only looks. Game in English"),
            new Eintrag("3.0",2, "Befehle auf Deutsch und Englisch und in mehr Schreibweisen: z. B. `!stopp`, `!weitermachen`, `!continue`, `!shutdown`, `!repair`, `!changelog`",
                        "Commands in English and German and in more spellings: e.g. `!stopp`, `!continue`, `!shutdown`, `!repair`, `!changelog`"),
            new Eintrag("3.0",3, "UPDATE.bat sucht die neue Datei in Downloads, auf dem Desktop und im Bot-Ordner, liest die Fassung aus jeder Datei und nimmt die HOECHSTE -- eine alte Datei mit neuerem Datum wird nicht mehr eingespielt. Am Ende steht da, welche Fassung jetzt drin ist",
                        "UPDATE.bat looks for the new file in Downloads, on the desktop and in the bot folder, reads the version inside each file and takes the HIGHEST one -- an old file with a newer date is no longer installed. At the end it shows which version is installed"),
            new Eintrag("3.0",2, "`!lighthouse off` / `!colosseum off` (auch `!leuchtturm aus`, `!kolosseum aus` und andere Schreibweisen) -- schneller als ueber `!settings`",
                        "`!lighthouse off` / `!colosseum off` (also `!leuchtturm aus`, `!kolosseum aus` and other spellings) -- quicker than via `!settings`"),
            new Eintrag("2.5", 3, "PC-Cleaner: im Bot-Ordner liegt jetzt PC-Cleaner.bat -- macht langsame PCs schneller (Programme pruefen, Datenmuell, Zwischenspeicher, Autostart, Hintergrund-Bremsen, Leistung, Virenscan). Fragt vor jedem Schritt. `!reiniger` erklaert es",
                        "PC cleaner: the bot folder now has PC-Cleaner.bat -- makes slow PCs faster (check programs, junk, caches, autostart, background brakes, performance, virus scan). Asks before every step. `!cleaner` explains it"),
            new Eintrag("2.4", 3, "`!settings` ist jetzt eine Karte mit Knoepfen: Clan-Krieg, Bezahlen, Hammer-Event, Nebenbei, Aufstellung -- Knopf druecken, im Formular umstellen, fertig. Alles ausfuehrlich: `!settings all`",
                        "`!settings` is now a card with buttons: clan war, payment, hammer event, on the side, squads -- press a button, change it in the form, done. Everything in detail: `!settings all`"),
            new Eintrag("2.4", 3, "`!fix`: Haengt der Bot oder macht etwas falsch, Formular ausfuellen (gern mit Bild) -- geht mit Spielbild und Fehlerbericht direkt an Autor",
                        "`!fix`: if the bot is stuck or does something wrong, fill in the form (picture welcome) -- goes to Autor with the game picture and error report"),
            new Eintrag("2.4", 2, "Kathedrale mit Nahrung bricht nicht mehr ab, wenn nach dem Run die Shrum-Challenge-Karten aufpoppen; Schluesselkisten werden geoeffnet, bis die Tasche leer ist",
                        "Cathedral with food no longer stops when the Shrum Challenge cards pop up after a run; key chests are opened until the bag is empty"),
            new Eintrag("2.3", 3, "Eigene Event-Motive: im Aufnahme-Werkzeug bis zu 10 Teile aufnehmen, die der Bot im Hammer-Event immer mitnimmt (z. B. Arena-Tickets) -- ist ein Bild nicht ganz sauber, schneidet der Bot es selbst nach",
                        "Own event items: capture up to 10 items in the capture tool that the bot always takes in the hammer event (e.g. arena tickets) -- if a picture is not perfect, the bot cuts it again by itself"),
            new Eintrag("2.3", 2, "Braucht weniger Arbeitsspeicher: Java bekommt hoechstens 192 MB statt bis zu einem Viertel des RAMs -- laeuft auch auf PCs mit 4 GB (nur mit dem neuen Paket, nicht mit dem Jar-Update)",
                        "Uses less memory: Java gets at most 192 MB instead of up to a quarter of your RAM -- runs on PCs with 4 GB too (only with the new package, not with the jar update)"),
            new Eintrag("2.2", 2, "Sehen die Antworten des Bots leer aus (nur bei `!help`, `!settings` ...): `!text an` -- dann antwortet er als normaler Text",
                        "If the bot's answers look empty (only for `!help`, `!settings` ...): `!text on` -- then it answers as plain text"),
            new Eintrag("2.2", 3, "Jeder Schluessel darf jetzt alle Modi und den ganzen Event-Ablauf (auch Hammer-Event, Leuchtturm, Kolosseum, Titanen-Turm, Clan-Laden)",
                        "Every key now covers all modes and the whole event loop (hammer event, lighthouse, colosseum, titan tower, clan store included)"),
            new Eintrag("2.2", 3, "Event-Ablauf wie bei Autor: Nahrungs-Limit je Runde (nach jedem Event neu), Event bis die Haemmer alle sind (`!settings nahrungskisten 999`), ohne Haemmer zurueck in die Kathedrale; Verlies-Runs am Ende nur, wenn eingestellt (0 = keine)",
                        "Event loop like Autor's: food limit per round (fresh after every event), event until the hammers are gone (`!settings foodchests 999`), without hammers back to the cathedral; dungeon runs at the end only if set (0 = none)"),
            new Eintrag("2.2", 2, "Nahrungskisten: Tasche sofort zu, Nahrung wird nachgelesen, Nachfrage bei vollem Lager wird abgelehnt (nichts geht verloren); Zauberstab-Grenze `!settings zauberstaebe 1000`",
                        "Food chests: bag closed right away, food re-read, the full-storage question is declined (nothing is lost); magic wand limit `!settings wands 1000`"),
            new Eintrag("2.2", 3, "Hammer-Event viel schneller (rund 15 s je Stage) und klueger: nimmt alle guten Belohnungen mit, Super-Hammer wird genutzt",
                        "Hammer event much faster (about 15 s per stage) and smarter: takes all good rewards, uses the super hammer"),
            new Eintrag("2.2", 3, "Neue Modi: Leuchtturm, Kolosseum, Titanen-Turm, Clan-Laden -- `!start lighthouse` usw., nebenbei von selbst",
                        "New modes: lighthouse, colosseum, titan tower, clan store -- `!start lighthouse` etc., also on their own"),
            new Eintrag("2.2", 3, "Event-Automatik: `!settings automation on`, dann laeuft `!start cathedral` im Kreis (Kathedrale -> Event -> Verlies)",
                        "Event automation: `!settings automation on`, then `!start cathedral` loops (cathedral -> event -> dungeon)"),
            new Eintrag("2.2", 3, "Bezahlen mit Nahrung: `!settings bezahlen kathedrale alles`, Limit je Tag `!settings nahrungslimit kathedrale 30m`, Auffuellen bis `!settings lager 1.2m`",
                        "Pay with food: `!settings pay cathedral all`, limit per day `!settings foodlimit cathedral 30m`, fill up to `!settings storage 1.2m`"),
            new Eintrag("2.2", 2, "`!stats`: geschaffte Event-Stages, Leuchtturm/Kolosseum als \"3 von 5 Kaempfen gewonnen\"",
                        "`!stats`: event stages done, lighthouse/colosseum as \"3 of 5 fights won\""),
            new Eintrag("2.2", 2, "Event-Ablauf: bei 9 Kathedralen-Kisten gleich ins Event, nach 25 Nahrungskisten zurueck; Leuchtturm/Kolosseum alle 105 min",
                        "Event loop: at 9 cathedral chests straight to the event, back after 25 food chests; lighthouse/colosseum every 105 min"),
            new Eintrag("2.2", 3, "Start-Formular: einfach `!start` oder `/start` -- Modus, Anzahl, Weg, Freund, PC-aus anklicken",
                        "Start form: just `!start` or `/start` -- click mode, runs, route, friend, PC off"),
            new Eintrag("2.2", 3, "`!start eventablauf` startet den Kreis direkt; nach Leuchtturm/Kolosseum/Clan-Krieg geht es zurueck ins Event, bis die Haemmer alle sind",
                        "`!start eventloop` starts the loop directly; after lighthouse/colosseum/clan war it goes back into the event until the hammers are gone"),
            new Eintrag("2.2", 3, "Kathedrale schneller und mit festen Karten geplant; Clan-Krieg: volle Tuerme, Kriegs-Symbol, schneller raus",
                        "Cathedral faster with planned fixed maps; clan war: full towers, war symbol, leaves faster"),
            new Eintrag("2.2", 2, "Kisten im Event einzeln an/aus: `!settings chest wood off`; Meldungen: `!settings reports 1` / `5` / `10`",
                        "Event chests on/off: `!settings chest wood off`; reports: `!settings reports 1` / `5` / `10`"),
            new Eintrag("2.2", 3, "Haengt oder stoppt der Bot mit Fehler, kommt von selbst eine Meldung mit Bild und Fehlerbericht; Alarme (Hitze, Spiel abgestuerzt ...) auch",
                        "If the bot hangs or stops with an error, a message with picture and error report arrives by itself; alarms (heat, game crashed ...) too"),
            new Eintrag("2.2", 2, "Stoerfenster (Apfel-Werbung, Tagesbelohnung, Active events) werden ueberall weggedrueckt",
                        "Pop-ups (apple ad, daily reward, active events) are closed everywhere"),
            new Eintrag("2.1", 3, "Kathedrale deutlich schneller (Graustufen, Weg c im Schnitt rund 2 Minuten)",
                        "Cathedral much faster (greyscale, route c about 2 minutes on average)"),
            new Eintrag("2.1", 2, "Verlies sucht jetzt auch in Graustufen – deutlich weniger Rechenlast",
                        "Dungeon now also searches in greyscale – much less CPU load"),
            new Eintrag("2.1", 3, "!stats wie beim Hauptbot: Warteschlange mit Uhrzeit, Schnitt, Schluessel, Etagen",
                        "!stats like the main bot: queue with times, averages, keys, floors"),
            new Eintrag("2.1", 3, "Notfall-Wache: !notaus, !off now und !pc off gehen auch, wenn der Bot haengt",
                        "Emergency watch: !notaus, !off now and !pc off work even if the bot hangs"),
            new Eintrag("2.1", 2, "!specs – Rechnerwerte (CPU, RAM, Grafikkarte), !specs 10 alle 10 Minuten",
                        "!specs – machine values (CPU, RAM, graphics card), !specs 10 every 10 minutes"),
            new Eintrag("2.1", 2, "!live – Livebild, das sich 5 Minuten lang alle 2 Sekunden erneuert",
                        "!live – live picture refreshing every 2 seconds for 5 minutes"),
            new Eintrag("2.1", 2, "!shortcut – eigene Kurzbefehle, z.B. !maus 25",
                        "!shortcut – your own short commands, e.g. !maus 25"),
            new Eintrag("2.1", 2, "!mute / !unmute, !clear, !queue, !ende, !pc, !english / !deutsch",
                        "!mute / !unmute, !clear, !queue, !ende, !pc, !english / !deutsch"),
            new Eintrag("2.1", 2, "!off now und !off 3pm werden verstanden; !resume sagt Bescheid",
                        "!off now and !off 3pm are understood; !resume answers"),
            new Eintrag("2.1", 2, "Pausen zaehlen nicht mehr in den Schnitt",
                        "Pauses no longer count towards the average"),
            new Eintrag("2.1", 2, "Leere Karten (fehlendes Recht \"Links einbetten\") kommen jetzt als Text",
                        "Empty cards (missing \"Embed Links\" permission) now arrive as text"),
            new Eintrag("2.1", 1, "Findet die Kathedrale auch auf einer verschobenen Weltkarte",
                        "Finds the cathedral on a scrolled world map"),
            new Eintrag("1.4", 3, "Schnelle Kathedrale c (Turbo), Waechter an der Schrift erkannt",
                        "Fast cathedral c (turbo), guardian recognised by its lettering")
    );

    private NeuerungenFreunde() {
    }

    /** Platz in der Karte (DiscordClient schneidet bei 3800 ab; Legende und Hinweis kommen noch dazu). */
    private static final int GRENZE = 3500;

    /**
     * Neueste Fassung zuerst, darin wichtig zuerst. Ohne "alle" nur die neueste Fassung --
     * alles zusammen passte nicht in eine Karte, die neuen Eintraege fielen hinten weg
     * (Freunde-Pruefung 11.10.). Was dann noch zu lang ist, wird mit "…" gekappt.
     */
    public static String text(boolean alle) {
        String neueste = LISTE.stream().map(Eintrag::fassung)
                .max(Comparator.comparingInt(NeuerungenFreunde::nummer)).orElse("");
        List<Eintrag> zeigen = LISTE.stream()
                .filter(e -> alle || e.fassung().equals(neueste))
                .sorted(Comparator.comparingInt((Eintrag e) -> nummer(e.fassung())).reversed()
                        .thenComparing(Comparator.comparingInt(Eintrag::wichtig).reversed()))
                .toList();
        StringBuilder b = new StringBuilder();
        for (Eintrag e : zeigen) {
            String zeile = (e.wichtig() == 3 ? "🔥 " : e.wichtig() == 2 ? "✨ " : "• ")
                    + "**" + e.fassung() + "** " + Sprache.t(e.en(), e.de()) + "\n";
            if (b.length() + zeile.length() > GRENZE) {
                b.append("…\n");
                break;
            }
            b.append(zeile);
        }
        return b.toString();
    }

    /** "3.0" -> 3000, "2.10" -> 2010: Fassungen als Zahl vergleichen. */
    private static int nummer(String fassung) {
        try {
            String[] t = fassung.split("\\.");
            return Integer.parseInt(t[0]) * 1000 + (t.length > 1 ? Integer.parseInt(t[1]) : 0);
        } catch (Exception e) {
            return 0;
        }
    }
}
