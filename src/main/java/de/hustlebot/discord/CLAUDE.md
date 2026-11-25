# discord/ — Discord-Anbindung und zentrale Befehlsverteilung

Paket `de.hustlebot.discord`, 10 Dateien, ~8,6k Zeilen. Eigene Gateway-/REST-Implementierung (Java `HttpClient` + WebSocket + Jackson), **keine** Discord-Bibliothek.

**Wichtigste Tatsache:** `DiscordBridge.befehl(...)` ist die EINZIGE Befehlsverteilung des Bots. Discord-Nachrichten, Discord-Knöpfe, das Start-/Settings-Formular, das Steuerfenster (`steuer/`), `befehl.txt` und Tastenkürzel landen alle dort als Text `"!..."`. Discord ist freiwillig — ohne Token läuft dieselbe Verteilung, nur ohne Gateway.

## Einbindung (wer ruft was)

`Kern.main` (siehe docs/claude/START-UND-BAU.md):
1. `new DiscordClient()` → `configure(discord.enabled, "" /*Webhook*/, discord.botToken, discord.channelId)`.
2. `new DiscordBridge(motor, discord, botToken, channelId, statsChannelId, Main.FASSUNG)` — Konstruktor hängt sich an den Motor: `engine.setMeldungOhneSpiel(...)`, `engine.setHeimFertig(...)`, `Kathedralzeiten.melder`.
3. `Steuerprogramm.vorbereiten(motor, bruecke, discord, config, configDatei)` → `discord.setMithoerer(...)` (VOR `bruecke.start()`, damit auch die ersten Meldungen ins Fenster kommen); `bruecke.setSteuerprogramm(steuer)` (Feld wird in der Freunde-Fassung sonst nicht benutzt).
4. `bruecke.start()`: liest `stumm.txt`; mit Token+Kanal: `DiscordGateway` (Kanäle = channelId + ggf. statsChannelId) mit Empfänger `befehl(k,t,absender)`, `setFixAnbindung(new StartFormularAnbindung())`, nach 8 s `client.rechtePruefen()` (Thread `discord-rechte`), `Log.setMelder`/`setHinweisMelder` → Karten, `TitanEventMode.tagesRanglisteZiel`, `StigmaMode.discordBildZiel`. Ohne Token: nur `Log.setMelder`/`setHinweisMelder` (gehen dann nur an den Mithörer = Steuerfenster). Immer: Daemon-Thread `discord-wache` → `wacheLaeuft()`.
5. `ctx.setDiscordHinweis(...)` → `discord.sendCard(..., FARBE_WARNUNG)`.
6. `bruecke.notAusStarten(notaus.taste|F9, configDatei)`, `notPauseStarten(notpause.taste|F10, weiter.taste|F11)`, `mauswacheStarten()` (nicht bei `ServerSparsam.an()`).
7. Shutdown-Hook → `bruecke.stop()` → `gateway.stop()`.

Bridge → Motor (`bot.BotEngine`): `start(modus, runs, "random"|"friend"|"friendwait", freund)`, `stop(grund)`, `togglePause()`, `geheHeim()`, `restart()`, `setCathedralRoute(a|b|c)`, `setStopAtClock(uhr)`/`stopAtMillis()`, `state()` (BEREIT/LAEUFT/PAUSIERT/FERTIG/GESTOPPT/FEHLER), `statusText()`, `tracker()`, `lastModeId()`, `activeMode()`, `context()` (window/matcher/frame), `wechselAngefordert()`, `wechselNachDurchlauf`, `folgeAbholen`, `fortsetzenOhneAutor()`, `heimGewuenscht()`, `heartbeatAlterMs()`, `modusPauseUm/Beenden`.

Öffentliche API der Bridge, die andere nutzen: `befehlVonBedienfeld(text)` (Steuerprogramm; Absender = eigener Rechner, darf alles), `static configWertSchreiben(datei, key, wert)` (zeilenweise, ISO-8859-1, `.neu` + atomic move — Kommentare bleiben), `spielbildPng()`, `kriegsplan()`, `notAus(taste)`, `start()/stop()`.

