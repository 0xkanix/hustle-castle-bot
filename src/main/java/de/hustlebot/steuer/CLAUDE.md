# steuer/ -- das Steuerprogramm (Bot ohne Discord bedienen)

Swing-Oberflaeche ("Bot-Programm"/"Steuerung") neben dem Spiel. 34 Dateien, ~11k Zeilen. Kein eigener Bot-Zugriff:
**jeder Knopf baut nur einen Text wie in Discord getippt** ("!start cathedral 10 c", "!settings leuchtturm aus") und gibt
ihn an `Steuerprogramm.senden` -> `DiscordBridge.befehlVonBedienfeld(text)` -> `DiscordBridge.befehl(channelId, text)`.
Das ist "der eine Befehlsweg" (Auftrag 1, 11.10.) -- nichts hier greift am Bot vorbei (Ausnahmen unten).

Dies ist der **Freunde-Bot** (nicht Autors "Hauptbot"): Standardsprache Englisch, Kommentare "Freunde-Bot: ..." markieren
Abweichungen (z. B. kein `!beenden`, Capture-Images.exe statt capture.bat, Token in config.properties statt geheim.properties).
Der Bauplan `doku/STEUERPROGRAMM.md`, auf den Kommentare verweisen, liegt NICHT im Repo.

## Einstieg, Aufrufer, Faeden

- `Kern.java` (Startablauf): `Steuerprogramm.vorbereiten(motor, bruecke, discord, config, configDatei)` **vor**
  `bruecke.start()` (haengt `Meldungen` als `DiscordClient.Mithoerer` an, damit auch "Bot bereit" ankommt), dann
  `bruecke.setSteuerprogramm(steuer)` (Feld in DiscordBridge, derzeit nur gespeichert), spaeter nach `BotFenster.zeigen`
  -> `steuer.zeigen(waechter)`.
- `zeigen()`: startet `Tagesstatistik`, verdrahtet statische Haken (`LogFenster.befehlSender`, `LogFenster.berichtDialog`,
  `Einstellungsaustausch.beenden`), baut auf dem EDT alle Fenster (`fensterBauen`), richtet Infobereich-Symbol ein,
  startet `Tastenwache` und den `befehl-datei`-Faden.
- **Faeden:**
  - EDT: alle Fenster. `Steuerfenster` hat einen `javax.swing.Timer` (1 s, `aktualisieren`): Zustands-Pille,
    alle 2 s Einstellungswerte (nur wenn Reiter sichtbar), alle 5 s Zeitleiste.
  - `steuerprogramm-befehle` (SingleThreadExecutor, Daemon): **jeder Befehl laeuft hier, nie im EDT** (ein `!stand` liest den
    Bildschirm; `bridge.befehlVonBedienfeld` blockiert). Befehle laufen strikt nacheinander.
  - Bot-/Motor-Faeden (BotEngine): rufen Meldungen-Zuhoerer **im Faden des Senders** auf -> Zuhoerer muessen selbst
    `SwingUtilities.invokeLater` machen.
  - `tastenkuerzel-wache` (GetAsyncKeyState-Polling 60 ms), `befehl-datei` (alle 2 s `befehl.txt` lesen+loeschen),
    Ampelkarte-Messfaden, Fehlerbericht-Faden, `System.exit` immer in eigenem Faden ("steuer-beenden").
- Zustand lesen (nur lesen, direkt aus dem Motor): `steuer.zustand()` = `engine.state()`, `statusText()`,
  `laufstatistik()` = `engine.context().laufstatistik()`. Ohne Motor (Vorschau) gestellte Werte.
- Direkte Eingriffe am Motor nur: `engine.stop(...)` vor `System.exit` (Beenden, Neustart nach Einstellungen-Import).
- `Einstellungswerte` liest aktuelle Werte direkt aus Bot-Klassen/Dateien (nur lesen, `Schalterdatei` u. a.).
- `Einstellungsaustausch` und `Profile`-Datei, `discordEinstellen` (via `DiscordBridge.configWertSchreiben`) schreiben Dateien
  selbst -- alles andere geht ueber Befehle.

## Rueckmeldung und Antworten (wichtigster Mechanismus)

