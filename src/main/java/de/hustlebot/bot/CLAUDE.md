# bot/ – Modus-Maschinerie (Index)

Paket `de.hustlebot.bot`: alle Spielmodi plus der Motor, der sie faehrt. ~68k Zeilen, 67 Dateien. Diese Datei
beschreibt das **Framework** (Motor, Kontext, Schnittstelle, Stoerfenster, Waechter) und ist der Index fuer die
Teildokumente der einzelnen Modi.

## Teildokumente (bei Arbeit an diesen Modi ZUERST lesen)

- `CLAUDE-arena.md` – ArenaMode und alle `Arena*`-Klassen, MeinTeam, TeamScan/TeamScanMode, Turnierbalken,
  core/Arena*, Gegnerbuch (SchirmLeser wird von dort mitbenutzt).
- `CLAUDE-dungeon.md` – DungeonMode, CathedralMode (+ Etagennetz, Zeitbericht, Lernwerte), PortalMode,
  LighthouseMode (Leuchtturm + Kolosseum), TitanTurmMode, Vasensuche.
- `CLAUDE-events.md` – EventMode (Hammer-Event), TitanEventMode (Grabung), TitanRauswurf, ClanWarMode, StigmaMode,
  GemBayMode/GemBayGrenzen, EventVorlagenSchwellen.
- `CLAUDE-alltag.md` – BurgenMode, Burgstand, ClanLaden(Mode), FreundeSenden(Mode), FreundesChat,
  Nahrungskisten(Mode), Nahrungsvideo, HeldenSet/HeldenSetMode/HeldenSetWechsel, Truhenoeffner, TruppAuffuellen,
  Zauberstaebe, ChatAnimation, LeereTruppe.

## Einbettung: wer ruft was

```
Main -> Kern.main
  Kern: Matcher(templates) + VorlagenNachschub + stellenLaden(vorlagen-stellen.txt)
        BotContext(fenster, kamera, matcher, eingabe, Laufstatistik)
        BotEngine(ctx)  -> registriert alle Modi im Konstruktor
        DiscordBridge(motor, ...)  -> Befehle, Warteschlange ("kette"), Not-Aus F9, Pause F10/Weiter F11, Mauswache
        Steuerprogramm (Swing, steuer/) -> dieselben Motor-Methoden ohne Discord
        SpielWaechter-Faden, BotFenster, ArenaBeobachter.starten(motor), Wachhalten (PC schlaeft nicht, solange LAEUFT/PAUSIERT)
```

- **Start eines Modus** kommt fast immer aus `DiscordBridge.naechstenStarten()` (Warteschlange) ->
  `engine.start(modus, runs, teammate, freund)`. Steuerprogramm und `!start` landen ebenfalls dort.
- **Ende eines Modus**: `setState(FERTIG|FEHLER)` ruft sofort `nachEnde` (Callback der Bruecke) -> Bruecke schaltet
  die Warteschlange weiter (nur wenn `zielErreicht()`; sonst Kette abbrechen).
- **Einschub** (Clan-Krieg-Zeit, Leuchtturm, Turm, Freunde senden ...): Bruecke fuegt Auftraege + Rest-Auftrag des
  laufenden Modus in die Kette und ruft `engine.wechselNachDurchlauf()` (siehe Moduswechsel).
- Bruecken-Callbacks am Motor: `setNachEnde`, `setMeldungOhneSpiel`, `setHeimFertig`, `setEndeHinweis`;
  Kontext: `ctx.setDiscordHinweis` (-> `ctx.melde(titel,text)` aus Modi). `BotEngine.haengerMelderSetzen` existiert,
  wird aber derzeit nirgends gesetzt (Haenger-Bericht geht nur ueber `Log.warn` -> Report/Discord-Log).
- Statische Abfragen fuer lange Modus-Schritte: `BotEngine.stoppAngefordert()`, `BotEngine.pausiert()`,
  `BotEngine.pauseAngefordert()` – lange Schleifen in Modi (Stigmas, Freunde senden ...) MUESSEN diese pruefen,
  der Motor prueft sonst nur zwischen zwei `step()`-Aufrufen.

## Datei-Karte (Framework)

