# Arena-Subsystem (Turnier) — Arbeitsnotizen für Claude

Größter und heikelster Teil des Bots. `ArenaMode.java` allein ~10.700 Zeilen, `ArenaPruefer` ~2.900, `TeamScan` ~1.900, `core/Gegnerbuch` ~1.900.
Fast jede Konstante hat einen datierten Kommentar ("Autor 24.10.: …", "Ben 21.10.", "Pruefung 29.10.", "Idee 8 (A04)") mit der Messung, die
sie begründet. **Vor dem Ändern einer Zahl den Kommentar daneben lesen** — die meisten sind aus echten Niederlagen/Hängern zurückgerechnet.
Kommentare sind laut Code "Autors einziger Zugang zum Verhalten des Bots": Kommentar und Code müssen übereinstimmen (bei Änderung beides pflegen).

Spielbegriffe: Turnier = "Tournament", 10 Spieler, 5 Runden ("Stage 1/5"), je Runde max. ~2 min, je Runde genau EIN eigener Angriff,
jeder Gegner nur EINMAL pro Turnier angreifbar. Sieg gegen jemanden unter dir 70–150 Pkt, über dir 105–225; Runde 1 immer max. 150;
Niederlage = 0 (= gleich wie nicht angreifen → **in jeder Runde IMMER angreifen**, nur die harte Sperrliste greift ohne Ausnahme).
Volle Punkte bis 10 s Kampfdauer, danach Abzug (~1/130 des Lohns je s, max. 1/5). Ziel ist **Top 1–3**, Platz 4+ ist wertlos.
Faustregel der Profis: 3×150 in Stage 1–3 + 2×225 in Stage 4–5 (nur Schubs, keine harte Regel).

## 1. Einstieg & Einbindung

- `ArenaMode implements GameMode`, id `arena`, registriert in `BotEngine` (`register(new ArenaMode())`). Start: `!start arena N` (N = Turniere,
  ~12 min/Turnier), `!start arena 5 food` (Bezahlung über `Bezahlkasse`/`core.Bezahlung`: Tickets, Nahrung, Kisten).
- `step(Mat screen, BotContext ctx, RunTracker runs)` = Dispatcher, **von hinten nach vorn** geprüft (spätester Bildschirm zuerst, sonst Klick in verdeckte Pyramide):
  Kauf-Fenster abwehren → Kassen-Gang (nur außerhalb Turnier) → Störfenster (AFK-Update, "ar_return" Runde verpasst, anderes Gerät,
  Belohnung; max. alle `STOER_BLICK_MS`=1,2 s) → Burgen-Ausflug → eigener Wachhund `runs.isIdleTooLong()`→`notausstieg` →
  `rundenwechselMerken` (jedes Bild!) → laufende `pruefer`-Prüfung hat Vorrang (inkl. Abbruch bei Zeitnot) → Rest-Fenster schließen
  (Einheiten-/Item-/Pal-Fenster) → `ar_ended`→`turnierEnde` → `ar_victory`/`ar_defeat`→`ergebnis` → `ar_your_squad` **und** (`ar_quick_attack` oder `ar_power_swords`)→`vergleich`
  → `ar_searching`→`warteschlange` → `ar_squad_title`→`truppe` → `ar_tournament`→`anmelden` → `ar_select_opponent`/`ar_next_stage`→`pyramide` → sonst `hinweg`.
- `haengerBerichtNachS()`=180 (Arena wartet normal bis 2 min). `endeHinweis()` = Grund aus der Kasse.
- **Nachspiel** (BotEngine `nachspielPruefen/nachspielSchritt`): steht oben der Turnierbalken (`Turnierbalken.sichtbar`, OCR "Wait for the next stage…"/
  "Time left to select an opponent"), spielt `ArenaMode` mit `nurZuEndeSpielen(true)` das Turnier fertig, bevor ein anderer Modus darf.
  `turnierLaeuft()`/`draussen()`/`wechselErbeten()`/`wechselJetzt()` — Einschübe und Spiel-Neustart warten während eines Turniers. Heimweg danach: `nachspielHeimweg` (`Heimweg`-enum, max 30 Bilder).
- **Beobachter** (`ArenaBeobachter.starten(motor)`, eigener Faden "arena-beobachter"): spielt der Mensch selbst Arena, schaut der Bot zu,
  **klickt nie**, zählt Kämpfe in Notizblock/`SelbstKaempfe` und setzt Listen. Logik in `ArenaMode.beobachtenSchritt` + `beob*`-Methoden. Schalter `arena-beobachter.txt` / `!settings arenawatch an|aus`.
- **Schicht/Seitenfenster** (Overlay, Paket `schicht`, `ArenaStand`): `schichtMelden`, `schichtImWarten`, `schichtBefehleAusfuehren` (Tasten am Overlay setzen Listen: `listeAusTaste`). Nur mit `arena-schicht.txt` an (`core.ArenaSchalter`).
- `TeamScanMode` (id teamscan): eigener Botengang für das eigene Team, beim ersten Start und alle 3 Echtzeit-Tage, `!team scan`/`!start teamscan`.

## 1b. TS8-Strategie (`ArenaEinfach`, seit 25.11.)

Nutzer spielt auf **Thronsaal 8**, die TS11-Logik ist dort überdimensioniert. `ArenaEinfach.aktiv()` (5-s-Cache) = Thronsaal-Stufe == 8,
übersteuerbar mit `arena-einfach.txt` (an/aus). Stufe: Team-Scan oder `!throneroom 8` (`Thronsaal.vonHandSetzen`; ohne Kontonamen
Zeile `standard=8` in `thronsaal.txt`). Wenn aktiv:
- **Punkte**: Runde 1 max 100; ab Runde 2 unter dir max 100, über dir max 150 (`lohn`, `ueberMir`; Schild-Punkte, gedeckelt).
- **Bilanz statt Kraft**: `arena-ts8.txt` (TAB: Schlüssel, Name, S/N, eigene Kraft, Zeit; max 10/Gegner). Letzte 3 gültige Kämpfe:
  ≥2 Siege = SCHAFFBAR, sonst NICHT_SCHAFFBAR, <3 = UNBEKANNT (Chance Laplace (S+1)/(n+2)). Kampf ungültig, wenn eigene Kraft
  heute ≥ 5 % über damals (`veraltet`) → Gegner wird neu getestet.
