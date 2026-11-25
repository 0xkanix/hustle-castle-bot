# Event- & Kriegs-Modi (Hammer-Event, Titan-Grabung, Clan-Krieg, Stigmas, Gem Bay)

Teil der Doku zu `bot/` (Index: `bot/CLAUDE.md`). Alles hier ist Bildschirm-Automatik nach dem `GameMode`-Muster
(`step(screen, ctx, runs)` = genau EINE Aktion je Aufruf, Rueckgabe `ACTED/WAITING/FINISHED/FATAL/PAUSE`).
Feste Pixelstellen sind auf ein Spielfenster **1159 x 770** (ohne Titelleiste) gemessen und werden per `px()/py()`
bzw. `ctx.width()/1159.0` skaliert. Spiel muss auf **Englisch** stehen (Texte werden per Windows-OCR gelesen).

## 1. Ueberblick & Einstiege

| Modus-ID | Klasse | Start | Wer ruft |
|---|---|---|---|
| `event` | `EventMode` (Hammer-Event, ~5k Z.) | `!start event [N]`, `!event ...` | `BotEngine.register`, **und** als Unter-Modus von `CathedralMode` (Feld `hammerEvent`) |
| `grabung` | `TitanEventMode` (Titan-Grabung "Titanic Excavation", ~2,7k Z.) | `!titan ...`/`!grabung` | `BotEngine`, Unter-Modus von `CathedralMode` (`titanEvent`) |
| `clanwar` | `ClanWarMode` (~4k Z.) + `TitanRauswurf` | `!clanwar`, automatisch ueber `Kriegsplan` | `BotEngine`; Einschub durch `DiscordBridge.nebenbeiPruefen()` |
| `stigmas` | `StigmaMode` (~3,3k Z.) | `!stigmas loeschen|leveln|hundert|einstellen [probe]` | `DiscordBridge.commandStigmas` -> `StigmaMode.naechsterStart(...)` -> Warteschlange |
| `gembay` | `GemBayMode` (~2k Z.) + `GemBayGrenzen` | `!gembay` | `BotEngine`; `GemBayplan.faellig()` ist **nirgends verdrahtet** (nur Vermerke/Reset/Watch werden genutzt) |

Core-Planer (nur Zeit-/Einstellungslogik, ohne Bild): `core/Eventautomatik`, `core/Eventkisten`, `core/Zauberstabziel`,
`core/Titanende`, `core/Kriegsplan`, `core/Stigmaplan`, `core/GemBayplan`.

### Der Event-Kreislauf ("!start eventloop c all")
`DiscordBridge` macht daraus `cathedral c eventloop` -> `Eventautomatik.fuerAuftrag(true)`. Der Kreislauf lebt in
**`CathedralMode`** (nicht in EventMode!): Kathedrale mit Schluesseln -> Nahrung (Limit `!settings foodlimit cathedral`,
`Bezahlung.rundeNeu("cathedral")` setzt es nach jedem Event zurueck) -> Nahrungskisten -> Kathedralen-Kisten oeffnen
(bringen Schluessel) -> wenn alles leer **oder** alle N Kathedralen-Runs -> Hammer- bzw. Titan-Event -> von vorn.
- `CathedralMode.hammerEventSchritt()`: Weg zur Burg (`eventUnterwegs`, max 80 Bilder sonst "Event ausgelassen"),
  dann `hammerEvent.reset()` + `nahrungsGrenze(Eventautomatik.nahrungskistenJeEvent())`, eigener `RunTracker`
  `eventZaehler` (Grenze = `stagesJeGang()`, 0 -> 9999). Fortschritt wird nur weitergereicht, wenn
  `eventZaehler.idleSeconds() < 30` (sonst greift der Haenger-Alarm nie).
- Event FINISHED mit 0 Faessern und nicht `haemmerAlle()` -> bis 2x neu hinein (`eventAbbrueche`); 0 Faesser +
  Haemmer alle -> `eventLeer`, zurueck in die Kathedrale mit neuem Nahrungsbudget, **keine** Verlies-Runs.
- `Zauberstabziel.erreicht()` (nur Hammer) -> Event wird ausgelassen.
- Welches Event: `Eventautomatik.titan()` (Datei `event-art.txt` titan|hammer; ohne Datei: Titan bis 19.10.2026,
  am 19.10. nur bis `Titanende.vorbei()`).
- Abstand: `hammerAlleRuns()` (`event-hammer-alle.txt`, Std 100, 0 = nur wenn leer), `titanAlleRuns()`
  (`event-titan-alle.txt`, sonst `alle=` aus `event-plan.txt`, sonst 100). Titan-Endspurt in
  `CathedralMode.titanAlleRuns()`: Restzeit <= 12 h -> min(alle,20); <= 45 min -> nach jedem Run. Zaehler
  persistent in `kathedrale-seit-event.txt`. Weg nicht gefunden/abgebrochen -> naechster Versuch nach 5 Runs.