## Dateien

| Datei | Zeilen | Rolle |
|---|---|---|
| `DiscordBridge.java` | 5490 | Befehlsverteilung `befehl()`, alle Befehls-Handler, Warteschlange `kette` (List<Auftrag>), Kriegsuhr, Hintergrund-Wache, Start-/Settings-Formulare (Discord-Komponenten-JSON von Hand), Not-Aus/F10/F11/Mauswache. |
| `DiscordGateway.java` | 1016 | WebSocket `wss://gateway.discord.gg/?v=10`, Intents `GUILD_MESSAGES|MESSAGE_CONTENT|DIRECT_MESSAGES` (1<<9,1<<15,1<<12). Heartbeat, Identify, RESUME (sessionId+resume_gateway_url), Reconnect mit Backoff, Anmeldewache. `dispatch`: READY/RESUMED/INTERACTION_CREATE/MESSAGE_CREATE. Registriert Slash-Befehl `/start` (`fixBefehlAnmelden`), holt Server-Besitzer (`besitzerErmitteln`, `GET /guilds/{id}`, Ersatzweg über `/channels/{id}`). Record `Absender(name,id,guild)`, Interfaces `Empfaenger`, `FixAnbindung`. |
| `DiscordClient.java` | 925 | REST `https://discord.com/api/v10`: `send`, `sendCard(To)`, `sendCardMitKnoepfen` (≤25 Knöpfe, custom_id = Befehlstext), `sendCardMitPing`, `sendOnce`, `sendFile`, `sendFileAndGetId`, `kanalLeeren`, `deleteOldMessages`, `findChannelByName`, `rechtePruefen` (Embed-Links-Recht berechnen). Farben `FARBE_INFO/ERFOLG/WARNUNG/FEHLER/START`. Interface `Mithoerer` (Steuerprogramm bekommt JEDE Meldung, auch ohne Discord). |
| `Befehlsnamen.java` | 243 | Alias-Gruppen `BEFEHLE` (erster Name = englisch = kanonisch), `kanonisch()`, Tippfehler-Suche `suchen()` (Damerau-Levenshtein), `wahl()` für Unterwörter, `GEFAEHRLICH` (Tippfehler nie ohne Rückfrage). |
| `Kuerzel.java` | 239 | Eigene Kurzbefehle (`!maus` → `!start cathedral 25 c`), Datei `kuerzel.txt` (`maus = cathedral|c`), Enum `Ziel` (KATHEDRALE_B/C, VERLIES, VERLIES_FREUND, CLANKRIEG). Darf keine bekannte Schreibweise überdecken. |
| `Rechte.java` | 159 | Gefährliche Befehle nur für Server-Besitzer (owner_id je Guild) + `discord.erlaubteNutzer` + eigener Rechner. Besitzer unbekannt ⇒ Sicherheitsmodus (gesperrt). |
| `Flutbremse.java` | 88 | `!screenshot`, `!live`, `!log`, `!watch`: max. 1×/5 s je Nutzer-ID, nur im Speicher. |
| `LiveBild.java` | 184 | `!live`: EINE Nachricht, Bild alle 2 s ersetzt (Discord sperrt bei <~1 s), 5 min Dauer, JPG 960 px breit. |
| `Spielfoto.java` | 76 | Foto NUR des Spielfensters via `core.FensterFoto` (PrintWindow), null ⇒ Aufrufer nimmt Bildschirmbereich. |
| `NeuerungenFreunde.java` | 202 | Changelog für `!update`; neueste Einträge oben einfügen, Wichtigkeit 3/2/1 (🔥/✨/•), Grenze 3500 Zeichen. |

## Ablauf eines Befehls (`DiscordBridge.befehl(kanal, roh, absender)`)

