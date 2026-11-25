# schicht/ -- Arena-Schicht (Overlay ueber/neben dem Spiel)

7 Dateien, ~4k Zeilen. Alpha-Funktion vom 28.10.-01.11.: Solange die Arena-Pyramide zu sehen ist, liegen duenne
Ampel-Rahmen um die Gegner (transparente Schicht UEBER dem Spiel) und links/rechts NEBEN dem Spiel steht das Seitenfenster
mit Gegnerliste, Bot-Ziel, Siegchance und "Rechenzettel". Bedienung nur per Tastatur ueber einen systemweiten Low-Level-Haken.

**Nur aktiv mit `arena-schicht.txt` = "an"** (`core/ArenaSchalter`, `Schalterdatei`, Standard AUS, einmal beim Klassenladen
gelesen, `Path.of("arena-schicht.txt")` relativ zum Arbeitsordner). "Erstmal nur fuer Autors englischen Bot" -- die Freunde
merken nichts davon. `ArenaSchalter.testWert(Boolean)` fuer Tests.

## Einstieg und Datenfluss

- `Kern.java`: `ArenaSchicht.starten(fensterTitel, discord::send)` (idempotent; tut nichts bei Schalter aus, headless
  oder schon laufend).
- **Producer = `bot/ArenaMode`** (~50 Aufrufe) und `bot/ArenaBeobachter`: legen nach jeder Zielwahl einen frischen
  `ArenaStand.Stand` ab (`ArenaStand.setzen`), melden die am Bild gelesene Stage (`rundeGelesen`), Chat offen/zu
  (`chatOffen/chatZu`), `stageFertig`, `auffrischen(runde)`. ArenaBeobachter wartet mit `ArenaStand.aufKlickWarten(ms)`
  (Semaphore) auf Autors Klicks und liest `letzterKlick()`.
- **Consumer = `ArenaSchicht`**: liest `ArenaStand.aktuell()` (null, wenn aelter als `HALTBAR_MS`=5 s oder kurz-aus),
  zeichnet Rahmen (`SchichtFenster`) und Seitenfenster (`SeitenFenster`).
- **Rueckkanal**: Tasten -> `SchichtSteuerung` -> `ArenaStand.befehl(Befehl)` (ConcurrentLinkedQueue). Der Arena-Modus holt
  sie selbst mit `ArenaStand.befehleNehmen()` ab (ArenaMode ~Zeile 2568) und schreibt Listen (sicher/machbar/... Bilder).
  `ArenaStand` ist also nur ein **statischer Zettel/Briefkasten** -- keine direkte Rueckwirkung auf den Bot.
- Notizen (Kommentar/Zustimmen/Ablehnen): `ArenaSchicht.notizSchreiben` auf Faden `arena-schicht-schreiber` ->
  `arena-kommentare.txt` (Bot-Ordner, append, UTF-8) + `core.KommentarPakete.anhaengen` (Ordner `arena-kommentare/`,
  offen.txt -> paket-<zeit>.txt; weitergereicht von externem `werkzeuge/kommentar-pakete.py`, nicht im Repo) + Discord.

## Faeden (kritisch)

- `eingabe-haken` (MAX_PRIORITY): eigene Windows-Message-Loop mit `WH_KEYBOARD_LL`/`WH_MOUSE_LL` (JNA). Im Haken wird NUR
  entschieden "schlucken ja/nein" (`ArenaSchicht.schlucken` -> `SchichtSteuerung.taste/maus`, alles `synchronized`, nur
  Speicher, Mikrosekunden). Mausbewegungen werden sofort durchgereicht. Zu langsamer Haken = Maus ruckelt systemweit, Windows
  wirft den Haken still raus -> Neu-Anmeldung alle 2 Minuten. **Niemals I/O, Logging-Lawinen, Swing oder Sperren mit langer
  Haltezeit im Haken-Pfad.**
- `eingabe-melder` (ScheduledThreadPoolExecutor 1): Zuhoerer laufen hier, nicht im Haken.
- `arena-schicht` (Takt 400 ms, `taktSicher` -> `takt`): Spielfenster suchen (eigene `GameWindow`-Instanz, nur
  `locateVisible/clientArea/isMinimized/istImVordergrund`, nie `prepare()`), Rahmen/Seitenfenster aktualisieren nur bei
  Aenderung. Jeder Throwable -> alles verstecken ("Die Schicht darf den Bot nie stoeren").
- `arena-schicht-schreiber`: Datei + Discord.
- EDT: `SchichtFenster`/`SeitenFenster` sind von jedem Faden aufrufbar, arbeiten intern per `aufEdt`/invokeLater.

## Dateikarte