- `Eventautomatik.eventZuerstMerken()/eventFertig()`: Merker, damit nach einem Einschub (Leuchtturm, Kolosseum,
  Clan-Krieg) der Kreislauf **zurueck ins Event** geht statt in die Kathedrale.
- Ende des Kreislaufs: `leereRunde()` -- keine einzige Runde seit dem letzten leeren Event; dann
  `verliesRuns()` (Std 0) Verlies-Runs und Ende.

## 2. Datei-Karte

**Hammer-Event**
- `EventMode.java` -- der ganze Ablauf. Wichtige Methoden: `step` (Prioritaetenkette, s. u.), `zumEventGehen` +
  `ueberDenKalender` (Weg), `kisteMitnehmen`/`belohnungenImFeld`/`abgeschaltetGewinnt` (Belohnungen),
  `vaseZerschlagen` (Hammer), `formLegen`/`bestaetigen`/`hakenKlicken`/`lupeAbbrechen` (Lupe),
  `superhammerAnstossen`/`superhammerLegen`/`superStattNormal`/`gutesPaar`/`superStelle` (blauer Hammer),
  `rasterHolen`/`rasterMessen`/`geschlossenesRaster*` (5x4-Feld), `platzDanach`/`unbrauchbareReste`/`ALLE_FORMEN`
  (Lupen-Platzierung), `schlussphaseJetzt`/`schlussphaseGilt`/`schlussphaseFertig`, `hammerAblesen`,
  `lupenZahlBild`/`zahlGleich`, `zauberstaebeAblesen`, `ranglisteZeigen`, `aufraeumen`, `stoerfensterPruefen`.
- `EventVorlagenSchwellen.java` -- eigene milde Schwellen je Belohnungs-Vorlage + Kraken-Sperre (`krakenAnStelle`).
- `core/Eventautomatik.java` -- Kreislauf-Schalter und alle Event-Einstellungen (Pass, Lupen, Super-Modus, Art, Abstaende).
- `core/Eventkisten.java` -- welche Kistenarten mitgenommen werden (`event-kisten.txt`).
- `core/Zauberstabziel.java` -- Zauberstab-Grenze (`zauberstaebe.txt`), Stand liest EventMode im Reiter "Rankings".

**Titan-Grabung**
- `TitanEventMode.java` -- Phasen `BOSS -> GRABEN -> WERKZEUG -> ENDE`; feste Stellen als `int[]`-Konstanten;
  `bossSeite`/`angreifen`, `graben` (Dauerklick + Spitzhacken-Zahl), `mineLesen` (6x5 Feld, offen/HC),
  `grabPlan`/`naechstesFeld`/`zugang`/`randFeld` (HC-Wege), `werkzeugSchritt` + `besteFormMitte` (Dynamit 3x3,
  Super-Spitzhacke Plus, Drill), `zahlLesen`/`plausibel`, `restLesen` -> `Titanende.setzen`, `ranglisteZeigen`.
- `core/Titanende.java` -- abgelesenes Event-Ende (`titan-ende.txt`), fuer Endspurt der Kathedrale.

**Clan-Krieg**
- `ClanWarMode.java` -- `step` (Helden-Set vorher/nachher, Rauswurf), `schrittKrieg` -> `schrittNachBedenkzeit`
  (nummerierte Schritte 0b..10), `kartenschritt`, `tuermeFinden` (Schraegstrich + Ziffern), `zielNachRegeln`,
  `turmLesen` (OCR Titel/Namen/Ruhm), `truppeStellen`, innere Klasse `TruppRunden`, Record `Turm`, `TurmInfo`.
- `TitanRauswurf.java` -- alle Titanen-Tuerme voll -> schwaechstes fremdes Team per "Editing mode" herausnehmen.
- Helfer (eigene Doku-Sektion, hier nur genutzt): `Nahrungskisten` (280k Nahrung vor dem Krieg aus Tasche/Clan-Laden),
  `Nahrungsvideo` (+90k per Werbung, max 2/Tag), `TruppAuffuellen` (unvollstaendigen Trupp fuellen),
  `HeldenSet`/`HeldenSetWechsel` (Set "War" vorher, "Normal" danach), `Aufstellung` (Deploy best vs. Previous je Stelle).
- `core/Kriegsplan.java` -- Kriegszeiten, Fenster, Vermerke, Kontroll-Lauf.

**Stigmas** -- `StigmaMode.java` (Weg ueber Turm -> Titan Squad -> Inventar -> Zerlegen; Leveln-Pfad `l*`-Methoden:
`lStart`, `lName`, `lKachel`, `kandidat`, `einsetzen`, `werteLesen`), `core/Stigmaplan.java` (Sorten, Werte,
Titanen-Regeln, Rangliste, Behalten-Sets).

