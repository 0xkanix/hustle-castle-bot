# Start, Konfiguration und Paketbau

Betrifft: `src/main/java/de/hustlebot/{Main,Kern,Einrichtung}.java`, `pom.xml`, `*.bat`, `config.properties`, `settings.properties`, `capture-images.properties`, `src/main/resources/fassung.txt`. Bot läuft nur unter Windows (64 Bit, Java 21); unter Linux nur `mvn test` sinnvoll.

## Einstiegspunkte
| Klasse | Gestartet von | Zweck |
|---|---|---|
| `de.hustlebot.Main` | Jar-Manifest (Shade), `run.bat`, **HustleBot.exe** (jpackage; `HustleBot.cfg` nennt diese Klasse — Name und `main` NICHT ändern) | `Main.main` → `Kern.main`. Hält `FASSUNG`, `bau()`, `spracheAusDatei()`. |
| `de.hustlebot.Kern` | `Main` | Eigentliche Startlogik (s. u.). |
| `de.hustlebot.Einrichtung` | **Settings.exe** (`settings.properties`: `main-class=de.hustlebot.Einrichtung`, `win-console=false`) | Swing-Dialog: Bot-Token, Kanal-ID, Not-Aus-Taste, Sprachwahl. |
| `de.hustlebot.tools.TemplateCapturer` | **Capture-Images.exe** (`capture-images.properties`: `win-console=true`), `capture.bat` | Vorlagen aufnehmen. |
| `de.hustlebot.tools.Selbstpruefung` | `paket-bauen.ps1` nach dem Kompilieren | Prüft Vorlagen-Namen Bot ↔ Capture-Images; bei Fehler keine ZIP. |

Alle drei GUI-Klassen setzen im `static`-Block `sun.java2d.uiScale=1` (16.10.: bei 125/150 % Windows-Skalierung lagen Fotos/Klicks daneben). Muss vor jedem AWT-Zugriff laufen — nicht entfernen/verschieben.

## Startsequenz `Kern.main`
1. `Ordner.basis()` — Bot-Ordner = Ordner von `System.getProperty("jpackage.app-path")` (die .exe), sonst Arbeitsverzeichnis; setzt `user.dir` (relative Pfade wie `titan-hc.txt`, `kuerzel.txt` landen dadurch richtig). Grund: Start über Desktop-Verknüpfung fand config.properties nicht.
2. `Log.init(heim/logs)`; `Fotobremse.imHintergrund()` (alte Beweisfotos in logs klein halten); `Sprache.set(Main.spracheAusDatei())` (`sprache.txt`, ohne Datei EN seit 3.1).
3. Log "Hustle Castle Bot <FASSUNG> (Bau <bau()>) startet", `Ordnerordnung.aufraeumen(heim)` (versteckt alles außer HustleBot.exe, Capture-Images.exe, 0-START-HERE.txt, "My pictures"; nur Windows; `ordner-offen.txt` schaltet ab).
4. `Aufstellung.konfigurieren(aufstellung.properties)`.
5. `ladeConfig()` → `config.properties` (fehlt ⇒ Standardwerte, Discord aus). Werte: `window.title|width|height` (Hustle Castle/1175/809), `bot.idleTimeoutMinutes` (5), `bot.tickDelayMillis` (**auf max. 150 gekappt**: `Math.min(150, …)`), `log.debug` (true), `templates.dir` (templates).
6. `nu.pattern.OpenCV.loadLocally()` — Fehler ⇒ `warteAufTaste()` + Ende. Headless ⇒ Ende.
7. `GameWindow`, `ScreenCapture`; `Matcher.EIGEN_ORDNER = templates/gelernt/eigen`; `Eigenbilder.einrichten` + `Matcher.EIGENE_BILDER` ("My pictures"); `ArenaVorlagenDoppelt.aufraeumen`; `new Matcher(vorlagen)`.
8. `Humanizer` immer aus (`human.*`-Keys in config.properties werden NICHT gelesen — toter Ballast), `Input(new Robot(), mensch)`.
9. `VorlagenNachschub.liefern` (fehlende Bilder aus Jar-`nachschub/` nach templates, Liste `nachschub/liste.txt` wird von paket-bauen.ps1 erzeugt), `ReinigerNachschub.liefern` (PC-Cleaner-Ordner), Vergleich `UpdateNachschub.fassungInDerJar()` (`/fassung.txt`) mit `Main.FASSUNG` ⇒ Warnung bei Abweichung.
10. `matcher.loadAll()`, `Matcher.STELLEN_DATEI = vorlagen-stellen.txt` + `stellenLaden`; 0 Vorlagen ⇒ Warnung.
11. `BotContext(fenster, kamera, matcher, eingabe, new Laufstatistik())`, `BotEngine` (Idle-Timeout, Takt, `bot.requireAllTemplates`), `Wachhalten.starten` (PC schläft nicht solange LAEUFT/PAUSIERT).
12. Discord: `DiscordClient.configure(discord.enabled, "", token, channel)`, `DiscordBridge(...)`, `Steuerprogramm.vorbereiten(...)` (VOR `bruecke.start()`), `bruecke.setSteuerprogramm`, `bruecke.start()`. Keine globalen Hotkeys außer Not-Aus (13.09.: `^`/`Q` wirkten systemweit).
13. `ctx.setDiscordHinweis`, `bruecke.notAusStarten(notaus.taste|F9)`, `notPauseStarten(notpause.taste|F10, weiter.taste|F11)`, `mauswacheStarten()` (nicht bei `ServerSparsam.an()` = `server-sparsam.txt`), `ArenaBeobachter.starten`, `ArenaSchicht.starten` (nur mit `arena-schicht.txt` an).
14. Shutdown-Hook → `bruecke.stop()`. Log "Bereit".
15. `KonsoleHinten.minimieren()` — erst JETZT (Startfehler sollen lesbar bleiben; sucht Konsolenfenster mit Titel-Ende "HustleBot.exe").
16. `SpielWaechter` (Thread `spiel-waechter`: beendet Bot, wenn Spiel 5 min zu), `BotFenster.zeigen` (nicht bei ServerSparsam), `steuer.zeigen(waechter)`.
17. `Thread.currentThread().join()` — Main-Thread wartet ewig; alles läuft in eigenen Threads. Jede Exception ⇒ Log + `warteAufTaste()`.