- `ArenaSchicht.java` (528) -- Orchestrierung, Singleton `laufend`. `starten`, `schlucken` (Haken-Callback), `linksKlick`
  (linker Klick wird nur mitgelesen -> `ArenaStand.klickMelden`, Rahmen sofort weg, nie verschluckt), `takt`, `verstecken`,
  `seiteUmschalten` (Taste `^`/Minus-Knopf; naechste Arena = kleinere Runde oeffnet wieder), `notizSchreiben`, statisch
  testbar: `rahmen(stand, innenB, innenH[, anzeige])`, `schildRahmen`, `platzBei`, `lageText`. Innere Klasse
  `WindowsTastatur` (Modifier-Abfrage, `ToUnicodeEx` fuer Zeichen beim Kommentar-Tippen). Konstanten `SEITE_HALTBAR_MS`
  (10 min: Seitenfenster bleibt mit letztem Stand stehen), `KOMMENTAR_DATEI`.
- `ArenaStand.java` (323) -- statischer Zustand: records `Gegner`, `Platz`, `Stand(um, runde, bildBreite, bildHoehe,
  gegner, plaetze)`, `Klick`, `Befehl(Art LISTE|CLAN|ZURUECK|ZUSTIMMEN|ABLEHNEN|KOMMENTAR, platz, wert, text, um)`;
  `setzen/aktuell/zuletztGesetzt/beiWechsel/kurzAus`, `anzeigeRunde` (frisch gelesene Stage schlaegt alten Stand,
  `GELESENE_RUNDE_MS`=3 min), `letztesZiel`, `chat*` (`CHAT_GEDULD_MS`), `befehl/befehleNehmen`, `lesbar(name)`.
- `SchichtSteuerung.java` (923) -- reine Tastenlogik ohne Windows (Interface `Tastatur` injiziert). `Modus NORMAL|KOMMENTAR|
  GRUND`, `taste`, `maus`, `klick(platz, ...)` (Seitenfenster-Mausklicks, negative `KLICK_*`-Codes), `anzeige(...)` ->
  record `Anzeige` fuer die Darstellung, `nieSchlucken(vk)`, `stufeAusZiffer`, `hilfe(botSpielt)`, `discordText`, `zeile`.
- `SeitenFenster.java` (877) -- JWindow, `setFocusableWindowState(false)`, `Type.UTILITY`, alwaysOnTop, eigenes dunkles
  Farbschema (nicht Holzstil). `lage(spiel, schirm)`: links neben dem Spiel, sonst rechts, passt nirgends -> null = nicht
  zeigen (**nie ueber dem Spiel**). `zeigen(innen, stand, anzeige)`, `verstecken`, Textaufbereitung `sortiert`, `zeilen`,
  `zweitbester`, `teile`, `erklaerungKurz`, `posten(gegner)`. Wird per `SchichtFenster`-Helfer ebenfalls aus Fotos ausgeblendet.
- `SchichtFenster.java` (584) -- transparente Schicht mit Rahmen: record `Rahmen(bereich, farbe, text, auswahl, ziel)`,
  Farben `SICHER, MACHBAR, FAST_SICHER, PAUSE, MEIDEN, NIEMALS, UNBEKANNT, AUSWAHL`, `RAHMEN_ALPHA`; `zeigen(schirmBereich,
  rahmen)`, `verstecken`, `schliessen`, `vorAufnahmeVersteckt()`, `aufnahmeSchutzMoeglich()`, `schrift(...)`.
- `EingabeHaken.java` (546) -- Low-Level-Haken: `start/stop`, `setSchlucken(Predicate<Ereignis>)`, `setLinksKlick`,
  `zuhoererDazu/Weg`, `setEingespeisteIgnorieren` (Standard: `LLKHF_INJECTED`/`LLMHF_INJECTED` = Robot-Klicks des Bots werden
  ignoriert). record `Ereignis(art TASTE|RECHTS|MITTE|SEITE, vkCode, gedrueckt, schirmPunkt, vorne)`. Nur 64-Bit-Java.
- `SchichtProbe.java` -- Handprobe, NICHT Teil des Bots: `java -cp hustle-bot.jar de.hustlebot.schicht.SchichtProbe
  ["Fenstertitel"]`, 30 s Beispielrahmen + Ereignis-Log + Gegenprobe Aufnahmeschutz.

## Invarianten / harte Lektionen

1. **Rahmen nie in Bildschirmfotos**: `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE=0x11)`, braucht Windows 10 2004+.
   Ohne funktionierenden Schutz zeigt `SchichtFenster` NICHTS (Review 28.10.) -- sonst faende der Matcher gruene Rahmen
   statt Knoepfe. Seitenfenster ebenso ausgeblendet.