**Gem Bay** -- `GemBayMode.java` (Ablauf + Fotozyklus), `GemBayGrenzen.java` (alle Zeitgrenzen/Text-Muster, testbar
ohne Bild), `core/GemBayplan.java` (Vermerke, Reset-Zeit, Fehlversuche, Watch-Video 1x).

**Vorlagen**: `templates/event/` (ev_*), `templates/event-belohnungen/` (ev_kiste_*, ev_gems, ev_stab*),
`templates/clanwar/` (cw_*), `templates/gelernt/{event,event-belohnungen,titan-event,clanwar,stigma,titanen}/`,
Nachschub-Kopien in `src/main/resources/nachschub/{event,event-belohnungen,clanwar,gelernt/...}`.
**gb_*-Vorlagen liegen nicht im Repo** (muessen aufgenommen werden). Ordner egal, nur Dateiname zaehlt.

## 3. Hammer-Event (`EventMode`) im Detail

Spiel: 5x4 = 20 Faesser/Vasen, genau 1 Zauberstab, 3 besondere + 16 gewoehnliche Inhalte. Hammer zerschlaegt eins
(1 Hammer), Lupe ("See through") deckt eine Dreier-Form auf (Tetris-artig, wird gezogen + gruener Haken),
blauer Super-Hammer zerschlaegt eine Dreier-Form (gelber Umriss). Stab geholt -> "Refresh the field" gelb ->
Nachfrage "Yes" -> neues Feld = 1 Stage.

**Weg hinein** (seit 28.09. NUR ueber Kalender, nicht ueber Flaschengeist/ev_genie): Burg (`map_button[_alt]`) ->
`ev_kalender` -> `ev_kalender_eintrag` -> `ev_view` (innerhalb 15 s). Weltkarte -> `Weltkarte.heim`.
> 10 Eintritte ohne Arbeit oder > 6 Kalender-Oeffnungen -> FINISHED ("ausgelassen"). Nie neben "Map" klicken
(das ist das Clan-Territorium).

**Prioritaetenkette in `step()`** (Reihenfolge ist Absicht):
0. `schlussStopp` -> nur noch `schlussphaseFertig` (PAUSE). AFK-"Update" max. alle 3 s. `amAufraeumen` -> FINISHED.
1. Kauf-Angebot (`ev_angebot_titel`/`ev_angebot_x`) **in jedem Bild** wegdruecken -- nie drosseln.
1b. "Not enough hammers?" (`ev_zu_wenig_haemmer`, alle 0,5 s): normal -> Haemmer alle, Fenster zu, Event verlassen;
    Schlussphase/`alleLupen` + Gold-Pass -> weiter nur mit blauen Haemmern (`nurSuperHaemmer`).
2. Stoerfenster alle 2,5 s; Belohnungstext nach Neumischen in den ersten 6 s jedes Bild. Nach dem "Ja":
   **nichts aufs Feld bis Text weg oder `FRISCH_WARTEN_MS`=2,5 s** (erste Lupe ging sonst ins Leere).
   Zauberstaebe ablesen (alle 10 Stages, wenn Grenze gesetzt), Rangliste-Foto, `zielStages` -> `aufraeumen`.
2b. `ev_refresh_yes` nur wenn selbst gefragt (`neuMischenGefragt`) oder sichtbar im Event -- **alle gruenen Yes sehen
    gleich aus** (cw_yes, dc_yes passen 0,87-0,89). Erst HIER wird `felderGeschafft++`, `runs.completeRun()`,
    `Laufzaehler.eventStage()`, Tempo-Lernschritt. Alles Feld-Lokale wird zurueckgesetzt.
3. `ev_claim`. 4. Kistenfenster `ev_open` -- **nur linke 75 % des Bildes** (rechts unten Event-Truhe 30/30: nie);
   bis 4 s nach Kisten-Klick milde Schwelle `OPEN_MILD`=0,68. `ev_belohnung_erhalten` ("Reward claimed!") -> dessen
   eigenes X `ev_inhalt_x` nur im Bereich relativ zum Text (das gleiche X oben rechts schliesst das Event!).
5. Nicht im Event (und keine Form offen -- Form dunkelt Feld ab) -> Stoerfenster, `zumEventGehen`.
   Schlussphase-Messung (alle 30 s, rote Pixel oben links >= 300; nicht messen, wenn Form offen; 2 Nicht-Rot in Folge
   noetig zum Zuruecksetzen). Raster vermessen, solange noch alle Vasen zu sind. Hammerstand: **zwei Nullen
   hintereinander** noetig, eine verlesene Null zaehlt nicht.
