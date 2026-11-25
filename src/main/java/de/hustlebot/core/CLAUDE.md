# core/ — Infrastruktur des Bots

Paket `de.hustlebot.core`: alles, was nicht Spiellogik ist — Fenster finden, Bild aufnehmen, Vorlagen suchen,
Text lesen, Maus bewegen, Protokoll, Dateien/Schalter, Sprache, Zeitzone, Rechner-Schutz. Dazu einige Zeitplaner
der Modi und die Arena-Datenhaltung (andere Docs). ~147 Dateien, ~24k Zeilen. Läuft nur unter Windows
(JNA/User32, `java.awt.Robot`, PowerShell/WinRT-OCR); auf Linux nur Tests ausführen.

Perf-Notizen mit offenen Ideen: `docs/PERFORMANCE-NOTIZEN.md` (OCR, Aufnahme, Matcher, Wartezeiten). VOR
Änderungen an Matcher/ScreenCapture/Texterkennung lesen — dort steht auch, was bewusst verworfen wurde.

## Einstieg und Aufrufer

- `de.hustlebot.Main.main` → `Kern.main` (Freunde-Fassung, `Main.FASSUNG`). Startreihenfolge in `Kern.main`:
  `sun.java2d.uiScale=1` (statisch, vor jeder Grafik! sonst bei 125/150 % Skalierung Klicks/Fotos daneben) →
  `Ordner.basis()` → `Log.init(basis/logs)` → `Fotobremse.imHintergrund()` → `Sprache.set(...)` →
  `Ordnerordnung.aufraeumen` → `config.properties` lesen → `nu.pattern.OpenCV.loadLocally()` →
  `GameWindow`, `ScreenCapture`, `Matcher.EIGEN_ORDNER = templates/gelernt/eigen`, `Eigenbilder.einrichten`,
  `Matcher.EIGENE_BILDER = "My pictures"`, `ArenaVorlagenDoppelt.aufraeumen`, `new Matcher(templates)`,
  `Humanizer` (in der Freunde-Fassung **aus**, Intensität 0), `Input`, `VorlagenNachschub.liefern`,
  `ReinigerNachschub.liefern`, `UpdateNachschub.fassungInDerJar` (Abgleich), `matcher.loadAll()`,
  `Matcher.STELLEN_DATEI = Ordner.bei("vorlagen-stellen.txt")`, `stellenLaden` → `BotContext`/`BotEngine` →
  `Wachhalten.starten` → Discord/Steuerprogramm → Not-Aus (F9), Not-Pause (F10/F11), Mauswache (nicht bei
  `ServerSparsam`) → `KonsoleHinten.minimieren()` (erst NACH erfolgreichem Start, damit Startfehler sichtbar
  bleiben) → `SpielWaechter`.
- Hauptkonsument ist `bot/BotContext` (refresh, find, sleep, sleepUntilChanged, grabSmall) und `bot/BotEngine`
  (Hauptschleife, Hänger-/Hitze-/Platten-Prüfung). Modi sprechen Matcher/Input fast nur über `BotContext`.
- config.properties-Schlüssel (in Kern gelesen): `window.title` (Hustle Castle), `window.width` 1175,
  `window.height` 809, `templates.dir` (templates), `bot.idleTimeoutMinutes`, `bot.tickDelayMillis` (≤150),
  `log.debug` (true), `bot.requireAllTemplates`, `discord.*`, `notaus.taste` (F9), `notpause.taste` (F10),
  `weiter.taste` (F11). `monitor.url` → `SystemMonitor.configure` (Standard `http://localhost:8085/data.json`).

## Dateikarte

### Bild: Fenster, Aufnahme, Vorlagensuche
- `GameWindow` (475) — Fenster per Titel (EnumWindows, nur sichtbare), `prepare()` stellt Größe 1175×809 außen ein,
  dockt oben rechts an (`RAND_KOMPENSATION`=10 px wegen unsichtbarem Greifrand), holt nach vorn (Alt-Trick gegen
  Vordergrund-Sperre). `innenPruefen`/`innenmassRichten`: Innenmaß muss **1159×770** sein (±2), sonst Außenmaß
  einmalig korrigieren. `clientArea()`, `istImVordergrund()`, `bringToFront()` (Launcher: auch bei −32000-Parkposition).
- `ScreenCapture` (181) — `Robot.createScreenCapture`; `toMat` (BGR, ThreadLocal-Zwischenbild gegen GC-Flut),
  `toMatAusschnitt` (Kastenblick), `toPreviewJpeg/Png`.
- `FensterFoto` (92) — PrintWindow+GetDIBits, ein wiederverwendeter Puffer; NUR für `discord/Spielfoto`, nicht der Bot.
- `Matcher` (1846) — Herzstück, siehe unten.
- `NumberReader` (423) — Zahlen aus Ziffern-Vorlagen (`digit_0..9`, Präfix wählbar) rechts von/um ein Anker-Symbol
  (`readRightOf`, `readAround`, `readInRegion`).