1. Gateway: nur `MESSAGE_CREATE` in `channelIds`, nicht von Bots, Inhalt beginnt mit `!`. Knöpfe (`INTERACTION_CREATE` type 3) → custom_id ist ein Befehlstext → wird wie getippt behandelt (erst `quittieren`, Discord will Antwort <3 s).
2. `"! start"` → `"!start"` (Handy-Leerzeichen), Verb klein.
3. `ortBefehl(verb)`: `!lighthouse/!leuchtturm/!colosseum/!kolo…` (inkl. Tippfehler-Regex) ⇒ `einstellungen("leuchtturm|kolosseum " + rest)`, Rechte wie `!settings`.
4. Wenn kein eigenes Kürzel gleich heißt: `verb = Befehlsnamen.kanonisch(verb)`; `!clearall` → `!clear`.
5. `erlaubt()`: `Rechte.sperrtext` (gefährlich & nicht Besitzer ⇒ Karte "Nicht erlaubt", ggf. `gateway.besitzerNachladen()`), dann `Flutbremse`. Absender `eigenerRechner()` darf alles.
6. `switch(verb)`; `default`: Kürzel → rekursiv `befehl(...)` mit gleichem Absender; eindeutiger harmloser Tippfehler → "Verstanden als" + ausführen; sonst Vorschlag bzw. "Den Befehl kenne ich nicht".
7. Exceptions → Log + "Da ist etwas schiefgegangen".

## Befehlsreferenz (vollständig, Stand `befehl()`-switch)

Aliase: alle Schreibweisen aus `Befehlsnamen.BEFEHLE` werden vorher auf den ersten Namen gemappt; zusätzliche case-Labels in Klammern. **G** = gefährlich (`Rechte.GEFAEHRLICH`, nur Besitzer), **B** = Flutbremse, **T** = Tippfehler nur mit Rückfrage (`Befehlsnamen.GEFAEHRLICH`).

### Start/Stop/Warteschlange
| Befehl (Aliase) | Argumente | Wirkung / Ziel |
|---|---|---|
| `!start` (!starte, !starten, !begin, !los …) | leer | `startKarte` → Knopf öffnet Start-Formular (auch Slash `/start`) |
| `!start <modus> [n] [b\|c\|turbo] [friend <Name>[,Name2] [wait]] [tickets\|food\|tickets+food\|all\|food+chests] [eventloop\|noevent]` | | `starten()`: Bezahlart (`Bezahlung.auftragBeginnt`), Kreislauf (`Eventautomatik.fuerAuftrag`), Freund abtrennen (`freundAbtrennen` MUSS vor `zahlAus`), `modusAus`, lehnt ab bei LAEUFT/PAUSIERT, Team-Scan wird vorangestellt wenn `MeinTeam.scanFaellig()`, `kette.clear()` + Auftrag, `naechstenStarten` → `engine.start` |
| | Portal: `!start portal bis 135 farm 80:2mio,…` | `PortalMode.planAusText` vorab |
| `!after` (!danach, !dann) | wie !start (nicht clanwar, kein Portal-Plan) | hängt Auftrag an `kette` |
| `!queue` (!wq, !warteschlange, !kette) | `clear` / `del N` | Warteschlange zeigen/leeren |
| `!stop` (!stopp, !halt, !anhalten) T | — | `kette.clear()`, `engine.stop` |
| `!pause` (!pausieren) T | — | `engine.togglePause` (nur LAEUFT) |
| `!resume` (!continue, !weiter, !fortsetzen …) | — | `togglePause` (nur PAUSIERT) |
| `!castle` (!home, !burg) | — | `kette.clear()`, `engine.geheHeim()`; Meldung via `setHeimFertig` |
| `!emergency` (!kill, !notaus, !not-aus) G T | — | sofort `engine.stop` |
| `!ablauf` / `!routine` | `[ohne\|noevent]` | Steuerfenster-Knopf "Loop": `starten("cathedral c eventloop")` bzw. `cathedral BIS_LEER c noevent`; `!ablauf aus` startet NICHT |
| `!formularstart` | — | Start-Formular (Knopf "So starten") |
| `!end` (!ende, !schluss) T | `HH:MM`/`6am`/`aus` | `engine.setStopAtClock` — nach Run aufhören, PC bleibt an |
| `!off` (!shutdown, !aus, !feierabend, !ausschalten, !herunterfahren) G T | `HH:MM`, `3pm`, `15 uhr`, `now` (zweimal), `delete` | PC-Abschaltung: `offAtMillis` (persistiert `Dauerwerte AUS_UM/AUS_UHRZEIT`), `engine.setStopAtClock`, `offPruefen` → `PowerControl.shutdown(60)` wenn Motor steht |
| `!pc` (!rechner) G T | `off [now]`, `restart` (zweimal), `cancel` | `PowerControl` |
| `!restart` (!neustart) G | — | `engine.restart()` im Thread |