| Datei | Rolle |
|---|---|
| `GameMode.java` | Schnittstelle eines Modus + `Outcome`-Enum + `Folge`-Record. |
| `BotEngine.java` (2,6k) | Motor: Registrierung, `start`, Hauptschleife `loop`, Pause/Stopp, Moduswechsel, Zeitgrenzen, Arena-Nachspiel, Heimweg `heimGehen`, Systemschutz `guardSystem`, Haenger-Erkennung `haengerPruefen`, Konto-Wechsel `kontoPruefen`, Zaehler-Ablesen `readEther`, Spiel-Neustart. |
| `BotContext.java` | Alles, was ein Modus braucht: Bild holen (`refresh`), Vorlagen suchen/klicken mit Bild-Cache, adaptives Warten (`sleepUntilChanged`), Kastenblick, Discord-Hinweis. |
| `RunTracker.java` | Durchlauf-Zaehler, Fortschritts-/Leerlauf-Uhr, Pausenzeit, Laufdauer, Klick-Schleifen-Erkennung. |
| `Stoerfenster.java` (1k) | Zentrale Abwehr von Popups/Stoerungen, von allen Modi und vom Motor gerufen. |
| `Weltkarte.java` | Erkennen der Weltkarte, Karte schieben, "Home" unten links. |
| `SpielWaechter.java` | Eigener Faden: beendet den ganzen Bot (`System.exit`), wenn Hustle Castle zu lange zu ist. |
| `Bezahlkasse.java` | Bezahlen auf Teilnahme-Bildschirm (Tickets/Nahrung), Nahrung lesen, Kauf-Fenster abwehren. Nur Dungeon/Cathedral/Arena. NIE Diamanten. |
| `Aufstellung.java` | Je Modus "Deploy the best" vs. "Previous Squad"; `aufstellung.properties`. |
| `SchirmLeser.java` | Liest das "Patronage"-Fenster (Schirmherren/gute Stigmas), Punkte via `Gegnerbuch.schirmPunkte`; genutzt von ArenaPruefer, TeamScan, MeinTeam. |
| `Zeitbericht.java` | Zeitverteilung eines Kathedralen-Runs nach Phasen (Laden/Kampf/Segen/...), Zeile im Log. |
| `Lernwerte.java` | Kathedralen-Wartezeiten/Schalter aus `lernwerte.txt`, live neu gelesen bei Run-Start. |
| `Lesetest.java` | `!lesetest N`: Nahrungsleiste in der Burg N-mal lesen (Burg verschieben), Ausschnitte nach `logs/lesetest`. Nur im Leerlauf. |

## Alle Modi (registriert in `BotEngine`-Konstruktor, Reihenfolge = `availableModes()`)

| id | Klasse | Kurz | Doc |
|---|---|---|---|
| `dungeon` | DungeonMode | Verlies mit Zufalls-/Freund-Mitspieler, regelbasiert | dungeon |
| `cathedral` | CathedralMode | Dunkle Kathedrale, Etagen/Wegplanung, Hammer-Kreislauf | dungeon |
| `lighthouse` / `colosseum` | LighthouseMode(LEUCHTTURM/KOLOSSEUM) | Bis zu 5 Kaempfe, dann zurueck; Botengang | dungeon |
| `titantower` | TitanTurmMode | Titanen-Turm; Botengang | dungeon |
| `clanladen` | ClanLadenMode | Food chests im Clan-Laden kaufen (02:00/12:00); Botengang | alltag |
| `arena` | ArenaMode (10,7k) | Arena/Turnier, Gegnerbewertung | arena |
| `stigmas` | StigmaMode | Stigmas loeschen | events |
| `event` | EventMode | Hammer-Event (Vasen) | events |
| `grabung` | TitanEventMode | Titan-Event "Titanic Excavation" | events |
| `clanwar` | ClanWarMode | Truppen zur Kriegszeit setzen; Botengang | events |
| `heldenset` | HeldenSetMode | Probe Helden-Set-Wechsel | alltag |
| `burgen` | BurgenMode | Burgen auf der Weltkarte angreifen (Zweitkonten) | alltag |
| `freundesenden` | FreundeSendenMode | Freunden Ressourcen senden; Botengang | alltag |
| `portal` | PortalMode | Portal Lv. 80 Autokampf | dungeon |
| `nahrungskisten` | NahrungskistenMode | Kisten aus Tasche oeffnen bis Lager voll | alltag |
| `gembay` | GemBayMode | Edelsteinbucht | events |
| `teamscan` | TeamScanMode | Eigenes Team scannen (alle 3 Tage); Botengang | arena |

**Botengaenge** (`BotEngine.istBotengang`): lighthouse, colosseum, clanwar, titantower, clanladen, freundesenden,
teamscan – zaehlen NICHT in `Laufstatistik.addRun` (verfaelschen sonst Durchschnittszeiten).

Uebrige Helferklassen (ohne `GameMode`) gehoeren zu einem Modus, siehe Teildokumente.

### Neuen Modus einbauen
1. Klasse `implements GameMode`; `id()` (kleingeschrieben, stabil – wird in Discord, Warteschlange, Statistik,
   `Thronsaal.gesperrtGrund`, `Aufstellung` als Schluessel benutzt), `displayName()` via `Sprache.t(en, de)`.