- `FesteStellen` (94) — `stellen-fest.txt`: Rahmen für `ar_stelle_*` aus dem Aufnahme-Werkzeug, skaliert mit Fenster.
- `Laufstatistik` (721) — Treffer/Klicks/Züge für die Vorschau, Run-Historie, Runs/h, Ø-Zeit je Modus.

### Vorlagen-Verwaltung
- `Vorlagenordner` (209) — Präfix→Unterordner-Regeln (`fuer(name)`, erste passende gewinnt, Rest `dungeon/knoepfe`).
  Für den Bot ist der Ordner egal; nur Aufnahme-Werkzeug/Lernbilder/Umsortieren nutzen es. Wichtige Präfixe:
  `ar_` Arena, `dc_`/`key_` Kathedrale, `cw_` Clan-Krieg, `ev_titan_` Titan-Event (muss vor `ev_` stehen),
  `ev_` Hammer-Event, `lh_` Leuchtturm, `ko_` Kolosseum, `tt_` Titanenturm, `gb_` Gem Bay, `cl_` Clanladen,
  `rs_` Burgstand, `fr_` Freunde, sonst Verlies (`unit_`, `digit_`, `waypoint_`, …).
- `VorlagenNachschub` (65) — kopiert aus Jar `/nachschub/liste.txt` fehlende Bilder nach templates/. Überschreibt
  NIE (Spieler-Aufnahmen bleiben). Neue mitgelieferte Vorlage ⇒ in `src/main/resources/nachschub/` ablegen UND
  in `liste.txt` eintragen.
- `Eigenbilder` (166) — sichtbarer Ordner `My pictures` (Arena lists/safe|almost-safe|doable|avoid|pause|never|clans,
  Arena/, Event rewards/, Cathedral/, Clan war/, Dungeon/). Matcher lädt ihn zusätzlich; gleicher Name → neuere Datei.
- `Ordnerordnung` (63) — versteckt alles im Bot-Ordner außer HustleBot.exe, Capture-Images.exe, 0-START-HERE.txt,
  My pictures. `ordner-offen.txt` = alles sichtbar (Autors PC). Nur Windows.
- `UpdateNachschub` (64) — `skriptAufJarStand`: legt `/skripte/*.ps1` neben den Bot; im Paket (jpackage,
  `jpackage.app-path`) bei Abweichung überschreiben (Sicherheits-Update 16.10.), beim Entwickeln nur wenn fehlend.
  `fassungInDerJar()` liest `/fassung.txt`.
- `ReinigerNachschub` (57) — Jar `/pc-reiniger/` → `<Bot>/PC-Cleaner` (fehlend oder älter ⇒ ersetzen).
- `ServerSparsam` (40) — `server-sparsam.txt`: kein Fenster, keine Wachen, lädt `gelernt/titanen|portal|stigma/` nicht.

### Texterkennung (OCR)
- `Texterkennung` (787) — Fassade. `lies(bild, sprache)`: erst `PaddleLeser` (wenn an+verfügbar und `sicher` ≥0,9
  je Zeile → fertig), sonst Windows-OCR (`liesWindows`), Paddle-Ergebnis nur, wenn Windows leer. `liesAuchKyrillisch`:
  Namenspipeline (Paddle "namen" → lateinisch → `ru` bei Zwillings-/Salat-Verdacht → `verbessern` mit anderen
  lateinischen/kyrillischen Sprachen → bis zu 14 weitere Schriften mit `schriftHinweis`-Priorisierung, Abbruch bei
  ≥3 sauberen Zeichen). `waehleZeilen`/`urteil`: kyrillisch NUR mit echtem Hinweis (Buchstabe ohne lat. Zwilling,
  Kapitälchen); im Zweifel lateinisch. `weisseSchrift(bild, zoom)`: Vorverarbeitung weiß-auf-farbig.
  `verfuegbareSprachen()` fragt Windows einmalig per PowerShell.
- `DauerLeser` (291) — hält `texterkennung-dauer.ps1` offen. Protokoll stdin: `sprache<TAB>pfad\n`; stdout: Zeilen,
  dann `--ENDE--`; Fehler `--FEHLER-- …`; Start meldet `--BEREIT--` bzw. `--STARTFEHLER--`; `--QUIT--` beendet.
  `synchronized` (seriell!), Mitlese-Faden + Queue, Auftrag 10 s, Start 20 s; nach 3 Fehlschlägen dauerhaft aus →
  `null` ⇒ Aufrufer fällt auf Einzelprozess `texterkennung.ps1 -Bild … [-Sprache …]` (40 s Grenze, stderr in
  Temp-Datei — NIE ungelesene Pipe, sonst Deadlock). Shutdown-Hook beendet PowerShell.