### Modus-Direktstarts (= `starten("<id>")`)
`!tower` (!turm, !titanturm) T → `titantower` · `!friends` (!freunde, !geschenke) → `freundesenden` · `!clanstore` (!clanladen, !clanshop) → `clanstore` · `!gembay` (!edelsteinbucht) T → `gembay` · `!portal` T → `portal` · `!foodchests` (!nahrungskisten) T → `nahrungskisten` · `!dig` (!graben, !titanstart …) T → `commandTitan("start …")` · `!game` (!spiel, !hc) T → `GameLauncher.wiederErlauben()` + `sicherstellen()` (Thread).

Modus-IDs aus `modusAus()` (Reihenfolge wichtig!): `grabung` (grabung/excavation/titanic/titan event/dig) → `arena` (+Tippfehler, turnier, pvp) → `heldenset` → `teamscan` → `burgen` (burgen/castles/raid) → `event`/hammer → `portal` → `gembay` → `nahrungskisten` → `lighthouse` → `colosseum` → `freundesenden` → `titantower` (titan/turm, nicht leuchtturm) → `clanladen` → `cathedral` → `dungeon` → `clanwar` (enthält "war"). Achtung: `modusAus` sucht Teilstrings — deshalb Freund vorher abtrennen (Freund "Warrior" ⇒ clanwar).