2. In `BotEngine`-Konstruktor `register(new XMode())`.
3. Discord-Befehle/Namen in `discord/DiscordBridge` + `discord/Befehlsnamen`; ggf. `istBotengang` erweitern;
   Thronsaal-Sperre in `core/Thronsaal`; Steuerprogramm-Auswahl in `steuer/`.
4. Vorlage: `ClanLadenMode` (60 Zeilen) – zeigt das Grundmuster.

## Vertrag `GameMode`

- `step(screen, ctx, runs)`: **genau EINE Aktion** pro Aufruf, nie eine ganze Kette (Motor prueft nur zwischen
  Schritten auf Stopp/Pause/Wechsel). Rueckgabe `Outcome`:
  - `ACTED` – geklickt/gezogen. Motor schlaeft danach nur ~25 ms (Modus hat schon adaptiv gewartet).
  - `WAITING` – Spiel/Mitspieler laedt; Motor schlaeft `scanDelayMillis` (120 ms, Kathedrale/Grabung 40 ms; x4 bei Drosselung).
  - `UNKNOWN` – Bildschirm nicht erkannt. Ab 3. Mal in Folge, dann alle 10: `matcher.naechstesBildGruendlich()` (ganzes Bild statt gemerkter Stellen).
  - `FINISHED` – fertig; Motor holt `folgeAuftrag()` + `endeHinweis()`, setzt `zielErreicht=true`, FERTIG.
  - `PAUSE` – Mensch muss etwas tun (z. B. "anderes Geraet"); Zaehler + Warteschlange bleiben, `modusPauseUm` gesetzt, `!weiter` setzt fort.
  - `FATAL` – Abbruch, FEHLER; Statustext = `Log.letzterFehler(30 s)` -> Modus soll vorher per `Log.error` den Grund schreiben. Setzt `haengerAbbruch` (Lernmodus `!fix`).
- Fortschritt melden: `runs.progress("Notiz")` bei jedem echten Fortschritt (setzt Leerlauf-Uhr zurueck; die Notiz
  erscheint im Status und im Haenger-Bericht). Durchlauf fertig: `runs.completeRun()`; Startzeit genauer:
  `runs.reiseBeginnt()` bei "Participate"; verlorener Kampf: `runs.countFailedBattle()`.
- Default-Methoden (alle optional):
  - `reset()` – vor erstem Durchlauf und nach Spiel-Neustart.
  - `darfHeim()` – sicherer Moment fuer `!burg` (Verlies mitten im Kampf: nein).
  - `draussen()` – nach gezaehltem Durchlauf wirklich draussen (Verlies zaehlt seit 18.10. erst draussen via `endRun()` am Abschlussfenster bzw. bei Rueckkehr in Burg/Weltkarte, nicht mehr bei "End journey"; `countRunNow()` ist tot).
  - `wechselJetzt()` – sofort wechseln trotz stehendem Zaehler (Hammer im Kathedralen-Kreislauf, Portal, Turm).
  - `inEmpfindlicherPhase()` – Werbevideo laeuft: Motor klickt NICHTS allgemein (`allgemeineStoerung` aus).
  - `sicherePunkte()` – zaehlt sichere Neustartpunkte ohne Run-Zaehler.
  - `haengerBerichtNachS()` – Stillstands-Frist (Default 20 s; Turm/Portal/Stigmas laenger).
  - `jetztInDieBurg()` – einmalig true: Motor geht heim, Modus laeuft danach weiter (Verlies: Freund nimmt nicht an).
  - `wartetNurAufAndere()` – kein Haenger-Bericht.
  - `endeHinweis()` – Satz fuer Discord bei FINISHED mit Grund ("keine Schluessel mehr").
  - `extraStats()` – Zusatzzeilen fuer `!stats` (LinkedHashMap fuer Reihenfolge).
  - `folgeAuftrag()` – Folge-Auftrag nach FINISHED (Kathedrale im Kreislauf meldet Verlies-Runs).

## Motor: `BotEngine`

Zustaende `State { BEREIT, LAEUFT, PAUSIERT, GESTOPPT, FEHLER, FERTIG }`. Ein Arbeitsfaden `bot-worker` (Daemon).

### `start(modeId, runs, teammate, friendName)` – Reihenfolge
1. Ablehnen bei LAEUFT; **bei PAUSIERT ebenfalls ablehnen** (alter Faden lebt noch -> sonst zwei klickende Faeden, Code-Pruefung 15.10.).
2. Thronsaal-Sperre (`Thronsaal.gesperrtGrund(modeId, konto)`): Modus gibt es fuer Konto noch nicht -> sofort
   `FERTIG` und `return true`, damit die Kette weiterlaeuft (Kathedrale ab TH6, Turm ab 7, Gem Bay ab 8, Verlies ab 9).