- `texterkennung.ps1` / `texterkennung-dauer.ps1` — liegen im Repo-Root (Arbeitskopie) UND identisch in
  `src/main/resources/skripte/` (Jar-Quelle). Beide Kopien gemeinsam ändern! Windows.Media.Ocr via WinRT-Reflection.
- `PaddleLeser` (766) — PP-OCR "rec"-Modelle als ONNX (onnxruntime), je Schrift ein Modell (lateinisch,
  kyrillisch, chinesisch(+groß), koreanisch, arabisch) unter `modelle/paddleocr/<schrift>/` oder Jar `/paddleocr/`.
  Schalter `paddleocr.txt` (Standard an). Modelle im öffentlichen Repo NICHT enthalten ⇒ `verfuegbar()` meist false.
  `lesungen` zerlegt in Zeilenbänder (`baender`), Nachbearbeitung (Arabisch umdrehen, Kana vereinheitlichen …).
  Globaler Lock auf `PaddleLeser.class`.
- `SchriftErkenner` (133) — winziges ONNX-Netz (30 Schriftklassen) wählt das Paddle-Modell für Namen;
  `modelle/paddleocr/schrift/erkenner.onnx` + `klassen.txt`. Eingabe 192×32 grau, normiert wie trainiert.
- `Onnxnativ` (59) — packt onnxruntime-DLLs einmal nach `natives/onnx-<bytesumme>/` und setzt
  `onnxruntime.native.path` (sonst 12,5 MB Temp-Müll je Start). VOR erstem `OrtEnvironment` aufrufen.
- `Glyphenschrift` (614) / `GlyphenZerlegung` (361) — OCR-freie Namens-„Fingerabdrücke“: Namensbild → Zeichen-
  Flecken → Raster (3 Größen) → Glyphen-Nummern (`glyphen/`, `glyphen.txt`, `glyphen/namen/`). `derselbeName`
  verzeiht 1 Fehler je 4 Zeichen. Genutzt von Arena/Gegnerbuch.
- `Lesarten` (206) — `arena-lesarten.txt`: „gelesen → kanonischer Name“, gilt ab 2 Bestätigungen, exakter Vergleich.
- `OcrTrainingsdaten` (161) — sammelt `logs/ocr-training/` + `labels.txt` für Paddle-Nachtraining; derzeit ohne
  Aufrufer außerhalb der Klasse.

### Eingabe, Menschlichkeit, Sicherheit
- `Input` (393) — alle Koordinaten relativ zur `clientArea` (vorher `setClientArea`, macht `BotContext.refresh`).
  `click(x,y[,jitter])`, `click(Hit)` (Jitter = min(w,h)/5), `tippeSofort`, `dauerklick` (Autoclicker, prüft
  `Mauswache.zeigerWeg` und `weiter`-Bremse, lässt Taste immer los), `drag`/`dragLangsam`/`dragSchnell`/`dragHoch`
  /`ziehenSofort`, `helligkeit` (eigene Mini-Aufnahme), `parkMouse`. Statisch `klickArtSetzen(druckMs, ruheMs)`
  (Kathedrale stellt bei verschluckten Klicks um). JEDE Bewegung über `bewege()` → meldet sich bei `Mauswache`.
  Keine Tastatur-Methoden.
- `Humanizer` (177) — `vary` (±30 %·Intensität, gelegentlich +250–1350 ms Zögern, min 60 ms), `reactionDelay`,
  `breakAfterRun`, Overshoot/Drift. In Kern deaktiviert.
- `Rechnertempo` (60) — `rechner-tempo.txt` (1..6), `mal(ms)` skaliert Pausen/Obergrenzen (VPS-Server).
- `Tempolerner` (132) — je Modus lernender Faktor: −0,1 % je fehlerfreier Runde, beim ersten Klick-Fehler eine
  Stufe zurück und „fest“. Datei je Modus (`"0,9512"` bzw. `"0,9531 fest"`). Nur Warnungen aus `KLICK_FEHLER`
  zählen als Fehler (`zaehltAlsFehler`). Minimum 0,5.
- `Mauswache` (237) — erkennt Menschen an der Maus: Zeiger >120 px neben letzter Bot-Stelle, zweimal binnen 2 s
  (oder direkt vor einer Bot-Bewegung) ⇒ Pause; 3 s Schonfrist nach Start, `ruheFuer(ms)` (Launcher klickt);
  nach 5 min Ruhe automatisch weiter. Schalter `mauswache.txt` (Standard an).
- `NotAusWache` (194) — Runnable, pollt `GetAsyncKeyState` alle 80 ms, Flanke ⇒ Callback. Bewusst NUR Anhalten,
  nie Starten (12.09.: globale Tasten starteten Bot versehentlich). Tasten F1–F12, A–Z, 0–9, Umlaute (OEM-Codes).