### Einstellungen
| Befehl | Argumente | Wirkung |
|---|---|---|
| `!settings` (!options, !config, !einstellungen, !aufstellung …) G | leer | `einstellungenKurz`: Karte mit 5 Formular-Knöpfen `setformular:krieg|bezahlen|automatik|nebenbei|aufstellung` |
| | `all` | alles ausführlich |
| | `tipp an\|aus\|jetzt\|weiter\|ping everyone\|aus\|<ID>` | `core.Tagestipp` |
| | `pay\|bezahlen <modus\|alle> tickets\|food\|tickets+food\|all\|food+chests` | `Bezahlung.setStandard` |
| | `foodlimit <modus\|alle> 30m\|aus` | `Bezahlung.setLimit` |
| | `storage\|lager 1.2m` | Nahrungskisten-Auffüllgrenze |
| | `pass kein\|silber\|gold`, `lupen\|magnifiers N` | `Eventautomatik` (Hammer-Event) |
| | `kiste\|chest <holz…> an\|aus` | Event-Kisten |
| | `reports\|meldungen N` | `Meldetakt.setTakt` (Discord nur alle N Runs) |
| | `nahrungskisten N`, `zauberstaebe N\|aus`, `hammerstages N`, `hammerevent N`, `titanevent N` | `Eventautomatik` |
| | `eventautomatik\|automation\|loop an\|aus\|N\|titan\|hammer` | Kreislauf, Verlies-Runs, welches Event |
| | `heldenset vorher\|nachher <Name>\|test` | `bot.HeldenSet` |
| | `spielneustart an\|aus\|jetzt` | `core.Spielneustart` (alle 2 h) |
| | `accounts\|konten …` | `core.Konten` (mehrere Spielkonten) |
| | `arenawatch\|beobachter an\|aus` | ArenaBeobachter |
| | `mauswache\|mouse an\|aus` | `core.Mauswache` |
| | `lighthouse\|leuchtturm\|colosseum\|kolosseum an\|aus` | `LighthouseMode.LEUCHTTURM/KOLOSSEUM` (auch direkt `!lighthouse off`) |
| | `titantower`, `friends`, `clanladen` `an\|aus` | Nebenbei-Schalter |
| | `notaus\|emergency\|panic <Taste>\|aus` | `notAusEinstellen` → `Einrichtung.einzelnSchreiben(config, notaus.taste, …)` + Wache neu |
| | `squad\|truppe best\|previous` | alle `AUFSTELL_MODI` (dungeon, cathedral, arena, clanwar_tower, clanwar_outpost) |
| | `<modus> best\|previous` | `bot.Aufstellung.setzen` → `aufstellung.properties` |
| `!clanwar` (!clankrieg, !krieg, !kriegsplan) | `on\|off` | `kriegsplan.setSelbsttaetig`; off nimmt eingereihten Krieg raus |
| `!timezone` (!zeitzone) G | `Europe/Berlin` / leer | `core.Zeitzone` |
| `!autostart` G | `an\|aus` | Verknüpfung `%APPDATA%\…\Startup\Hustle Castle Bot.lnk` per PowerShell (Pfade über Env `HB_LINK/HB_EXE`); nur im jpackage-Paket |
| `!english` (!en) / `!deutsch` (!german, !de) | — | `Sprache.set` + `Sprache.speichern` → `sprache.txt` |
| `!text` (!plain, !nurtext) | `an\|aus` | `client.setNurText` → `nur-text.txt` (Karten als Text) |
| `!mute` (!stumm) / `!unmute` (!laut) | — | `stumm.txt`, Nachrichten ohne Benachrichtigung |
| `!shortcut` (!shortcuts, !kuerzel) | `list`, `delete <wort>`, sonst Dialog Modus→Wort (Knöpfe `!shortcut wahl N`) | `Kuerzel` |
| `!opfer` / `!sacrifice` | `Name1, Name2` / `aus` | `opfer-namen.txt` → `DungeonMode.opferNamen()` |

### Spielmodus-spezifisch
| Befehl | Argumente | Ziel |
|---|---|---|
| `!titan` (!excavation, !grabung) | `start [n]`, `N` (max Spitzhacken), `rangliste\|ranking`, `drill an\|aus`, `hc an\|aus` (`titan-hc.txt`, relativer Pfad!) | `commandTitan`, `TitanEventMode` |
| `!stigmas` (!stigma, !stigmata) T | `loeschen\|delete [probe\|test] [alles]`, `leveln [<Titan>] [100]`, `hundert\|100`, `einstellen\|settings` | `commandStigmas` → `StigmaMode`, `core.Stigmaplan`; unbekanntes Zusatzwort bei loeschen ⇒ nichts löschen |
| `!team` / `!teamscan` G | leer, `scan`, `an\|aus`, `punkte an\|aus`, `held 12 4`, `name <Name>`, `vorlagen` | `commandTeam` → `bot.MeinTeam`, `TeamScanMode` |
| `!arenareserve` (!reserve, !arenatickets) | `N` | `ArenaMode.setTicketReserve` (arena-reserve.txt) |
| `!arenareport` (!arenabericht, !arenastats) | `an\|aus` | `ArenaMode.BERICHT` (Schalterdatei) |
| `!notes` (!notizen, !notizblock) | — | `Gegnerbuch.NOTIZBLOCK` als Datei |
| `!note` (!notiz) | `<Name> <Text>` / `<Name>` / `<Name> weg`; Namen mit Leerzeichen in "" | Gegnerbuch-Notiz |
| `!list` (!liste) | — | `ArenaMode.LISTE` + Notizblock |
| `!arena` (!opponent, !gegner) | `<Name> safe\|doable\|avoid\|never` (de: sicher/machbar/meiden/niemals) | `core.ArenaAufnehmen` |
| `!glyphs` (!glyphen) G | `[ab-Nummer]` | `core.Glyphenschrift` Tafel (30 je Bild) |
| `!glyph` (!glyphe) G | `<Nr> <Zeichen>` | Glyphe beschriften |
| `!event` | `ranking\|rangliste` | nur während `EventMode` läuft: `ev.ranglisteSchicken` |
| `!times` (!zeiten, !kathedralzeiten) | — | `Kathedralzeiten.uebersicht()` |
| `!fotoserie` / `!photoseries` | `[min]` (1–10, Std 5) | `core.Fotoserie.starten` |
| `!lesetest` / `!readtest` | `[n]` (Std 20) | `bot.Lesetest.starten` |

