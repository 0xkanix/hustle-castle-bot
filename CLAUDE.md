# Hustle Castle Bot – CLAUDE.md (Root)

Java-21/Maven-Bot für das PC-Spiel Hustle Castle (MY.GAMES Launcher, **nur Windows**). Sieht nur auf den Bildschirm:
Fensterfoto → OpenCV-Vorlagensuche + Windows-OCR → Mausklicks. Kein Speicherlesen. ~137k Zeilen, 398 Klassen, 174 Testklassen.
Bezeichner/Kommentare **deutsch** ("Autor" = Originalautor). Dies ist die **Freunde-Fassung** (abgespeckt ggü. Autors Hauptbot;
Kommentare "Freunde-Bot: …", `Tagestipp.FREUNDE = true`). Entwicklung hier unter Linux: nur bauen + Tests, nicht ausführen.

## Teildokumente (vor Arbeit im jeweiligen Bereich lesen)

| Bereich | Datei |
|---|---|
| Start, Build, Packaging (.exe), Config-Dateien | `docs/claude/START-UND-BAU.md` |
| Motor, Modus-Vertrag, alle Modi (Index) | `src/main/java/de/hustlebot/bot/CLAUDE.md` |
| Arena (größter Teil, inkl. core/Arena*, Gegnerbuch) | `src/main/java/de/hustlebot/bot/CLAUDE-arena.md` |
| Verlies, Kathedrale, Portal, Leuchtturm/Kolosseum, Titanen-Turm | `src/main/java/de/hustlebot/bot/CLAUDE-dungeon.md` |
| Hammer-Event, Titan-Grabung, Clan-Krieg, Stigmas, Gem Bay | `src/main/java/de/hustlebot/bot/CLAUDE-events.md` |
| Clan-Laden, Freunde senden, Burgen, Nahrungskisten, Helden-Set … | `src/main/java/de/hustlebot/bot/CLAUDE-alltag.md` |
| Infrastruktur: Aufnahme, Matcher, OCR, Input, Log, Ordner, Zeitzone | `src/main/java/de/hustlebot/core/CLAUDE.md` |
| Discord-Brücke + vollständige Befehlstabelle | `src/main/java/de/hustlebot/discord/CLAUDE.md` |
| Steuerfenster (Swing) | `src/main/java/de/hustlebot/steuer/CLAUDE.md` |
| Arena-Overlay | `src/main/java/de/hustlebot/schicht/CLAUDE.md` |
| Werkzeuge, Vorlagen aufnehmen, Template-Ablage | `src/main/java/de/hustlebot/tools/CLAUDE.md` |
| Offene Performance-Ideen | `docs/PERFORMANCE-NOTIZEN.md` |

## Bauen & Testen

```
mvn clean package            # -> target/hustle-bot.jar (Shade; nur Windows-x64-Natives drin)
mvn -q test                  # unter Linux ok; OCR-/Windows-Teile per Assumptions übersprungen
mvn -q -o test -Dtest=ArenaListenRegelTest
```
- Tests prüfen meist **package-private static** Helfer → neue Regeln als statische Funktion herausziehen + testen.
- Bildtests laden OpenCV (`nu.pattern.OpenCV.loadLocally()`), Bilder in `src/test/resources/`.
- `SpeicherTest` eigene Surefire-Execution. `*Probe`-Klassen nur mit Bildschirm/xvfb und Flag.
- Regex-Tests auf Quelltext: `SteuerBefehleTest` liest `case "!…" ->` in `DiscordBridge.java` und Literale in `Steuerfenster.java`;
  `BefehlsnamenTest`; `RessourcenVorhandenTest` (jede `"/…"`-Ressource im Code muss in der Jar liegen). Form nicht ändern.

## Architektur in einem Blick

```
Main -> Kern.main: Ordner.basis() -> Log -> Sprache -> OpenCV -> GameWindow -> Matcher(+Nachschub, vorlagen-stellen.txt)
     -> BotContext -> BotEngine (registriert alle GameModes) -> Steuerprogramm.vorbereiten -> DiscordBridge.start
     -> SpielWaechter, ArenaBeobachter, ArenaSchicht, BotFenster, Steuerfenster
```
- **Ein Befehlsweg**: Discord, Steuerfenster (`Befehlsbau`), `befehl.txt`, Tasten → `DiscordBridge.befehl(...)`. Discord optional.
- **Warteschlange** (`kette`) + Einschübe (Clan-Krieg, Leuchtturm, Turm, Freunde senden, Laden, Teamscan) leben in der Bridge
  (`nebenbeiPruefen`, Wache-Faden `discord-wache`).
- **Motor** `BotEngine`: ein Faden `bot-worker`; pro Takt `ctx.refresh()` → `allgemeineStoerung` → `mode.step()`.
  `step()` = **genau eine Aktion**, Rückgabe `ACTED/WAITING/UNKNOWN/FINISHED/PAUSE/FATAL`. Stopp/Pause nur zwischen Schritten.
- `runs.progress()` nur bei echtem Fortschritt (Hänger-Bericht 20 s, Totmann 15 min; Klick-Schleifen zählen nicht).

## Querschnitts-Regeln (gelten überall)