- `Wachhalten` (49) — eigener Dauerfaden mit `SetThreadExecutionState` solange Modus läuft/pausiert.
- `KonsoleHinten` (76) — Konsolenfenster minimieren ohne Fokus (auch Windows-Terminal-Reiter „HustleBot.exe“).

### Spiel-Prozess
- `GameLauncher` (642) — `sicherstellen(fenster)`: startet IMMER über MY.GAMES Launcher (`C:/MY.GAMES/MY.GAMES
  Launcher/MGL.exe`, URL `mygames://show/0.2002058/`), nie HC.exe direkt (sonst „Authentication error“, Token kommt
  vom Launcher). Sucht Knopf „Spielen“ per Farbe RGB 0/90/255 ±14 in rechter Hälfte, ≥1000 Punkte. Max 3 Aufrufe,
  20 s Abstand; vom Benutzer geschlossener Launcher bleibt zu bis `wiederErlauben()` (`!spiel`). `startLaeuft()`
  hält den SpielWaechter ab.
- `Spielneustart` (92) — alle 120 min Spiel neu (Schalter `spielneustart.txt`, Standard an); `spielBeenden()` =
  `destroyForcibly` auf `hc.exe` (Spielstand liegt auf dem Server). Der Ablauf selbst: `BotEngine.spielNeustartZwischenRuns`.
- `CrashDetector` (267) — Marker `logs/laeuft.marker` mit 30-s-Herzschlag. In dieser Fassung wird `start()` nicht
  aufgerufen; nur `expectShutdown()` vor Hitze-Abschaltung.

### Protokoll, Berichte, Statistik
- `Log` (211) — Level DEBUG/INFO/ACTION/WARN/ERROR; Ringpuffer 2000 Einträge (`snapshot`, `since`) + Datei
  `logs/bot_yyyy-MM-dd_HH-mm-ss.log` (UTF-8, append je Zeile) + stdout. Debug-Entprellung: gleiche Zeile (Ziffern
  ignoriert) unter den letzten 3 ⇒ 30 s stumm. `alarm` (→ `setMelder`, Discord), `hinweis` (→ `setHinweisMelder`),
  `letzterFehler(ms)`. Quelle wird via `Sprache.quelle` übersetzt.
- `Report` (384) — Fehlerbericht aus WARN/ERROR mit Kontext → `logs/bericht_<stamp>.txt`; `arbeitetSichtbar`,
  `klickSchleife` (Hänger-Heuristiken für BotEngine), `merkeZeitbericht`.
- `Beleg` (72) — Gate für Beweisfotos: schreibt nur bei `beweisfotos.txt` = an, sonst no-op/false. Alles, was der
  Bot nie wieder liest, MUSS hier durch (`Beleg.schreiben` statt `ImageIO.write`/`Imgcodecs.imwrite`).
- `Fotobremse` (376) — Aufräumen beim Start (Hintergrundfaden): Fotos in logs (außer `behalten`), Fotoserie >3 Tage,
  `logs/arena-fotos` >14 Tage, `bot_*.log` >7 Tage (außer `protokolle-behalten.txt`), `held-N.png`, Gegner-Aussehen
  in `arena-aussehen/` >30 Tage ungesehen (nie `_eigenes_team_-`), eigene Temp-Reste >6 h (Muster `TEMP_REST`).
- `Fotoserie` (89) — `!fotoserie N` oder Datei `fotoserie.txt` (Minuten, wird gelöscht): speichert die ohnehin
  aufgenommenen Bilder (≤1 je 500 ms) nach `logs/fotoserie/f_NNN_HHMMSS.png`. Aufruf in `BotContext.refresh`.
- `Verlauf` (164) — Strom (kWh/€), Spitzenwerte je Lauf aus `SystemMonitor.Sensors`.
- `Laufzaehler` (58, `lauf-zaehler.txt`), `Laufzeiten` (216, `laufzeiten.txt`, Ø je Modus für ETA in !stats),
  `Meldetakt` (53, `meldungen.txt`, 1|5|10), `KommentarPakete` (83, `arena-kommentare/offen.txt` → `paket-*.txt`).

### Dateien, Einstellungen, Sprache, Zeit
- `Ordner` (63) — `basis()` = Ordner der .exe (jpackage `jpackage.app-path`) sonst CWD; `bei(name)`.
- `Dateien` (63) — `zeilen/text`: entfernt UTF-8/UTF-16-BOM, liest UTF-16, ungültiges UTF-8 als windows-1252.
  Für jede von Windows/PowerShell beschreibbare Datei benutzen.
- `Schalterdatei` (78) — an/aus-Datei: erste Nicht-#-Zeile; an = an|on|ja|yes|true|1|ein, aus = aus|off|nein|no|
  false|0; fehlt/unklar ⇒ Standard. `setzen` schreibt Kommentar + Wert.
