# Verlies, Kathedrale & Nebenorte (Dungeon-artige Modi)

Gilt fuer: `DungeonMode` (Verlies, ~5,5k Zeilen), `CathedralMode` (Dunkle Kathedrale, ~6,3k), `Etagennetz` (Wegplanung
Kathedrale, ~1,9k), `LighthouseMode` (Leuchtturm **und** Kolosseum), `TitanTurmMode` (Titanen-Turm "Tower"),
`PortalMode` (Portal-Autokampf), `Vasensuche` (Helfer des Hammer-Events), dazu `core/Kathedralzeiten`, `core/Turmplan`,
`core/Energieplan`, `core/Eventkisten`, `core/Eventautomatik`, `bot/Bezahlkasse`.
Uebergeordneter Index: `bot/CLAUDE.md`. Der Bot laeuft nur unter Windows -- unter Linux nur Tests/Lesen.

## 1. Einbindung

- Alle Modi `implements GameMode` (`bot/GameMode.java`): `id()`, `requiredTemplates()`, `reset()`,
  `step(Mat screen, BotContext ctx, RunTracker runs) -> Outcome` (ACTED / WAITING / UNKNOWN / FINISHED / PAUSE / FATAL),
  dazu Default-Hooks `darfHeim()`, `draussen()`, `wechselJetzt()`, `sicherePunkte()`, `haengerBerichtNachS()`,
  `jetztInDieBurg()`, `wartetNurAufAndere()`, `endeHinweis()`, `extraStats()`, `folgeAuftrag()`.
- Registriert in `BotEngine` (Konstruktor, `register(...)`): `DungeonMode` ("dungeon"), `CathedralMode` ("cathedral"),
  `new LighthouseMode(LighthouseMode.LEUCHTTURM)` ("lighthouse"), `new LighthouseMode(LighthouseMode.KOLOSSEUM)`
  ("colosseum"), `TitanTurmMode` ("titantower"), `PortalMode` ("portal").
- Der Motor ruft `step()` pro Bild. Jeder Modus ist eine **Regelkette**: bei jedem Bild von oben nach unten pruefen,
  erste passende Regel handelt. Kein starrer Ablauf -> unerwartete Fenster werden abgefangen. Die Reihenfolge der
  Regeln ist hart erkaempft (siehe Gotchas) -- nicht umsortieren ohne die Kommentare zu lesen.
- `BotEngine.start()`: setzt fuer Verlies `setTeammate(RANDOM|FRIEND, friendName, aufEinladungWarten)` (teammate-Wort
  "random" / "friend" / "friendwait"), `setLeaderPickTimeoutMinutes` (Std. 3), `setPartnerReadyTimeoutMinutes` (Std. 5).
  `setCathedralRoute("b"|"c")` (alles ausser "b" = TURBO; "a" gibt es seit 26.09. nicht mehr -> c).
  `kathedraleKistenGang()` / `nurKistenGang()` existieren in BotEngine (Kisten-Gang ohne Reise).
- `LighthouseMode` wird vom Motor erst aus der Burg gestartet (`vorBesuchInDieBurg()`).
- Leuchtturm/Kolosseum/Titanen-Turm werden **eingeschoben** von `DiscordBridge.nebenbeiPruefen()` bzw.
  `resetStartPruefen()` (Plan-Klassen `Energieplan`, `Turmplan`; Clan-Krieg geht immer vor). Danach laeuft der Rest des
  Auftrags weiter.
- Statische Namens-Getter (`DungeonMode.bildNamensschild()`, `bilderKaempfer()`, `bilderKaempferNamen()`,
  `bildOpferPortraet()`, `bildOpferName()`, `freundVorlage(name)`, `altarPlaetze()`, `platzVon()`,
  `CathedralMode.bilderWiederbeleben()`, `bildBelohnungText()`) werden von `tools/Bilderwerkzeug`
  (Capture-Images.exe), `tools/Selbstpruefung` (Paketbau) und `steuer/Gesundheitscheck` benutzt -> **Bildnamen nur hier
  aendern, nie abschreiben** (Abschreibfehler war der Grund fuer den Umbau).

## 2. Dateien