- `senden(befehl, quelle)` -> fire-and-forget. `senden(befehl, quelle, abfrage, Consumer<Rueckmeldung.Ergebnis>)`:
  im Befehls-Faden setzt ein `ThreadLocal<List<Meldung>> sammler`; alle Meldungen, die der Bot **im selben Faden** waehrend
  des Befehls ausgibt, gehoeren zu diesem Befehl -> `Rueckmeldung.auswerten` -> gruen/rot/gelb-Label hinter dem Knopf (EDT).
  Gotcha: gibt der Bot asynchron (anderer Faden) Antwort, landet sie NICHT in der Rueckmeldung.
- Ohne Rueckruf und kein Start-/Profilbefehl: `antwortErwartetBis = now+15 s` -> neue Meldungen erscheinen im
  `Antwortfenster` (ersetzt den alten Reiter "Meldungen", Autor 14.10.). Startbefehle oeffnen es nie (Maus gehoert dem Bot).
  Die Start-Erkennung ist eine Regex in `senden` mit Tippfehler-Varianten (`statr|sart|stard|stadt...`) -- an `!start` in
  DiscordBridge angelehnt; bei neuen Start-Aliassen dort UND hier pflegen.
- `sendenMitNachfrage`: `Befehlsbau.gefaehrlich(befehl)` (notaus, beenden, quit, aus, pc, neustart, clear, clearall, sperren,
  an; auch kanonische Formen via `Befehlsnamen.kanonisch`) -> Ja/Nein-Dialog. Fuer Tastenkuerzel/Sprache.
- `fragen()` erzeugt einen `JOptionPane`-Dialog mit `setAlwaysOnTop(true)` -- sonst liegt er hinter dem Spiel, das der Motor
  staendig nach vorn holt. Gilt fuer alle Dialoge/Fenster hier (Stigmafenster ebenso).

## Dateikarte

Kern:
- `Steuerprogramm.java` (943) -- Mitte von allem: `vorbereiten`, `zeigen`, `senden*`, `fragen`, `botBeenden`, `botNeuStarten`
  (`!neustart`), `umschalten(Tastenkuerzel.Aktion)`, `anzeigeSetzen`, `profilAnwenden`, `discordEinstellen`,
  `berichtDialog`/`berichtSpeichern`/`berichtZip` (Fehlerbericht-ZIP), `fehlerberichtAnlegen`, `aussehenSetzen` +
  `fensterNeuBauen`, `vorschau(Path)` (ohne Motor/Bruecke, fuer Probes), Interface `Abmeldbar`.
- `Steuerfenster.java` (2286) -- das Hauptfenster (JFrame, X = nur verstecken). Reiter-Konstanten `REITER_STEUERUNG=0,
  ANZEIGEN=1, EINSTELLUNGEN=2, KUERZEL=3, PROFILE=4, WERKZEUGE=5, SUPPORT=6` (der Klassenkommentar nennt noch "Meldungen" --
  veraltet). Wichtige Methoden: `steuerungReiter` (Modus-Combo inkl. Stigma-Auftraege und Profile, Anzahl, Weg c/b, Titan,
  Mitspieler, Portal-Felder, Zeitleiste, Ampelkarte), `starten`, `startBefehl`, `alsProfilSpeichern`, `modusListeNeu`,
  `titanNamen` (liest `templates/gelernt/titanen/titanen.txt`), `einstellungenReiter` (Karten via `bereich(...)`, Zeilen via
  `zeile/freiZeile/zahlenZeile/schalter/befehlSchalter/knoepfe`), `werteAktualisieren`, Rueckgaengig (`rueckgangMerken/
  Ausfuehren`), `standardZuruecksetzen`, `ansichtAnwenden` (Einfach/Alle + Suche), `kuerzelReiter`, `profileReiter`,
  `werkzeugeReiter` (`batStarten` von Capture-Images.exe, PC-Check.bat, PC-Cleaner\PC-Cleaner.bat, pc-aufraeumen.bat),
  `supportReiter`, `discordZeilen`, Tutorial-Ziele `ziel(schluessel)`/`markieren` (GlassPane `Markierung`),
  `karteBauen(Meldung, ...)` (Meldungskarte, statisch, auch von Antwortfenster genutzt), `ruhe()` (ServerSparsam).