- `Dauerwerte` (74) — `dauerwerte.properties` (stats.minuten, wach.minuten, ende.*, aus.*); sofort geschrieben.
- `Konten` (216) — Mehrkonten: aktive Dateien im Bot-Ordner, ruhende unter `konten/<name>/`, Tausch per Datei-Move.
  Schalter `konten.txt`, aktiv in `konto-aktiv.txt`. Neuer Name zählt erst nach 2 gleichen Lesungen.
- `Sprache` (253) — `Sprache.t(englisch, deutsch)` überall, Standard EN, `sprache.txt`; `zahl`, `komma`, `quelle`
  (Log-Quellen-Übersetzung über `QUELLEN`-Map).
- `Zeitzone` (278) — `zeitzone.txt`, Standard Europe/Berlin (Spiel-Reset 02:00 Berlin). ALLE Zeitpläne über
  `Zeitzone.jetzt()/heute()/uhrzeit()`, nie `LocalDateTime.now()`. Darf beim Laden NICHT loggen (Log→Zeitzone-Zyklus).
- `Bezahlung` (371) — Bezahlart + Nahrungslimit je Modus (`bezahlung.txt`); Diamanten gibt der Bot nie aus.

### Rechner/Strom
- `SystemMonitor` (614) — CPU/RAM/Platte aus JVM, Temperatur/Lüfter/Watt via LibreHardwareMonitor-JSON (alle 15 s);
  `ensureRunning` startet geplante Aufgabe (in Freunde-Fassung nicht genutzt). `cleanOldLogs`.
- `HitzeWaechter` (198) — Stufen 75/80/83 °C mit Verlauf/Ursache, Rückstufung erst 4 °C darunter, `hitze-stufe.txt`.
  Ab Stufe 2 bremst BotEngine. Abschaltung liegt in `BotEngine.abschaltGrenze` (`TEMP_AUS_CELSIUS`, laut Kommentar 90 °C;
  höher nur bei `hitze-bau-95.txt` + Bau-Prozess) — nur Autor ändert sie.
- `Rechenlast` (75) — Top-Prozesse nach CPU-Zeit-Delta (Namen zusammengefasst).
- `PowerControl` (229) — `shutdown /s /t N /c grund` mit Abbruchfrist (`/a`), `restart`, `cancel`, Monitor an/aus.

### Modus-Zeitpläner (je 1 Zeile, Details in den Modus-Docs)
`Kathedralzeiten` (Run-Zeiten Kathedrale), `Turmplan` (Titanenturm 02:00/12:00), `Energieplan` (Leuchtturm/
Kolosseum-Energie 21/100 min), `Eventautomatik` (Hammer-Event-Kreislauf), `Eventkisten` (welche Kisten),
`Kriegsplan` (Clan-Krieg-Fenster), `Stigmaplan` (Stigma-Regeln), `GemBayplan` (02:00/12:00), `Titanende`
(Event-Ende), `Burgenplan` (alle 30 min), `Ladenplan` (Clanladen), `Freundeplan` (Geschenke 02:00/12:00),
`Thronsaal` (Stufe je Konto sperrt Modi), `Zauberstabziel` (Stab-Grenze), `Tagestipp` (Discord-Tipp).

### Arena (siehe bot/CLAUDE-arena.md)
`Arena*.java` (ArenaSperre, ArenaSperrBilder, ArenaListenOrdner, ArenaListenNetz, ArenaVorlagenDoppelt,
ArenaZusammenfuehren, ArenaVerlierer, ArenaPlaetze, ArenaAufnehmen, ArenaExtras, ArenaSchalter, ArenaRegelnNeu),
`Gegnerbuch` (1912), `NamensVergleich`, `Namensbilder`, `ListenBildVergleich`, außerdem arena-nah `SelbstKaempfe`.

## Matcher im Detail

Vorlagen: alle `*.png` rekursiv aus templates/, dann `gelernt/` (Lernmodus !fix, relativ zum CWD), dann
`My pictures`. Name = Dateiname ohne `.png`, Ordner egal. Geladen mit `IMREAD_COLOR` (BGR, Alpha ignoriert); Pfade
mit Nicht-ASCII über `imdecode(Files.readAllBytes)` (OpenCV öffnet unter Windows keine Umlaut-Pfade). Doppelter
Name ⇒ neuere Datei (mtime) gewinnt; Warnung nur, wenn eine Seite aus „My pictures“ stammt. `templates/gelernt/eigen/`
(`EIGEN_ORDNER`) überschreibt immer, Original bleibt in `originale` als Rückfall.

Vergleich immer `TM_CCOEFF_NORMED`, `DEFAULT_THRESHOLD` 0,84. Ablauf `find(screen, name, schwelle)`:
1. `bestHit`: Cache `frameBest` je **Mat-Identität** (`screen != frameScreen` ⇒ leeren). Bestwert ohne Schwelle,
   Schwelle prüft erst `find`.