| Datei | Rolle |
|---|---|
| `bot/DungeonMode.java` | Verlies zu zweit (Leiter/Mitspieler), Zufallsspieler oder Freund(e). `step()` = Regelkette Nr. 0-19. |
| `bot/CathedralMode.java` | Dunkle Kathedrale allein, 3 Etagen, Turbo-Blindklicks, Schluessel/Kisten/Event-Kreislauf. |
| `bot/Etagennetz.java` | Wegenetz einer Kathedralen-Etage (Punktlinien erkennen, aus dem Spiel lernen, 5 feste Karten, kuerzester Plan). |
| `bot/LighthouseMode.java` | Leuchtturm + Kolosseum (record `Ort`), Energie lesen, bis 5 Kaempfe, Refresh, Kolosseum-Power-Spaehen. |
| `bot/TitanTurmMode.java` | Titanen-Turm Easy/Hard/VeryHard je 30 Autokaempfe. |
| `bot/PortalMode.java` | Portal: Level-80-Autokampf (30 Kaempfe/Durchgang) bzw. Plan "Aufstieg bis N + Farm-Liste". |
| `bot/Vasensuche.java` | Fass-Suche im Hammer-Event nur im Spielfeld-Ausschnitt (`EventMode.Raster`). Gehoert fachlich zu `EventMode`. |
| `bot/Bezahlkasse.java` | Bezahlen am Teilnahmefenster (Tickets=Fackeln/Schluessel oder Nahrung, Nahrungskisten). **Nie Diamanten.** |
| `core/Kathedralzeiten.java` | Rundenzeiten der Kathedrale (letzte 50, `kathedrale-zeiten.txt`), Meldung alle 10 Runs, `!times`. |
| `core/Energieplan.java` | WANN Leuchtturm/Kolosseum faellig (nach Kaempfen 105 min, nicht voll 21 min). |
| `core/Turmplan.java` | WANN Titanen-Turm (Fenster 02:00 und 12:00, je 55 min), Maxima je Turm. |
| `core/Eventautomatik.java` | Kreislauf Kathedrale -> Kisten -> Hammer-/Titan-Event -> Kathedrale -> (Verlies-Runs). |
| `core/Eventkisten.java` | Welche Kisten (holz/eisen/gold/mithril/mana/nahrung) das Hammer-Event mitnimmt. |
| `src/main/resources/kathedrale/punkt_blass.png`, `punkt_hell.png` | Punkt-Vorlagen fuer Etagennetz-Linien (Klassenpfad, gemessen an 1159 px Breite, mitskaliert). |

Hilfsklassen, die hier oft auftauchen: `Stoerfenster` (Update/AFK, taegliche Belohnung, anderes Geraet),
`FreundesChat` (Einladung im Chat annehmen), `Truhenoeffner` (Kathedralen-Kisten in der Tasche), `EventMode` /
`TitanEventMode` (Event-Gang aus der Kathedrale), `NahrungskistenMode` (Portal holt Nahrung), `Aufstellung`
(`istPrevious("dungeon")`: Previous Squad vs. Deploy best), `Lernwerte` (`lernwerte.txt`), `Zeitbericht`,
`core/Tempolerner`, `MeinTeam` (eigener Spielername fuer Leiter-Erkennung).

## 3. Vorlagen

Name = Dateiname ohne `.png`, Ordner egal, Name darf **nur einmal** existieren (bei Doppelten gewinnt die alphabetisch
letzte -- so hat `dc_guardian` aus dem Verlies-Ordner die Kathedrale lange blind gemacht -> deshalb `dc_guardian_lair`).
Bundled Fallbacks liegen 1:1 gespiegelt in `src/main/resources/nachschub/<ordner>/` (+ `nachschub/gelernt/...`).

- `templates/dungeon/`: Verlies **und** viele `dc_*` der Kathedrale (dc_node, dc_participate, dc_previous_squad,
  dc_attack, dc_blessing*, dc_move_on, dc_claim_reward, dc_finish_selection, dc_next_floor, dc_journey_done, dc_key_icon,
  dc_resurrect*, dc_question, Wegpunkt-Motive dc_altar/dc_camp/dc_danger/dc_minion/dc_skirmish/dc_ally ...),
  Ziffern `digit_0..9`, Schluesselziffern `key_0,2,3,8,9` (Rest `key_1,4-7` in `cathedral/`), Effekt- und Wegpunkt-Bilder.
- `templates/cathedral/`: dc_stage_1..3 (Etagenzahl), dc_guardian_lair/_text, dc_end_journey (gruen), dc_for_food(_off),
  dc_for_keys_off, dc_text_* , dc_done_empty, dc_home_done, dc_health_zero ...
- `templates/lighthouse/`: `lh_*` + `ar_victory` (Arena-Siegbanner passt auch hier).
- `templates/colosseum/`: `ko_*` -> **LighthouseMode(KOLOSSEUM)**; `ko_tower` (Weltkarte "Tower") nutzen auch
  `TitanTurmMode` (`T_KARTE_TURM`), `StigmaMode`, `Weltkarte`.
- `templates/titantower/`: `tt_*`. `templates/gelernt/portal/`: `pt_*` (Portal, live geschnitten bei 1158x770).
- Varianten-Konvention: jede Vorlage `<grundname>_irgendwas` zaehlt als derselbe Knopf (`variantsOf`, `clickAny`,
  `visibleAny`): ready*, deploy_best*, resurrect*, moving_here*, ally_left*, go_here*, finish_selection*,
  dc_previous_squad*, waypoint_swords* ... Neues Aussehen = neue Aufnahme, keine Codeaenderung.
- Vom Nutzer aufgenommen (nicht im Repo): `unit_1..6`, `name_1..6` (Kaempfer nach Wichtigkeit), `unit_sacrifice`,
  `name_sacrifice`, `unit_auto_opfer`/`name_auto_opfer` (Team-Scan), `nameplate_me`, `friend_<name>`
  (`freundVorlage`: klein, Umlaute ersetzt, nur a-z0-9), `block_*` (Sperrliste untaetiger Mitspieler),
  `effect_ban_*` (verbotene Effekte), `quit_warning`/`quit_yes`.