- `Befehlsbau.java` -- reine Textbauerei: Enums `Modus` (befehlswort/anzeige/mitAnzahl; KATHEDRALE..PORTAL), `Stigma`,
  `Bezahlart`; Konstanten `PAUSE, WEITER, STOP, NOTAUS, NEUSTART, STATUS, STATS, STAND, HILFE ...`; `start`, `danach`,
  `portalPlan`, `portalFarm`, `einstellung`, `ende`, `pcAus`, `verb`, `gefaehrlich`, `zahl`, `woerter`. Ohne Bildschirm testbar.
- `Holzstil.java` (1221) -- Look & Feel an einer Stelle (siehe unten).
- `Symbol.java` -- selbst gemalte Icons (`Symbol.Art`: Start, Pause, Stopp, Not-Aus, Liste, ...), keine Bilddateien/Glyphen.

Meldungen:
- `Meldung.java` (record: zeit, kanal, titel, text, farbe, knoepfe), `Meldungen.java` (Mithoerer, Ringpuffer `HOECHSTENS`,
  `zuhoeren/nichtMehrZuhoeren`, `eigene(...)`, `letzteMit(titelWoerter)`; Zuhoerer-Exceptions werden verschluckt).
- `Antwortfenster.java` -- Popup fuer Antworten auf Knoepfe. `Rueckmeldung.java` -- `auswerten`, `Art` (GEKLAPPT/
  NICHT_GEKLAPPT/HINWEIS), `label/warten/zeigen`. `Windowsmeldung.java` -- Infobereich (TrayIcon; Menue Zeigen/Beenden).
- `Warnhilfe.java` -- Regex-Regeln `REGELN` (EN+DE) -> "Was heisst das?" fuer Log-Warnungen; `finde`, `alsHtml`.
  Auch von `tools/LogFenster` benutzt.

Anzeige-Fenster (alle `implements Steuerprogramm.Abmeldbar`, Lage via `Holzstil.lageMerken`):
- `Kartenfenster` (Status, neueste Karte mit Titel "Zustand"/"State"), `Statistikfenster` (Karte + 3 `Diagramm`e +
  `Zeitverteilung`), `Vorratsfenster` (Burg-Stand-Tabelle, "Neu holen" = `!stand`), `Protokollleiste` (letzte Log-Zeilen aus
  `Log.snapshot()`, unter dem Spiel), `Minileiste` (JWindow obenauf: Pille + Start/Pause/Stopp), `Diagramm` (Balken/Linie).
- Statistik-Rechnung ohne UI: `Statistikdaten` (Durchlaeufe/h aus `Laufstatistik.history`, Arena-Quote aus
  `arena-lernen.txt` Spalte 11 S/N, Nahrung aus Burg-Stand-Karten), `Tagesstatistik` (persistiert `statistik-tage.txt`,
  Zeile `yyyy-MM-dd;Durchlaeufe;Sekunden;verloreneKaempfe;NahrungErste;NahrungLetzte;h0,...,h23`), `Zeitverteilung`
  (liest `kathedrale-zeiten.txt`), `Zeitleiste` (liest Energieplan/Kriegsplan/Turmplan, `kriegsplan-an.txt`; Warteschlange
  der Bruecke ist NICHT einsehbar).

Einstellungen-Helfer:
- `Einstellungswerte` (aktueller Wert je `!settings`-Befehl, `Wert.roh` nur wenn eindeutig zuruecksetzbar -> Rueckgaengig),
  `Eingabepruefung` (`zahlenbereich` -> JSpinner, `fehler` -> roter Hinweis; "Setzen" bleibt immer frei, der Bot prueft selbst),
  `Einstellungssuche` (Woerter-Suche, Umlaut-/HTML-/Case-egal), `Hilfesuche` ("Wo finde ich ...?", `INDEX` mit Zielen =
  Tutorial-Schluesseln).
- `Einstellungsaustausch.java` (1138) -- Code "HCBSET1:" + base64url(gzip(text)); nur Whitelist `LISTE` (Dateien + Schluessel,
  Arten SCHALTER/ZAHL/WORT/SCHLUESSEL/PROFIL/TEXT); private Profil-Befehle (`PRIVATE_BEFEHLE`) fliegen raus. `exportieren`,
  `lesen` (Vorschau), `anwenden` (vergleicht gegen JETZIGEN Stand, Sicherung nach `logs/einstellungen-vorher-<stempel>/` mit
  LIESMICH.txt, dann schreiben), `programmNeuStarten` (PowerShell `Start-Sleep 5; Start-Process $env:HB_EXE`, nur mit
  `jpackage.app-path`), Dialoge `kopierenDialog/einfuegenDialog`. Persoenliches (Lager, Limits, Lizenz, Discord, Sprache,
  Zeitzone, Spielername, Team/Arena-Daten, Bilder, Fensterlagen) NIE in die Liste.