3. Fristen/Merker zuruecksetzen, `Turnierbalken.vergessen()`.
4. Dungeon: Teammate `random|friend|friendwait` ("friendwait" = auf Einladung des Freundes warten, damit sich zwei
   Bots nicht gegenseitig einladen) + Fristen `leaderPick`/`partnerReady`.
5. `matcher.loadAll()` (Vorlagen frisch). Fehlende `requiredTemplates()` -> **nur Warnung**, Start trotzdem
   (Freunde-Fassung, 01.10.; `bot.requireAllTemplates` wird ignoriert).
6. Kein Spielfenster -> `BEREIT` + `meldungOhneSpiel` (ruhige Meldung, KEINE rote Fehlerkarte; 13.09.).
7. Graustufen-Suche nur fuer `cathedral` und `dungeon` (`matcher.setGrauErlaubt`); Arena bewusst in Farbe (25.09.:
   Schwerter-Zeichen/Quick Attack in Grau unzuverlaessig).
8. `ctx.setSchnell(true)` nur fuer `cathedral` und `grabung` (20 ms Takt / 10 ms blind); sonst 60/40. Schnell in
   allen Modi fuehrte zu Klicks in Uebergangsbilder (Arena 5 min im Kreis, 25.09.). NICHT global einschalten.
9. `mode.reset()`, Konto-Pruefung scharf schalten (wenn `Konten.an()`), neuer `RunTracker`, `laufstatistik.startSession()`.
10. Alten Worker bis 3 s `join` (er speichert in `finally` noch Knopfstellen), dann neuer Faden.

### Hauptschleife `loop()` – pro Runde
0. Vorab einmalig: Leuchtturm/Kolosseum und Stigmas (ausser schon im Titan-Bild) -> `vorBesuchInDieBurg()`.
1. `lastLoopHeartbeat` setzen (Bruecke meldet "Bot antwortet nicht" nach 3 min ohne Herzschlag, haelt nichts an).
2. Modus-Pause beenden gewuenscht (`modusPauseBeenden()`, Clan-Krieg soll trotz Pause starten) -> FERTIG mit `zielErreicht`.
3. `paused` -> 400 ms schlafen, weiter.
4. `heimGewuenscht && darfHeim()` -> `heimGehenUndMelden()`, Ende (BEREIT).
5. **Moduswechsel** (`wechselAngefordert`): wechseln, wenn (Durchlauf fertig UND `draussen()` oder 90 s nach dem
   Zaehlen) ODER `wechselJetzt()` ODER Frist 15 min um (bei laufendem Arena-Turnier 30 min). -> FERTIG, `zielErreicht=true`.
6. **Zeitgrenze** (`!ende HH:MM` / Laufzeit-Minuten): laufenden Durchlauf noch fertig spielen, max. 8 min
   (`FINISH_GRACE_MS`) -> FERTIG, aber `zielErreicht` bleibt false (Kette endet!).
7. `ctx.refresh()`; null -> zaehlen; nach >20 Fehlern: Spiel abgestuerzt -> `GameLauncher.wiederErlauben()` +
   `sicherstellen()` (max. 1x je 10 min, `spielAbsturzUm` setzen – Verlies zaehlt den Lauf dann nicht), sonst FEHLER.
8. `dialogWegdruecken` (Erzaehltext `dialog_weiter`, max alle 1,5 s), `readEther` (Ether/Light/Keys/Multiplikator alle
   5 s oder sofort nach `ctx.requestCounterRead()`; im Clan-Krieg uebersprungen).
9. `haengerPruefen`, `guardSystem` (alle 15 s) -> evtl. FEHLER.
10. `kontoPruefen` (Spielername in der Burg lesen, 2 gleiche Lesungen; bei anderem Konto `Konten.wechseln` +
    Gegnerbuch/Bezahlung/Vorlagen neu laden).
11. `allgemeineStoerung(screen)` -> sonst Arena-Nachspiel pruefen -> `nachspielSchritt` oder `activeMode.step(...)`.
    Exception im Modus: geloggt, 2 s Pause, weiter (kein Abbruch!). Danach `jetztInDieBurg()` abfragen.
12. Neuer Durchlauf gezaehlt (vor dem switch, sonst fehlte der letzte Run): Knopfstellen speichern,
    Laufstatistik (ausser Botengaenge).
13. switch Outcome (siehe oben). Bei ACTED + neuem Durchlauf: `takeBreak` (Humanizer-Pause, in 500-ms-Stuecken)
    und `spielNeustartZwischenRuns`.
14. `finally`: `matcher.stellenSpeichern(STELLEN_DATEI)`.