## 4. Verlies (`DungeonMode`)

Ablauf: Burg -> `map_button` -> Weltkarte (wischen bis `dungeon_node`, max 8) -> Teilnahmefenster (`participate`,
Reiter `tab_torches`/`du_for_food`) -> Mitspielerwahl (`teammate_random` / `teammate_friend`) -> Suche (`cancel_search`)
-> Verlieskarte (`end_journey`) -> Wegpunkte bis 100 % -> `end_journey_green` -> `end_journey_yes` ->
Abschlussfenster `journey_complete` (+ ggf. Folge-Reise mit Freund) -> `close_x` -> Burg/Teilnahmefenster.

Regelkette in `step()` (Reihenfolge = Prioritaet):
0 Kassen-Gang (`kasse.schritt`) / anderes Geraet (`Stoerfenster`) / Chat offen beim Warten auf Einladung ->
1 Update/AFK -> 1a taegliche Belohnung -> 1b Kathedralen-Teilnahmefenster im Weg (`onCathedralScreen` -> `close_x`) ->
1c `quit_warning` -> 2 `journey_complete` (`folgeReise`, sonst X; vorher ggf. "Yes") -> 2b `end_journey_yes` nur wenn
`endJourneyAllowed` -> 3 `close_reward` -> 4 `home_button` (Sieg/Niederlage, nie zaehlen) -> 4 `go_to_map`
(Leiterwechsel) -> 4b Stillstand 60 s (`IDLE_ABORT_SECONDS`, nicht waehrend Suche/Teilnahme/Warten auf Partner) ->
4c Ausstiegsgruende (ally_left, Partner nicht Ready, Sperrliste `block_*`) -> `handleAbort` -> 5 Wiederbeleben ->
6b Opferaltar -> 6 Effektauswahl -> 7 Zweikampf -> 8 Aufstellen/Confirm/Ready/Quick Attack/To battle ->
9 `go_here` -> 10 Suche -> 10b `invite_send` -> 11 Freundesliste -> 11b `invite_accept`/`decline` ->
11c `start_journey` -> 12 Mitspieler waehlen -> 13 Teilnahmefenster (`handleParticipation`) -> 14 Weltkarte ->
15 Karte wischen -> 15b Werbe-X (`ad_close`) **nur wenn Map-Knopf (Burg) sichtbar** -> 16 Burg -> Map ->
17 Verlieskarte (`handleDungeonMap`) -> 18 Sicherheitsabfrage -> 19 Notausgang (rotes X) / UNKNOWN.

Wichtige Teilablaeufe:
- **Leiter vs. Mitspieler** (`isLeader`/`leiterNachName`): Leiter steht links unten auf dem Namensschild
  (`nameplate_me`); faellt das Bild aus, OCR beider Schilder (x 0,36 / 0,66, y 0,90) gegen Spielernamen aus `MeinTeam`.
  Leiter: `pickWaypoint` = bester Treffer aus `WAYPOINTS` (skull, swords, mirror, boss, effect, sacrifice; Schwelle 0,82)
  ohne `lock_icon` in der Naehe. `waypoint_question` bewusst NICHT (zu viele Fehltreffer).
  Mitspieler: `followTeammate` klickt Wegpunkt unter `moving_here*` (Schwelle 0,72; Rueckfall 5 % Hoehe darunter),
  folgt bei Umwahl neu; Frist `leaderPickTimeoutMillis` (3 min), dann raus.
- **Aufstellen**: Schalter `deployedThisScreen` (keine Uhr!). Knopfreihenfolge nach `Aufstellung.istPrevious`:
  `dc_previous_squad*` oder `deploy_best*`. Klick nur, wenn Knopf stillsteht (`stehtStill`, Fenster faehrt herein).
  **Nie "Ready" vor "Deploy"** (Ready erst nach Aufstellen, frühestens 0,8 s danach, oder 2,5 s kein Deploy-Knopf).
  Fehlt Deploy 10 s (`DEPLOY_MISSING_GRACE_MS`), wird trotzdem gekaempft.
- **Kampf**: `partnerIsReady` = BEIDE gruenen Haken (links+rechts) oder gar keiner. Quick Attack nur wenn farbig
  (`kampfKnoepfeGrau`: Saettigung grau 0,00, farbig 0,51-0,76). `to_battle` nur als Rueckfall: als Leiter nach 2,5 s,
  als Mitspieler nach 15 s ohne Quick Attack.
- **Effektauswahl** (`handleEffectSelection`, `pickEffectSlot`): erst `go_here`; dann Effekt waehlen ->
  `effect_select` -> Ready -> Finish selection. Eigene Seite am Ready-Knopf, positiv/negativ an Kartenfarbe (gruen/rot).
  Reihenfolge positiv: Magier, Bogen, Krieger; negativ (Gegner schwaechen): Krieger, Bogen, Magier; unerkannte Felder
  (Mitte, links, rechts) vor der unerwuenschten Klasse; `effect_ban_*` nie. Max 8 Klicks. `effect_selected` = fertig.
