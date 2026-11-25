# tools/ -- Aufnahme-Programm (Capture-Images), Begleitfenster, Paket-Selbstpruefung

15 Dateien, ~4.4k Zeilen. Drei Gruppen:
1. **Aufnahme-Programm** (eigenes Programm, eigener `main`): `TemplateCapturer` -> `Bilderwerkzeug` + Helfer
   (`Aufnahmepruefung`, `Beispielbild`, `Bildablage`, `Bilderpruefung`, `MeineBilder`).
2. **Begleitfenster im laufenden Bot**: `BotFenster`, `LogFenster` (+`Protokollzeile`), `NotizFenster` (+`Notizblocktabelle`,
   `NotizblockListen`), `Fensterstil`.
3. **Build-Pruefung**: `Selbstpruefung` (main, beim Paketbau).

## Einstiege

- `capture.bat` (Hauptbot/Entwickler): sucht Java 21 (JAVA_HOME -> `C:\Program Files\Eclipse Adoptium\jdk-21*` -> PATH),
  dann `java -cp target\hustle-bot.jar de.hustlebot.tools.TemplateCapturer templates`. Braucht vorher `build.bat`
  (`mvn clean package`).
- `Capture-Images.exe` (Freunde-Paket, jpackage): `capture-images.properties` = `main-class=de.hustlebot.tools.TemplateCapturer`,
  `win-console=true`. **Klassenname `TemplateCapturer` nicht umbenennen** (Capture-Images.cfg nennt ihn). Gestartet auch aus
  dem Steuerprogramm (Reiter Werkzeuge, Ampelkarte) via `Ordner.bei("Capture-Images.exe")`.
- `TemplateCapturer.main` -> `Bilderwerkzeug.main(args)`: `Ordner.basis()` (Bot-Ordner, ueber `jpackage.app-path`),
  `nu.pattern.OpenCV.loadLocally()`, `Log.init(<bot>/logs)`, Ordner = `args[0]` oder `<bot>/templates/dungeon`, dann
  `new Bilderwerkzeug(ordner, "Hustle Castle", 1175, 809)` auf dem EDT.
- Im Bot (`Kern.java`): `BotFenster.zeigen(motor, waechter)` (nicht bei ServerSparsam). `LogFenster.aufmachen(Path.of("logs"))`
  und `NotizFenster.aufmachen()` werden vom Steuerprogramm (Kuerzel/Knoepfe) gerufen; beide haben statische
  `istOffen/zumachen` (Singleton). Haken vom Steuerprogramm gesetzt: `LogFenster.befehlSender` (Consumer<String>),
  `LogFenster.berichtDialog` (Runnable, Fehlerbericht).
- `Selbstpruefung.main`: `java -cp target\hustle-bot.jar de.hustlebot.tools.Selbstpruefung templates`; vom Paketbauskript
  `paket-bauen.ps1` aufgerufen (Skript NICHT im Repo) -- bei Fehler keine ZIP.

## Dateikarte