6. Form offen (`formOffen` oder Haken/rotes X sichtbar) -> `formLegen` (gelb/`zielPaar` -> Super-Hammer).
6b. `superhammerAnstossen`: zwei gute Items (oder Stab + Item) nebeneinander offen -> ein blauer Hammer.
7. Belohnungen nehmen (`kisteMitnehmen`) -- aber **erst alle Lupen, dann Kisten, zuletzt der Stab** (Autor 24.10.).
8. Stab (`stabImFeld`, `ev_stab_kern` bevorzugt) klicken, `stabGeholt`. 9. Stab fertig (`ev_wand_fertig`) **und
   `ev_refresh_grau` NICHT sichtbar** -> `ev_refresh_gelb` -> Nachfrage. Nach Stab nie Lupe/Hammer auf Rest;
   Stab nach 3 s noch da -> erneut; nach 20 s ohne Gelb weiter.
10. Lupe, wenn erlaubt (Pass != KEIN), `lupenBenutzt < lupenGrenze()`, genug zu (`lupeAbZu`), erste Lupe erst bei
    >= 18 sichtbaren Vasen bzw. 3 s nach Neumischen. Lupenknopf fehlt -> einmal je Feld 2,5 s warten (Angebot
    kann davor liegen). Zahlenfeld ueber "See through" wird als **Bild** verglichen (OCR liest Einzelziffer nicht):
    sieht aus wie "keine Lupen" -> `lupenGesperrt`.
11. `vaseZerschlagen`: Raster-Farbe statt Vorlage (ev_vase_zu passt auch auf offene Vasen). Wirkungs-Kontrolle:
    Vasenzahl muss sinken; sonst erst freie Flaeche antippen (3x), `MAX_WIRKUNGSLOS`=8 -> Event neu betreten
    (max 2x je Feld) bzw. aufhoeren. Super-Hammer statt normal nur, wenn gemerkte Form **alle 3 Felder auf
    geschlossene** trifft (Ausnahme `nurSuperHaemmer`: 1 reicht).

**Regeln des Autors (nicht aendern ohne Rueckfrage)**
- Es geht IMMER um den Stab; fuer Kisten wird nie extra eine Vase zerschlagen.
- Lupen je Feld: `Eventautomatik.maxLupen()` (Gold 6, sonst 5; `event-lupen.txt` sticht, 0..10). Harte Sperre im Code.
- Nie die 3 Kisten mit Uhren unten, nie Angebote mit Preis, nie Event-Truhe 30/30.
- Kraken-Kisten nie gezielt (Kraken-Vorlage mit >= 0,64 und < 0,10 schlechter als Treffer -> liegen lassen).
- Form-Platzierung: alle Verschiebungen durchprobieren, bewertet nach `unbrauchbareReste` (geschlossene Vasen in
  Stuecken < 3, fuer jede kuenftige Form verloren) und `platzDanach`; Rand zuerst. Max 2 Zuege, dann bestaetigen,
  wo sie liegt. Blind-Haken max ~6 s (`blindHaengtFest`) -- Bericht #9P2L: 10 873 Haken-Klicks.
- Super-Hammer: nur Gold-Pass; "Use!" 2x ohne Form -> `superLeer` **nur bis zum naechsten Feld** (fuellen sich nach,
  gruener Balken x/20). >= 2 Abbrueche je Feld -> nur normale Haemmer. `ev_schloss_superhammer` -> kein Pass.
  `superhammer-modus.txt` "viel" -> schon ab 2 geschlossenen Faessern.
- Ausnahme "alle Lupen" (`event-lupen-alle.txt` = an, nur beim Start gelesen): alle Lupen je Stage auch bei offenem
  Stab, spielen bis Haemmer + Super-Haemmer leer, eigenes Tempo `event-ausnahme-tempo.txt`.
- Schlussphase (Zeit oben links rot, "Argonius leaves in"): ohne normale Haemmer mit blauen weiter; alles leer -> PAUSE.

**Belohnungs-Erkennung / Schwellen** (`kisteMitnehmen`, `belohnungenImFeld` sucht nur im `feldBereich()`):
- Alles mit Praefix `ev_kiste_` + `ev_gems` wird genommen (Namensanfang, keine feste Liste -- neue Vorlage = neue Beute;
  Unerwuenschtes: Vorlage loeschen). `HAMMER_BELOHNUNGEN` (extra_3/5/9, hammer2) bringen Haemmer.
- Standard `KISTEN_SCHWELLE` 0,78; darunter nur **mittig in einem offenen Rasterfeld**: `_feld`-Vorlagen und Nahrung
  0,70, sonst 0,73, Sonderwerte in `EventVorlagenSchwellen.EIGENE` (elixier 0,61, metall 0,64, mithril 0,63, ...).
- `Eventkisten` (holz/eisen/gold/mithril/mana/nahrung/fackeln, `!settings kiste holz aus`): abgeschaltete Art,
  die an der Stelle um > 0,03 besser passt, gewinnt -> liegen lassen. `ev_kiste_essenz` != `ev_kiste_essen`.