- **Opferaltar** (`handleSacrifice`): Reihenfolge `SACRIFICE_ORDER` unit_sacrifice, name_sacrifice, unit/name_auto_opfer,
  unit_6..unit_1; zusaetzlich OCR-Namen aus `opfer-namen.txt`; Leiste bis 3x wischen (`LEISTE_WISCHE_MAX`), zuletzt
  letzte lebende Einheit (`schriftBloecke`/`letzteLebende`). Ziehen in Feld links (Leiter, x 0,286) / rechts (0,709).
  Danach Ready -> Finish selection (Schalter `sacrificeDone`).
- **Wiederbeleben** (`resurrectionActive`/`handleResurrection`): nur auf Aufstellbildschirm (`deploy_screen`-Zeile).
  Tote ueber `unit_dead*`, Karte (`switch_dwellers`) -> `resurrect` -> `resurrect_confirm`. Opfer-Kaempfer
  (`name_sacrifice`) nie; am Altar nur `ALTAR_KEEPERS` {1..4}. `aliveSkip`: 1-HP-Einheiten sehen tot aus -> merken.
  **resurrectionActive und handleResurrection muessen exakt dieselbe Auswahl treffen** (sonst ewige Warteschleife).
- **Zweikampf** gegen Partner (`isPartnerDuel`, `duel_marker`/`duel_slot`): unit_1/unit_2 in leere Slots ziehen;
  `duelActive`-Merker, weil der Marker-Text verschwindet, sobald beide drin sind.
- **Fertig**: `isDungeonComplete` = `end_journey_green` >= 0,96 ODER `progress_100` >= 0,985 (92 % sah wie 100 % aus).
  Nach Boss: `ally_left` 6 s tolerieren (`BOSS_FERTIG_FRIST_MS`), falls doch fertig.
- **Zaehlen**: Run zaehlt erst draussen (Autor 18.10.): `endRun()` am Abschlussfenster bzw. Rueckkehr Burg/Weltkarte,
  hoechstens einmal (`runCounted`), nicht nach Abbruch (`runAborted`, ueberlebt `abortReason`), nicht nach Spielabsturz
  (`betretenUm`). FINISHED erst draussen, sonst startet der Folgemodus im offenen Verlies. Beim Klick auf
  "End journey" bei 100 % wird NICHT mehr gezaehlt (nur `fertigGezaehltUm` gemerkt); `countRunNow()` ist inzwischen
  ungenutzt -- ihr Javadoc und aeltere Kommentare ("zaehlt schon bei End journey") sind veraltet.
- **Ausstieg** (`beginAbort`/`handleAbort`): X ueber offenem Stuetzpunkt -> rotes `end_journey` -> Yes (Regel 2b).
  Draussen = Teilnahmefenster/Burg/Weltkarte/Insel UND kein `end_journey` sichtbar. `abort_stuck`-Cooldown 30 s.

Freund-Flow (`Teammate.FRIEND`, Namen per Komma, mehrere Freunde der Reihe nach):
- Vor "Participate": `vorDemTeilnehmen` -> erst Chat (`FreundesChat`) nach Einladung pruefen, dann zufaellige Pause
  8-40 s (zwei Bots laden sich sonst gleichzeitig ein). `friendwait` (nur genau 1 Freund): nie selbst einladen,
  `einladungAbwarten` schaut alle 45 s in den Chat, nimmt mit `invite_accept` an (max 30 min).
- Freundesliste (`handleFriendList`): `friend_<name>` + `online_dot` -> `friend_invite` -> `invite_send` ->
  erst bei `invited_haken` in der Zeile das rote X. Ende der Liste per Bildvergleich (`listeZuEnde`, max 40 Scrolls).
  Warten auf Zusage 90-180 s zufaellig, dazwischen alle 20-40 s Chat auf gekreuzte Einladung. Freund weg -> Zufallsspieler.
- Folge-Reise im Abschlussfenster (`folgeReise`): nur wenn `invite_partner`, `start_journey`, `invite_accept` alle
  vorhanden; Pause 2-20 s, `invite_partner` -> `invite_sent` -> `start_journey`. 3 min keine Zusage -> `burgJetzt`
  (`jetztInDieBurg()`), danach mit Fremden.

## 5. Kathedrale (`CathedralMode`)

Ablauf: Burg -> Map -> Weltkarte (`dc_node` ueberall suchen, max 12 Wischer) -> `dc_participate` (Schluessel lesen,
Bezahlkasse) -> "Gather troops" (`dc_previous_squad`, `dc_confirm` / Rueckfall `confirm`, `dc_attack`) -> Etagenkarte ->
Wegpunkt -> Truppen (`dc_deploy_best*`) -> `dc_quick_attack`/`quick_attack` (Rueckfall `dc_to_battle`) -> Ergebnis
`home_button`/`dc_battle_back` -> Segen (`dc_blessing`: immer **mittlere Karte** 0,50/0,44 -> `dc_blessing_ok`) -> ...
Waechter (`dc_guardian_lair`) -> `dc_next_floor` -> nach 3 Etagen (`MAX_FLOORS`) gruener `dc_end_journey` ->
`end_journey_yes` -> `dc_journey_done` (freie Ecke `dc_done_empty`/0,9|0,9 antippen) -> `dc_home_done`.
Allein, kein Ready, kein Leiter.