### Nachsehen / Diagnose
| Befehl | Argumente | Wirkung |
|---|---|---|
| `!help` (!hilfe, !befehle, !commands) | `[all]` | `hilfe()` / `hilfeAlles()` (Texte zweisprachig inline) |
| `!status` (!state, !stand) | — | `status()` |
| `!stats` (!statistik) | `N` (alle N min, persistiert `Dauerwerte.STATS`), `off` | `statsSchicken()`; geht an statsChannelId |
| `!specs` (!hardware, !werte) | `N`, `off` | `SystemMonitor.read()`, nvidia-smi |
| `!screenshot` (!screen, !pic, !photo, !bild, !foto) G B | — | `spielbild()` (Spielfoto, sonst Frame) als PNG |
| `!live` (!liveview, !livebild) G B | `stop` | `LiveBild` |
| `!watch` (!wach, !awake) G B | `N` (1–240 min, persistiert `Dauerwerte.WACH`), `off`, `status` | `watchPruefen`; endet wenn Motor GESTOPPT/FERTIG/FEHLER |
| `!log` (!report, !bericht, !protokoll) B | — | `core.Report.build` → `fehlerbericht.txt` |
| `!update` (!changelog, !whatsnew, !neu …) | `[alle]` | `NeuerungenFreunde.text` |
| `!cleaner` (!reiniger) | — | Hinweis auf `PC-Cleaner\PC-Cleaner.bat` |
| `!clear` (!leeren, !aufraeumen, !putzen; !clearall) G T | `ja\|yes` (oder Knopf) | `client.kanalLeeren` (Recht "Nachrichten verwalten") |

Nicht im Switch (gehen ins `default`/Tippfehler): `!steuer`, `!fix`, `!beenden`, `!quit`, `!sperren` — die behandelt nur das Steuerprogramm lokal (`Steuerprogramm`, `Befehlsbau.BEENDEN`), bevor es `befehlVonBedienfeld` ruft. Die Hilfe erwähnt eine "Notfall-Wache" (2.1) — in der Freunde-Fassung ist dafür kein Code in `discord/` (nur Sprachtext, `ServerSparsam`-Kommentar).

## Hintergrund-Wache (`wacheLaeuft`, alle 2 s)
`zustandPruefen` (Zustandswechsel: FERTIG ⇒ Auftrag aus `kette` entfernen, Event-Automatik-Folge, `naechstenStarten(false)`; FEHLER ⇒ Meldung+Bild+Bericht, Einschub-Fehler ⇒ Rest der Kette weiter; GESTOPPT ⇒ Kette leeren) · `motorPruefen` (Heartbeat) · `runPruefen` (Meldung je Run, Takt `Meldetakt`) · `kriegPruefen` (Kriegsuhr: fälliger Krieg wird nach laufendem Run eingeschoben; nicht nach Rauswurf/eigener Pause) · `nebenbeiPruefen` (Leuchtturm/Kolosseum/Turm/Clanladen/Krieg einschieben bei dungeon/cathedral/arena; bei portal/titantower/event nur Krieg) · `resetStartPruefen` (02:00–06:00 nach Reset, nur aus BEREIT/FERTIG) · `statsPruefen` · `watchPruefen` · `specsPruefen` · `offPruefen` · `tagestippPruefen` (12:00, nachgeholt 02:00).