### `allgemeineStoerung` (jeder Modus, vor `step`)
- Server-Wartung (OCR "maintenance"+"closed/retry", nur wenn >= 8 s ohne Fortschritt, alle 20 s): WAITING, alle 3 min
  "Retry"; bis 3 h zaehlt das Warten als Fortschritt.
- `inEmpfindlicherPhase()` -> nichts weiter.
- Alle 2 s: `st_got_it` (Burg angreifbar), `Stoerfenster.afkUpdate` (Update/Try again), ausser Kathedrale:
  `Stoerfenster.anderesGeraet` -> PAUSE oder ACTED. (Kathedrale prueft "anderes Geraet" selbst.)

### Arena-Nachspiel (11.10.)
Turnier-Balken oben (`Turnierbalken.sichtbar` beim 1. Schritt, danach `Turnierbalken.abholen(15 s)` – gemeldet von
Nahrungs-Lesern) -> ArenaMode mit `nurZuEndeSpielen(true)` und eigenem RunTracker spielt das Turnier zu Ende, dann
der eigentliche Modus. Max 20 min, 4 min ohne Fortschritt -> Ende. FINISHED/FATAL/PAUSE der Arena beenden nur das
Nachspiel. Ohne gespieltes Turnier: 10 min Sperre (`nachspielSperreBis`, getestet), sonst Endlosschleife.

### Heimweg `heimGehen()` (max 2 min, `HEIM_FRIST_MS`)
Fertig, wenn `map_button`/`map_button_alt` sichtbar. Reihenfolge: Weltkarte -> `Weltkarte.heim`; "anderes Geraet"
-> Abbruch (nichts druecken!); `afkUpdate`; unfertiges Verlies nach 15 unbekannten Bildern (roter `end_journey`)
abbrechen; dann Klickkette `cancel_search` > Verlies-Reste > fertige gruene Verlies-Reise > `home_button` >
`tt_home` > `ar_home_result` > `dc_home_done` > `close_reward` > `bag_close` > `end_journey_yes` > `quit_yes` > `home` >
`close_x`; Kathedralen-Etagenkarte (`dc_floor_hint`) -> Home; sonst `Stoerfenster.notausgang`.
`!burg` = `geheHeim()`: laeuft ein Modus, nur Wunsch (wartet auf `darfHeim`); sonst eigener Faden `heim-gang`.

### Stopp / Pause / Not-Aus
- `stop(reason)`: `stopRequested`, `heimGewuenscht=false` (sonst ging er nach !stop noch heim, 17.09.), `paused=false`,
  `worker.interrupt()`. Endzustand GESTOPPT. Bruecke leert bei GESTOPPT die Warteschlange.
- Not-Aus-Taste (Default F9, `notaus.taste`, `!settings notaus <Taste>`) = `stop`. F10 (`notpause.taste`) =
  `togglePause`, F11 (`weiter.taste`) = fortsetzen.
- Mauswache (`core/Mauswache`, ausser ServerSparsam): Maus bewegt -> **Stopp** (seit 16.10., nicht Pause).
  `pausierenWennLaeuft`/`fortsetzenOhneAutor` existieren noch (5 min Ruhe -> weiter).
- `togglePause()` fuer `!pause`/`!weiter`; Pausenzeit wird via `setState` aus der Laufzeit herausgerechnet
  (`RunTracker.pauseBeginnt/Endet`).
- `restart()` (`!neustart`): stoppen, 6 s join – lebt der Faden noch, KEIN Neustart (sonst zwei Bots); Maus parken,
  Zaehlerstaende zuruecksetzen, `start(last...)`.
- `startWithLastSettings(zaehlerZuruecksetzen)`: Standard setzt Ether-/Schluessel-Anfangsstaende zurueck; nur
  Warteschlange/Moduswechsel uebergeben false.

### Zeitgrenzen
`setStopAfterMinutes`, `setStopAtClock("HH:MM")` (Zone `BotEngine.ZONE` = Europe/Berlin). `!ende`/`!off` ueberleben
einen Neustart via `Dauerwerte.ENDE_UM`/`ENDE_UHRZEIT` (Instanz-Initialisierer; abgelaufen = verworfen).