- `Bilderwerkzeug.java` (1745) -- JFrame-Assistent "Bild N von M": immer EIN Bild dran, Balken, Knoepfe "Take picture",
  "Save and next", "< Back", "Skip >", "Picture from file…", "Other picture…" (freier Vorlagenname, ersetzt auch
  Paketbilder), "Arena: save name…", "Arena: type names…" (-> `arena-aufnehmen.txt`, der Bot schneidet selbst aus),
  "Arena: save item…" (Totems/Artefakte, naechste freie Nummer), "Undo last picture" (Journal), "My pictures…",
  "Check all my pictures…", "Advanced ▾". Wichtige Teile: `EIGENE` (LinkedHashMap Pflicht-/Wunschbilder in Reihenfolge:
  Spielername, Opfer-Gesicht/-Name, Kaempfer 1-6 Gesicht+Name, Freund-Name, Arena eigener Name), `ZUSATZ` (Confirm-Knopf,
  Belohnungstext, `ev_kiste_eigen_N`), enum `Ort` (KARTE, AUFSTELLUNG, FREUNDESLISTE, BESTAETIGEN, BELOHNUNG, EVENT, ARENA),
  `ordnerFuer(name)`, `eigeneBilder()` (fuer Selbstpruefung), Arena-Listen `ARENA_LISTEN`, Doppelt-Pruefung (kein Name
  zweimal in Listen; Umzug alter Aufnahme nach `templates-aussortiert` via `ArenaVorlagenDoppelt.aussortiertZiel`).
  Foto: `GameWindow.locate()` + **`prepare()` (richtet Spielfenster auf Bot-Groesse!)** + `ScreenCapture.grab(clientArea)`.
  Dateinamen kommen aus `DungeonMode` (`bildNamensschild`, `bildOpferPortraet`, `bildOpferName`, `bilderKaempfer`,
  `bilderKaempferNamen`, `freundVorlage`), `ArenaMode.bildEigenerName`, `CathedralMode.bildBelohnungText` -- nie hier
  duplizieren (genau das verursachte den Fehler "name_zarina" vs. "name_magier1").
  Sprache: statischer Block ruft `Sprache.set(Main.spracheAusDatei())` beim Klassenladen (vor main!). Der Klassenkommentar
  "ALLES AUF DEUTSCH" ist veraltet -- Texte laufen ueber `Sprache.t`.
- `Aufnahmepruefung.java` -- vor dem Speichern: Test-Suche des Ausschnitts im eigenen Foto (TM_CCOEFF_NORMED wie der Bot;
  gruen eindeutig / gelb aehnliche 2. Stelle / rot), Qualitaet (`Mangel`: <12 px, einfarbig, >40 % des Fensters, unscharf),
  Fenstergroesse `SOLL_BREITE=1159 x SOLL_HOEHE=770` (Client-Bereich). `bewerte`, `suche`, `qualitaet`, `vorschau` (3x),
  `groessenHinweis`. Ohne OpenCV -> `bewerte` null.
- `Beispielbild.java` -- JComponent, gezeichnetes Schema je Schritt (`Motiv`), bewusst kein Foto (fremde Konten/Namen).
- `Bildablage.java` -- Dateiarbeit mit Rueckweg: `schreibeMitSicherung` (alte Datei -> `name.png.alt`), `nachAlt`
  (= "Loeschen"), `verschiebe`, `dateiStand`; `Journal` (Undo-Stapel). **Nichts wird endgueltig geloescht**; Bot liest nur `*.png`.
- `Bilderpruefung.java` -- "Alle meine Bilder pruefen": jede eigene Aufnahme gegen EIN frisches Foto, `FUND_SCHWELLE=0.84`;
  je Bildschirm (Ort) unterschieden: anderer Bildschirm = grau, nicht Fehler. `pruefe`, `zusammenfassung`, `zeigeErgebnis`.
- `MeineBilder.java` -- Liste der Bilder unter "My pictures" mit Vorschau/Groesse/Datum, "Entfernen" = `.alt`.
- `BotFenster.java` -- kleines Fenster "Hustle Castle Bot" (490x170, unten rechts): Status, Spielwaechter-Countdown,
  "Stoppen" (Modus) / "Beenden" (Programm; X = Beenden). **Nicht alwaysOnTop** (stoert Vollbild), nimmt keinen Fokus,
  Knoepfe loesen bei **mousePressed** aus (Motor holt das Spiel alle paar 100 ms nach vorn, Loslassen landete sonst im Spiel).
- `LogFenster.java` (500) -- Protokoll-Mitschau: liest die Datei in `logs/` (nicht das laufende Programm; springt bei neuer
  Datei um, ueberlebt Neustart), Takt 300 ms; Holzstil-Farben je Stufe, Suche + Filter (Klicks/Meldungen/Warnungen/Fehler/
  Details), "Was heisst das?" ueber `steuer.Warnhilfe`, "Fehler melden" -> `berichtDialog`. Eigener `main` (Ordner-Arg).