- `Profile.java` -- `profile.txt` ("[Name]" + Befehlszeilen, `#` Kommentar, Reihenfolge = Profil 1/2/3); `anwenden(name,
  Consumer)` schickt Zeile fuer Zeile ueber den Befehlsweg; fasst selbst keine Einstellungsdatei an.
- `Steuereinstellungen.java` -- `steuerprogramm.properties`. Schluessel: `windowsmeldungen`, `minileiste.an/.x/.y`,
  `fenster.obenauf`, `steuerfenster.beimstart`, `protokollleiste.an`, `aussehen` (hell|dunkel), `schrift`
  (klein|mittel|gross|riesig), `tutorial.gesehen`, `tutorial.position`, `einstellungen.einfach`, `mitspieler.namen`, je
  Fenster `<name>.x/.y/.breite/.hoehe` (steuerfenster, status, statistik, vorrat, protokollleiste).
- `Tastenkuerzel.java` -- `tastenkuerzel.properties`; Enum `Aktion` (PROTOKOLL Strg+Alt+A, NOTIZBLOCK N, VORRAT V, STATISTIK S,
  STATUS Z, MINILEISTE I, MELDUNGEN G (=Antwortfenster), STEUERFENSTER B, BEENDEN X, PROFIL_1..3 leer); `frueher` (O+X)
  wird beim Laden auf die neue Vorgabe gehoben. Nicht E/M: Strg+Alt = AltGr (Euro, Mikro). Keines pausiert/stoppt den Bot.
- `Tastenwache.java` -- Polling-Faden, alle Tasten einer Kombi gleichzeitig unten, einmal je Druck; liest Kuerzel jedes Mal
  frisch. Nur Windows. Not-Aus F9 / Not-Pause F10 / Weiter F11 sind NICHT hier (config.properties, `NotAusWache`).

Sonstiges:
- `Tutorial.java` (722) -- Mini-Eule Pip: `SCHRITTE` (lange Tour, Kapitel), `KURZ` (5 Schritte, Erststart), `erststart`,
  `starten`; schaltet Reiter, `Steuerfenster.markieren(ziel)`, Funken-Animation (`PipBild`), speichert `tutorial.gesehen`.
- `Gesundheitscheck.java` + `Ampelkarte.java` -- Selbstcheck-Ampel (`Stufe` GRUEN/GELB/ROT/GRAU, `Punkt`): Spielfenster da,
  Fenstergroesse, DPI-Skalierung, eigene Bilder (`templates`), Energieplan + Virenschutz (langsam, nur auf Knopf, fragen
  Windows via Prozess). Bewertung in reinen statischen Funktionen (`*Aus(...)`) -> testbar. `groesseRichten()` nur auf Knopf.
- `Stigmafenster.java` -- JDialog vor Stigma-Auftraegen (Loeschen: Haken = LOESCHEN, gespeichert als "behalten" in
  `stigma-einstellungen.txt`; Leveln: Haupt/Zweitwert je Titan via `Stigmaplan.wahlSetzen`).

## Holzstil -- Konventionen (nicht brechen)

- **Alle Farben/Fonts/Bausteine kommen aus `Holzstil`**: `knopf/kleinerKnopf/symbolKnopf`, `karte(titel, layout)`,
  `kopfleiste`, `feld`, `geheimfeld`, `kaestchen`, `auswahl`, `zahlenfeld`, `liste`, `tabelle` (Zebra), `lesebereich`,
  `rollen`, `reiter`, `hinweis`, `beschriftung`, `ueberschrift`, `Pille`, `zustandsfarbe/zustandswort`, `zahl` (Tausender),
  `ohneMarkdown`, `kartenfarbe(discordFarbe)`. Keine eigenen Farbliterale in neuen Fenstern (Ausnahme: schicht/ hat eigenes
  dunkles Schema).