2. `KAESTCHEN` (feste Arena-Rechtecke bei 1159×770, skaliert): Vorlage NUR dort suchen.
3. `computeBest`: bekannte Stellen (`stellen`, ≤4 je Vorlage, `{x,y,bildB,bildH}`) ⇒ Kasten ±60 px. Treffer ≥0,80
   ⇒ fertig (+ `bestaetigt`, ggf. `weitereStelleMerken` ab 12 px Abstand). Noch nicht in dieser Sitzung bestätigt ⇒
   ganzes Bild. Bestätigt und leer ⇒ „nicht da“ (schwachen Kastenfund zurückgeben), nur jeder 25. Fehlschlag
   (`VOLLPRUEFUNG_JEDER`) sucht ganz.
4. `ganzesBild`: Sparregel für nie gesehene Vorlagen (nur Bild-Faden + Grau erlaubt oder `SELTEN_AUCH_IN_FARBE`):
   nach 10 Abfragen nur jede 6. wirklich rechnen. Graustufen, wenn `setGrauErlaubt` (BotEngine: nur cathedral/
   dungeon), Aufruf im Bild-Faden und nicht `nurFarbe(name)`. Treffer ≥0,84 ⇒ `stelleMerken`. `*_node` und
   Vorlagen mit >4 Stellen sind „wandernd“ (`wandert`, Datei-Wert `*`) ⇒ immer ganz.
5. `find` ⇒ `report` an Laufstatistik und `eigenVielleicht` (nur wenn `EIGEN_ORDNER` gesetzt): ab 0,95, zweite
   Sichtung ≥1 s später ≥0,97 gleich ⇒ als eigene Vorlage speichern. Nie bei `quick_attack`, `to_battle` und Namen
   mit grau/gelb/grey/gray. `eigeneZuruecknehmen` (BotEngine nach 2 min ohne Fortschritt) löscht alle eigenen.

Weitere APIs: `findImBereich` (Rechteck, ohne Cache/Stellen), `findAllSchnell` (halbes Bild vorsuchen, voll
nachprüfen; Vorschwelle ~0,1 unter Schwelle), `findAll`/`findAllGrau` (mehrere Treffer, Cache je Bild+Schlüssel),
`findFirst`, `visible`, `findOberteil` (nur obere keepY-Anteile), `findCore` (Kern + Skalen), `weissBild`/`hellBild`
+ `weisseSchrift` (Maskenvergleich für weiße/helle Schrift, z. B. Freundesnamen im Chat), `kaesten` + `imAusschnitt`
(Kastenblick ohne Vollbild-Umrechnung), `serie(grund)` (grund, grund_1.._N numerisch sortiert), `has`,
`liegtBereit` (Datei existiert, auch ungeladen), `missing`, `sizes`, `vorlageNachladen`, `vergleichsBericht`.

Zustands-Hooks von außen: `neuesBild()` (jede Aufnahme in BotContext; setzt `bildFaden`, löscht Grau-/findAll-Cache),
`stellenVergessen()` (Fenstergröße >16 px anders), `bestaetigungVergessen()` (≤16 px, Karla 17.10.),
`naechstesBildGruendlich()` (BotEngine nach 3 unerkannten Bildern), `stellenSpeichern(STELLEN_DATEI)` (nach jedem
Run und im finally der Hauptschleife), `stellenLaden` (Start).

## Regeln, Invarianten, harte Lektionen

- **Fenstergröße heilig**: Vorlagen passen nur bei Innenmaß 1159×770. Keine Skalierung der Vorlagen einbauen; bei
  Abweichung Fenster korrigieren (GameWindow) bzw. Windows-Skalierung 100 % (PC-Check). `uiScale=1` muss vor jeder
  AWT-Nutzung stehen.
- **Fenstersuche**: Titel exakt („Hustle Castle  “ mit Leerzeichen, getrimmt) oder Präfix + Trenner `:`/`-`/`–`/`—`.
  Nie `contains` (sonst Discord/„Hustle Castle Bot“-Fenster). „ritterspiele“/„google play“ ausschließen.
- **Kastensuche nicht aufweichen**: geladene Stellen sind nur „schneller erster Blick“ bis zur Bestätigung in der
  Sitzung (25.09. verpasster Clan-Krieg). „Nicht im Kasten = nicht da“ gilt nur für bestätigte. Weltkarten-Knoten
  (`*_node`) immer ganz suchen (23.09. Kathedrale „nicht gefunden“).
- **Verkleinerte Vorsuche** ist gemessen und verworfen (Score-Bänder überlappen). Nicht erneut vorschlagen.
- **Grau nur wo sicher**: End-journey grün/rot, Ziffern, `cw_*`, Arena-Banner/Artefakte/Totems/Sieg/Niederlage in
  Farbe (`NUR_FARBE`/`nurFarbe`). Arena insgesamt in Farbe (Kraft-Schwerter nicht gefunden in Grau).