- `Protokollzeile.java` -- record (zeit, stufe, bereich, text) + Parser fuer `22:21:37.587  [INFO] Arena: Text` (Format
  aus `core/Log`), Filter-/Suchtreffer.
- `NotizFenster.java` -- Arena-Notizblock als sortierbare Holzstil-Tabelle; liest `Gegnerbuch.NOTIZBLOCK`
  (`arena-notizblock.txt`) und `arena-liste.txt`; Reiter "gescannt"/"angegriffen" ueber `NotizblockListen`. Eigener `main`.
- `Notizblocktabelle.java` -- Parser fuer `arena-notizblock.txt` (Format von `Gegnerbuch.lesbar`: "-> Name (S/N)", Clan,
  Kraft, Zeit, Punkte, Arena-Punkte, zuletzt).
- `NotizblockListen.java` -- Tabellen aus `arena-daten.txt` (gescannt, mit Pruefdatum) und `arena-lernen.txt` (Kaempfe).
- `Fensterstil.java` -- altes weiches Farbschema der Begleitfenster; BotFenster nutzt es voll, Log/Notiz nur noch Reste
  (Farben dort inzwischen aus `steuer.Holzstil`).
- `Selbstpruefung.java` -- `pruefePlaetze` (Portraets/Namen paarweise, Opferaltar, Kathedrale belebt nur Bekanntes),
  `pruefeWerkzeug` (Capture-Images bietet genau, was der Bot sucht), `pruefePaket` (kein Bild aus `EIGENE` im Paket, keine
  fremden Bilder von Autor, Pflichtvorlagen im Paket oder im Werkzeug). Kein JUnit, weil auf Autors Rechner offline nicht da.
- `TemplateCapturer.java` -- 14 Zeilen, nur Weiterleitung.

## Vorlagen (Templates): Ablage, Namensregeln, Nachschub, gelernt/

**Grundregel (`core/Matcher`, templates/LIESMICH.txt):** Vorlage = PNG; **Name = Dateiname ohne `.png`, der Ordner ist
egal** (nur Ordnung). `Matcher.loadAll` laedt rekursiv in dieser Reihenfolge: `templates/` (gefiltert durch
`ServerSparsam.bildLaden`), dann `gelernt/` im Bot-Ordner (`Matcher.GELERNT`, relativ), dann "My pictures"
(`Matcher.EIGENE_BILDER`). Bei gleichem Namen gewinnt die **neuere Datei** (Aenderungszeit; `doppeltStehend` wird nur
geloggt). `.png.alt` wird ignoriert.

**Ordner-Konvention** (`core/Vorlagenordner.fuer(name)`, 160+ Praefix-Regeln, erste passende gilt): z. B. `dc_`/`key_` ->
cathedral, `cw_` -> clanwar, `ev_kiste_` -> event-belohnungen, `ar_*` -> arena/... (`ar_block_` niemals, `ar_ungern_`
meiden, `ar_sicher_`, `ar_machbar_`, `ar_fastsicher_`, `ar_pause_`, `ar_clan_` clans, `ar_name_` namensbilder,
`ar_schirm_`/`ar_stigma_` schirmherrschaften), `rs_` burgstand, Rest dungeon. Gilt fuer Aufnahme-Werkzeug, vom Bot gelernte
Bilder und Umsortieren.

**Repo-Ordner `templates/`** (756 Dateien, 747 PNG): arena (anzeigen, artefakte, einheiten-pruefung, gegner, kampfprotokoll,
knoepfe, stellen), burgstand, cathedral, clanladen, clanwar, colosseum, dungeon, event, event-belohnungen, lighthouse,
titantower, gelernt. Praefix-Beispiele: `dc_` Kathedrale, `key_N` Ziffern, `rs_` Ressourcen, `ts_` Team-Scan, `tc_` Titanen,
`st_` Stigma, `ev_` Event, `cw_` Clan-Krieg. LIESMICH.txt in `templates/` und `arena/gegner/*/` erklaeren die Listen.

