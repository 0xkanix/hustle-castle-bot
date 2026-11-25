# Performance-Notizen

Laufende Arbeitsnotizen zu möglichen Optimierungen. Neue Funde unten unter
**Neue Funde** anhängen, erledigte Punkte mit Datum und Messwert abhaken.

Stand: 2026-11-14 (erste Analyse, noch nichts geändert, nichts gemessen).

---

## 0. Ausgangsfrage: Neubau in Go?

**Entscheidung: nein.** Begründung:

- Die teuren Teile sind schon nativer Code: OpenCV `matchTemplate` (C++), onnxruntime (C++),
  Windows-OCR (WinRT). Go würde dieselben Bibliotheken per cgo aufrufen (gocv), also keinen Gewinn bringen.
- Der Großteil der Laufzeit ist **Warten auf das Spiel** (Animationen, Ladebildschirme), egal in welcher Sprache.
- JVM ist bereits schlank konfiguriert (`run.bat`: `-Xmx192m -XX:+UseSerialGC`), das Speicherargument für Go fällt kleiner aus als gedacht.
- Kosten: ~137.000 Zeilen (398 Dateien), viel fein abgestimmte Spiellogik (`ArenaMode.java` allein 10.700 Zeilen), Swing-UI ohne guten Go-Ersatz.
- Einziger echter Vorteil von Go: eine einzelne `.exe` ohne JDK. Das ist Komfort für Nutzer, kein Tempo.

---

## Messgrundlage (wichtig vor jeder Änderung)

- Bot läuft nur unter **Windows**. Diese Analyse ist unter Linux entstanden, also ohne Laufzeitmessung.
  Alle Gewinne unten sind **Schätzungen**, solange nicht auf Windows gemessen.
- Vorhandene Messtechnik im Code nutzen, statt neue zu bauen:
  - `BotContext.sleepUntilChanged` loggt jede Wartezeit auf Debug-Ebene:
    `gewartet X ms von hoechstens Y` bzw. `OBERGRENZE erreicht`.
  - `BotContext.bildMsSchnitt()`: gleitender Schnitt der Aufnahmezeit.
  - `Matcher.vergleichsBericht()` / `zeitMerken()`: Zeit je Vorlage.
  - `Laufstatistik`, `gewartetMs`: Anteil Warten vs. Arbeit je Run.
- Zahlen aus Code-Kommentaren (vom Autor gemessen, nicht von mir):
  - Windows-OCR: 335 ms je Lesung, davon 160 ms PowerShell-Start (mit `DauerLeser` jetzt ~175 ms).
  - `matchTemplate` ganzes Bild 1159×770: 33,6 ms; im Kasten 287×122: 0,85 ms.
  - Foto des Fensters: ~7 ms; ein ganzer „Blick“ (Foto + Umrechnen + Verkleinern + Vergleich): 60–75 ms.

---

## 1. Texterkennung (OCR): höchste Priorität

**Dateien:** `core/Texterkennung.java`, `core/DauerLeser.java`, `texterkennung-dauer.ps1`, `core/PaddleLeser.java`

### Befund
Pro `lies(bild, sprache)`:
1. `ImageIO.write(bild, "png", tempfile)`: PNG kodieren + Datei schreiben.
2. Pfad über stdin an dauerhaft offene PowerShell (`DauerLeser`).
3. PowerShell: `StorageFile` öffnen, `BitmapDecoder` (PNG dekodieren), `SoftwareBitmap`, `RecognizeAsync`.
   Jeder `Warte(...)`-Aufruf macht `MakeGenericMethod` per Reflection neu (5× je Bild).
4. Ergebnis zeilenweise über stdout zurück.

**Multiplikator:** `liesAuchKyrillisch()` ruft `lies()` für **dasselbe Bild mehrfach** auf:
lateinisch, dann `ru`, dann `verbessern()` mit weiteren lateinischen/kyrillischen Sprachen,
dann bis zu 14 weitere Schriften. Jedes Mal wird das Bild **neu als PNG geschrieben, neu gelesen, neu dekodiert**.
Laut Kommentar (24.10.) kostete ein Namensbild so teils **mehrere Sekunden**.
Die Arena liest pro Runde ~12× unter Zeitdruck (Rundenuhr).

`DauerLeser.lies` ist `synchronized` und nutzt einen einzigen Prozess, also läuft OCR strikt seriell.