- **Fenster**: Spiel innen 1159×770 (außen 1175×809). Vorlagen werden nie skaliert; Koordinaten als Brüche von 1159/770.
  `sun.java2d.uiScale=1` im static-Block von Main/Kern/Einrichtung – nie entfernen (Windows-Skalierung).
- **Vorlagen**: Name = Dateiname ohne `.png`, Ordner egal, global eindeutig. Serien `name_*` = gleicher Knopf (neues Aussehen ohne Code).
  Optionale Vorlagen nur über `...IfKnown`/`matcher.has`. Neue mitgelieferte Vorlage: `templates/` + `src/main/resources/nachschub/` + `nachschub/liste.txt`.
- **Matcher**: `TM_CCOEFF_NORMED`, Schwelle 0,84, gemerkte Stellen ±60 px, alles `synchronized`. Jede temporäre `Mat` `release()`.
  Grau nur Kathedrale/Verlies; Arena immer Farbe. Verkleinerte Vorsuche wurde gemessen und verworfen.
- **OCR**: Windows-OCR via dauerhaft offene PowerShell (`DauerLeser`), strikt seriell; Paddle optional (Modelle nicht im Repo).
  `texterkennung*.ps1` liegt doppelt (Root + `src/main/resources/skripte/`) – beide ändern.
- **Warten**: `ctx.sleepUntilChanged(max)` (adaptiv, max = Obergrenze), kein festes Sleep. Wartezeiten nie pauschal kürzen
  (Klicks in Übergangsbilder). Fristen mit `Rechnertempo.mal(...)` skalieren.
- **Texte**: immer `Sprache.t("english", "deutsch")`, Englisch Standard. `Log.alarm/hinweis` gehen nach Discord.
- **Zeit**: `Zeitzone.jetzt()/heute()` (Europe/Berlin, Reset 02:00), nie `LocalDateTime.now()`. Fristen mit `nanoTime`.
- **Pfade**: neu immer `Ordner.bei(...)` (Bot-Ordner). Altcode nutzt `Path.of("x.txt")` relativ zum Arbeitsordner.
  Windows-Dateien lesen mit `Dateien.zeilen`, Schalter mit `Schalterdatei` (fehlend = Standard).
  `config.properties` nur zeilenweise ISO-8859-1 schreiben (`.neu` + atomic move), nie `Properties.store`.
- **Geld**: nie Diamanten ausgeben, nie Kauf-Angebote. Rotes X/grünes Yes sehen überall gleich aus → nur mit Kontextprüfung.
  "Anderes Gerät" → PAUSE, nie "Update"/"Try again" von selbst.
- **Nichts löschen**: Bilder → `templates-aussortiert`/`.png.alt`; Nutzerbilder nie überschreiben. "Nichts soll wachsen": Beweisfotos nur via `Beleg`.
- **Kommentare**: datierte Kommentare ("Autor 24.10.: …", "Bericht #XXXX") begründen fast jede Zahl mit echter Messung.
  Vor jeder Änderung lesen; Kommentar und Code synchron halten (Autor liest Verhalten aus Kommentaren).
- **Neue Verhaltensregeln** hinter Schalterdatei (Standard aus), damit Freunde-Fassung unverändert bleibt (Muster: `arena-regeln-neu.txt`).

## Laufzeitdaten (Bot-Ordner, nicht im Repo, siehe .gitignore)
`config.properties`, `vorlagen-stellen.txt`, `aufstellung.properties`, `sprache.txt`, `dauerwerte.properties`, viele `*-erledigt.txt`,
`arena-*.txt`/`arena-*/`, `lernwerte.txt`, `*-tempo.txt`, `logs/` (`bot_<stamp>.log`, `berichte/*.zip`). Details je Teildokument.

## Bekannte Auffälligkeiten (Stand 2026-11-21, nicht behoben)
- `Log.error(..., Throwable)` druckt Stack-Frames nur für `de.autor*` → unter `de.hustlebot` keine Frames (Rest der Umbenennung).
- `CrashDetector.start()` nie aufgerufen; `OcrTrainingsdaten` ohne Aufrufer; `BotEngine.haengerMelderSetzen` nie gesetzt.
- `GemBayplan` nie aufgerufen (Gem Bay nur `!gembay`); gb_*-Vorlagen fehlen. `TruppAuffuellen` faktisch tot (cw_nein/vacant/helm/kartenkraft fehlen).
- `bot.requireAllTemplates` und `human.*` in config.properties wirkungslos.
- Doku-Drift: `docs/EVENT-FLOW-EN.txt` Kriegszeiten veraltet (Code: UTC 01:20/07:20/13:20/19:20); Arena-LIESMICH nennt 8 s statt 7 s;
  `ArenaListenRegel.Entscheidung.abstufen`-Javadoc sagt "gewonnen", Code stuft bei Niederlage ab; Spieler-Guides nennen teils `capture.bat`/`templates\dungeon`.
- Fixtures ohne Test: `src/test/resources/burg/`, `event-fenster/`, `stoerbilder/cw_edit_*`.
- Nicht im Repo, aber referenziert: `paket-bauen.ps1`, `Install.bat`, `doku/STEUERPROGRAMM.md`, Paddle-Modelle.