- Farben sind **statische, nicht-finale Felder** (`GRUND, KOPF, TEXT, HELL, FELD, LINIE, LEISE, ZEBRA, GUT, WARNUNG, FEHLER,
  KLICK, AUF_SIGNAL`), gesetzt von `thema(dunkel)` **vor** dem Bauen. Gebaute Fenster malen sich nicht um -> Hell/Dunkel-Wechsel
  baut alles neu (`Steuerprogramm.fensterNeuBauen`, merkt Lage/Reiter/Sichtbarkeit; Log-/Notizfenster zu+auf). Neue Fenster
  dort in `fensterBauen` und `fensterNeuBauen` eintragen und `Abmeldbar` implementieren (Timer/Zuhoerer abmelden).
- Zustandsfarben fest: gruen laeuft, bernstein wartet/warnt, rot Fehler, grau still. Kontrast >= 4,5:1 in beiden Themen
  (`HolzstilTest` rechnet WCAG nach) -- neue Farben dort pruefen lassen.
- Schriftgroesse: `SKALA` aus `steuerprogramm.properties` Schluessel `schrift`, **einmal beim Klassenladen** gelesen
  (`Path.of("steuerprogramm.properties")`, relativ zum Arbeitsordner!) -> gilt erst nach Neustart. Masse immer ueber `px()`.
- `lageMerken(fenster, einst, name, vorgabe)`: Lage merken; Fenster duerfen nie ueber dem Spiel starten
  (`Holzstil.SPIEL` = Autors 1920x1080-Layout, Spiel rechts oben 758,0 1200x810; Steuerfenster links 765x800).
- i18n: jeder sichtbare Text `Sprache.t("english", "deutsch")` (EN zuerst!). Tests, die deutsche Texte pruefen, setzen
  `Sprache.set(DE)` in `@BeforeAll` und zurueck auf EN.

## Invarianten / Gotchas (aus "Autor ..."-Kommentaren)

- Keine Fenster ueber dem Spiel; Steuerfenster startet `toBack()` (ausser nach Update-Marker `fenster-nach-vorn.txt` oder
  erstem Start ohne `tutorial.gesehen` -> einmal vorn). `setAutoRequestFocus(false)`.
- Waehrend der Bot spielt gehoert die Maus dem Bot: Stopp/Pause/Beenden nur per Taste; ein Start-Knopf (Autor 14.10.).
- Stigma Leveln/Hundert fragen vorher (verbraucht Strahlen/Stones); Loeschen mit allen Sets angehakt -> Befehl + " alles".
- `ServerSparsam.an()`: kein Fenster, kein Sekundentakt (`ruhe()`), keine Tastenwache, kein BotFenster.
- Fehlerbericht: max. 1 je 60 s; ZIP nach `logs/berichte/bericht-<id>.zip` (+ `-spielbild.png`), Inhalt info.txt,
  fehlerbericht.txt (`core.Report.build`), letzte Protokolle, Einstellungen **ohne Geheimnisse**, eigene Knoepfe aus
  `Matcher.EIGEN_ORDNER` (<= 2 MB), Kathedralen-Zeiten. `fehlerberichtAnlegen` legt `fehlerberichte/<zeit>/` an.
- `befehl.txt` im Bot-Ordner: jede Zeile mit "!" wird alle 2 s als Befehl ausgefuehrt und die Datei geloescht (Fernsteuerung
  fuer Tests/Automatisierung, Autor 18.10.).
- **`SteuerBefehleTest` parst `Steuerfenster.java` per Regex**: Aufrufe `zeile("t","h","bereich","bsp")`,
  `freiZeile(...)`, `schalter("t","bereich")`, `befehlSchalter(...)`, `{"Text", "!befehl"}` muessen String-Literale bleiben;
  jeder `"!wort..."`-Literal in steuer/ muss ein Verb sein, das DiscordBridge kennt (`case "!..." ->`). Neue Befehle zuerst
  in der Bruecke anlegen.
- `EINFACH_KARTEN = {0,1,2,3,6,7,8,14}` sind **Positionsindizes** der Karten in `einstellungenReiter` -- beim Einfuegen
  einer Karte anpassen.
- Tutorial-/Hilfe-Ziele (`ziele.put("modus"|"start"|"werkzeuge"|...)` und Bereichstitel-Praefixe, `ZIEL_EN` fuer Englisch)
  muessen existieren -- `HilfesucheTutorialTest.alleZieleGibtEsImFenster` baut das Fenster und prueft.