Regelkette `step()`: Event-Gang (falls `eventUnterwegs/eventLaeuft` -> `hammerEventSchritt`/`titanEventSchritt`) ->
leeres Ladebild (`bildLeer`, Streuung < 6) still warten -> 1 Update/AFK -> 0 anderes Geraet -> 1a Belohnung
(seltene Notfaelle nur jedes 5. Bild, `SELTEN_TAKT`, sofort wenn nichts erkannt) -> 1a1/1a2 Kisten-Gang
(`Truhenoeffner`) -> 1c im Kampf zuerst Home -> 1b Verlies-Teilnahme im Weg -> 2 Abschlussfenster -> 3 Warnungen "Yes" ->
3b naechste Etage laedt -> 4 `dc_waypoint_done` -> 4b "Ally in Reflection" (`dc_join_squad`: immer aufnehmen) ->
5 Segen -> 6 Entscheidungsfenster (`handleChoice`/`fensterKette`) -> 7 Kampfergebnis -> 7b Einheitenkarte offen ->
8 Truppenbildschirm (`handleSquad`, `tryRevive`) -> 9 Gather troops -> 10 naechste Etage / Schluss nach Etage 3 ->
10b Reise fertig -> 11 Etagenkarte (`handleFloorMap`/`etagenkarte`) -> 12 Teilnahme -> 13 Weltkarte -> 13b Werbe-X ->
14 Burg -> 14b Karte im Weg -> 15 nichts erkannt.

- **Routen** (`enum Route`): `TURBO` (c, Standard: kuerzester Weg, Blindklicks auf gemerkte Knopfstellen
  `turboStelle`, kurze Blind-Wartezeit `turboMinMs` 35 ms, nur Bosse+Waechter) und `FULL` (b: alle offenen Wegpunkte
  abraeumen, dann Waechter). Variante a entfernt.
- **Etagenkarte**: Etage vom Sockel ablesen (`dc_stage_1..3`, `readStage`) -- zuverlaessiger als Mitzaehlen.
  Waechter: `dc_guardian_lair` (Rueckfall `dc_guardian`, Schrift `dc_guardian_text` Schwelle 0,60), **auch gesperrt
  zaehlt er als Ziel**, einmal gut gesehen (>=0,70) wird er je Etage gemerkt (Schriftzug "You have got a blessing" wandert
  0,7-1,4 s darueber). Entscheidungen erst nach **zwei Blicken** (`zweiterBlickMs` 600, `entscheidungReif`), Ausnahme:
  genau ein offener Stuetzpunkt. Bosse = nur `dc_minion` (`BATTLE_WAYPOINTS`); Kampf-Wegpunkte = minion/skirmish/arena
  (Umweg bis 70 px lohnt, um Kampf zu meiden). Turbo: erst alle Bosse und Fragezeichen (`dc_question`), Waechter
  zuletzt; Waechter wartet max 120 s (`WAECHTER_MAX_WARTE_MS`). Gerade besuchte (6 s) nicht aus der Ziel-Liste filtern.
- **Etagennetz**: `beobachten()` je Bild (Sockel + Punktlinien), `planen(reif)` -> `Plan(Art SCHRITT|WAECHTER|WARTEN)`;
  kleinste Stuetzpunktmenge, die alle Fragezeichen aufdeckt, alle Bosse schlaegt und den Waechter erreicht. Linien aus
  Punktvorlagen (Abstand 17 px), aus dem Spiel bewiesen (Nachbar wird frei = verbunden), "denkbar" (<215 px, max 3/Knoten),
  5 fest eingetragene Karten (`festeKarte`), unbekannte gelernt in `etagen-netze.txt`. Gibt das Netz nichts her ->
  alte Wegsuche (`chooseTowards`, `wegenetz`/`schritteBisZiel` ueber `verbunden`). WARTEN max 2 s, dann alte Suche.
  `gespielt(target)` in `goTo`, `letzterAbgeschlossen()` nach Sieg/Wahl.
- **Entscheidungsfenster** (Altar of Blood, Dangerous Game, Camp): immer sicher -- "Move on" bzw. "Claim reward" ->
  `dc_finish_selection` -> Warnung `dc_finish_yes`/`dc_finish_confirm`. Hat "Finish" nicht gegriffen
  (`abschlussPruefen`): nochmal, dann Wahl neu (andere zuerst, Schwelle 0,70), dann X (ab 6 Fehlschlaegen).
  `spielTraegheitMs` (gemessen an Quick-Attack-Verzoegerung) verlaengert Wartezeiten fuer langsame PCs (Karla).
  **Keine Blindklick-Stelle fuer Finish im Warnfenster** (traf im Altar eine Einheit -> Karte ueber allem).
- **Wiederbeleben** (`tryRevive`): `REVIVE_ORDER` = unit_1..unit_4, Opfer-Einheit nie, `dc_health_zero` bestaetigt tot,
  max 8 erfolglose Versuche je Run (`MAX_REVIVE_VERSUCHE`), Karte schliessen per Klick auf 0,83|0,50.