## Version
- `Main.FASSUNG = "3.142"` und `src/main/resources/fassung.txt` = `3.142` müssen gleich sein (sonst Start-Warnung "beim Bau ist etwas schiefgegangen (paket-bauen.ps1)"). Beim Versionssprung BEIDE ändern + `discord/NeuerungenFreunde` (oberster Eintrag derzeit "3.3"; Vorsicht: `nummer("3.142")`=3142 > 3003).
- `Main.bau()`: Zeitstempel des Jar-Eintrags `de/hustlebot/Main.class` ("dd.MM. HH:mm"), sonst mtime der Code-Quelle, sonst "?". Grund (21.10.): Freunde liefen tagelang auf altem Bau, Berichte zeigten nur "3.3".

## Einrichtung (Settings.exe)
- `main`: Sprache laden, `zeigen(config.properties, null)` (modal, `invokeAndWait`), bei Speichern Hinweis "Starte HustleBot.exe", `System.exit(0)`. Wird im Freunde-Start NICHT automatisch aufgerufen (Kern ruft `zeigen` nicht); Discord ist freiwillig.
- `pruefen(token, kanal)`: beide leer = ok; nur eines ⇒ Fehler; Token ≥50 Zeichen + Regex `TOKEN` (zwei Punkte; fängt Application-ID/Client-Secret ab); Kanal `\d{17,20}`.
- `schreiben()`/`einzelnSchreiben()`: zeilenweise ersetzen (ISO-8859-1), Kommentare bleiben (kein `Properties.store`!), Schreiben in `config.properties.neu` + `ATOMIC_MOVE`. `discord.statsChannelId` wird mitgesetzt, außer dort steht bewusst eine andere ID. Not-Aus-Taste via `NotAusWache.gueltig`. `einzelnSchreiben` wird auch von `DiscordBridge.notAusEinstellen` benutzt.
- Fenster: Token als `JPasswordField` (03.11.: Token stand lesbar auf Screenshot ⇒ `EinrichtungTokenTest`), blaues "i" je Feld öffnet Bildhilfe `src/main/resources/hilfe/<thema>-<n>.png|jpg` (fehlt Bild ⇒ nur Text), Sprachwahl schreibt `sprache.txt`, `setAlwaysOnTop`, ESC schließt; Absturz wird geloggt statt verschluckt (12.09.).

## Konfigurationsdateien (Repo-Wurzel = Bot-Ordner beim Entwickeln)
- `config.properties`: `discord.botToken` (LEER lassen, nie Token committen), `discord.channelId`, `discord.statsChannelId`, `discord.enabled=true`, `window.*`, `templates.dir`, `bot.tickDelayMillis=150`, `bot.idleTimeoutMinutes=5`, `bot.requireAllTemplates=true`, `log.debug=true`, `human.*` (ungenutzt). Weitere, optional: `discord.erlaubteNutzer`, `notaus.taste`, `notpause.taste`, `weiter.taste`. Nie mit Word öffnen (Kommentar in Datei).
- `settings.properties` / `capture-images.properties`: jpackage `--add-launcher`-Dateien (main-class, win-console) für Settings.exe / Capture-Images.exe.
- `.gitignore`: target, logs, aufnahmen, fehlerberichte, arena-*, `befehl.txt`, `geheim.properties`, `config.local.properties`, `src/main/resources/paddleocr/`.