## Formulare (Discord-Komponenten)
- `/start` (Slash, Typ 2) oder Knopf `startformular*` → `startFormular()` liefert Modal-JSON (type 9). Abgeschickt `start:form` / `start:event` (type 5) → `startEingereicht` → baut Befehlstext (`!start …` + optional `!off HH:MM`) → `startAusfuehren` zeigt "Beim nächsten Mal einfach tippen: …" und ruft `befehl()`. `!off` aus dem Formular nutzt den echten Absender (`_nutzerId`, `_guild`).
- `setformular:<bereich>` / `set:<bereich>` → `setFormular`/`setEingereicht`; Rechteprüfung im Gateway (`!settings`), weil alte Knöpfe in alten Karten stehen bleiben.
- Interaktionen nur in `channelIds` (sonst ephemere Antwort flags 64).

## Invarianten / Gotchas
- **Erster Name jeder `Befehlsnamen.BEFEHLE`-Gruppe muss als `case` in `befehl()` stehen** und ist englisch (BefehlsnamenTest). Neue Gruppe nur, wenn der Befehl hier existiert — sonst wird er auf einen unbekannten Befehl umgebogen.
- **`SteuerBefehleTest` parst `DiscordBridge.java` per Regex `case ("![^>]*)->`** — die case-Labels müssen als String-Literale in dieser Form bleiben, sonst schlagen Steuerprogramm-Tests fehl.
- Gefährliche Befehle sind in ZWEI Listen: `Rechte.GEFAEHRLICH` (Berechtigung) und `Befehlsnamen.GEFAEHRLICH` (Tippfehler-Rückfrage). Beide nutzen kanonische (englische) Namen. Neuer gefährlicher Befehl ⇒ beide prüfen + `RechteTest`.
- Kürzel/Tippfehler-Auflösung ruft `befehl()` rekursiv MIT demselben Absender — Rechte greifen auch für den aufgelösten Befehl. Nicht umgehen.
- Eigenes Kürzel gewinnt vor `kanonisch()`; Kürzel dürfen aber keine bekannte Schreibweise belegen (`BEFEHLE`-Set in der Bridge + `alleSchreibweisen()`).
- `starten()` darf bei LAEUFT/PAUSIERT die Kette NICHT anfassen (22.10.: Arena x20 war weg).
- Jeder Auftrag trägt seinen Freund; leerer Freund ⇒ "random" (sonst lief der Freund vom letzten Mal mit).
- `freundAbtrennen` vor `zahlAus` (sonst "friend Ady712" ⇒ 10712 Runs) und vor `modusAus`.
- `!clear`: nur explizites `ja/yes` (Wiederholen zählt nicht als Ja).
- `!stigmas loeschen` mit unbekanntem Zusatzwort löscht nichts (Tippfehler "prbe" löschte einst echt).
- `!settings titan/hammer` allein ≠ Event — `titan best|previous` stellt den Außenposten-Trupp.
- Client: 429 ⇒ 20 s stumm; 5 Fehlschläge in Folge ⇒ 5 min Discord-Pause. Fehlendes "Links einbetten" ⇒ Karten kommen leer an ⇒ Fallback Text (`kartenGehenNicht`).
- Mithörer bekommt Meldungen VOR der Discord-Entscheidung — Steuerfenster funktioniert ohne Discord. Mithörer-Exceptions werden geschluckt (Fenster darf Bot nie aufhalten).
- `Rechte.ausConfig` liest config.properties als UTF-8, `configWertSchreiben`/`Einrichtung` schreiben ISO-8859-1 (IDs sind ASCII, daher unkritisch).
- `NeuerungenFreunde.nummer("3.142")` = 3142 > "3.3" (=3003): ein Eintrag mit Fassung "3.142" würde als neueste sortiert; aktuell oberster Eintrag ist "3.3".
- Kommentare/Texte zweisprachig über `Sprache.t(en, de)` — immer beide Sprachen pflegen (EN ist Standard seit 3.1).