- **Sparregeln nie für** `other_device`/`update_button` (Schutz, wenn Autor selbst am Handy spielt) und
  `ar_victory`/`ar_defeat` (Leuchtturm entscheidet mit einem Blick).
- **Thread-Sicherheit**: Matcher-Methoden `synchronized`; Grau/Caches/Sparregel nur im Bild-Faden (`bildFaden`),
  Discord-Fäden suchen in Farbe und ohne Cache. `names()` liefert Kopie. Während `loadAll` wartet `vorlage()` bis 3 s.
- **Mat-Speicher**: jede `new Mat(…)`/Sub-Mat-Sicht explizit `release()` (auch ROI-Köpfe; nächtliche Lecks).
  `WeissBild` per try-with-resources schließen.
- **Prozesse**: stderr von Kindprozessen nie als ungelesene Pipe lassen (Datei oder `redirectErrorStream`).
- **OCR seriell**: `DauerLeser.lies` und Paddle sind global gelockt; Mehrfach-Sprachen kosten je Aufruf PNG-Schreiben
  + Dekodieren (Perf-Notiz 1a–1f). Kyrillisch-Entscheidung im Zweifel lateinisch (Autor 11.10.).
- **Dateien wachsen nicht** (Autor 01.11.): Beweisfotos nur über `Beleg`, Temp-Reste über `Fotobremse.TEMP_REST`
  bekannt machen, onnxruntime über `Onnxnativ`.
- **Sprache**: jeder nutzer-sichtbare Text als `Sprache.t("en", "de")`; Log-Quellen deutsch lassen und ggf. in
  `Sprache.QUELLEN` ergänzen.
- **Zeit**: Zeitpläne über `Zeitzone`. (`Log`-Zeitstempel/Dateinamen nutzen dagegen `LocalDateTime.now()`.)
- **Pfade**: neue Dateien über `Ordner.bei(...)`. Viele ältere Klassen nutzen `Path.of("x.txt")` relativ zum CWD
  (Laufzeiten, Laufzaehler, Meldetakt, Matcher.GELERNT, Glyphenschrift.ORDNER, CrashDetector, Report …). `Ordner`
  setzt zwar `user.dir`, aber das JDK cached das Arbeitsverzeichnis beim Start — relative Pfade folgen dem NICHT
  zuverlässig. Bei .exe-Start über Verknüpfung können diese Dateien also woanders landen.
- **Schalterdateien** mit BOM: `Schalterdatei` entfernt `\uFEFF`; andere Leser über `Dateien.zeilen`.
- **Not-Aus** nur stoppen, nie starten. Mauswache-Schwelle 120 px/2 Sichtungen bewusst „resistent“ (Autor 21.10.).
- **Launcher**: nie HC.exe direkt starten; geschlossenen Launcher respektieren.
- `Log.error(…, Throwable)` druckt Stack-Zeilen nur für Klassen mit Präfix `de.autor` — passt nicht zum Paket
  `de.hustlebot`, daher erscheinen derzeit keine Stack-Frames (Relikt der Umbenennung).

## Laufzeit-Ordner (Bot-Ordner = `Ordner.basis()`)

```
HustleBot.exe, Capture-Images.exe, 0-START-HERE.txt   sichtbar
My pictures/            Eigenbilder (Arena lists/…, Arena/, Event rewards/, Cathedral/, Clan war/, Dungeon/)
templates/              mitgelieferte Vorlagen (+ Nachschub), templates/gelernt/ (Lernbilder), templates/gelernt/eigen/
gelernt/                Lernmodus-Ausschnitte (!fix), auch Altlast-Arena-Listen
logs/                   bot_<stamp>.log, bericht_<stamp>.txt, laeuft.marker, fotoserie/, arena-fotos/, ocr-training/
glyphen/                Glyphen-Bibliothek (N.png, glyphen.txt, namen/)
modelle/paddleocr/      <schrift>/rec.onnx+dict.txt, schrift/erkenner.onnx+klassen.txt (optional)
natives/onnx-<n>/       onnxruntime-DLLs
konten/<name>/          ruhende Kontodateien (+ gegner/<liste>)
arena-aussehen/, arena-kommentare/, PC-Cleaner/
texterkennung.ps1, texterkennung-dauer.ps1, config.properties, vorlagen-stellen.txt, stellen-fest.txt
*.txt-Schalter/Zustände: sprache, zeitzone, rechner-tempo, server-sparsam, mauswache, spielneustart, paddleocr,
  beweisfotos, protokolle-behalten, ordner-offen, fotoserie, konten, konto-aktiv, hitze-stufe, meldungen,
  laufzeiten, lauf-zaehler, bezahlung, kathedrale-zeiten, arena-lesarten, dauerwerte.properties, Planer-Dateien …
```
Einen Ordner `aufnahmen/` legt core nicht an. Temp: `hustle-chat-*.png`, `hustle-ocr-*.txt`,
`hustlebot-paddleocr/` (Modelle aus der Jar).