- Je Kistenstelle eigener Zaehler: > 3 Klicks -> fuer dieses Feld in Ruhe lassen (sonst A-B-A-B Endlosschleife).
- Nahrungskisten-Grenze je Gang (`event-nahrungskisten.txt`, Std 999) -> `zielStages` -> raus.

## 4. Titan-Grabung (`TitanEventMode`)

Regeln Autor 09.10.: erst Boss (X10 = 500 Tickets, solange Zahl am Knopf nicht rot; X1 nur in der roten Endphase
oder mit `titan-x1.txt` an), dann Spitzhacken "wie ein Autoclicker" auf `GRABEN_HIER` {540,642} (trifft auch den
Claim-Knopf). **Nie** Store, Activate, Watch (Werbung), Kaufknoepfe, Diamanten. Nie oefter tippen als Hacken da sind.
- Erkennung: `ev_titan_titel` = im Event; Reihenfolge wichtig: erst `ev_titan_vorbei`, dann `ev_titan_quick`
  (abgedunkeltes Truppenfenster passte noch -> 110 Tipps ins Leere). Claim sofort.
- Graben: Spitzhacken-Zahl (OCR, `plausibel()` verwirft Spruenge wie 124 statt 1234), "wirkt nicht" erst bei Schub
  >= 1,5 s; Fortschritt nur, wenn die Zahl sinkt. HC-Graben (`titan-hc.txt`, Std an): 6x5-Mine per Farbe lesen,
  kuerzester Weg zu HC, nur EIN Weg zugleich, nur Felder mit Zugang zum Schacht (`zugang`). Ziel-Tiefe `titan-ziel.txt`.
- Werkzeuge nach den Hacken: Dynamit -> Super-Spitzhacke -> Drill (`titan-drill.txt` "an [N]"); `Watch`-Knopf
  (gelb, 0 Stueck) nie; Schloss `ev_titan_schloss` = kein Pass. `titan-nur.txt` = Testlauf nur ein Werkzeug.
- Selbsthilfe: 30 s ohne Fortschritt -> rotes X/Event schliessen. Rangliste-Bild 0/12 Uhr am Ende des Besuchs.
- Dateien: `titan-klick.txt` (Dauerklick-Werte), `grabung-tempo.txt`, `logs/titan-1000.txt`, `logs/titan-tempo.txt`.

## 5. Clan-Krieg (`ClanWarMode`)

**Auftrag**: genau 2 Trupps -- einer in einen NORMALEN Turm, einer (Titanen-Trupp) in einen AUSSENPOSTEN/Tempel.
Fertig erst, wenn beide **gesehen** sind ("gesehen zaehlt, nicht geklickt"; `cw_your_squad` ab 0,60, blasses
Schild auf dem Band misst 0,69).
**Weg**: Burg -> `cw_icon` (nur zu Kriegszeiten; `cw_symbol_krieg_laeuft` = Krieg laeuft schon, nichts zu tun) ->
Kriegskarte (`cw_prep_stage`/`cw_war_progress`) -> Turm -> `cw_deploy_squad` -> Truppenfenster: normal
`cw_deploy_best`, Aussenposten `cw_previous_squad` (einstellbar `Aufstellung` "clanwar_tower"/"clanwar_outpost";
Element-Pflicht -> Deploy best) -> `cw_deploy_cost` (normal, Apfelpreis) bzw. `cw_confirm` (Aussenposten) ->
zurueck, naechster Turm. Auswahlknopf **genau einmal** je Turm (er bleibt stehen).
**Tuerme finden**: nicht per Bild, sondern ueber `cw_slash` ("0/3") + Ziffern `cw_digit_0..9` links/rechts
(`zahlNeben`, zweistellig moeglich); Aussenposten ueber `cw_outpost*`-Motive in Graustufen (0,72) nahe der Zahl
(`NAH_AM_TURM` 0,12, `TURM_UEBER_ZAEHLER` 0,06 -- urspruenglich GERATEN). `Turm.voll()` nur wenn beide Ziffern gelesen.
**Zielwahl** (`zielNachRegeln`, Erkundung 17.10.): jeden Turm einmal zum Lesen oeffnen (max 30), OCR von Titel,
"Message from the Clan Administration"-Namen und "Glory for winning". Reihenfolge: Turm mit Thronsaal-Notiz fuer
dieses Konto -> eigener Spielername (`MeinTeam.spielername()`, `NamensVergleich`) -> Notiz passt zur eigenen
TR-Stufe (`stufePasst`, z. B. "TR11") -> sonst leerer Turm; Titan: `titan-ruhm.txt` an -> meister Ruhm, Tempel nur
wenn einziger. `cw_skip*`-Vorlagen = Turmnamen, die nie benutzt werden. Roter Kasten (Thronsaal-Stufe) -> Turm gesperrt.
**Harte Regeln**
- **Nie Aepfel mit Diamanten kaufen**: `cw_buy_resources` -> `cw_buy_no`; ohne "No"-Treffer gar nichts klicken.
  Normaler Turm dann aufgegeben, Titan trotzdem (kostet nichts).