## Build (pom.xml)
- `de.autor:hustle-bot:1.0.0`, Java 21, `finalName` hustle-bot ⇒ `target/hustle-bot.jar` (Shade, Main-Class `de.hustlebot.Main`, ServicesResourceTransformer).
- Deps: `org.openpnp:opencv 4.9.0-0` (native Libs gebündelt), `jna-platform 5.14.0`, `jackson-databind 2.17.2`, `onnxruntime 1.22.0` (PaddleOCR-ONNX, Modelle nicht im Repo), `junit-jupiter 5.14.4` (test).
- Shade-Filter: Signaturen raus; OpenCV linux/osx/windows-x86_32 raus (269 MB → 49,6 MB), onnxruntime linux/osx + `*.pdb` raus. **Jar läuft deshalb nur auf Windows x64** — unter Linux `mvn package` baut, aber `java -jar` scheitert an OpenCV.
- Surefire: `SpeicherTest` ausgeschlossen im Standardlauf, eigene Execution `speicher-pruefung` mit `workingDirectory=target/speicher-pruefung` (schreibt Einstellungsdateien, sonst echte überschrieben).
- Tests unter Linux: `mvn test` (headless; Einrichtungstest setzt `java.awt.headless=true`).

## Bat-Dateien
- `build.bat`: `mvn clean package`, dann Hinweis capture.bat → run.bat.
- `run.bat`: `java -Xmx192m -Xms32m -XX:+UseSerialGC -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10 -XX:ReservedCodeCacheSize=48m -Xss512k -jar target\hustle-bot.jar`. Speicherbremse (06.09.: 3,3 GB im Task-Manager; 02.10. von 512m auf 192m; "im Paket 1g"). Achtung `PaddleLeser`: 135 MB Modelle sprengten -Xmx192m.
- `capture.bat`: Java-Suche JAVA_HOME → `C:\Program Files\Eclipse Adoptium\jdk-21*` → PATH (Autors PC hat zusätzlich altes Java 8), `-cp target\hustle-bot.jar de.hustlebot.tools.TemplateCapturer templates`, Fenster bleibt offen.
- `PC-Check.bat`: Batch mit eingebettetem PowerShell ab Marke `#PS-START` (Pfad über Env `HB_SELF`), ändert nichts, schreibt `PC-Check-Ergebnis.txt`. Prüft Windows/64 Bit/PS-Version, Bildschirm ≥1175×809 + 100 % DPI, MY.GAMES Launcher `MGL.exe`, Windows-OCR, Speicher, Discord erreichbar, Energiesparen, Bot-Ordner (HustleBot.exe, Settings.exe, Capture-Images.exe, `runtime\bin\javaw.exe`, texterkennung.ps1, config.properties). `HB_LANG=de|en`, `HB_NOPAUSE=1` (vom Installer genutzt).
- `texterkennung.ps1`, `texterkennung-dauer.ps1`: Windows-OCR-Skripte; Originale in `src/main/resources/skripte/`, `UpdateNachschub.skriptAufJarStand` legt sie im Paket bei Abweichung neu ab (Sicherheit 16.10.), beim Entwickeln nur wenn fehlend.

## Echte Distribution (nicht im Repo)
Gebaut von Autors `paket-bauen.ps1` (fehlt im Repo) mit **jpackage** als App-Image + ZIP: `HustleBot.exe` (Konsole, Main), `Settings.exe` (Einrichtung), `Capture-Images.exe` (TemplateCapturer), `app\` (Jar + `HustleBot.cfg` mit Main-Klasse/Java-Optionen), `runtime\` (gebündeltes JRE), `0-START-HERE.txt`, `templates\` (`PC-Cleaner\` legt der Bot selbst per `ReinigerNachschub` an). Erkannt am Property `jpackage.app-path` (`Ordner`, `UpdateNachschub.imPaket`, `!autostart`). `Install.bat` (ebenfalls nicht im Repo) legt Autostart-Verknüpfung `Hustle Castle Bot.lnk` und `fenster-nach-vorn.txt` an. paket-bauen.ps1 ruft `Selbstpruefung` und erzeugt `nachschub/liste.txt`. Kein launch4j.

## Tests
`src/test/java/de/hustlebot/EinrichtungTokenTest.java` — Token-Feld ist `JPasswordField` mit Echo-Zeichen. `pruefen`/`schreiben` sind package-private und ohne Fenster testbar (bewusst so gebaut).

## Rezepte
- Neuer config-Key: in `Kern` lesen (`config.getProperty`/`zahl`), Default + Kommentar in `config.properties`; Schreiben aus Code nur über `Einrichtung.einzelnSchreiben` oder `DiscordBridge.configWertSchreiben` (kommentarerhaltend).
- Neue Startkomponente: in `Kern.main` VOR `KonsoleHinten.minimieren()` einhängen; Fensterkram hinter `ServerSparsam.an()`-Prüfung.
- Version erhöhen: `Main.FASSUNG` + `src/main/resources/fassung.txt` + `NeuerungenFreunde`.
- Settings-Fenster erweitern: `Einrichtung.Fenster.aufbauen/abschnitt`, `TITEL/SCHRITTE(_EN)`, Bilder in `resources/hilfe/`.