2. Klicks gehen durch die Schicht: `WS_EX_TRANSPARENT | WS_EX_NOACTIVATE`, nie aktives Fenster, nur Linien/Schilder, keine
   Flaechen. Koordinaten: echte Schirmpunkte (`GameWindow.clientArea()`); der Bot setzt `sun.java2d.uiScale=1`, sonst wird umgerechnet.
3. Verschluckt wird nur: Tasten der Schicht, nur wenn Pyramide zu sehen (oder Kommentar/Grund-Modus) UND Spiel vorn UND kein
   Spiel-Chat offen. **Nie**: F1-F24 (F9 Not-Aus, F10 Pause, F11 Weiter!), Shift/Strg/Alt/Win/Caps/Druck/Num/Rollen,
   Medientasten, Tastenkombis (Alt+Tab ...), eigene eingespeiste Eingaben des Bots, linke Maustaste.
4. Bedienung nur Tastatur: die **Mauswache stoppt den laufenden Bot bei jeder Mausbewegung** (Autor 29.10.). Maustasten
   rechts/mitte/seite bedienen nichts mehr.
5. Fasst das Spielfenster nie an (kein `prepare()`, keine Klicks, kein Nach-vorn-Holen).
6. Rahmen nur bei echter, frischer Pyramide und Spiel vorn; Seitenfenster bleibt bis 10 min mit letztem Stand
   (Autor 30.10.: "fuer die kompletten 5 Arena-Stages offen"). Neu zeichnen nur bei Aenderung (Review 28.10.).
7. Siegchance auf 8 Punkte gerundet wie im ArenaMode (Erkennung springt 1-2 Punkte).

Tasten (`SchichtSteuerung.hilfe`): WASD/Pfeile waehlen, 1 sicher, 2 machbar, 3 meiden, 4 niemals, 5 fast sicher,
6 Pause, 0 keine Liste, Ruecktaste zuruecknehmen, K Kommentar (Enter/Esc/Tab), G Stage-Kommentar, J passt, N passt nicht
(dann Grund-Frage), Leertaste Rechenzettel, Esc schliessen, `^` (VK 0xDC) Seitenfenster weg/her. Der Klassenkommentar
nennt nur 1-4 -- die Codezeile `stufeAusZiffer` ist massgeblich. Listen-Namen = Vorlagen-Praefixe `ar_sicher_`,
`ar_machbar_`, `ar_ungern_` (meiden), `ar_block_` (niemals), `ar_fastsicher_`, `ar_pause_`, `ar_clan_` (siehe tools/CLAUDE.md).

## Dateien

Gelesen: `arena-schicht.txt` (Schalter). Geschrieben: `arena-kommentare.txt`, `arena-kommentare/offen.txt`,
`arena-kommentare/paket-*.txt` (via KommentarPakete). Listen-Bilder schreibt ArenaMode, nicht die Schicht.

## Tests (src/test/java/de/hustlebot/schicht)

`SchichtSteuerungTest` (34 Tests, reine Tastenlogik mit Fake-Tastatur), `ArenaSchichtTest` (Rahmen, Lage Seitenfenster,
Rechenzettel; raeumt `ArenaStand.setzen(null)` auf), `ArenaStandRundeTest` (Stage-Anzeige), `ArenaAnzeigeTest`,
`SeitenFensterTextTest` (echte Texte aus Autors Arena). `SchichtVorschau` (main, kein Test): malt Schicht+Seitenfenster auf
ein echtes Spielbild: `java -cp target/classes;target/test-classes de.hustlebot.schicht.SchichtVorschau spiel.png aus.png [oben]`.

## Rezepte

- Neue Taste: `SchichtSteuerung.normalTaste` (+ VK-Konstante, `hilfe()`-Text EN/DE, Test in SchichtSteuerungTest). Pruefen,
  dass sie nicht in `nieSchlucken` faellt und im Spiel-Chat nicht stoert.
- Neue Aktion an den Bot: neue `ArenaStand.Befehl.Art` + Auswertung in `ArenaMode` (Schleife ueber `befehleNehmen()`).
- Aussehen der Rahmen: `SchichtFenster` (Farben, `RAHMEN_ALPHA`, `AUSWAHL_STAERKE`, `RUNDUNG`, Schild-Geometrie aus Autors
  Foto) und `ArenaSchicht.rahmen/schildRahmen`; Vorschau mit `SchichtVorschau`.
- Inhalt Seitenfenster: `SeitenFenster.Inhalt` (paint) und Text-Helfer; Daten kommen aus `ArenaStand.Gegner` (vom ArenaMode befuellt).