### Haenger und Leerlauf (mehrstufig)
| Stufe | Wo | Frist | Folge |
|---|---|---|---|
| Haenger-Bericht | `haengerPruefen` | `haengerBerichtNachS()` (20 s) x `Rechnertempo`; Notizen wie "Mitspieler wird gesucht"/"Warteschlange" -> 180 s; Partnersuche/`wartetNurAufAndere` -> nie; 45 s nach "Update" nie; sichtbares Arbeiten (`Report.arbeitetSichtbar`) bis 180 s nicht | einmal `Log.warn` |
| Eigene Bilder zuruecknehmen | `haengerPruefen` | max(120 s, Frist) | `matcher.eigeneZuruecknehmen` (eigene Aufnahmen koennten schuld sein) |
| Modus-eigene Fristen | Modi via `runs.isIdleTooLong()` | `bot.idleTimeoutMinutes` (5) | Modus entscheidet (Verlies verlassen etc.) |
| Totmann | `guardSystem` | 15 min ohne Fortschritt | FEHLER, `haengerAbbruch` |
| Hauptschleife steht | DiscordBridge.motorPruefen | 3 min ohne Herzschlag | nur Meldung + Foto |
- `RunTracker.progress()` mit identischer Notiz waehrend einer Klick-Schleife (>= 25 Klicks/60 s, 90 % derselbe,
  `Report.klickSchleife`) setzt die Uhr NICHT zurueck – Endlos-Klicken gilt nicht als Fortschritt.

### Systemschutz `guardSystem` (alle 15 s)
Platte < 5 GB -> Stopp. CPU-Temp (nur mit LibreHardwareMonitor, sonst -1): HitzeWaechter-Stufen 75/80/83 mit
Ursache (`Rechenlast.top`), ab Stufe 2 Drosselung; >= 85 Warnung, >= 90 Stopp; ueber Abschaltgrenze
(`abschaltGrenze`, 90 bzw. Bau-Ausnahme `hitze-bau-95.txt`) 2 min -> `PowerControl.shutdown(60)` (`!pc abbruch`).
Kein Luefter bei >= 60 Grad -> Alarm. CPU >= 85 % -> 60 s Drossel (`isThrottled`, Wartezeiten x4/x10); >= 95 % fuer
10 min -> Stopp. `server.txt` im Bot-Ordner schaltet die CPU-Pruefung ab (Server ohne GPU laeuft immer bei 99 %).

### Spiel-Neustart alle 2 h (`spielNeustartZwischenRuns`)
Nur `cathedral`, nur direkt nach fertigem Run, `Spielneustart.faellig()`, nicht bei Wechsel, nicht vor dem letzten
Run, nicht < 15 min vor Clan-Krieg (`Kriegsplan.minutenBisKrieg`), nicht wenn Turm faellig. Ablauf:
`Mauswache.ruheFuer(240 s)` -> `Spielneustart.spielBeenden` -> `GameLauncher.sicherstellen` -> max 2 min auf
Map-Knopf warten -> `activeMode.reset()` + `tracker.progress(...)` (sonst Haenger-Alarm nach Neustart).

## `BotContext`

- `refresh()`: neues Bild (holt Fenster nach vorn via `window.prepare()`), leert Bild-Cache, setzt
  `input.setClientArea`. Fenstergroesse geaendert: <= 16 px -> `matcher.bestaetigungVergessen()`, sonst
  `stellenVergessen()` (gemerkte Knopfstellen ungueltig). Jedes Bild geht an `Fotoserie`. **Nach `refresh()` ist
  ein frueher geholtes `Mat` freigegeben** – `lebendesBild()` ersetzt leere Mats durch das aktuelle (30.10., 664
  "Template larger than screen 0x0"-Fehler). Trotzdem: nach Unteraufrufen, die selbst `refresh()` machen
  (z. B. `Nahrungskisten.nahrungLesen`), `ctx.aktuellesBild()` nehmen.
- Suchen: `find`/`visible` (Cache je Bild, warnt bei unbekannter Vorlage), `visibleIfKnown`/`clickIfVisibleIfKnown`
  (still bei fehlender Vorlage – fuer optionale Vorlagen), `clickIfVisible`, `klick(hit, grund)`. Jeder Klick:
  `Log.action`, `laufstatistik.setStep`, `Stoerfenster.klickGemacht` (merkt "Update"-Zeit).
- Warten: `sleep(ms)` (Humanizer-Streuung x `Rechnertempo`), `sleepUntilChanged(max)` bzw. `(min,max)`: blind
  warten, dann alle `pollMs` 64x48-Graubild vergleichen, Ende bei mittlerer Abweichung > 8.0. Obergrenze ist nur
  Obergrenze. `bildGeaendert(min,max)` = Klick-Kontrolle ohne Log. `gewartetMs()` fuer Zeitbericht.
  `grabSmall` fotografiert nur, wenn das Spiel im Vordergrund ist (Privatsphaere: sonst Autors Desktop).
- Kastenblick (schnell, ~8 ms): `kastenBlick`, `kastenBlicke`, `kastenAufnahme(kasten)`, `warteAufKnopf`,
  `warteBisWeg`, `weitBlicke` (ganzes Bild, lernt neue Stelle). Leer = unbekannt -> Aufrufer faellt aufs ganze Bild zurueck.