- **Schluessel** (`turboSchluesselLesen`, `dc_key_icon` + `key_0..9`, im selben Bild wie Participate): 0 erst glauben,
  wenn zweimal gesehen (140 wurde mal als 10 gelesen). `KEY_FLOOR` = 0. Bei 0 -> `schluesselAlle()`: je nach Bezahlart mit
  Nahrung weiter, sonst Kisten-Gang (`Truhenoeffner`, max 50 Gaenge, Ende wenn Tasche leer) -> im Kreislauf ins Event
  -> sonst Ende mit `endeHinweis` fuer Discord. `teilnahmeLage()` (Bugjagd 29.10., 8562 Participate-Klicks): nach 8
  Anlaeufen ohne Reise mit Schluesseln>0: 2x erholen (X), dann Auftrag beenden.
- **Event-Kreislauf** (`core/Eventautomatik`): Kathedrale bis Schluessel alle -> Kisten -> Hammer-Event
  (`EventMode`, 25 Nahrungskisten je Gang) bzw. seit 10.10. Titan-Grabung (`TitanEventMode`) -> zurueck. Event-Stages
  sind `sicherePunkte()`; `wechselJetzt()` erlaubt Einschub nach fertiger Stage; `folgeAuftrag()` haengt Verlies-Runs an.
  `kathedrale-seit-event.txt` merkt Runs seit letztem leeren Event.
- **Zeitmessung**: `Zeitbericht` je Run -> `Kathedralzeiten.merken()`; `Lernwerte` (`lernwerte.txt`, je Run neu gelesen:
  nachKampfMs, turboMinMs, segenWahlMs, segenTaktMs, waechter_blick_ms, Schalter fotozyklus*/siegAntippen).
- !stats-Extras (`extraStats`): Etagen gesamt, Bosse gesehen/besiegt, Waechter gesehen/besiegt, Route.

## 6. Leuchtturm / Kolosseum (`LighthouseMode`)

Ein Besuch = bis 5 Kaempfe. Burg -> Map -> wischen (Leuchtturm erst nach links, Kolosseum nach rechts; gemerkte
Zugzahl je Ort) -> `lh_node` bzw. `ko_tower` -> (Kolosseum: "Select an activity" -> `ko_auswahl`, Energie darunter
lesbar) -> Titel `lh_titel`/`ko_titel` -> Energie lesen (`lh_max`/`lh_energie_N`; "5/5" frueh nach 2 Lesungen bei
gleicher Helligkeit, `ENERGIE_FRUEH_*`) -> oberster Gegner mit `*_to_battle` -> `*_quick_attack` -> `ar_victory`/Home.
2 Niederlagen -> kostenloser `*_refresh` (max alle 15 min). Hinaus: Leuchtturm per `close_x`, Kolosseum per Home.
Kolosseum: jeden offenen Gegner ansehen (`spaehen`: Reiter `ko_tab_ich`/`ko_tab_gegner`, `ko_power` + OCR), schwaechere
zuerst; alle > eigene Power + Grenze (`kolosseum-grenze.txt`, Std. 500 000) -> Refresh, sonst schwaechster.
**Nie Diamanten**: nie mehr "To battle" als Energie; wird die Liste dunkler (`DUNKEL_GRENZE` 0,8 = Fenster darueber),
nichts klicken, Besuch beenden. Wettbewerb vorbei: `lh_claim_reward`/`lh_contest_close`. 10 verschluckte Quick Attacks
-> Abbruch (ab 3 Pruefung leere Truppe). Plan: `Energieplan.LEUCHTTURM/KOLOSSEUM` (an/aus `leuchtturm.txt`/
`kolosseum.txt`, faellig `orte-faellig.txt`, Zaehler `orte-zaehler.txt`); Kolosseum kommt immer mit dem Leuchtturm.

## 7. Titanen-Turm (`TitanTurmMode`)

Burg -> Weltkarte `ko_tower` -> `tt_aktivitaet` (Balken "90/90" lesen -> `vollGesehen`, Turm-Tag ab 02:00) ->
`tt_titel` -> `tt_easy`, dann `tt_hard`, `tt_veryhard` (fertig bei `tt_voll` "30/30" oder Maximum erreicht) ->
`tt_auto` -> `tt_auto_titel`: mit `tt_minus` "Reach the level" auf Maximum -> `tt_zur_truppe` -> `tt_deploy_best` ->
`tt_start` -> nur warten (`tt_laeuft`/`tt_naechster`, max 30 min) -> `tt_fertig` -> `tt_home`. Verloren
(`tt_verloren`): 4 Versuche je Level, dann ganzen Turm aufgeben. **"Finish auto battle" nie druecken.** Kein
Haenger-Bericht (`haengerBerichtNachS` = MAX). `Turmplan`: Fenster 02:00/12:00 (55 min), `titanturm.txt` an/aus,
`titanturm-erledigt.txt`, `titanturm-max.txt` (Maxima easy/hard/veryhard, Std. 30). `!tower` startet von Hand.