## Tests (src/test/java/de/hustlebot/core, JUnit 5)

Reine Logik ohne Windows: `DateienTest`, `SpracheTest`/`SpracheAltesPaketTest`, `ZeitzoneTest`, `RechnertempoTest`,
`TempolernerTest`, `MauswacheTest`, `HitzeWaechterTest`, `BelegTest`, `FotobremseTest`/`FotobremseBehaltenTest`,
`OrdnerordnungTest` (nur Windows, `assumeTrue`), `ServerSparsamTest`, `EigenbilderTest`, `KontenTest`,
`OnnxnativTest`, `KommentarPaketeTest`, `LesartenTest`, `OcrTrainingsdatenTest`, `ReportFlussTest`,
`TexterkennungSchriftTest`/`NamenSchriftTest` (Kyrillisch-/Schriftlogik ohne OCR), `PaddleLeser*Test`
(Nachbearbeitung; Modelle per `assume`), `GlyphenschriftTest` (+ `GlyphenschriftPruefung` Messung),
Planer-Tests (`KriegsplanAlleZonenTest`, `KriegsplanZeitumstellungTest`, `FreundeplanTest`, `BurgenplanTest`).
Matcher mit OpenCV (`nu.pattern.OpenCV.loadLocally()` im `@BeforeAll`): `VorlagenDoppelNamenTest`,
`bot/ArenaKaestenTest` (KAESTCHEN-Skalierung), `bot/EventVorlagenSchwellenTest`. `RessourcenVorhandenTest`: jeder
im Code als `"/…/x.png|txt|ps1…"` genannte Ressourcen-Pfad muss in der Jar liegen. `bot/SpeicherTest` läuft
separat (surefire-Execution `speicher-pruefung`, eigenes Arbeitsverzeichnis). Ausführen: `mvn test`.

## Rezepte

- **Neue Vorlage nutzen**: PNG in templates/<Ordner nach Vorlagenordner>, im Code `ctx.find(…, "name")`. Für Freunde
  mitliefern ⇒ auch `src/main/resources/nachschub/<pfad>` + `nachschub/liste.txt`. Feste Lage in der Arena ⇒
  `Matcher.KAESTCHEN` (Koordinaten bei 1159×770). Farbsensibel ⇒ `NUR_FARBE`/`nurFarbe`. Selten ⇒ ggf.
  `SELTEN_AUCH_IN_FARBE`.
- **Vorlage wird „nicht gefunden“, obwohl sichtbar**: `vorlagen-stellen.txt` (falsche gemerkte Stelle; Zeile löschen),
  Doppelnamen im Log (neuere Datei gewinnt), `templates/gelernt/eigen/<name>.png` (eigene Aufnahme), Grau vs. Farbe.
- **Schwelle/Vergleich ändern**: `Matcher.DEFAULT_THRESHOLD`, `KASTEN_VERTRAUEN`, `KASTEN_LUFT`; Messwerte in Kommentaren.
- **Neuer An/Aus-Schalter**: `new Schalterdatei(Ordner.bei("x.txt"), "Name", standard)`; Datei für Discord/!settings
  in den Steuer-/Discord-Code eintragen.
- **Neue Einstellungsdatei lesen**: `Dateien.zeilen(Ordner.bei(...))`.
- **OCR ändern**: Java-Seite `Texterkennung.lies`/`liesWindows`, Protokoll `DauerLeser` ⇄ `texterkennung-dauer.ps1`
  (Root UND resources/skripte). Fallback-Einzelpfad muss erhalten bleiben.
- **Klick-/Zugverhalten**: `Input` (Zeiten in `drag(...)`-Parametern), Pausen `BotContext.sleep` → `Humanizer.vary`
  + `Rechnertempo.mal`; je Modus lernend `Tempolerner`.
- **Aufnahme beschleunigen**: `ScreenCapture.toMat`, `BotContext.refresh/grabSmall` (Perf-Notiz 2a–2c).
- **Fenster/Andocken**: `GameWindow.prepare`, `innenPruefen`; Launcher-Klickfarbe `GameLauncher.KNOPF_*`.
- **Temperatur/Abschaltung**: Stufen `HitzeWaechter`, Abschaltung `BotEngine` (`abschaltGrenze`, `TEMP_AUS_MINUTEN`).
- **Logging**: `Log.info/warn/action/debug(quelle, Sprache.t(en, de))`; nach Discord: `Log.alarm` / `Log.hinweis`.
- **Aufräumregeln**: `Fotobremse` (Tage-Konstanten, `behalten`, `TEMP_REST`).