- `zumZusehen()` / `standbild(bild, mat)`: Kopien mit eigenem Cache, klicken nie (ArenaBeobachter, Lesen in eigenem
  Faden). `beobachten()`: Bild nur wenn Spiel ohnehin vorn, holt nichts nach vorn.
- `requestCounterRead()` – Modi melden "Zaehler jetzt sichtbar"; `frischesFoto()` ohne Motor-Takt.

## `Stoerfenster` – Popup-Abwehr (Regeln)

- `belohnungWegklicken` (Modi rufen es i. d. R. max. 1x/s): Ereignistext (`dc_reward_text`), "Active events"
  (`ev_view`, nicht waehrend `eventUnterwegs()`), Event-Belohnung (`event_reward`, Klick 30 % Hoehe UNTER die Schrift,
  nie unter 80 %), Werbefenster "Running out of" (`ad_running_out` + `ad_close*`, Notnagel-Klick berechnet),
  Tagesbelohnung `daily_reward` (alle bekannten roten X durchprobieren).
- **Rotes X nie blind druecken**: es sieht ueberall gleich aus; der Bot schloss einmal seinen eigenen
  Verlies-Eintritt. Rotes X nur im `notausgang`, und der greift erst ab `NOTAUSGANG_AB = 12` unbekannten Bildern
  (dann alle 12). Davor (jedes Bild): Verlies-Reste, `bag_close` (ab 3), Lupen-Auswahl `ev_form_x` (nur Feldmitte);
  ab 12: `cw_back`, fertige gruene Verlies-Reise, `ev_form_x`, dann `close_x`.
  `notausgang(..., verliesRestBeenden=false)` nur aus DungeonMode (das zaehlt den Run selbst).
- `warnungFaellig(n, stilleS)`: "Nothing recognized"-Warnung nur alle 12 Bilder UND >= 8 s ohne Fortschritt (94 % waren normale Uebergaenge).
- "Anderes Geraet" (`other_device`): erstes Sehen -> `ANHALTEN` (PAUSE). Erst nach `!weiter` (Modus sieht es erneut)
  "Try again"/`update_button`, max 3 Versuche. `autorIstDrin()` -> **nie "Update"/"Try again" druecken**, sonst fliegt
  der Spieler vom Handy. `istRausgeworfen()` blockiert Auto-Starts.
- `spielfehler` ("Game error", `try_again` nur Bildmitte): max 5 in 10 min, dann Alarm.
- `afkUpdate`: "Are you still here?" -> Update, danach 20 s schlafen; 45 s keine Haenger-Meldung.
- Lager-Fragen: `lagerUeberlaufJa` (OCR muss "storage/room for" sagen, nie bei Kauf-Text) vs. `lagerVollFrage`
  (aelter, lehnt "No" gespiegelt zu "Yes" ab, gibt nach 5x/min auf).
- Gruener "End journey": `gruenerEndJourney(min)` muss besser passen als der rote (`end_journey`); immer in Farbe.
  Nachfrage/Abschluss nur innerhalb 10 s/20 s nach eigenem Klick (kein fremdes "Yes").
- Fristen dort mit `System.nanoTime()` (Windows-Uhr springt beim Zeitabgleich).

## `SpielWaechter`
Takt 15 s. Spiel war offen und ist 5 min zu -> Discord-Abschied, `System.exit(0)`; nie gesehen -> 15 min. Uhr laeuft
NICHT, solange Motor LAEUFT/PAUSIERT oder `GameLauncher.startLaeuft()`, oder `bot-bleibt-offen.txt` = an.

## Weltkarte
`erkannt()`: irgendein Ort aus `worldmap_home, dc_node, ar_node, dungeon_node, lh_node, ko_tower` (nicht nur Home –
`worldmap_home` passt auf der Karte nur ~0,72). `homeUntenLinks`: nur im linken unteren Drittel, Schwelle 0,55
(`HOME_ECKE_AB`, gemessen an 888 Fotos) – diese Regel fuer ALLE Home-Klicks benutzen. `heim()`: Vorlage, sonst feste
Stelle (7 %/90,5 %). `schieben(zug)`: 12 Zuege (3 links, 6 rechts, 3 zurueck) auf 52 % Hoehe.

## Laufzeit-Dateien (Bot-Ordner = `core/Ordner.bei(...)`)
- `config.properties`: `bot.idleTimeoutMinutes` (5), `bot.tickDelayMillis` (<= 150), `bot.requireAllTemplates`
  (wirkungslos), `notaus.taste` (F9), `notpause.taste` (F10), `weiter.taste` (F11), `discord.*`.