- "Warning"-Yes nur, wenn OCR-Text ueber dem Yes `truppFrage()` erfuellt (incomplete/reserve..., nichts mit
  buy/gem/resource). Erst "No" -> `TruppAuffuellen` versuchen, beim 2. Mal Yes.
- `cw_apple_red` NICHT benutzen (Apfel ist immer rot -> liess jeden Turm aus).
- Ungewollter "Editing mode" (`cw_edit_save` + `cw_edit_exit` gleichzeitig) -> Exit (max 5); berechnete Klicks nie
  in dessen Zone (`inSperrzone`).
- Eine Aktion je Schritt: Turmfenster nach "No" erst im NAECHSTEN Schritt schliessen (`schliesseSpaeter`).
- Vor jeder Entscheidung `BEDENKZEIT_MS` schlafen und **frisch aufnehmen** (`ctx.refresh()`); Zeiten x `Rechnertempo`.
- Kein freier Turm: max `OHNE_ZIEL_GEDULD_MS` = 3 min warten, dann sauber raus. Burg nie gesehen != "kein Krieg"
  (`ohneBurgAufgegeben` -> `Kriegsplan.nochmalErlauben`, max 3x).
- Confirm ohne Wirkung `TruppRunden.MAX_RUNDEN`x -> Turm aufgeben; Rauswurf direkt nach Deploy -> Aufstellung
  zuruecknehmen (`rauswurfNachDeploy`).
**TitanRauswurf** (Schalter `titan-rauswurf.txt`, Std AUS): alle Titanen-Tuerme voll -> Editing mode -> Turm ->
Liste scrollen, Team mit kleinster Kraft (nie man selbst, nie `titan-schutz.txt`, nie ueber Grenze
`titan-bewertung.txt`; ohne Grenze niemand) -> "≡" -> Withdraw -> Save. Log `titan-rauswurf.log`, kein Discord.
**Weitere Schalter**: `clanwar-nur-pruefen.txt`, `clanwar-name-lesen.txt` + `clanwar-konto.txt` (Mehrkonten),
`clanwar-kein-titan.txt`, `nahrungskisten.txt` (Std an), `clankrieg-preis.txt`, `heldenset-krieg.txt`,
Testfotos `logs/clankrieg-test/`.

**Kriegsplan (Zeitplan)**: Zeiten in **Weltzeit** `01:20, 07:20, 13:20, 19:20` UTC (= Winter Berlin 02:20/08:20/14:20/
20:20; Spiel stellt Uhr nicht um), Fenster 100 min (endet mit Kriegsbeginn). Kontroll-Lauf 15 min vor Kriegsbeginn
(UTC 02:45/08:45/14:45/20:45). Innere Uhr = fest UTC+1 (`INTERN`), Anzeige in Spielerzone (`Zeitzone`).
`kriegsplan-an.txt` (ohne Datei AN; unlesbar -> aus), `kriegsplan-erledigt.txt` (Fenster fertig, ueberlebt Neustart).
Einschub: `DiscordBridge.nebenbeiPruefen()` -> `kriegsplan.faellig()` -> "clanwar" vor den Rest der Kette, `angestossen`
einmal je Fenster, nicht aufgestellt -> nach 15 min nochmal. `BotEngine` startet das Spiel nicht neu < 15 min vor Krieg.
**Achtung**: `docs/EVENT-FLOW-EN.txt` nennt noch 03:01/09:01/... -- veraltet, `Kriegsplan.zeitenText()` ist die Wahrheit.

## 6. Stigmas (`StigmaMode`, `Stigmaplan`)
Weg: Burg -> Map -> Tower -> Titan-Knopf (30,518) -> Titan Squad -> Truhe (52,570) -> Titan Inventory -> Muelleimer
-> "Disassembling items" -> Reiter Stigmas -> Quick add -> Kachel fuer Kachel lesen (Titel/Sterne/Level, OCR) ->
"Add" nur wenn ALLES stimmt -> "Disassemble (N)" -> Warnzahl mit eigener Zaehlung vergleichen -> OK.
- `loeschen`: Level 1-79 (Sterne/Level muessen zusammenpassen: `sternePassen`), ausser `Stigmaplan.behalten()`
  (Std Bull, Dragon, Wolf; "alles" = ohne Sets). Unlesbares bleibt liegen. Tippfehler hinter "loeschen" -> Rueckfrage.