**`templates/gelernt/`** (309 Dateien; Unterordner arena 59, titanen 124 + `titanen.txt`, team 30, portal 16, freunde 13,
burgen 12, titan-event 12, event-belohnungen 11, event 10, burgstand 7, dungeon/heldenset/stigma 3, clanwar/lighthouse 2,
cathedral/stoer 1). Herkunft: Bilder aus **Autors Lernmodus** (nach `!fix` aus Autors Screenshots ausgeschnitten, Matcher-
Kommentar 27.09.) und aus Autors Bildern geschnittene Sets (TeamScan, ArenaAussehen, SchirmLeser, StigmaMode), mit dem Paket
ausgeliefert. Zur **Laufzeit** schreiben Bot-Modi zusaetzlich hinein: `Matcher.eigenVielleicht` -> `templates/gelernt/eigen/`
(`Matcher.EIGEN_ORDNER`, Kern setzt es; sicher erkannte Knoepfe vom Bildschirm des Freundes, Score >= 0.95 + zweite Sichtung
>= 0.97, nie `quick_attack`/`to_battle`/Farbzustands-Vorlagen; bei Haenger zurueckgenommen), `StigmaMode` ->
`gelernt/stigma/st_kopf_<set>_gelernt_N.png` (max 12/Set), `TeamScan.NAME_FOTO` -> `gelernt/team/spielername.png`,
`CathedralMode.lernen` -> `gelernt/lokal/`. In den Bot-Ordner `gelernt/` (ohne templates) schreiben `ArenaMode`
(Namensbilder `Matcher.GELERNT.resolve(Vorlagenordner.fuer(...))`), `TeamScan` (`dungeon/einheiten/auto`), `EventMode`
(`event-belohnungen`). `Matcher.gelernt(teile...)` bevorzugt `templates/gelernt/...`, sonst `gelernt/...`.
`ServerSparsam` laedt `gelernt/titanen/`, `gelernt/portal/`, `gelernt/stigma/` auf dem Server nicht.

**"My pictures"** (`core/Eigenbilder`, seit 24.10.): eigene Aufnahmen der Spieler, sichtbar neben templates:
`My pictures/Arena lists/{safe,doable,almost-safe,pause,avoid,never,clans,names}`, `Arena/<unter>`, `Event rewards`,
`Cathedral`, `Clan war`, `Dungeon`. `Bilderwerkzeug.ordnerFuer` speichert dorthin (`Eigenbilder.ordnerFuer(botOrdner, name)`),
aeltere Aufnahmen liegen noch in `templates/dungeon` u. a. (wird beim Suchen mitgeprueft). `Eigenbilder.einrichten` zieht beim
Start alte Arena-Listen aus `templates/arena/gegner` um. `ArenaVorlagenDoppelt.aufraeumen` haelt jeden Spieler nur einmal.

**Nachschub** (`core/VorlagenNachschub.liefern`, beim Start): `src/main/resources/nachschub/liste.txt` (~757 Pfade relativ zu
templates, Kopf: "Wird von paket-bauen.ps1 erzeugt -- nicht von Hand pflegen") + Bilder unter `nachschub/<pfad>` in der Jar.
Fehlt eine Datei in `templates/`, wird sie kopiert; **vorhandene nie ueberschrieben** (Freund-Aufnahme bleibt). Grund: Freunde
aktualisieren nur `app\hustle-bot.jar`, der templates-Ordner wird nicht angefasst. Im Repo liegen 509 PNG im Nachschub;
Ordner spiegeln templates/ (gelernt nur 77 von 309, clanwar 46 von 53) -- Eintraege ohne Ressource werden still uebersprungen.
Neue mitgelieferte Vorlage = in `templates/` UND in `nachschub/` + `liste.txt`.