## 8. Portal (`PortalMode`)

Burg -> Karte (ein Zug links, max 6) -> `pt_karte`/`pt_karte_schild` -> `pt_titel` -> Zeile "Level 80" (OCR prueft die
Zahl! Vorlage passte auf "Level 109"; `stufenSichtbar` liest Titelspalte in Streifen) -> rechts neben Belohnungen tippen
(dx 285, dy 73) -> `pt_auto_battle` -> Haken "Use portal spells", "Continue if lost", "Open food chests" an,
**"Buy extra food for diamonds" aus** -> `pt_start` -> 30 Kaempfe (`pt_kampf_laeuft`/`pt_kampf_pause` NIE tippen) ->
`pt_fertig` -> `pt_home`. `pt_kauf_frage` -> `pt_kauf_nein`. Plan aus Startbefehl (`planAusText`):
`!start portal bis 135 farm 80:2mio,135:2mio,130:1,5mio` (gilt 2 min nach Setzen; Fortsetzung nach Clan-Krieg-Einschub
bis 3 h). Dateien: `portal-nahrung.txt` (Budget, Std. 40 Mio), `portal-bis.txt` (Uhrzeit ISO, keine neuen Durchgaenge
danach), `portal-stand.txt` (letzter Sieg der Woche, Reset Freitag). Nahrung fehlt -> eingebetteter `NahrungskistenMode`;
keine Clan-Laden/Videos.

## 9. Gemeinsame Regeln / Gotchas (nicht aendern!)

- **Schalter statt Uhren**: "schon aufgestellt", "Effekt gewaehlt", "geopfert", "Wahl getroffen", "Zweikampf bestaetigt"
  fallen erst, wenn der Bildschirm verschwindet. Zeitsperren liefen ab -> Doppelklicks, Ready ohne Truppen (jeder 2.
  Stuetzpunkt ohne Aufstellung).
- **Fortschritt (`runs.progress`) nur bei echtem Weiterkommen** -- sonst setzt ein Endlos-Klick die Haenger-Uhr zurueck
  (223 gleiche Effekt-Durchgaenge, 938x Finish selection, 8562x Participate).
- Stillstandsuhr laeuft nicht waehrend Suche/Teilnahmefenster/Warten auf Partner (`wartetNurAufAndere`,
  `sucheGesehenUm`); als Mitspieler gilt nur die Leiter-Frist.
- Werbe-X (`ad_close`) nur bei sichtbarer Burg (Map-Knopf) -- sonst schliesst der Bot sein eigenes Teilnahmefenster.
- Nicht wischen, wenn Map-Knopf noch da (= Burg; Wischen verschiebt Bewohner).
- `du_for_food` == `dc_for_food` (Guete 1,00): Kathedrale erkennt man an `dc_for_keys_off`. Verlies-"Participate" mit
  Apfel aehnelt `dc_participate` (0,95) -> eigene Merkmale pruefen (`onCathedralScreen`, `onDungeonScreen`).
- Quick Attack ist erst grau (Sync) -- grauen nie klicken. Knopf erst klicken, wenn er stillsteht.
- End journey: rot = laeuft, gruen = fertig; gruen muss besser passen als rot (Kathedrale `END_JOURNEY_MIN_SCORE` 0,88).
- Nie Diamanten (Bezahlkasse, Leuchtturm-Energie, Portal-Haken, Refresh mit Diamant nie).
- Ein stehender Bot ist das Schlimmste: lieber warten+weiterprobieren+Alarm (`Log.alarm`) als stillschweigend aufhoeren.
- Tempo-Lernen (`core/Tempolerner`, Autor 24.10.): pro fehlerfreier Runde Pausen x 0,999, bei Fehler eine Stufe
  zurueck und fest. Nur feste Pausen, nie `sleepUntilChanged`/Kampf/Partnerwarten. Fehler = Warnung zu eigenen Klicks
  (`Tempolerner.zaehltAlsFehler`). Dateien: `verlies-tempo.txt`, `kathedrale-tempo.txt`, `leuchtturm-tempo.txt`,
  `kolosseum-tempo.txt`, `turm-tempo.txt`, `portal-tempo.txt`. Immer ueber `warnung()` des Modus loggen.
- Koordinaten-Konstanten sind an 1159x770 (Portal 1158x770) gemessen und als Anteile oder mit `ctx.height()/770.0`
  skaliert.
- Belege/Fotos bei Problemen: `logs/verlies-kathedrale-N.png`, `logs/ko-energie-ausschnitt.png`, `logs/etagen/...`
  (Etagenfotos Anfang/Ende neuer Karten).

## 10. Laufzeitdateien (Bot-Ordner)