- `leveln [titan]`: Sorten Mind..Tenacity durchgehen, bessere einsetzen, gute hochleveln (Regeln je Titan in
  `Stigmaplan.TITANEN`; ohne Titan Last Guardian; unbekannter Name = eigener Titan). `hundert` = Richtung Lv 100
  (kauft Raenge, kein Probelauf). `probe`/`test`/`dry run` = nichts druecken.
- Nur frisch erteilter Auftrag darf laufen (`auftragNaechster`) -- sonst wuerde ein Neustart mit alten Angaben aus
  "probe" echtes Loeschen machen. Jeder Menue-Tipp nur, wenn das Menue am Bild belegt offen ist.
- Dateien: `stigma-tempo.txt` (Repo-Root: `0,5` = Pausenfaktor, lernt schnellstes fehlerfreies),
  `stigma-filter-tempo.txt`, `stigma-einstellungen.txt`, `stigma-rangliste.txt`, gelernte Koepfe
  `templates/gelernt/stigma/`, Titanen-Bilder `templates/gelernt/titanen/` (+`titanen.txt`), Fotos `logs/stigmas/`.

## 7. Gem Bay (`GemBayMode`, `GemBayGrenzen`, `GemBayplan`)
Burg -> Map -> `gb_karte` -> Preis unter "Start journey": Diamant (`gb_preis_diamant`) = heute erledigt; zu wenig
Nahrung -> Kisten holen, 1x neu; sonst Start -> Deploy best -> Confirm -> ggf. Yes (nur nach Textpruefung, nie mit
Preis). In der Bucht: "Battle begins" sofort, "Pause" NIE druecken, bei "Auto" tote Einheiten aus Reserve ersetzen,
sonst "Restore all units" (Video, 1x je Reset, `gembay-watch.txt`). "Journey over!" -> gruenes Claim -> nochmal,
bis Preis in Diamanten. NIE "End journey"/oranges Claim in der Bucht, nie rotes X waehrend Kampf.
Grenzen (`GemBayGrenzen`): Besuch 45 min (hart 60), Start nur mit >= 4 min Rest, max 6 Kartenklicks ohne Fortschritt,
Video max 90 s, Fotozyklus 100 ms Kastenaufnahme + alle 2 s Vollbild. Dateien: `gembay.txt` (Schalter, Std aus),
`gembay-erledigt.txt`, `gembay-reset.txt`, `gembay-fehlversuche.txt`.

## 8. Laufzeit-Dateien (Bot-Ordner) Event-Seite
`event-automatik.txt`, `event-automatik-verlies.txt`, `event-pass.txt` (kein|silber|gold, Std gold), `event-lupen.txt`,
`event-lupen-alle.txt`, `superhammer-modus.txt`, `superhammer-test.txt` (Einmal-Test), `event-art.txt`,
`event-nahrungskisten.txt`, `event-hammer-alle.txt`, `event-titan-alle.txt`, `event-plan.txt`,
`event-stages-je-gang.txt`, `event-kisten.txt`, `zauberstaebe.txt`, `event-tempo.txt`/`event-ausnahme-tempo.txt`
(`Tempolerner`: je fehlerfreier Stage 0,1 % schneller, beim ersten Fehler eine Stufe zurueck und fest),
`event-raster.txt` (Feldlage je Fenstergroesse), `event-formen.txt`, `titan-ende.txt`, `kathedrale-seit-event.txt`.
Logs/Belege: `logs/event-genommen/` (max 80 Fotos je Start), `logs/event-stab-*`, `logs/event-ende-*`,
`logs/event-fenster-HHmmss.png`, `logs/lupe-daneben-*`, `logs/superhammer-ohne-form-*`.
Zahl-Dateien werden ueber `Eventautomatik.zahlLaden` gelesen: BOM weg, nur ERSTE Zahl, geklemmt (Code-Review 24.10.).

## 9. Discord / `!settings` (Auszug)
`!start event [N]` (N Stages), `!start eventloop c all`, `!event ranking`, `!settings pass gold|silber|kein`,
`!settings lupen 5`, `!settings kiste <art> an|aus`, `!settings foodchests N`, `!settings zauberstaebe 1000|aus`,
`!settings stages N` (hammerstages), `!settings hammerevent N` / `titanevent N`, `!settings eventautomatik an|aus|N|titan|hammer`,
`!clanwar on|off` (= `!kriegsplan`), `!titan hc on|off`, `!titan ziel 150000|aus`, `!stigmas ...`, `!gembay`.
Parsing in `DiscordBridge` (Bereich um `w0.equals("kiste")`, `commandTitan`, `commandStigmas`, `clanwar`).

## 10. Tests (`src/test/java/de/hustlebot/...`)
- `bot/EventSchlussphaseTest` -- `schlussphaseGilt`/`frischPruefen` (Wechselmessungen aus Log 29.10. kippen nicht).
- `bot/EventVorlagenSchwellenTest` -- Kreuztabelle Belohnungsvorlagen vs. Feldbilder `src/test/resources/event-belohnungen/`
  (kraken_*, metall_0656 ...: Dateiname = gemessener Wert); braucht `templates/`, sonst `assumeTrue`-Skip.