**Eigene Pflichtbilder der Spieler** (nie im Paket, `Selbstpruefung` erzwingt das): `nameplate_me`, `unit_sacrifice`,
`name_sacrifice`, `unit_1..6`, `name_1..6`, Freund-Namen (`DungeonMode.freundVorlage`), Arena eigener Name
(`ArenaMode.bildEigenerName`, ar_me). Namen nur aus DungeonMode/ArenaMode beziehen.

**Anleitungen** (fuer Spieler, z. T. veraltet -- nennen noch `capture.bat` und `templates\dungeon\` statt Capture-Images.exe /
"My pictures"): `docs/CAPTURE-IMAGES-EN.txt` (Ablauf, Arena-Listen, Embleme `ar_emblem_gut_N`, `arena-punkte-erzielt.txt`,
`arena-aufheben.txt`), `docs/VORLAGEN-AUFNEHMEN-DE.txt` (deutsche Fassung), `docs/GUIDE-2-PICTURES-EN.txt` (Kurzfassung:
Knoepfe, Pflichtbilder, Regeln: eng rahmen, nichts Bewegtes, keine Animation, Kaempfer nur im Verlies).

## Invarianten / Gotchas

- Aufnahme-Fenstergroesse muss zur Bot-Groesse passen (innen 1159 x 770), sonst findet der Bot nie etwas -> Aufnahmepruefung F22.
- Ein Name darf in den Arena-Listen nur einmal vorkommen (Bild UND getippter Name); `_auto`-Bilder des Bots blockieren nie.
- Nichts loeschen: immer `.alt` bzw. `templates-aussortiert`, jede Aktion mit Rueckweg (Journal).
- Bilderwerkzeug-Fenster = Bildschirm minus Taskleiste, max. 1240 x 900 (Laptop 1366x768!).
- Begleitfenster nie ueber dem Spiel / nie Fokus stehlen; Klick-Handler auf mousePressed (BotFenster).
- LogFenster/NotizFenster lesen dieselben Dateien wie der Bot -- kein zweiter Datenstand.

## Dateien

Gelesen/geschrieben vom Aufnahme-Programm: "My pictures/...", `templates/**` (alte Orte, "Other picture"), `*.png.alt`,
`templates-aussortiert/`, `arena-aufnehmen.txt`, Arena-Punkte-Notizen im Bot-Ordner, `logs/`. Gelesen von Begleitfenstern:
`logs/*`, `arena-notizblock.txt`, `arena-liste.txt`, `arena-daten.txt`, `arena-lernen.txt`.

## Tests (src/test/java/de/hustlebot/tools)

`AufnahmepruefungTest` (laedt OpenCV in @BeforeAll), `BeispielbildTest`, `BildablageTest`, `BilderpruefungTest`,
`MeineBilderTest`, `NotizblockListenTest`. Bilderwerkzeug selbst (GUI) hat keine Tests; `Selbstpruefung` ist der
Konsistenz-Check zwischen Werkzeug, DungeonMode, CathedralMode und Paket.

## Rezepte

- **Neues Pflicht-/Wunschbild fuer Spieler**: Namen in `DungeonMode`/zustaendigem Modus definieren, Eintrag in
  `Bilderwerkzeug.EIGENE` (oder `ZUSATZ`) mit `Ort` und EN/DE-Text, Beispiel-`Motiv` in `motivFuer`, `Selbstpruefung`
  (`pruefeWerkzeug`-Soll-Liste) anpassen.
- **Neue mitgelieferte Vorlage**: PNG unter `templates/<ordner nach Vorlagenordner>/`, Kopie in `src/main/resources/nachschub/`,
  Zeile in `nachschub/liste.txt`; Name eindeutig halten.
- **Neues Praefix/Unterordner**: `core/Vorlagenordner.REGELN` (Genaueres zuerst), ggf. `Eigenbilder.ordnerFuer`.
- **Log-Darstellung**: `LogFenster` + `Protokollzeile` (Format haengt an `core/Log`), Erklaerungen in `steuer/Warnhilfe`.