### Ideen (aufsteigend nach Aufwand)
- [ ] **1a. Einmal kodieren je Bild:** In `liesAuchKyrillisch`/`verbessern` die Temp-Datei einmal anlegen und für alle Sprachen wiederverwenden. Klein, sicher.
- [ ] **1b. BMP statt PNG:** keine Kompression beim Schreiben/Dekodieren. `BitmapDecoder` kann BMP. Ein-Zeilen-Änderung, messen.
- [ ] **1c. Mehrere Sprachen in einem Auftrag:** Protokoll erweitern: `ru,tr,pl<TAB>pfad`. Bild nur **einmal dekodieren**, `SoftwareBitmap` für alle Modelle wiederverwenden. Antwort pro Sprache mit Trennzeile.
- [ ] **1d. Reflection im PS-Skript cachen:** `MakeGenericMethod` je Typ einmal beim Start, nicht je Aufruf.
- [ ] **1e. Eigener kleiner OCR-Helfer (C#/.NET oder C++/WinRT)** statt PowerShell: rohe BGRA-Bytes über stdin/Named Pipe → `SoftwareBitmap.CreateCopyFromBuffer` → kein Temp-File, kein Kodieren/Dekodieren, kein PS-Interpreter. Größter Gewinn, aber neues Build-Artefakt (`.exe`) neben dem Jar.
- [ ] **1f. Parallelität:** 2 OCR-Prozesse (Pool) statt einem, wenn mehrere Namen auf einmal gelesen werden (Arena-Liste). Erst prüfen, ob `RecognizeAsync` CPU-gebunden ist und mehr Kerne nutzt.

**Geschätzter Gewinn:** 1a–1d zusammen ca. 20–40 % je Lesung, 1e deutlich mehr (Schätzung, muss gemessen werden).
**Risiko:** gering bei 1a/1b/1d; 1c/1e ändern das Protokoll. Fallback-Pfad (`einzeln lesen`) muss bleiben.

### Notiz Paddle
Sessions werden gecacht (`PaddleLeser.Modell`), `setIntraOpNumThreads(2)`. Globaler Lock `synchronized (PaddleLeser.class)` macht alles seriell.
Modelle sind im öffentlichen Repo **nicht enthalten**, also ist für normale Nutzer Windows-OCR der Hauptpfad.
- [ ] Prüfen: Lohnen sich mehr Intra-Op-Threads oder DirectML? Nur relevant, wenn Paddle wirklich genutzt wird.

---

## 2. Bildschirmaufnahme

**Dateien:** `core/ScreenCapture.java`, `bot/BotContext.java` (`refresh`, `grabSmall`), `core/FensterFoto.java`

### Befund
- Bot sieht per `java.awt.Robot.createScreenCapture` (GDI unter der Haube).
  Robot liefert `TYPE_INT_RGB`, also läuft `ScreenCapture.toMat` jedes Mal über `drawImage` in ein BGR-Zwischenbild und kopiert dann ~2,7 MB in die `Mat`.
- **`grabSmall()`** (Änderungserkennung in `sleepUntilChanged`) läuft **alle 60 ms (Kathedrale 20 ms)**:
  ganzes Fenster aufnehmen → ganzes Bild nach `Mat` umrechnen → auf 64×48 verkleinern → grau.
  Für einen 64×48-Vergleich wird also jedes Mal das volle Bild aufgenommen und konvertiert.
- `FensterFoto` (PrintWindow + `GetDIBits` → BGRA-Bytes, Puffer wiederverwendet) existiert schon, wird aber **nur für Aufnahmen/Discord** genutzt, nicht vom Bot.

### Ideen
- [ ] **2a. `grabSmall` billiger machen:** per GDI `StretchBlt` (HALFTONE) direkt auf 64×48 in ein kleines DIB, dann ohne Umweg in eine `Mat`. Spart Aufnahme + Umrechnung des vollen Bildes bei jedem Takt. Wirkt auf **jede** Wartestelle (~500 Aufrufstellen), verkürzt die Reaktionszeit nach Bildwechsel.
- [ ] **2b. BGRA direkt in `Mat`:** Aufnahme per `GetDIBits` in nativen Puffer → `new Mat(h, w, CV_8UC4, ByteBuffer)` → `cvtColor(BGRA2BGR)`. Spart `drawImage` und eine Kopie im Java-Heap. Wiederverwendbar aus `FensterFoto`.
- [ ] **2c. DXGI Desktop Duplication** (oder Windows.Graphics.Capture) statt GDI. Schneller, GPU-seitig, liefert nur bei Änderung neue Frames (gratis Änderungserkennung!). Aufwand hoch über JNA; eventuell im gleichen nativen Helfer wie 1e.
- [ ] Vorher messen: `bildMsSchnitt()` aus den Logs, und Zeit je `grabSmall`-Durchlauf (gibt es noch nicht, ggf. Debug-Messung ergänzen).

**Achtung:** PrintWindow mit `PW_RENDERFULLCONTENT` kann langsamer sein als BitBlt (DWM rendert neu). Für den Bot ist BitBlt vom Fenster-DC vermutlich besser; für „nur das Spiel, auch verdeckt“ ist PrintWindow nötig. Messen.

---

## 3. Pixelschleifen mit `getRGB(x, y)`

### Befund
~50 Stellen mit `getRGB(x, y)` je Pixel (meist `bot/ArenaMode`, `TitanEventMode`, `StigmaMode`, `ArenaLeser`).
Fast alle laufen auf **kleinen Ausschnitten** (Knopffarbe, Helligkeit eines Flecks). `Input.helligkeit` macht dafür zusätzlich eine eigene Robot-Aufnahme (~2 ms).
`ScreenCapture.toMatAusschnitt` nutzt schon das Massen-`getRGB(x,y,w,h,...)`.

### Einschätzung
**Niedrige Priorität.** Der JIT kommt damit gut klar, die Bilder sind klein. Nur angehen, wenn ein Profiler eine dieser Stellen als heiß zeigt.
- [ ] Optional: Sammel-Helfer `Pixel.mittelwert(BufferedImage, Rect)` über `DataBuffer`, wenn man ohnehin in der Nähe ändert.
- [ ] Prüfen: Könnten diese Farbprüfungen direkt auf der schon vorhandenen `lastMat` laufen (OpenCV `mean` auf ROI) statt eigener Aufnahme?

---

## 4. Bildsuche (`core/Matcher.java`)

### Befund: schon stark optimiert
- Gemerkte Stellen je Vorlage, Suche nur im Kasten ±60 px (40× schneller), Stellen werden in `vorlagen-stellen.txt` gespeichert.
- „Nicht im Kasten = nicht da“, Vollsuche nur jeden 25. Fehlschlag.
- Graustufen-Suche mit gecachten Graubildern; Sparregel „nur jede 6. Vollsuche“ für nie gesehene Vorlagen.
- Ergebnis-Cache je Bild (`frameBest`), feste Kästchen (`KAESTCHEN`).
- Verkleinerte Vorsuche wurde getestet und **bewusst verworfen** (Score-Bänder überlappen sich). Nicht erneut vorschlagen ohne neue Idee.

### Offene Ideen
- [ ] **4a. OpenCV-Threads prüfen:** kein `Core.setNumThreads` im Code. Prüfen, ob die openpnp-Build parallel rechnet (`Core.getNumThreads()` loggen). Vollsuche 33 ms könnte mit mehreren Kernen kleiner werden.
- [ ] **4b. `findFirst(names...)` parallel:** sucht Vorlagen nacheinander. Bei Vollsuchen könnten mehrere Vorlagen parallel laufen. Haken: `Matcher` ist durchgehend `synchronized` mit geteiltem Zustand (`stellen`, `frameBest`, Graubild). Nur sinnvoll, wenn 4a nichts bringt.
- [ ] **4c. Messen, welche Vorlagen noch Vollsuchen auslösen:** `vergleichsBericht()` aus echten Runs auswerten. Die Top-Kandidaten in `KAESTCHEN` aufnehmen oder ihre Stellen vorbefüllen.

---

## 5. Wartezeiten

### Befund
- `sleepUntilChanged(max)` ist schon **adaptiv**: 40 ms blind (Kathedrale 10 ms), dann Polling alle 60 ms (20 ms), Ende bei Bildänderung (`meanDifference > 8.0` auf 64×48 grau).
  Die großen Zahlen (1200/1500/2000 ms) sind **Obergrenzen**, keine festen Pausen.
- Nur 31 Stellen nutzen eine explizite Blindzeit (meist 20 ms, 3× 500 ms).
- Feste `sleep(200–400)` an ~110 Stellen; `sleep()` geht durch `Humanizer.vary` (±30 %, gelegentlich +250–1350 ms „Zögern“, Minimum 60 ms).
- `Rechnertempo`-Faktor skaliert alles für langsame PCs.

### Ideen
- [ ] **5a. Logs auswerten:** Wie oft steht `OBERGRENZE erreicht` da, und bei welchen Aufrufern? Das sind Stellen, an denen sich das Bild nach dem Klick **nicht sichtbar ändert**, also wird jedes Mal die volle Zeit gewartet. Kandidaten für eine gezieltere Bedingung (z. B. „warte bis Vorlage X sichtbar“ statt „bis sich irgendwas ändert“).
- [ ] **5b. „Erste Änderung“ ≠ „fertig“:** Die Erkennung endet beim ersten geänderten Bild, also oft mitten in der Animation. Danach schlägt die Suche fehl und der Aufrufer wiederholt das. Prüfen, ob „warten bis Bild wieder **stabil**“ (2 aufeinanderfolgende gleiche Briefmarken) Wiederholungen spart.
- [ ] **5c. Feste `sleep(200–400)`** sichten: Welche sind wirklich nötig (Spiel braucht Zeit), welche könnten `sleepUntilChanged` sein?
- [ ] Verbindung zu 2a: billigeres `grabSmall` heißt engeres Polling bei gleicher CPU-Last.

**Risiko:** hoch. Kürzere Wartezeiten führen zu Klicks in Übergangsbilder (Kommentar 25.09.: „fünf Minuten im Kreis“ in der Arena) und zu weniger menschlichem Verhalten. Immer modusweise testen.

---

## Reihenfolge-Vorschlag

1. Messen auf Windows (Logs: Wartezeiten, `bildMsSchnitt`, `vergleichsBericht`, OCR-Dauer).
2. OCR 1a + 1b + 1d (klein, sicher, Arena profitiert direkt).
3. `grabSmall` 2a (wirkt überall).
4. OCR 1c, danach entscheiden, ob 1e (nativer Helfer) lohnt. Falls ja, 2c gleich mitdenken.
5. Wartezeiten 5a/5b anhand der Messdaten.

---

## Neue Funde

<!-- Hier anhängen: Datum, Datei, Befund, Idee, Status -->

---

## Erledigt

<!-- Datum, Punkt, Messung vorher/nachher -->