- `vorlagen-stellen.txt` – gelernte Knopfstellen (`Matcher.STELLEN_DATEI`), nach jedem Run und am Ende gespeichert.
- `aufstellung.properties` – `Aufstellung` (Kern konfiguriert den Pfad; Discord `!settings verlies previous`;
  Schluessel dungeon/cathedral/arena/clanwar_tower/clanwar_outpost, Standard BEST ausser Outpost PREVIOUS).
- `lernwerte.txt` – **relativ zum Arbeitsverzeichnis** (`Path.of`), wird bei Fehlen mit Standardwerten angelegt.
- `bot-bleibt-offen.txt`, `server.txt`, `hitze-bau-95.txt` – Schalterdateien (s. o.).
- `Dauerwerte` ENDE_UM/ENDE_UHRZEIT; `logs/lesetest/` (Lesetest-Ausschnitte).

## Konventionen / Gotchas
- Alle Texte zweisprachig: `Sprache.t(englisch, deutsch)`. Log-Kategorien: `Log.info/warn/error/alarm/hinweis/action/debug`;
  `alarm`/`hinweis` gehen nach Discord.
- Optionale Vorlagen immer mit `...IfKnown` bzw. `matcher.has(name)` absichern; Vorlagen-Serien: jeder Name
  `base` oder `base_*` zaehlt (`iconVariants`, `map_button_*`, `ad_close_*`) -> zweites Aussehen ohne Codeaenderung.
- Moduswechsel/Zeitgrenze erkennen "Durchlauf fertig" nur am Zaehler (`completedRuns() > stand`) – Modi muessen
  `completeRun()` korrekt setzen, und `draussen()` muss stimmen, sonst startet der Folgemodus mitten im alten Bild.
- Kein festes `Thread.sleep` nach Klicks im Modus, sondern `ctx.sleepUntilChanged(max)`; Wartezeiten nicht pauschal
  senken (Uebergangsbilder!), immer modusweise testen (siehe `docs/PERFORMANCE-NOTIZEN.md`, Abschnitt 5).
- `Rechnertempo.mal(...)` skaliert Fristen fuer langsame PCs/Server – neue Fristen ebenso skalieren.
- `setState(FERTIG|FEHLER)` triggert die Bruecke synchron; `zielErreicht` entscheidet, ob die Kette weiterlaeuft
  (FINISHED/Wechsel: ja; Zeitgrenze/Fehler: nein).
- Tests laufen auf Linux; der Bot selbst nur unter Windows (Robot, Windows-OCR, Fenster-API).

## Tests (src/test/java/de/hustlebot/bot)
- `BotEngineTest` – `nachspielSperreBis` (10 min Sperre nur ohne Turnier).
- `RunTrackerSchleifeTest` – Klick-Schleife setzt Leerlauf-Uhr nicht zurueck.
- `LebendesBildTest` – `BotContext.lebendesBild`.
- `UnbekanntWarnungTest` – `Stoerfenster.warnungFaellig`. `UpdateKlickTest` – Update-Zeit merken.
- `SchirmLeserTest` – Punkte, Zusammenfassen naher Treffer, eigener Bereich.
- `BezahlkasseNahrungKistenTest` – Bezahlarten/Ticket->Nahrung.
- Ausfuehren: `mvn -q -o test -Dtest=BotEngineTest`.

## Rezepte "Wenn du X aendern willst"
- Neues Popup, das ueberall stoert -> `Stoerfenster` (+ in `belohnungWegklicken` oder `allgemeineStoerung` einhaengen), nicht in jeden Modus.
- Stillstands-Bericht kommt zu frueh fuer einen Modus -> `haengerBerichtNachS()`/`wartetNurAufAndere()` im Modus, oder Notiztext in `NORMALES_WARTEN`/`MITSPIELER_SUCHE` (BotEngine).
- Wechsel startet Folgemodus zu frueh -> `draussen()` des alten Modus; zu spaet -> `wechselJetzt()`.
- Wartetempo eines Modus -> `ctx.setSchnell` in `start()` (nur nach Messung), Kathedrale: `lernwerte.txt`.
- Weg zur Burg haengt -> `BotEngine.heimGehen` Klickkette bzw. `Weltkarte.heim`.
- Modus nur ab Thronsaal X -> `core/Thronsaal.gesperrtGrund`.
- Bezahlen mit Nahrung/Tickets -> `Bezahlkasse` + `core/Bezahlung`.
- Deploy best/previous -> `Aufstellung` (+ Discord `!settings`, Steuerprogramm `Einstellungswerte`).
- Hitze-/CPU-Grenzen -> Konstanten oben in `BotEngine` (`TEMP_*`, `CPU_*`, `DISK_MIN_GB`, `DEAD_MAN_MINUTES`).