## Dateien auf Platte (Bot-Ordner, `core.Ordner.bei`)
Lesen: `config.properties` (`discord.botToken`, `discord.channelId`, `discord.statsChannelId`, `discord.enabled`, `discord.erlaubteNutzer` (kommagetrennte IDs), `notaus.taste`, `notpause.taste`, `weiter.taste`). Lesen/Schreiben: `kuerzel.txt`, `stumm.txt`, `nur-text.txt`, `sprache.txt`, `opfer-namen.txt`, `dauerwerte.properties` (STATS, WACH, AUS_UM, AUS_UHRZEIT via `core.Dauerwerte`), `aufstellung.properties`, `titan-hc.txt` (relativer Pfad → `user.dir` = Bot-Ordner), Arena-Dateien (arena-reserve.txt, arenabericht.txt), `opfer-namen.txt`. Autostart-Link in `%APPDATA%`.

## Tests (`src/test/java/de/hustlebot/discord/`)
`BefehlsnamenTest` (erster Name englisch, deutsche Aliase gültig, Tippfehler bei Modus-Starts fragen nach, GEFAEHRLICH sind erste Namen) · `RechteTest` (Besitzer/Fremde, Sicherheitsmodus, erlaubte Liste, eigener Rechner, je Server, ausConfig) · `FlutbremseTest` · `DiscordGatewayTest` (`verarbeiten(json)`: Absender-ID/Guild, fremde Kanäle/Bots draußen, Knopf-IDs, Settings-Formular nur Besitzer, Start-Formular legt `_nutzerId` ab, `besitzerAus`) · `SpielfotoTest` (BGRA→RGB, ohne Fenster null). Indirekt: `steuer/SteuerBefehleTest` (alle Steuerprogramm-Befehle müssen im Switch existieren; nutzt per Reflection `ortBefehl`, `istAn`, `istAus`, `menge`, `zahlAus`, `normalizeClock`).

## Rezepte
- **Neuer Befehl:** case in `DiscordBridge.befehl()` (englischer Name zuerst), Gruppe in `Befehlsnamen.BEFEHLE`, Aliase ins `BEFEHLE`-Set der Bridge (Kürzel-Schutz), ggf. `Rechte.GEFAEHRLICH`/`Befehlsnamen.GEFAEHRLICH`/`Flutbremse.GEBREMST`, Hilfe in `hilfe()` + `hilfeAlles()` (EN+DE), Eintrag in `NeuerungenFreunde`, ggf. Knopf in `steuer/Befehlsbau`.
- **Neuer Spielmodus startbar:** ID in `modusAus()` (Reihenfolge beachten!), `modusName()`, ggf. `laeuftBisLeer`, `NEBENBEI_BEI`/`KRIEG_FORTSETZEN`; Modus selbst in `bot/BotEngine`.
- **Neue Einstellung:** Zweig in `einstellungen()` (vor dem Aufstell-Fallback am Ende), ggf. Formularfeld in `setFormular`/`setEingereicht`, Steuerfenster nutzt `!settings …`-Text.
- **Discord-Protokoll / Reconnect:** `DiscordGateway.handle/dispatch/connect/scheduleReconnect`.
- **Meldungsformat / Rate-Limit:** `DiscordClient.karteNachDiscord`, `fire`, `failure`.
- **Wer darf was:** `Rechte`, `DiscordBridge.erlaubt`, Gateway `fixInteraktion`.
- **Befehle ohne Discord testen (Windows):** Zeile `!start dungeon 3` in `befehl.txt` im Bot-Ordner (Steuerprogramm liest alle 2 s und löscht sie).

## Nachtrag 25.11.
- `!throneroom` / `!thronsaal` / `!ts` [N] → `DiscordBridge.thronsaal`: setzt Thronsaal-Stufe von Hand (`Thronsaal.vonHandSetzen`, Konto oder `standard=`), zeigt Stufe + Arena-Strategie (`ArenaMode.einfacheStrategie`).
- `!arenacathedral` / `!arenakathedrale` [an|aus] → `arenaKathedraleSchalter`: Schalter `arena-kathedrale.txt` (Kathedrale in der Arena-Wartezeit).