- **Wahl** `zielWaehlen` → `einfachWaehlen` → `ArenaEinfach.wahl`: max Chance × Lohn; Gleichstand → Unbekannter, dann mehr Punkte.
  v1 ohne echte Positionierung (Nutzer liefert Strategie nach ein paar Runden).
- **Aus**: Durchschauen/Erkunden, Pal-Besuch, Einheiten-Prüfung, Schnell-Blicke (`brauchtPruefung`, `brauchtSchnellblick`,
  `brauchtExtras`, `vorAngriffBlickNoetig`), "dreimal verloren"/OP-Sperre, ArenaSperre-Prüfung im Vergleichsfenster, "30 % stärker
  → zurück", Listen-Lernen (`namensbildVormerken`). **Bleibt**: harte Liste `ar_block_` (auch früher automatisch gelernte!),
  späte Angriffszeit, Kampfprotokoll, Gegnerbuch.
- Tests: `ArenaEinfachTest`, `core/ThronsaalStandardTest`.
- Ausflüge in der Wartezeit: harte Überzeit `AUSFLUG_UEBERZEIT_MS` = 60 s (vorher 40 s, Nutzer 25.11.).

## 1c. Kathedrale in der Wartezeit (seit 25.11.)

Schalter `arena-kathedrale.txt` / `!arenacathedral an|aus` (`ArenaMode.KATHEDRALE_AUSFLUG`, Standard aus). Nach dem eigenen Angriff
(Stage 1–4, Ergebnis eingetragen, ≥ `KATHEDRALE_MIN_REST_MS` 30 s bis Rundenende) → `kathedraleAusflugStarten`: Turnierfenster zu
(`T_CLOSE_X`), eingebettete `CathedralMode`-Instanz (`kathedrale.reset()` je Ausflug, eigener `RunTracker` je Ausflug) bis
`kathedraleFrist` = Rundenende + 60 s − 20 s Rückweg. Danach `burgenRueckwegSeit` → `hinweg`-Rückweg: `dc_floor_hint` →
`Weltkarte.heim`, `home_button`, Map, `ar_node`. Reise bleibt offen (nie "End journey"). Kathedrale FINISHED/FATAL → `kathedraleLeer`
für diesen Start. Nicht im Event-Kreislauf (`Eventautomatik.istAn()`), nicht unter Thronsaal 6, nicht im Nachspiel.
`neueRunde` setzt `rundeSeit` per `rundeBegannUm` auf das bekannte alte Rundenende (verspäteter Rundenwechsel nach Ausflug).
**Warteschlange bewusst NICHT**: Autor 11.10. hat Kathedrale während der Warteschlange entfernt ("Niemals während dem Warten auf
Arena") — Turnierbeginn dort unbekannt. Tests: `ArenaKathedraleAusflugTest`.

## 2. Dateikarte

### bot/
| Datei | Rolle |
|---|---|
| `ArenaMode` (10,7k) | Alles: Zustandsautomat, Lesen der Pyramide, Zielwahl, Siegchance, Ergebnis, Listenlernen, Beobachter, Schicht. Sektionen per `// ----` / `// ====` Kopf (1. Hinweg … 7. Ergebnis, 8. Turnierende). |
| `ArenaPruefer` (2,9k) | Einheiten-Prüfung eines Gegners (Sets/Artefakte/Steine/Items/Held/Schirm/Pals) im Vergleichsfenster, ein Schritt je Bild. `Phase`-enum: `AUS, SCHIRM, SCHIRM_FENSTER, PAL, PAL_FENSTER, ZU, HELD, HELD_FENSTER, HELD_ZU, OEFFNEN, EINHEIT, ITEM, WAFFE, WAFFE_ITEM, RING, RING_ITEM, WEITER, SCHLIESSEN`. Varianten: `starten`, `startenNurExtras`, `startenAussehen` (Schnell-Blick), `startenEigenesTeam`, `startenKaserne` (TeamScan). Frist `GEGNER_FRIST_MS`=25 s. Ergebnis-Record `Ergebnis`. |
| `ArenaLeser` | OCR-Helfer (Windows-OCR, ~1 s/Aufruf): `zahlUnter/Neben/RechtsVon`, `zahlHinterWort`, `liesName`, `liesNamenZeilen`, `nameImKastenPaddle` (PaddleOCR), `sekunden`, `zahlAus` (O→0 usw.), `gemerktLesen` (Cache). Keine Ziffernvorlagen (3 Schriftarten). |
| `ArenaTabelle` | Eigene Pixel-Ziffernerkennung (`weissMaske`, `ziffernLesen`, `stageLesen`, `uhrLesen`) für Punkte aller 10 Plätze, Stage, Uhr. Top-3-Rechnung: `endpunkteAndere`, `rang`, `wahlWertVoraus`, `keineWette`, `platzBei`. `RECHNUNG_AB`=2. |
| `ArenaSets` | Set-Erkennung aus Brustplatten-Titel (`erkennen`), Tabelle `arena-sets.txt` (Ressource; Datei im Bot-Ordner hat Vorrang). Unbekannte → `arena-sets-unbekannt.txt`. |
| `ArenaItems` | Item-Bewertung (op/gut/mittel/neutral/schlecht, 16×16-Fingerabdruck, Abstand <0,04), Ordner `arena-items/` (bewertung.tsv, eigen.tsv, op-gegner.tsv). "OP"-Item beim Gegner → nie angreifen, außer eigenes OP (dann −30 %). Nur Thronsaal 5 (`OP_THRONSAAL`). |
| `ArenaSteine` | Edelsteine je Gegenstand zählen über Farbton (gelb +0,5, rot +0,1); zwei Bilder wegen Glanz, "dunklere" Lesung gewinnt. |
| `ArenaAussehen` | Ausrüstungsfelder je Einheit (ab 0,95 gleich) → Schnell-Blick "noch dasselbe Team?". Ablage `arena-aussehen/<Name>/`. Guardian/Talent-Punkte. |
| `ArenaBewertung` | Autors gut/mittel/schlecht-Bögen als 8×8-Farbmittel aus `arena-bewertungen.txt` (nur Autors PC; ohne Datei wirkungslos). |
| `ArenaListenRegel` | **Reine Funktion**: welche Liste nach einem Kampf (`entscheide`, `mitZwischenstufen`, `nachSelbstKaempfen`, `autoStufe`). Von Bot und Beobachter gemeinsam genutzt. |
| `ArenaLernen` | Hängt je Kampf eine Zeile an `arena-lernen.txt` (Schätzung vs. Ergebnis). Kommentar sagt "nur schreiben" — **stimmt nicht mehr**: `ArenaVergleich` und `aehnlicheDauern` lesen sie. |
| `ArenaVergleich` | Erwartete Punkte für nie bekämpfte Gegner aus ähnlicher Chance in `arena-lernen.txt`; ohne Daten 220/149 (`ohneVergleich`). |
| `ArenaKaesten` | Feste Suchkästen für Banner/Fenster (ar_defeat, ar_item_fenster, ar_dwellers, Pal-/Schirm-Titel) statt Vollbildsuche; Sicherheitsnetz: jeder `NETZ_ALLE`-te Fehlschlag Vollbild. |
| `ArenaSchildLage` | Name/Clan eines Schilds nach **Lage** (Namensband/Clanband), nicht OCR-Zeilenreihenfolge. Clan rückt nie nach oben. |
| `ArenaVerdeckt` | Darf die Pyramide gelesen werden? Nein bei offenem Kampfprotokoll oder Banner "Tournament phase N has begun" (`banner`: weiße Schrift mit dunklem Rand). |
| `ArenaSteckbrief` | Text für den Zettel im Seitenfenster (Bilanz, Dauer, Punkte, Kraft, Team). |
| `ArenaBeobachter` | Startet den Beobachter-Faden. |
| `MeinTeam` | Eigenes Team: `arena-ich.txt` (punkte, squadpower, einheiten, opfer, held, schirm…). `punkte()` <0 wenn >3 Tage alt. |
| `TeamScan` (1,9k) / `TeamScanMode` | Kaserne→Info (Squad power), Einheiten einzeln (ArenaPruefer-Logik), Held im Thronsaal, Schirmherren im Rift → `MeinTeam.speichernScan`. Opfereinheit (kleinste Kraft) fürs Verlies. |
| `Turnierbalken` | OCR des Turnierbalkens oben (auch in der Burg); im Zweifel "kein Balken". |

### core/ (Arena-Teil, hier mitbehandelt)
| Datei | Rolle |
|---|---|
| `Gegnerbuch` (1,9k) | **Notizblock** aller Gegner. `gemeinsam()` = Singleton (auch für `!notiz`). `arena-daten.txt` (Daten, TAB, editierbar bei Bot aus) + `arena-notizblock.txt` (Lesefassung, wird bei jeder Änderung NEU geschrieben → Handänderungen gehen verloren). `Notiz`-Record: verlauf ("NNSNS", neueste rechts, max `MERKT_SICH`=10), staerke, sets, dauern, einheiten, steine, schirm, pals, fingerabdruecke… Bilanz zählt erst ab `GENUG_KAEMPFE`=3. Punkteformeln: `teamPunkte`, `kraftPunkte`, `steinPunkte`, `schirmPunkte`, `palPunkte`. Wiedererkennung: `nachschlagen(name,clan[,kraft,fp])`, `nachKraft`, `wiedererkennen`, `bilanzNachName`, `nameIstClan`. |
| `ArenaSperre` | Sperrprofil je Spieler {Kraft, Name, Clan, Sets, Einheitennamen, Aussehen, Glyphen-Fingerabdrücke} mit S/N; 3 Niederlagen ohne Sieg = gesperrt (`sperrgrund`, `ergebnis`). Datei `arena-sperre.txt` + `arena-sperre-bilder/`. |
| `ArenaSperrBilder` | Aussehen aus dem Vergleichsfenster (Einheiten-Porträts, Clan-Banner; Anker `ar_power_swords`), `ausVergleich`, `eigeneReihe`, `gleicheEinheiten`. Gleicher Spieler ≥4–5 gleiche Porträts. |
| `ArenaZusammenfuehren` | Dubletten in Notizblock/Sperre zusammenlegen (gleiches Namensbild, gleiche Kraft auf die Einerstelle, oder erstes Namenswort + Kraft ±1,5 %). `beimStart()` mit Sicherung, Bericht `arena-zusammengefuehrt.txt`. Nur mit `arena-schicht.txt`. |
| `ArenaListenNetz` | Zweitweg: Listen-Spieler am **Namen im Dateinamen** des Listenbildes erkennen, wenn das Bild nicht trifft; Kraft-Gegenprobe. Vorsilben-Konstanten `NIEMALS, PAUSE ("ar_pause_"), MEIDEN, MACHBAR, FAST ("ar_fastsicher_"), SICHER`. Nur mit `arena-regeln-neu.txt`. |
| `ListenBildVergleich` | Listenbild ↔ Schild nur über die **weiße Schrift** (Schwelle 0,835, Abdeckung 0,85), nur im Namenskasten eines Schilds. |
| `ArenaListenOrdner` | "Der Ordner entscheidet" (Bild in anderen Listen-Ordner verschoben → Vorsilbe wird angepasst), `_auto_MMdd-HHmm`-Namen für Bot-Bilder, vom Nutzer gelöschte Auto-Bilder → `arena-entfernt.txt` (nie wieder automatisch eintragen), Merker `arena-auto-bilder.txt`. |
| `ArenaVorlagenDoppelt` | Name schon in einer Liste? (Bild-in-Bild ≥0,90). `ordnerFuer(templates, vorsilbe)` → `My pictures\Arena lists\<safe|doable|avoid|never…>` (über `Eigenbilder.listenOrdner`). `aufraeumen` beim Start: Umzug aus `gelernt/arena/gegner`, Doppelte nach `templates-aussortiert` (**nie löschen**). |
| `ArenaAufnehmen` | Zeilen "Name \| sicher" in `arena-aufnehmen.txt` (Discord `!arena Name sicher`, Capture "Arena: type names"). Bot schneidet das Namensbild aus, sobald er den Namen liest (`ArenaMode.aufZurufAufnehmen`), und streicht die Zeile. |
| `ArenaExtras` | `arena-extras.txt`: welche Zusatzpunkte zählen (`schirm stigma pals clans` / `alles`); ohne Datei alles an. |
| `ArenaRegelnNeu` | Schalter `arena-regeln-neu.txt` (Standard **aus**, einmal beim Start gelesen): Listen-Netz, Zwischenstufen fast-sicher/pause, knappe 150er, PaddleOCR-Namen, ohne-Clan-nach-Punkten, Dauer aus Vergleich. |
| `ArenaSchalter` | Schalter `arena-schicht.txt` (Standard aus): Schicht/Seitenfenster, Namensschild vor Angriff, Auto-Listenbilder, ListenBildVergleich, Zusammenführen. |
| `ArenaVerlierer` | Ewiges Gedächtnis, wer laut Protokoll gegen dich verloren hat: `arena-verlierer.txt` (Name, Anzahl, zuletzt, angriff/verteidigung/beides). |
| `ArenaPlaetze` | Endplatz je Turnier → `arena-plaetze.txt` ("yyyy-MM-dd HH:mm\tPlatz"); fehlt die Datei, aus Logs nachgeholt. Für Stats Top1/2/3. |
| `SelbstKaempfe` | Notizblock nur für Spieler, die nur der Mensch selbst bekämpft hat: `arena-selbst-kaempfe.txt`. Wandern bei Bot-Kampf in den Gegnerbuch. |
| `NamensVergleich` | "Ungefähr gleicher Name" (ohne Sonderzeichen gleich / ≥5 Zeichen enthalten / ≥6 Zeichen ≤⅓ anders; <5 Zeichen exakt). |
| `Namensbilder` | Platzhalter "Bild-MMdd-HHmmss" für unlesbare Namen; Zuordnung `arena-namensbilder.txt` ("Bild-… = Sascha"). |
| (genutzt, nicht arena-eigen) `Glyphenschrift` | Eigene Schrifterkennung/Fingerabdruck des Namensbildes (~1–2 ms, keine OCR). Identität bei Zierschrift. |

## 3. Ablauf eines Turniers

1. **hinweg**: Burg → Weltkarte (`map_button`, `go_to_map`) → Arena-Insel `ar_node` (danach 2 s Ruhe). Rauswürfe per `home_button`/`close_x`.
2. **anmelden** (`ar_tournament`): Zähler (Tickets/Abzeichen) **nur hier** lesbar. Ticket-Reserve (`arena-reserve.txt`, `!arenareserve N`) erreicht → keine Turniere mehr. `ar_participate` klicken, danach `PARTICIPATE_SPERRE_MS`=8 s Finger weg (sonst 2 Tickets). Fehlstarts → Äpfel alle?
3. **truppe** (`ar_squad_title`): `ar_deploy_best` (oder `ar_previous_squad`) → `ar_confirm`.
4. **warteschlange** (`ar_searching`): bis 10 Spieler; Geduld `WARTESCHLANGE_GEDULD_MS`=30 min.
5. **pyramide** (`ar_select_opponent`/`ar_next_stage`), je Bild:
   - Uhr lesen (`restzeitLesen`: `ar_clock` + `ArenaTabelle.uhrLesen`, Fallback OCR), Stage (`rundeLesen`: primär **selbst gezählt** = Angriffe+1, OCR nur Korrektur max alle 3 s).
   - `ar_next_stage` sichtbar ⇒ eigener Angriff dieser Runde durch → Namensbild speichern, evtl. Burgen-Ausflug, `nachAngriffDurchschauen` (Rest der Zeit Gegner ansehen, nicht in Stage 5).
   - Rundenwechsel-Notbremsen: Uhr abgelaufen + "Select" wieder da, oder >130 s seit eigenem Angriff ⇒ `neueRunde()`.
   - Nach Quick-Attack 15 s nur warten (sonst falsche Zuordnung des Ergebnisses). Nach Schild-Klick 3 s warten.
   - **Spät angreifen**: `zeitKnapp` = Rest ≤ `spaetestensS()` (25 s, auf langsamen PCs mehr: schlechteste der letzten 5 Vorarbeiten +10 s, max 50) oder `RUNDE_SPAETESTENS_MS`=90 s in der Runde (eigene Uhr, fällt nie aus). Grund: im Kampfprotokoll steht dann, wer gegen dich verloren hat.
   - Vorher Wartezeit nutzen: `erkundungStarten` (Durchschauen: Schild öffnen, im Vergleichsfenster mit `ar_next_arrow` blättern, Kraft lesen, ggf. Prüfung). Startplatz Runde 1: Platz 8 (`ZUERST_RUNDE1`), Runde 2–3: Platz 9. Stage 1: Plätze 1–3 und 10 erst nach dem Angriff. Danach `palBesuchStarten`.
   - Kampfprotokoll (`protokollLesen`, Vorlagen `ar_log_*`, `ar_me` = eigenes Namensbild): Zeilen "Angreifer → Verteidiger", Pfeil grün/rot = Ergebnis aus **deiner** Sicht. Liefert `gegenMichVerloren` (−20 Pkt, Boden 80 %), `gegenMichGewonnen` (+30), Verbrauchte, fremde Kämpfe. Im Warten alle 20 s; vor Angriff via `protokollVorAngriff` (nicht in Runde 1, nicht <15 s + Lesedauer). Offenes Protokoll verdeckt Schilder → `ProtokollZu` klappt zu.
   - `schilderLesen`: sucht **nur "Reward:"** (`ar_reward`) — wo es fehlt (Victory!/Defeat), ist der Gegner verbraucht ⇒ Liste der Schilder = mögliche Gegner. Cache max 5 s (Pyramide ordnet sich um!). Rückgabe `VERDECKT` bei Protokoll/Banner. Listenbilder nur innerhalb der Schildfelder suchen (Perf). Schild-Breite ~14 % der Fensterbreite, `SCHILD_HOEHE`=80.
   - `zielWaehlen` → Klick aufs Schild (`schildOeffnen`), `namensbildVorAngriff`, `autoListenBilder`.
6. **vergleich** (`ar_your_squad` + Quick/Power): Name/Clan/Kraft des Gegners lesen (`gegnerNameLesen`, `fensterBaender`), eigene Kraft (nur wenn plausibel, `eigeneKraftPruefen`), eigenes Team (Reihe/Skins gleich → kein Scan; sonst Schnell-Blick; sonst voll). Ggf. `pruefer.starten…` (volle Prüfung, `pruefungBrauchtS()` gleitender Schnitt 10–30 s; nur wenn Zeit reicht). Sperr-Gegenprobe mit hier gelesener Kraft + Aussehen (`gegnerIstGesperrt`) → zurück, neu wählen. Kraft >30 % über Erwartung → einmal je Runde zurück. Schnell-Blick vor Angriff (`VOR_ANGRIFF_AB_S`=14/`BIS_S`=8). Dann `ar_quick_attack`: setzt `rundeGespielt`, `imKampfGegen` (= wer **wirklich** dasteht, nicht wer angeklickt wurde), merkt Schätzung für `arena-lernen.txt`, Vorarbeitszeit. Knöpfe grau ohne eigenen Angriff ⇒ dieser Gegner verbraucht (`graueSchilder`), zurück.
7. **ergebnis** (`ar_victory`/`ar_defeat`): Zahlen zählen hoch ⇒ über mehrere Bilder das **Maximum** von Punkten (`ar_points_label`) und Dauer (`ar_battle_time`) nehmen, mind. 2,5 s, bis 6 s; Punkte >300 / Dauer >`MAX_KAMPFZEIT_S`(120) = verlesen. Dann `namensbildVormerken` (Listenregel) + `offenesErgebnisEintragen` (Gegnerbuch.ergebnis, ArenaLernen, ArenaSperre.ergebnis, ArenaVerlierer, Stats) — **genau einmal je Kampf** (imKampfGegen danach null). Nur den **unteren** Home (`homeUnten`) klicken — der grüne oben rechts verlässt das Turnier.
8. **turnierEnde** (`ar_ended`): Platz (`ar_position`) eintragen (ArenaPlaetze, Discord-Bericht), `ar_claim`. Nie im selben Zweig `neuesTurnier()` aufrufen (früher Doppelzählung). `turnierDoppelt` schützt vor zweitem Ende.

## 4. Zielwahl (`zielWaehlen`) und Siegchance

Reihenfolge in `zielWaehlen(schilder, runde)`:
1. Raus: `gesperrt` (ar_block_/pause), `dreimalVerloren` (im Fenster gesperrt, `ArenaSperre.sperrgrund`, Listen-Netz-niemals, 0 Siege aus ≥3), `opGesperrt` (OP-Item).
2. Verbrauchte (`schonAngegriffen`) raus — **aber nie bis zur Leere** (Lesefehler darf keine Runde kosten). Graue Schilder raus (wenn Rest bleibt).
3. **Runde 1**: mit `arena-regeln-neu`: `knappZiel` (Kraftverhältnis ≈0,77, Chance ≥0,85, Plätze 4–9 → Ziel 149 statt 150 Pkt, um unter den 150ern zu stehen). Sonst höchste `siegchance`, Gleichstand → weniger Kraft. Ohne bekannte Kraft: Plätze 4–9 (`STAGE1_VON/BIS`) nach **Lage** (`platzAusLage`). Aufgehobene (`spaetZiel`) möglichst nicht.
4. Runde 2–3 (regeln-neu): noch kein Kampf unter 150 → knapper 150er.
5. Ab `ArenaTabelle.RECHNUNG_AB`=2: `top3Wahl` (Turnierpunkte aller Plätze via `tabelleLesen`, Endpunkte hochrechnen, Wert = Chance auf Top 1–3).
6. Letzte Runde, Platz 4–5 (`WAGNIS_BIS_PLATZ`): bester 225er ohne 85-%-Grenze (Wagnis).
7. Sonst max `wert(s, runde)` = chance² × punkte × faktor (Top-3-Modus: chance³). 225er zählt nur als 225 wenn `darf225`: Autors "sicher" (nicht auto), fast-sicher ab Stage 4, aufgehoben ab Stage 4, klar schwächer (<0,9× Kraft) und ≥75 %, oder ≥`SICHER_FUER_225`=0,85 mit geprüftem Team und nicht Top-3-Modus. Kein 225er gegen Angstgegner. `reihenfolge`: 225er in Stage 2–3 ×0,8, Stage 4–5 ×1,1. Abzug lange Kämpfe `dauerAbzug`.

**Siegchance-Kette**: `siegchanceRoh` (Quellen nach Verlässlichkeit: 1. Notizblock-Bilanz ab 3 Kämpfen über alle Schreibweisen (`gesamtBilanz`), 1–2 Kämpfe als Bayes-Mischung mit 3 gedachten; 2. Protokoll "hat gegen mich verloren" Boden 80 %; 3. "Not in clan" (Bots) fast immer Sieg; 4. Punkte-Abstand zur Messlatte `chanceNachAbstand` (Messlatte = `MeinTeam.punkte()` oder stärkster je Geschlagener); `kraftBasis` aus Kraftverhältnis (Stützstellen `KRAFT_STUETZEN`); Artefakte ~5 %/Stück; `wissenLos` ⇒ max 50 % (`UNBEKANNT_HOECHSTENS`)) → `siegchanceOhneDeckel` → `deckeln` (Angstgegner ≥2 Niederlagen und nicht öfter gewonnen: max 60 %; genau 1 Niederlage, 0 Siege: −15 Pp, max 75 %) → `opAbzug` = `siegchance`. Für die Wahl zusätzlich `wahlChance`: `zielArtKorrektur` (Logit: 150er +1,0; 225er ab Stage 3 mit ≥0,9× Kraft −0,7; gemessen an 614 Kämpfen), aufgehoben in Stage ≤3 ×0,4 / ab 4 mind. 92 % (nur wenn roh ≥0,60), ohne-Clan nach Arena-Punkten. `arena-lernen.txt` speichert die **rohe** `siegchance()` (Korrektur ist daran gemessen — nicht ändern).

**Punkte-System** (`punkteVorbereiten`, hoch = stark): Team (`Gegnerbuch.teamPunkte`: Sets nach `arena-sets.txt` + 3/gutes Artefakt + 5/Bauarbeiter (≤250k Leben) + 12/fehlende Einheit ggü. eigenem Team) + Steine + Schirm (+1, +3 gut, +2 Stigma) + Pals (+8 gut) + Kraftpunkte (**±0,5 je Mio** Differenz, +10/+20/+50 ab 20/30/40 Mio mehr — der Blockkommentar über `punkteJetzt` sagt noch "+1 je Mio", Code ist maßgeblich) + Listenpunkte (niemals +99 nur Anzeige, meiden +15, sicher −40, machbar −15, fast-sicher −27, Clan-Liste +8) + Protokoll (+30/−20). Ungeprüfte bekommen den Median der Geprüften (unbekannt ≠ schwach). Top-3-Modus: ≥6 Gegner stärker als Messlatte oder <5 klar Schwächere.

## 5. Gegner-Listen und Lernen

Listen = **Namensbilder** (PNG nur der weißen Namensbuchstaben, ohne Wappen/Clan/Rahmen), Vorsilbe bestimmt Liste:
`ar_block_` niemals (hart gesperrt) · `ar_pause_` pause (vorerst nicht, nach `PAUSE_TAGE`=14 wie meiden) · `ar_ungern_` meiden (+15, nicht gesperrt!) ·
`ar_machbar_` (−15) · `ar_fastsicher_` (−27, 225 ab Stage 4) · `ar_sicher_` (−40, bevorzugt) · `ar_clan_` Clan-Name (+8) · `ar_name_Bild-…` Fingerabdruck unlesbarer Namen · `ar_me` eigener Name.
Orte: Nutzer-Listen in `My pictures\Arena lists\<safe|doable|avoid|never|…>` (früher `templates/arena/gegner/{sicher,machbar,meiden,niemals,clans}`, ausgeliefert **leer** mit LIESMICH — jeder Spieler hat eigene Stärke, Listen sind nicht übertragbar). Auto-Bilder des Bots: `ar_<liste>_<Name>_auto_MMdd-HHmm.png` bzw. `_MMdd-HHmm`; Altbestand `gelernt/arena`.
**Regel: Autors eigene Bilder (ohne Datums-Endung) werden nie vom Bot überschrieben; es wird nie gelöscht** (→ `templates-aussortiert`, `arena-abgestuft.txt`).

Lernregel `ArenaListenRegel.entscheide` (nach eigenem Kampf in `namensbildVormerken`, Bild wird beim nächsten Pyramidenbild in `namensbildSpeichern` ausgeschnitten, Frist 20 s/gleiche Runde):
- Niederlage: stand auf sicher → nur abstufen (`arena-abgestuft.txt`, Bild zählt als machbar); ≥3 Kämpfe ohne Sieg → niemals; <7 s → niemals; ≥20 Mio stärker → meiden (außer >20 s); ≤14 s → meiden; 15–19 s → meiden ab 2. Niederlage; ≥20 s → nichts; Dauer unbekannt → meiden ab 3. Niederlage.
- Sieg: 3. Sieg → sicher; <7 s → sicher; ≤14 s → machbar; sonst nichts.
- Mit regeln-neu (`mitZwischenstufen`): sicher → erst fast-sicher, niemals → erst pause; nächster Kampf entscheidet.
- **Achtung Doku-Drift**: `nachschub/arena/gegner/LIESMICH.txt` nennt 8 s und "Niederlage ab 20 s → machbar" — Code sagt 7 s und "ab 20 s nichts". Code gilt.
- Automatische Liste ohne Bild (`autoListe`/`mitAutoListe`, nur wenn auf keiner eigenen Liste): ≥2 Siege und mehr S als N → machbar; ≥3 Siege, N×3 ≤ S → sicher (regeln-neu: bei genau 3 fast-sicher). Letzter Kampf N → eine Stufe runter.
- Beobachter (Mensch spielt): gleiche Regeln; unbekannte Spieler in `SelbstKaempfe`, Liste nach 3 Kämpfen (`nachSelbstKaempfen`: 3S sicher, 2S machbar, 1S meiden, 0S niemals).

Identität eines Gegners (OCR liest Zierschrift jedes Mal anders): `namensSchluessel`/`vergleichsform` + `NamensVergleich`, `schildAlias`, `schluesselNachStaerke` (Kraft auf die Einheit genau), Glyphen-Fingerabdruck, `ArenaSperre`-Profil (Aussehen), `ArenaZusammenfuehren`. **Die Clan-Zeile ist nie ein Name** (`nameIstClan`); "Not in clan" = Bot-Gegner (`istBot`), nie in Listen/Lernbilder.

## 6. Invarianten & harte Lektionen (nicht "aufräumen")

- In jeder Runde angreifen; nie selbst alle Ziele wegfiltern. Nur `ar_block_`/pause/3×verloren/OP sperren hart.
- `rundeLesen` zählt selbst; OCR der Stage nur Korrektur (sonst war Runde ewig 1 ⇒ alle als 150 bewertet).
- Eigene Rundenuhr (`RUNDE_SPAETESTENS_MS`) als dritter Ausgang neben 9 Häkchen (`ar_check`) und Uhr-OCR — fehlende AFK-Gegner/Vorlagen ließen sonst alle Runden verstreichen.
- Vergleichsfenster nur mit Angriffsknopf/Power-Zeile als erkannt werten (sonst 119× "Angriff nach 9 Blicken").
- Ergebnis: Maximum über Bilder, einmal eintragen; nur unteren Home klicken; nach Quick-Attack nicht nochmal klicken (traf Item → Info-Fenster verdeckt Zahlen).
- `ar_item_zu` nur klicken, wenn `ar_item_fenster` sichtbar (X passte 0,99 auf ein Turmdach, 250 Klicks).
- Schild-Cache max 5 s: Pyramide ordnet sich nach jedem Sieg um, alter Punkt = anderer (evtl. gesperrter) Gegner.
- Pyramide ist **nicht** nach Punkten sortiert — echter Rang aus `tabelleLesen`.
- Nichts lesen/lernen bei offenem Protokoll oder Phasen-Banner (`ArenaVerdeckt`; "Xenos"→"enos" traf ein sicher-Bild).
- Wartezeiten nicht kürzen ohne modusweisen Test (Klicks in Übergangsbilder, "fünf Minuten im Kreis").
- Fortschritt (`runs.progress`) nur bei echtem Fortschritt (neues Häkchen, Prüfungsklick) — sonst Wachhund taub.
- Beobachter-Faden schreibt in dieselben Felder: `reset()` wartet auf ihn; Sets dort `ConcurrentHashMap`.
- Freunde-Fassung: neue Regeln hinter Schaltern (`arena-regeln-neu.txt`, `arena-schicht.txt`, Standard aus); Discord-/Logtexte zweisprachig via `Sprache.t(en, de)`.

## 7. Laufzeitdateien (Bot-Ordner, relativ zum Arbeitsverzeichnis)

Notizblock `arena-daten.txt` / `arena-notizblock.txt` · Sperre `arena-sperre.txt`, `arena-sperre-bilder/` · Lernen `arena-lernen.txt` (TAB: Zeit, Gegner, Clan, SeineKraft, MeineKraft, Punkte, Messlatte, Abstand, Chance%, Top3, Ergebnis, Dauer_s, ArenaPunkte, Runde, Lohn, Rest_s, Platz, Einheiten…) ·
Turnierliste `arena-liste.txt` (`listeSchreiben`, `!liste`) · `arena-plaetze.txt` · `arena-verlierer.txt` · `arena-selbst-kaempfe.txt` · `arena-abgestuft.txt` · `arena-aufheben.txt` (Spieler für Stage 4–5 aufheben) · `arena-punkte-erzielt.txt` (eigene Punkte je Spieler, capture.bat) · `arena-aufnehmen.txt` · `arena-entfernt.txt`, `arena-auto-bilder.txt` · `arena-namensbilder.txt` · `arena-lesarten.txt` (core.Lesarten) · `arena-zusammengefuehrt.txt` ·
eigenes Team `arena-ich.txt` (MeinTeam), `arena-ich-team.txt`, `arena-ich-reihe.png`, `arena-ich-reihe-bilder.png`, `arena-name.txt` (eigener Name gelesen) · `arena-aussehen/<Name>/`, `arena-spieler/` (Vergleichsfotos Beobachter), `arena-angegriffen/` · `arena-items/` · `arena-bewertungen.txt` · `arena-sets.txt` (Override), `arena-sets-unbekannt.txt` · `arena-tempo.txt` (Tempolerner) · `arena-rangleiter.txt` (Rang-Leiter-Test 215–224) · `arena-kommentare/` (Schicht) ·
Schalter: `arena-reserve.txt`, `arena-extras.txt`, `arena-regeln-neu.txt`, `arena-schicht.txt`, `arena-beobachter.txt`, `arenabericht.txt` (`BERICHT`, `!arenareport`). Logs/Fotos: `logs/arena-fotos` (Fotobremse), `logs/arena-helden`, `logs/arena-bogen`.

Discord: `!start arena N [food]`, `!arenareserve N`, `!arenareport an|aus`, `!notizen`, `!notiz Name Text`, `!liste`, `!arena Name sicher|machbar|meiden|niemals` (→ArenaAufnehmen), `!settings arenawatch an|aus`, `!team scan`. Guide für Nutzer: `docs/GUIDE-4-ARENA-EN.txt`.

## 8. Vorlagen (`templates/arena/*`, Fallback `src/main/resources/nachschub/arena/*`; Name = Dateiname, Ordner egal)

`anzeigen/` (ar_node, ar_tournament, ar_select_opponent, ar_next_stage, ar_reward, ar_check, ar_clock, ar_stage_label, ar_power_swords, ar_your_squad, ar_victory/defeat, ar_banner_*, ar_points_label, ar_battle_time, ar_ended, ar_position, ar_not_in_clan, ar_searching, ar_squad_title, ar_ticket_icon, ar_badge_icon, ar_waehrung) · `knoepfe/` (participate, deploy_best, previous_squad, confirm, quick_attack, next/prev_arrow, to_battle, close_x, home, home_result, claim, return, cancel, for_food(_off), for_tickets(_off), apfel_knopf) · `kampfprotokoll/` (ar_log_open/close/title/stage/won/lost/victory/defeat) · `einheiten-pruefung/` (ar_dwellers, ar_dweller_next/close, ar_item_fenster, ar_item_zu, ar_leben_herz) · `stellen/` (ar_stelle_artefakt/brustplatte/leben/setname: feste Ausschnitte) · `artefakte/` ar_art_1..7 · `gegner/` leer.
Pflicht: `requiredTemplates()`; Rest `optionalTemplates()` (Protokoll fehlt → spielt ohne Protokoll-Hinweise). `ar_reward` muss aus einem **Pyramiden-Schild** (hellblau) stammen, nicht aus dem Vergleichsfenster. `ar_me` nimmt jeder selbst auf.

## 9. Tests (`src/test/java/de/hustlebot/{bot,core}`)

Fast alle testen **package-private static** Funktionen ohne Bildschirm (z. B. `ArenaMode.spaetestensSekunden`, `autoListe`, `zielArtKorrektur`, `reihenfolge`, `dauerAbzug`, `heimwegSchritt`, `ArenaListenRegel.*`, `ArenaTabelle.*`) — darum sind viele Helfer `static` und nicht `private`. Neue Regel ⇒ als statische Funktion herausziehen und testen.
bot: `Arena*Test` (AutoListe, Bewertung, Durchschau, FensterRuhe, Goettlich, Item*, Kaesten, KeineWette, Kraft*, Leben, Leser*, ListenRegel, Nachspiel, NamensAbzeichen, OhneClanPunkte, Protokoll*, PruefungAbbruch, Reihenfolge, Sets, Stage1Knapp, Steckbrief, Steine, UhrZeit, Vergleich, Zaehlung, ZielArt, Zwischenlisten, Zwischenstufen, BeobachterKlick, BeweisFoto), `MeinTeam*Test`, `TeamScan*Test`, `TurnierbalkenTest`.
core: `Arena*Test` (Aufnehmen, ExtrasEigenesTeam, Listen{Aufraeumen,Lesung,Netz,Ordner,Review,Sicherheit}, Plaetze, SperrBilder, Sperre*, VorlagenDoppeltAuto, Zusammenfuehren), `Gegnerbuch*Test`, `ListenBildVergleichTest`, `NamenSchriftTest`, `SelbstKaempfeTest`.
Bilder: `src/test/resources/arena/` (stage-*.png Pyramiden), `arena-basis/` (Item-Bilder), `arena-namen/` (Listenbilder ar_block_Xenos…), `arena-pruefung/`, `sperrbilder/`, `glyphen/`. Bildtests laden OpenCV (`nu.pattern.OpenCV.loadLocally()`) und nutzen `Assumptions` (überspringen, wenn Fotos fehlen). OCR-abhängiges ist unter Linux nicht testbar.

## 10. Rezepte

- **Zielwahl ändern** → `zielWaehlen`, `wert`, `darf225`, `wahlChance`, `top3Wahl`; Konstanten oberhalb (`STAGE1_*`, `SICHER_FUER_225`, `AUFHEBEN_*`, `UNTER/UEBER_DIR_LOGIT`). Danach `ArenaReihenfolgeTest`/`ArenaZielArtTest`.
- **Siegchance** → `siegchanceRoh` (~7686), `kraftBasis`, `chanceNachAbstand`, `deckeln`, `einmalVerloren`, `opAbzug`.
- **Gegnerstärke-Punkte** → `Gegnerbuch.teamPunkte/kraftPunkte/...`, `ArenaMode.punkteVorbereiten/listenPunkte/protokollPunkte`, Set-Werte in `src/main/resources/arena-sets.txt` (Spalte 2 = Punkte je Einheit, auch negativ; Spalte 4 = Lesevarianten).
- **Wann angegriffen wird / Zeitbudget** → `spaetestensSekunden`, `erkundenBisS`, `reserveS`, `protokollVorAngriff`, `schichtLesenErlaubt`, `pruefungBrauchtS`.
- **Listen-Lernregeln** → `ArenaListenRegel` (+ `ArenaListenRegelTest`); Speichern/Bild `namensbildVormerken`/`namensbildSpeichern`; Ordnerlogik `ArenaListenOrdner`/`ArenaVorlagenDoppelt`.
- **Name falsch gelesen / Dubletten** → `schilderLesen`, `ArenaSchildLage`, `nameImKasten`, `fingerabdruckAnlegen`, `Gegnerbuch.nachschlagen/wiedererkennen`, `ArenaZusammenfuehren`, `NamensVergleich`.
- **Sperre greift nicht** → `dreimalVerloren`, `ArenaSperre.sperrgrund`, `gegnerIstGesperrt` (Vergleichsfenster), `ArenaSperrBilder`, `ArenaListenNetz`.
- **Einheiten-Prüfung (Sets/Items/Steine/Held)** → `ArenaPruefer.schritt` + Phase-Handler; Stellen relativ zu 1159×770 (Brüche wie `570/1159.0`).
- **Hänger/Bildschirm nicht erkannt** → `step`-Dispatcher, `notausstieg`, `warteMit`, `hinweg`; Vorlagenlage `ArenaKaesten`.
- **Turnier-Endplatz/Stats** → `turnierEnde`, `turnierBeendet`, `extraStats`, `ArenaPlaetze`, `arenaBericht`.
- **Eigenes Team** → `MeinTeam`, `TeamScan`, in-Arena `eigenesTeamMerken`/`reiheVergleichen`/`langerLauf`.