`verlies-tempo.txt`, `opfer-namen.txt` (OCR-Namen der Opfer, Komma/Zeile), `einheiten-auto.txt` (Team-Scan "N | Name"),
`kathedrale-zeiten.txt`, `kathedrale-tempo.txt`, `kathedrale-seit-event.txt`, `lernwerte.txt`, `etagen-netze.txt`,
`leuchtturm.txt`, `kolosseum.txt`, `orte-faellig.txt`, `orte-zaehler.txt`, `kolosseum-grenze.txt`,
`leuchtturm-tempo.txt`, `kolosseum-tempo.txt`, `titanturm.txt`, `titanturm-erledigt.txt`, `titanturm-max.txt`,
`turm-tempo.txt`, `portal-nahrung.txt`, `portal-bis.txt`, `portal-stand.txt`, `portal-tempo.txt`, `event-kisten.txt`.
Config: `bot.leaderPickTimeoutMinutes` (config.properties). Bezahlart: `core/Bezahlung` (`bezahlung.txt`).

Discord (DiscordBridge): `!start dungeon N [random|<freund>[,<freund2>]]` (friendwait-Variante),
`!start cathedral N [b|c] [noevent]`, `!times`/`!zeiten` (Kathedralzeiten), `!tower`/`!turm`, `!portal`,
`!settings leuchtturm|kolosseum an|aus`, `!settings kolosseum grenze N`, `!settings titanturm an|aus` (+ Maxima),
`!settings kiste <art> an|aus` (Eventkisten), `!settings verlies previous|best` (Aufstellung).

## 11. Tests (`src/test/java/de/hustlebot/bot/`)

- `DungeonAltarTest`: `istOpferBild` (Scan-Bilder zaehlen), `schriftBloecke` auf synthetischem 1159x770-Bild,
  `letzteLebende`. Kein OpenCV-Bild noetig.
- `TeilnahmeStauTest`: `CathedralMode.teilnahmeLage(versuche, schluessel, erholungen)`.
- `BezahlkasseNahrungKistenTest`: Startverhalten der Kasse je Bezahlart (Verlies/Kathedrale).
- `LighthouseFruehTest`: Helligkeits-Ruhepruefung fuer fruehes "5/5".
- `PortalPlanTest`: `PortalMode.planAusText` (Ziel, Farm-Liste, Mengen "2mio"/"500k", `;`).
- `VasensucheVergleichTest`: braucht echte Fotos `C:\HustleBot\logs\event-*.png` + Templates -> unter Linux uebersprungen.
- `SpeicherSchritte`/`SpeicherTest`, `steuer/SteuerBefehleTest`: Einstellungen/Befehle inkl. leuchtturm, dungeon.
- `Titan*Test` (EinWeg, Form, Plan, Rauswurf, Tiefe, Weg, Zugang) betreffen **TitanEventMode/TitanRauswurf**
  (Grabung), NICHT den Titanen-Turm. Fuer TitanTurmMode, CathedralMode-Etagenkarte und Etagennetz gibt es keine Tests.

## 12. Rezepte

- Neuer Wegpunkt-Typ / neues Knopf-Aussehen: nur Vorlage `<grundname>_neu.png` aufnehmen (Varianten-Regel).
- Verlies-Wegpunktwahl als Leiter: `DungeonMode.pickWaypoint` + `WAYPOINTS`; Folgen: `followTeammate`/`waypointBelow`.
- Effekt-Prioritaet: `DungeonMode.pickEffectSlot` + `EFFECT_CLASSES`.
- Opfer/Wiederbeleben Verlies: `handleSacrifice`/`SACRIFICE_ORDER`/`ALTAR_KEEPERS`; `handleResurrection` +
  `resurrectionActive` (beide synchron halten!); Kathedrale: `tryRevive`/`REVIVE_ORDER`.
- Wann ein Verlies-Run zaehlt: `endRun`, Regel 2 und 16 in `step()` (nicht `countRunNow`, ungenutzt).
- Fristen Verlies: `IDLE_ABORT_SECONDS`, `leaderPickTimeoutMillis`, `partnerReadyTimeoutMillis`, `FOLGEREISE_WARTEN_MS`,
  `FREUND_WARTEN_*`, `VOR_EINLADEN_*`.
- Kathedralen-Weg: `etagenkarte()` (Variante b vor Waechter, dann Netz-Plan, dann Bosse zuerst, dann Waechter);
  Netz/Lernen: `Etagennetz.planen`, `lernen`, `festeKarte`, Datei `etagen-netze.txt`.
- Kathedrale Schluessel/Kisten/Event: `handleParticipation`, `turboSchluesselLesen`, `schluesselAlle`,
  `hammerEventSchritt`, `core/Eventautomatik`.
- Segen/Wahl: `handleBlessing` (BLESSING_MIDDLE_*), `handleChoice`, `fensterKette`, `abschlussPruefen`.
- Leuchtturm-Takt aendern: `core/Energieplan` (`NACH_KAEMPFEN_MIN` 105, `NICHT_VOLL_MIN` 21); Turmzeiten:
  `core/Turmplan.ZEITEN`; Einschub-Logik: `DiscordBridge.nebenbeiPruefen`.
- Kolosseum-Gegnerwahl: `LighthouseMode.gegnerliste`/`spaehen`/`kolosseumGrenze`.
- Portal-Klickstellen: Konstanten oben in `PortalMode` (HAKEN_*, ZEILE_DX/DY, X_*), Plan: `planAusText`/`planSetzen`.