- `bot/ZuWenigHaemmerTest`, `bot/VasensucheVergleichTest` -- nur mit lokalen Fotos unter `C:\HustleBot\logs` (sonst Skip).
- `bot/Titan{Plan,EinWeg,Zugang,Form,Tiefe,Weg}Test` -- reine Logik (Gitter als Strings: o offen, b/s Bloecke, H HC).
- `bot/TitanRauswurfTest` (`waehlen`, Schutzliste, Grenze), `bot/ClanWar{Aufstellungen,Pruefer,Stuetzpunkt,TruppRunden}Test`
  (Zaehlen, Sperrzone Editing mode, Ziffern "4/12", Ruhm-OCR "8 ooo", Geduld 3 min), `bot/TruppAuffuellenTest`.
- `core/KriegsplanZeitumstellungTest`, `core/KriegsplanAlleZonenTest` (jede Java-Zone, jede Umstellung 2026/27).
- `bot/GemBayHaengerTest`, `bot/BezahlkasseNahrungKistenTest`, `core/BezahlungNahrungKistenTest`.
- Fixtures `src/test/resources/event-fenster/` und `stoerbilder/cw_edit_*.png` werden aktuell von keinem Test per
  Pfad referenziert (Stand grep) -- Kandidaten fuer neue Tests.
Ausfuehren: `mvn test -Dtest=EventSchlussphaseTest` (Linux geht fuer reine Logik-Tests; OpenCV muss laden).

## 11. Rezepte
- **Neue Event-Belohnung mitnehmen**: Vorlage `ev_kiste_<name>[_feld].png` nach `templates/event-belohnungen/` -- fertig.
  Faellt sie im Feld zu schwach aus: Eintrag in `EventVorlagenSchwellen.EIGENE` + Fall in `EventVorlagenSchwellenTest`.
- **Belohnung nie nehmen**: Vorlage loeschen, oder (die 7 Arten) `Eventkisten`; als Gegenprobe `ev_nie_*`-Vorlage
  analog `KRAKEN`.
- **Neues Fass-Motiv** (neues Event-Design): `ev_fass_zu_<n>.png` (Serie, `vasenMotive`).
- **Lupen-/Hammer-Strategie**: `EventMode.step` Schritte 7-11, `lupeNochDran`, `lupenGrenze`, `superStattNormal`.
- **Kreislauf-Reihenfolge/Abstaende**: `CathedralMode.hammerEventSchritt/titanEventSchritt/titanAlleRuns` + `Eventautomatik`.
- **Kriegszeiten**: nur `Kriegsplan.ZEITEN`/`KONTROLLE` (Weltzeit!) + beide Kriegsplan-Tests laufen lassen.
- **Clan-Krieg Turmwahl**: `ClanWarMode.zielNachRegeln`, `turmLesen`, `stufePasst`, `titanenName`.
- **Kauf-/Yes-Schutz**: `ClanWarMode.schrittNachBedenkzeit` Schritte 3/4 + `truppFrage` -- nur verschaerfen, nie lockern.
- **Titan-Grabung Stellen**: Konstanten oben in `TitanEventMode` (1159x770); Werkzeugreihenfolge im Enum `Werkzeug`.
- **Stigma-Loeschregel**: `Stigmaplan.behalten()`/`standard()`, `StigmaMode.sternePassen`.
- **Gem-Bay-Zeiten**: `GemBayGrenzen`-Konstanten (testbar), nicht im Modus hartcodieren.

## 12. Querschnitt-Gotchas
- Gruene "Yes"/"Confirm"/rote "X" sehen spielweit gleich aus: immer Kontext pruefen (selbst gefragt? Text darueber?
  Bereich relativ zum Fenster?). Das grosse X oben rechts schliesst das ganze Event.
- Zustand am Bild erkennen, nicht am eigenen Klick ("gesehen zaehlt"); Zaehler erst nach Bestaetigung erhoehen.
- Jede Schleife braucht eine Grenze (je Stelle/je Feld/je Krieg) -- fast jeder dokumentierte Nachtausfall war eine
  Endlosschleife, die als "Fortschritt" zaehlte und den Haenger-Alarm aushebelte. `runs.progress()` nur bei echtem
  Fortschritt aufrufen.
- `Stoerfenster.eventUnterwegs()` in jedem Event-Schritt: sonst klickt der globale Stoerfenster-Filter das
  "Active events"/Kalender-Fenster weg.
- Datierte Kommentare ("Autor 24.10.: ...", "Nacht 28.10.", "Bericht #XXXX") erklaeren fast jede Schwelle -- vor
  dem Aendern einer Zahl den Kommentar daneben lesen.