- Pfade: meist `Ordner.bei(...)` (Bot-Ordner), aber `Steuerprogramm.vorbereiten` nutzt `Path.of("")` und Holzstil
  `Path.of(...)` -> Arbeitsordner muss der Bot-Ordner sein (run.bat/exe sorgen dafuer).

## Dateien auf der Platte (Bot-Ordner)

Gelesen+geschrieben: `steuerprogramm.properties`, `tastenkuerzel.properties`, `profile.txt`, `statistik-tage.txt`,
`config.properties` (nur Discord-Schluessel ueber `DiscordBridge.configWertSchreiben`), Einstellungsaustausch-Whitelist
(leuchtturm.txt, kolosseum.txt, titanturm.txt, kriegsplan-an.txt, clanladen.txt, event-*.txt, mauswache.txt,
arena-beobachter.txt, konten.txt, bezahlung.txt [nur dungeon/cathedral/arena], aufstellung.properties, event-kisten.txt,
stigma-einstellungen.txt, stigma-rangliste.txt, tastenkuerzel.properties, profile.txt), `stigma-einstellungen.txt`.
Nur gelesen: `arena-lernen.txt`, `arena-plaetze.txt`, `kathedrale-zeiten.txt`, `templates/gelernt/titanen/titanen.txt`,
`befehl.txt` (dann geloescht), `fenster-nach-vorn.txt` (dann geloescht). Geschrieben: `logs/berichte/`, `fehlerberichte/`,
`logs/einstellungen-vorher-*/`.

## Tests (src/test/java/de/hustlebot/steuer, JUnit 5, alle ohne Bildschirm ausser Probes)

BefehlsbauTest, SteuerBefehleTest (Quelltext-Parser, s. o.; ein Test @Disabled wegen Hauptbot-Interna), ProfileTest,
TastenkuerzelTest, SteuereinstellungenTest, MeldungenTest, RueckmeldungTest, EinstellungsaustauschTest (@TempDir),
EinstellungswerteTest, GesundheitscheckTest, HilfesucheTutorialTest, HolzstilTest (WCAG), SchriftgroesseTest,
StatistikdatenTest, TagesstatistikTest, WarnhilfeTest, ZeitleisteTest.
Probes (nur auf Wunsch, brauchen Display bzw. `xvfb-run`): `SteuerVorschauProbe` (`mvn test -Dtest=SteuerVorschauProbe
-Dsteuer.vorschau=1 [-Dsteuer.vorschau.ordner=...]`, malt PNG je Reiter hell/dunkel nach doku/steuerprogramm-bilder/),
`RueckmeldungProbe` (dito). Beide nutzen `Steuerprogramm.vorschau(ordner)` -- Befehle werden dort nur als Meldung "Vorschau" angezeigt.

## Rezepte

- **Neuer Knopf, der etwas am Bot tut**: Befehl in `Befehlsbau` (Konstante/Bauer) -> Knopf ueber `Holzstil.knopf(...)` ->
  `steuer.senden(text, quelle, abfrage, rueck::zeigen)`. Kennt die Bruecke das Verb nicht, erst `DiscordBridge.befehl`.
- **Neue Einstellungszeile**: in `Steuerfenster.einstellungenReiter` in passendem `bereich(...)` mit `zeile/schalter/...`
  (Literale!), ggf. `ERKLAERUNG`/`OPTIONEN`-Map, Wert in `Einstellungswerte.baue()`, Regel in `Eingabepruefung`,
  Stichwort in `Hilfesuche.INDEX`; teilbar? -> `Einstellungsaustausch.LISTE`.
- **Neues Anzeige-Fenster**: JFrame + `Abmeldbar`, in `fensterBauen`/`fensterNeuBauen`/`umschalten`/`offen` eintragen,
  `Tastenkuerzel.Aktion` ergaenzen, Lage mit `lageMerken`.
- **Neues Tastenkuerzel**: `Tastenkuerzel.Aktion` + `Steuerprogramm.umschalten` switch.
- **Startmodus hinzufuegen**: `Befehlsbau.Modus` (befehlswort wie `!start <wort>`), ggf. Felder in `steuerungReiter`/
  `startBefehl`; `SteuerBefehleTest.jederModus...` deckt es ab.
- **Warnung erklaeren**: Regel in `Warnhilfe.REGELN` (beide Sprachen), WarnhilfeTest.
- **Farbe/Schrift aendern**: nur `Holzstil.thema`/Font-Konstanten; HolzstilTest laufen lassen.
