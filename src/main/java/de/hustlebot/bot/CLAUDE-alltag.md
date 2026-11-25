# Alltags- und Hilfsmodi (bot/ + Planer in core/)

Kleine "Botengaenge" und Helfer, die NEBEN den grossen Modi (Verlies, Kathedrale, Event, Arena, Clan-Krieg, Portal)
laufen: Clan-Laden, Freunden senden, Burgen angreifen, Nahrungskisten, Truhen (Kathedralen-Kisten), Helden-Set,
Trupp auffuellen, Chat-Einladungen, Burg-Leiste lesen. Dazu die Zeitplaner in `core/` (Ladenplan, Freundeplan,
Burgenplan, Zauberstabziel, Tagestipp, Thronsaal).

Hinweis: Dieses Repo ist (mindestens teilweise) die **Freunde-Fassung** des Bots (`Tagestipp.FREUNDE = true`,
Kommentare "Freunde-Bot 22.10.: ... fehlt hier noch (kommt mit den Nahrungskisten des Hauptbots)"). "Autor" =
Hauptspieler/Auftraggeber, "Ben"/"Mia" = Freunde, "Zweitkonto A/B", "TR5" = Zweitkonten.

## 1. Zwei Bauformen

1. **Eigener GameMode** (registriert in `BotEngine` Konstruktor, `register(new ...)`): `ClanLadenMode` (id `clanladen`),
   `FreundeSendenMode` (`freundesenden`), `BurgenMode` (`burgen`), `NahrungskistenMode` (`nahrungskisten`),
   `HeldenSetMode` (`heldenset`, nur Probe). Muster in `step()`: zuerst `Stoerfenster.afkUpdate`, ggf.
   `Stoerfenster.ereignisTextWegklicken`, `Stoerfenster.belohnungWegklicken` hoechstens 1x/s (kostet ~0,2 s), dann
   den eigentlichen "Gang" einen Schritt weiter. `Outcome.ACTED/WAITING/FINISHED/FATAL`; `runs.progress(...)` fuettert den
   Haenger-Waechter ("No progress" nach ~20 s ohne Meldung!), `runs.completeRun()` vor `FINISHED`.
2. **Eingebettete Gang-Klasse** ohne GameMode, mit `starten()` + `boolean schritt(Mat, BotContext)` (eine Handlung pro
   Aufruf, keine innere Schleife -> Pause/Stopp greifen zwischen Klicks; `false` = vorbei, Bot steht in der Burg):
   `ClanLaden`, `FreundeSenden`, `Nahrungskisten`, `Nahrungsvideo`, `Truhenoeffner`, `HeldenSetWechsel` (liefert
   `Outcome`), `TruppAuffuellen`. Werden von Modi gehalten (`private final X x = new X()`) und durchgereicht.

`BotEngine.istBotengang(modus)` = lighthouse, colosseum, clanwar, titantower, clanladen, freundesenden, teamscan
(eingeschobene Kurzauftraege).

## 2. Einschub-Logik (wie sie zwischen die Hauptmodi kommen)

Zentrale Stelle: `discord/DiscordBridge.nebenbeiPruefen()` — nach jedem fertigen Run des laufenden Auftrags (nur bei
Modi in `NEBENBEI_BEI`/`KRIEG_FORTSETZEN`). Reihenfolge/Prioritaet:
1. Clan-Krieg (Kriegsplan) geht vor; wenn eingeschoben, kommt NICHTS anderes dazu, und `HeldenSet.automatischerKrieg()`
   wird vermerkt.
2. sonst (nicht `nurKrieg`): Leuchtturm (+Kolosseum), Titanen-Turm (`Turmplan`), **Freunden senden**
   (`Freundeplan.faellig()`), **Clan-Laden** (`Ladenplan.faellig()`), Team-Scan.
3. **Burgen** (`Burgenplan.faellig()`): bei jedem Modus ausser Arena und nie zusammen mit clanwar.
Jeweils nur wenn `vorlagenDa(new XMode(), id)` (requiredTemplates vorhanden). Die `angestossen(...)`-Vermerke laufen
erst NACH dem Einschub (Liste `vermerken`). Danach laeuft der Rest des urspruenglichen Auftrags weiter.

`DiscordBridge.resetStartPruefen()`: Steht der Bot (BEREIT/FERTIG, nicht nach Stopp/Not-Aus/Fehler), startet er im
02:00-Fenster (bis 06:00, hoechstens alle 20 min) selbst die Kette `titantower` -> `freundesenden`. Um 12:00 nicht
(Autor spielt evtl. selbst). Versuche werden erst nach gelungenem Start verbucht.

Arena: `ArenaMode` macht Burgen selbst als "Ausflug" in den Wartezeiten zwischen Stages (`burgenWunsch(runde)`:
Runde 1–4, `Burgenplan.faellig()`, Vorlagen da; `BurgenMode.fristSetzen(rundenendeErwartet)`; im Ausflug zaehlt kein Run).

## 3. Dateikarte

### Modi / Gaenge (bot/)
| Datei | Rolle |
|---|---|
| `ClanLadenMode.java` (55) | Mode `clanladen`: startet `ClanLaden`, bei Ende `Ladenplan.erledigt()`; nicht sauber -> FATAL. |
| `ClanLaden.java` (443) | Gang Food chests mit Clan-Muenzen kaufen. Phasen `HIN, KARTE, LADEN, RAUS`; `laden()` = Kaufen-Logik, `muenzenLesen()`. |
| `FreundeSendenMode.java` (93) | Mode `freundesenden`: startet `FreundeSenden`, Fortschritt melden, `Freundeplan.erledigt(tag, gesendet)`. |
| `FreundeSenden.java` (975) | Gang Ressourcen senden + Geschenke abholen. Phasen `HIN, LISTE, ABHOLEN, RAUS`; `liste()`, `abholen()`, `wischen()`, `zaehlerLesen()`, `zahlGlaubhaft()`, `sendeKandidaten()`, `knopfIstBlau()`. |
| `FreundesChat.java` (405) | Statische Helfer: Verlies-Einladung eines Freundes im Chat finden/annehmen (`nachsehen(...)` -> `Ergebnis`). Nur von `DungeonMode` benutzt. |
| `ChatAnimation.java` (96) | `warteBisRuhig(ctx, ms)`: wartet, bis linke Bildhaelfte still steht (Chat-Animation). |
| `BurgenMode.java` (375) | Mode `burgen`: Feind-Burgen auf der Weltkarte angreifen. `enum Schritt`, `burgFinden()`, `kraftLesen()`, `zuWenigEinheitenJa()`. |
| `Burgstand.java` (377) | Nur lesen: Rohstoffleiste oben in der Burg (`zahlen()`), `nahrung()`, `taschenZahl()`; eigener Ziffernleser `burgZahl()`/`burgZiffer()`. |
| `Nahrungskisten.java` (1337, gross) | Gang: Nahrungskisten aus der Tasche oeffnen (Clan-Krieg, Portal, GemBay, Bezahlkasse). Phasen `LESEN, TASCHE, ERKUNDEN, OEFFNEN, ZUMACHEN, NACHLESEN, LADEN, VIDEO`. Planung `planen()`, `bestePlanung()`, `bestePlanungNah()`, `wenigerKisten()`. Lesehelfer `nahrungLesen()`, `zahlVerstaerkt()`, `textVerstaerkt()`, `ziffernGlaetten()`, `nachlesenNoetig()`. Preis `kosten()`/`preisLesen()`. |
| `NahrungskistenMode.java` (161) | Mode `nahrungskisten` (`!nahrungskisten`/`!foodchests`): Tasche bis ~Lager auffuellen; auch eingebettet von `PortalMode`. |
| `Nahrungsvideo.java` (213) | Gang: +90 000 Nahrung per Werbevideo (nur Clan-Krieg, max 2/Tag). Phasen `APFEL, KNOPF, WERBUNG`. |
| `Truhenoeffner.java` (715) | Gang: Kathedralen-Kisten (`bag_chest_N`) in der Tasche oeffnen, wenn Schluessel fehlen. Benutzt von `CathedralMode`. `kistenBildschirmWegklicken()` statisch. |
| `HeldenSet.java` (141) | Einstellungen Helden-Set (Datei `heldenset-krieg.txt`), Flag "automatischer Krieg". |
| `HeldenSetWechsel.java` (394) | Gang: Helden-Set ueber Kleiderbuegel wechseln. `enum Schritt { HEIM, PROFIL, BATTLES, HELD, BUEGEL, WAEHLEN, APPLY, ZU, FERTIG }`. |
| `HeldenSetMode.java` (59) | Mode `heldenset`: Probe vorher-Set -> nachher-Set ohne Krieg. |
| `TruppAuffuellen.java` (412) | Clan-Krieg: unvollstaendigen Trupp mit staerksten Ersatz-Karten auffuellen. Von `ClanWarMode` gehalten. |
| `Zauberstaebe.java` (56) | `ausRangliste(bild)`: eigene Zauberstab-Zahl im Hammer-Event-Ranking (gelbgruene Zeile). Von `EventMode`. |
| `LeereTruppe.java` (37) | OCR-Erkennung "Move your troops to the Barracks!" (`sichtbar(ctx)`, ~0,3 s). Von `CathedralMode`, `LighthouseMode`. |

### Planer (core/)
| Datei | Rolle |
|---|---|
| `Ladenplan.java` | Clan-Laden-Fenster 02:00 und 12:00 (je 55 min, endet vor Krieg 03:01). Schalter `clanladen.txt` (Std. **aus**), Vermerk `clanladen-erledigt.txt`, Retry 20 min. |
| `Freundeplan.java` | Fenster 02:00 und 12:00, jedes gilt bis zum naechsten. Schalter `freunde-senden.txt` (Std. **an**), Vermerk `freunde-senden-erledigt.txt`, 20 min Retry, max 3 Versuche/Fenster. Reine Regel `pruefen(...)` testbar. |
| `Burgenplan.java` | Alle 30 min (`ABSTAND_MS`), Schalter `burgen-plan.txt` (erste Nicht-Kommentarzeile `an`/`on`, ohne Datei AUS; nur Zweitkonto-Bot). Datei alle 10 s neu gelesen. Kein Vermerk auf Platte. |
| `Zauberstabziel.java` | Grenze Zauberstaebe im Hammer-Event (`zauberstaebe.txt`, 0 = aus). `erreicht()` -> Event verlassen, Kathedrale. |
| `Tagestipp.java` | Discord-Tipp des Tages (Befehlsliste `ALLE`), beim Start + 12:00–12:30, Wechsel 02:00. Datei `tagestipp.txt`. |
| `Thronsaal.java` | Thronsaal-Stufe je Spielername (`thronsaal.txt`, `name=stufe`); `enum Teil` mit Mindeststufe (Kathedrale 6, Titanenturm/Kolosseum 7, Edelsteinbucht 8, Verlies/Held 9, Sets 11 ...). `gesperrtGrund(modeId, name)` sperrt Modi; Datei `<modus>-aus.txt` schaltet Modus ganz ab. |
| `SelbstKaempfe.java` | GEHOERT ZUR ARENA, nicht zum Alltag: Notizblock `arena-selbst-kaempfe.txt` fuer Spieler, die Autor selbst bekaempft/angesehen hat (Name TAB Clan TAB Kaempfe TAB Liste). Benutzt von `ArenaMode`, `ArenaListenRegel`, `Konten`. Unlesbare Datei -> nie schreiben. |

## 4. Ablaeufe im Detail

### Clan-Laden (`ClanLaden`)
Burg -> `cl_clan_knopf` -> Clan-Karte (Muenzen `cl_muenzen` oben links ablesen) -> `cl_store` (ggf. Karte wischen, Store
liegt links, max 6) -> Menue "Store (Level 3)" -> `cl_store_knopf` -> "Royal Store" (`cl_store_titel`) -> langsam
scrollen (`dragLangsam`, max 40) bis `cl_food_chest` -> Preisfeld `cl_preis_muenze` in derselben Spalte darunter ->
kaufen bis ausverkauft (`cl_ausverkauft` ueber dem Namen), Muenzen reichen nicht oder 7 Stueck (`MAX_JE_TAG`) -> X ->
`cl_home` -> Burg. Grenzen: 4 min, 60 Klicks.
Regeln:
- Kaufen NUR wenn unter der Kiste das Clan-Muenzen-Symbol steht (sonst Diamant -> nichts). Nie Diamanten.
- Nach jedem Kauf MUSS der Muenzstand sinken, sonst Schluss ("Kauf ohne Wirkung"). Die Bestaetigung
  `cl_kauf_bestaetigen` liegt UEBER dem Laden (imLaden bleibt true, Live-Test 16.10.).
- Preis oder Muenzen unlesbar -> nichts kaufen.
- Weltkarte hat denselben "Home"-Knopf wie die Clan-Karte! Clan-Karte = Home sichtbar && !`Weltkarte.erkannt` && Muenzen sichtbar.
- Schneller Wisch sprang ueber die Food-chest-Reihe -> langsame Zuege (ein Drittel Bild), Kiste am unteren Rand -> nachruecken.
- Auch von `Nahrungskisten` (Phase LADEN) benutzt, wenn vor dem Clan-Krieg Kisten fehlen.

### Freunden senden (`FreundeSenden`)
Burg -> Pokal oben links (`fr_pokal`/`rs_pokal`) -> Rangliste, Reiter Friends (`fr_reiter_friends[_aktiv]`) -> Zahl
unter `fr_heute_text` lesen -> immer den obersten blauen `fr_send` (Schwelle 0,85) in der Send-Spalte zwischen
`fr_kiste_balken` und `fr_add_friends` druecken, bis 0 -> keine Knoepfe: Liste nach unten wischen (am Ende einmal hoch)
-> ABHOLEN: `fr_get_resources` -> `fr_claim_all` (bei `fr_no_space`/`fr_limit_text` -> `fr_claim_ok`), max 8 s warten
-> RAUS: rotes X -> Burg.
Regeln (Gegenlese 05.10. — nicht lockern):
- NIE klicken: Balken-Kiste, "Add friends", Freundschaftsanfragen-Schalter, irgendwas mit Diamanten.
- Zahl (gruen auf gelb) wird per Gruen-Maske + OCR hinter drei Woertern ("Nummer","Zahl","Anzahl") gelesen, Mehrheit
  zaehlt. Glaubhaft: am Anfang 1..20, danach genau eins weniger. 0 gilt nur wenn vorher 1 oder kein blauer Knopf, und
  erst beim zweiten Lesen. Ohne sichere Zahl >0 kein Send. Nie mehr Sends als Startzahl / `MAX_JE_TAG`=20.
- Zahl von "Get the resources" verdeckt -> `ohneZahl`-Modus: senden solange blaue Knoepfe, max 20. Graue Knoepfe
  (GRAU_SCHWELLE 0,70, erst beim zweiten Blick) = Tageslimit.
- Pausen (`!pause`) zaehlen nicht zur 3-min-Zeitgrenze (`LUECKE_MS`).
- Mode: nicht zurueck in Burg -> FATAL; Abbruch in der Burg -> kein Vermerk, Retry in 20 min. `Freundeplan.erledigt`
  vermerkt kurz nach Reset (02:00–02:10) eine 0 NICHT (koennte gestriger Stand sein).
- Mode-Id bewusst nicht "freunde" (im Verlies = Mitspieler).
- Fortschritt bei jedem Klick melden (Berichte #FHF4, #ZQFM/#8GD9: sonst "No progress").

### Burgen angreifen (`BurgenMode`, nur Zweitkonten)
`ZUR_KARTE` (Map/`bu_karte`/`map_button[_alt]`) -> `INSELN`: `bu_feind_symbol` (findAll 0,82, bearbeitete Inseln
ausgeschlossen) -> `INSEL_OEFFNEN` -> `BURG_SUCHEN` (beste aus Serie `bu_ziel_burg`, `_2`, `_3` ... je Inseltyp) ->
`FENSTER`: Kraft 275 px ueber Scout lesen, vergleichen mit `MeinTeam.squadPower()`; >200 000 staerker -> bis zu 2x
`bu_wechseln` -> `bu_ansehen` (Scout) -> `ANGRIFF` `bu_schnell` -> `KAMPF` bis `bu_heim` -> naechste Burg; max 6
Angriffe je Insel (verlorene Burg bleibt stehen!) -> keine Symbole mehr: `HEIM`.
- "Yes" (`bu_ja`) nur, wenn OCR-Text darueber "units"/"start battle" enthaelt und KEIN Kaufwort (buy, gem, energy, refill ...).
- HEIM: Map-Knopf der Insel == Map-Knopf der Burg (map_button_alt 1,00 auf beiden) -> `heimGeklicktUm` merkt, ob
  `Weltkarte.heim` schon getippt wurde. Nach Abbruch erst X (`Stoerfenster.notausgang`), dann heim.
- Mit Frist (Arena-Ausflug): neue Insel nur bei >=50 s, neue Burg bei >=40 s Rest. `haengerBerichtNachS()` = 90.
- Keine Invasions. Nahrung egal.

### Nahrungskisten (`Nahrungskisten`)
Aufrufer und Startarten:
- `ClanWarMode` (wenn `Nahrungskisten.istAn()`): `starten()` -> Ziel = `kosten()` (zuletzt unter "Deploy" gelesener
  Preis, `clankrieg-preis.txt`, sonst 280 000), mit Clan-Laden und Werbevideos.
- `NahrungskistenMode`/`PortalMode`: `startenAuffuellen(ziel, mindestens, false, false)` — nur Tasche.
- `GemBayMode`, `Bezahlkasse`: `starten(ziel,false,false)` bzw. Auffuellen bis Lager.
Ablauf: LESEN (`nahrungLesen` — liest bis 6x bis zwei gleiche Werte, Anzeige zaehlt beim Betreten hoch; fallender Wert
nach Anstieg = Lesefehler; Arena-Turnierbalken -> nicht lesen) -> genug? fertig. Tasche zeigt 0 (`Burgstand.taschenZahl`)
-> direkt Clan-Laden. Sonst TASCHE (`bag_button`) -> ERKUNDEN: jede Kiste `cw_nahrung_kiste_N` (Serie ohne Obergrenze)
einmal antippen, "Contents:" (`cw_nahrung_inhalt`) Inhalt lesen, Stueckzahl oben links -> `planen()` -> OEFFNEN
(`cw_nahrung_oeffnen` "Open x1", Animation per Tippen bei (0,24;0,60) beschleunigen, `cw_nahrung_weiter` "Next x1")
-> ZUMACHEN -> NACHLESEN (`nachlesenNoetig`: Zwischenwerte beim Hochzaehlen nachlesen) -> ggf. LADEN (ClanLaden) /
VIDEO (Nahrungsvideo, nur Krieg).
Planung:
- `bestePlanung`: kleinste Summe >= Bedarf, bei Gleichstand wenigste Kisten. Reicht alles zusammen nicht -> NICHTS oeffnen
  (Clan-Krieg setzt dann nur Titanen).
- `wenigerKisten`: lieber wenige grosse (2x1 Mio statt 7x135k+1 Mio), wenn <= 200 000 zu viel (Autor 25.10.).
- `bestePlanungNah` (Auffuellen): nah ans Ziel, mindestens `mindestens`.
Regeln: nichts kaufen, Diamantfenster nie bedienen. "Lager koennte ueberlaufen -- trotzdem?" -> Yes
(`Stoerfenster.lagerUeberlaufJa`); "Lager voll"-Frage -> ablehnen, gezaehlte Kiste zurueckbuchen (nur wenn <10 s nach Open),
zumachen. Reihenfolge seit 17.10.: erst Tasche, dann Clan-Laden. Waehrend VIDEO nie zumachen/X (kostet Belohnung).
Grenzen 13 min / 120 Klicks. Schalter `nahrungskisten.txt` (Std. an).

### NahrungskistenMode
Nicht in der Burg -> bis 6x X/`ev_angebot_x`/Weltkarten-Home (`worldmap_home` ab 0,65, unten links)/`home_button`/
`ts_burg_knopf`. Ziel: `zielMenge>0` -> min(Lager, da+Menge), Mindest = Ziel−135 000; sonst Lager, Mindest 3/4 Lager.
Danach nachlesen, bis 2 weitere Versuche (Werbefenster verdeckte die Tasche). `gebracht()`/`nachher()` fuer PortalMode.

### Nahrungsvideo
Apfel in der Burg -> Fenster "Food" -> `cw_video_knopf` (+90 000 Free) -> Werbung (25–180 s, NICHTS druecken). Max 2/Tag
(Zaehler `nahrung-videos.txt`, Reset 02:00), 60 s Pause. `moeglich(ctx)`.

### Truhenoeffner (Kathedrale)
Kathedralen-Fenster X -> Burg -> `bag_button` -> Warehouse (`bag_title`) -> `bag_chest_N` (nur selbst aufgenommene Sorten;
durchgestrichene NIE — ausdruecklich verboten) -> `bag_open_10`/`bag_open_1` bis Knopf weg -> `bag_close` -> naechste
-> Tasche zu. Tippen (0,24;0,60) beschleunigt Animation. Fertig erst, wenn Tasche offen und leer (nicht "in der Burg").
`starten(ctx, wenigeUeberspringen)`: <= `WENIGE_KISTEN` (9) -> nichts oeffnen, Kreislauf geht ins Event. Nach Klickgrenze
erst aufraeumen (max 40 Schritte, Bericht #A6Y3). X-Schwelle 0,70 (close_x passt dort nur ~0,77).

### Helden-Set (Clan-Krieg)
`HeldenSet` liest `heldenset-krieg.txt` (`an`, `vorher`=War, `vorherplatz`=2, `nachher`=Normal, `nachherplatz`=1,
`vonhand`). `ClanWarMode.reset()` holt `HeldenSet.automatischAbholen()` (gilt 30 min); Wechsel nur bei automatischem
Krieg oder `vonhand=an`, und nur wenn `an`. `HeldenSetWechsel`: Profil (52,48) -> Battles -> Held -> `hs_kleiderbuegel` ->
`hs_sets_titel`, Namen per OCR an Zeilen y=340+63,5*i -> nur wenn an Platz N GENAU der Name steht (`passt`, "War" !=
"Tank War") -> `hs_apply` -> X bis Burg. Scheitern -> zurueck, Krieg wird trotzdem aufgestellt. Alle Zeitgrenzen
x `Rechnertempo.mal()` (langsamer Server). Koordinaten fest fuer 1159x770, als Anteile.

### TruppAuffuellen (Clan-Krieg)
Deploy -> Previous Squad -> Deploy -> Warnung "incomplete squad" -> `cw_nein` -> obere Reihe nach links wischen,
untere Reihe wischen bis neue Einheiten -> staerkste in "Vacant"-Plaetze ziehen bis Zaehler voll -> Deploy.
Kernregel (Server 01.11.): Nach JEDEM Zug Zaehler (`zaehlerLesen`, schmaler Ausschnitt, "6/8" sonst "618") pruefen;
waechst er nicht/schrumpft -> `cw_previous_squad` stellt wieder her, Karte tabu. Ohne lesbaren Zaehler nichts ziehen.
Scheitert alles -> wie frueher bestaetigen ("Yes" auf zweite Warnung): lieber unvollstaendig als gar nicht.
ACHTUNG: Vorlagen `cw_nein`, `cw_vacant`, `cw_helm`, `cw_kartenkraft` liegen (Stand jetzt) weder in `templates/` noch in
`nachschub/` -> ClanWarMode prueft `has(T_NEIN)`, Auffuellen ist ohne sie inaktiv.

### FreundesChat (Verlies)
`DungeonMode.vorDemTeilnehmen()` / `chatAnnehmen()` / `aufFreundWarten()`: Chat oeffnen (`chat_tab`), `ChatAnimation.warteBisRuhig`,
Reiter `chat_invite_tab`, Zeile `chat_invite`, Name mit Freundesvorlagen (`DungeonMode.freundVorlage`) per
`Matcher.weisseSchrift` in Groessen 1.0–1.35 vergleichen: >= 0,75 UND 0,10 Vorsprung vor jedem anderen Freund ->
`chat_invite_accept`; Erfolg erst, wenn Teilnahmebildschirm mit "Accept (1 Fackel)" in 5 s kommt. `annehmen=false` nur
nachsehen (Kreuzungs-Fall: beide laden gleichzeitig ein -> Muenzwurf). Feste Wartezeiten fuer Animationen sind verboten
(ChatAnimation-Kommentar): immer "bis das Bild steht".

### Burgstand (lesen)
Symbole `rs_*` + `cw_nahrung_burg`; "Kerne" (`*_kern` in `templates/gelernt/burgstand`) ohne Hintergrund passen 85–100 %
statt 50–80 % — der bessere Fund zaehlt (`symbol()`). Nur obere 16 % (Pokale 20 %). Zahl endet vor dem naechsten Symbol
der Zeile; zuerst weisse Spielziffern (`burgZahl`), dann OCR. Genutzt von `Lesetest` (alle Zahlen),
`Nahrungskisten.nahrungLesenEinmal` (`nahrung`), `taschenZahl` (Zahl im gruenen Kreis an `bag_button`).

## 5. Laufzeit-Dateien (Bot-Ordner, `Ordner.basis()`; relative `Path.of` landen dank `user.dir` auch dort)
| Datei | Inhalt |
|---|---|
| `clanladen.txt` / `clanladen-erledigt.txt` | Schalter (Std. aus) / ISO-LocalDateTime des erledigten Fensters |
| `freunde-senden.txt` / `freunde-senden-erledigt.txt` | Schalter (Std. an) / Fenster (alt: nur Datum = 02:00-Fenster) |
| `burgen-plan.txt` | `an`/`aus` (ohne = aus) |
| `nahrungskisten.txt` | Schalter Kisten vor Clan-Krieg (Std. an) |
| `clankrieg-preis.txt` | zuletzt gelesener Deploy-Preis (10k..5 Mio) |
| `nahrung-videos.txt` | Videozaehler je Tag |
| `heldenset-krieg.txt` | key=value, s. oben |
| `zauberstaebe.txt` | Zahl, 0 = aus |
| `tagestipp.txt` | an/ping/index/Tage |
| `thronsaal.txt` | `Spielername=Stufe` |
| `<modus>-aus.txt` | Modus komplett aus (Thronsaal.gesperrtGrund) |
| `arena-selbst-kaempfe.txt` | SelbstKaempfe (Arena) |

## 6. Discord
- `!settings clanladen|clanstore an/aus` -> `Ladenplan.setAn`; `!settings freunde|friends an/aus` -> `Freundeplan.setAn`.
- `!settings zauberstaebe|wands 1000|aus` -> `Zauberstabziel.setZiel`.
- `!settings heldenset|heroset an|aus|vorher <Name> [Platz]|nachher <Name> [Platz]|test` (test startet `heldenset 1`, nur wenn Bot steht).
- `!settings tipp|tip an|aus|jetzt|weiter|ping everyone|aus|<ID>`.
- `!nahrungskisten` / `!foodchests` -> Mode `nahrungskisten`. (`!settings nahrungskisten N` ist etwas ANDERES:
  `Eventautomatik` Kisten je Event-Gang.) Nahrungskisten-Schalter ueber Einstellungs-Formular (Feld `nahrung`).
- `!start burgen|clanladen|freundesenden|heldenset` direkt; Mode-Erkennung in `DiscordBridge` (Woerter "burgen/castles/raid",
  "clanladen/clanstore/clanshop", "heldenset/heroset").
- Einstellungsfenster `steuer/Einstellungswerte` liest `HeldenSet`.

## 7. Tests (src/test/java/de/hustlebot/..., reine Logik, keine Spielbilder)
- `bot/HeldenSetTest` (Namensvergleich `passt/normal`, `nameUndPlatz`), `bot/HeldenSetZeitenTest` (Grenzen x Rechnertempo via Reflection).
- `bot/NahrungNachlesenTest` (`nachlesenNoetig`, Protokollzahlen 29.10.), `bot/NahrungskistenGrossTest` (`bestePlanung` vs `wenigerKisten`).
- `bot/TruppAuffuellenTest` (Zaehler "6/8"/"4l4", `zugMisslungen`, `ganzZuSehen`, `untenWischX`, `schonGesehen`, `eineZifferAnders`).
- `bot/LeereTruppeTest` (`ausText` mit echten OCR-Lesungen). `bot/BezahlkasseNahrungKistenTest`, `core/BezahlungNahrungKistenTest` (Bezahlarten food/food+chests).
- `core/FreundeplanTest` (`pruefen`, `fenster`, `kurzNachReset`), `core/BurgenplanTest` (`lesen` mit @TempDir), `core/SelbstKaempfeTest`.
- `src/test/resources/burg/leiste-rechts-{1,2}.png` werden derzeit von keinem Test referenziert.
- Kein Test fuer ClanLaden/FreundeSenden-Ablauf, BurgenMode, Burgstand-Lesen, Truhenoeffner (nur live/Lesetest).
Paket-private `static`-Methoden existieren gezielt fuer Tests — bei Refactoring Sichtbarkeit beibehalten.

## 8. Vorlagen
- `templates/clanladen/cl_*` + `nachschub/clanladen` (Fallback), `templates/burgstand/rs_*` + `nachschub/burgstand`,
  Kerne nur in `templates/gelernt/burgstand`.
- `templates/gelernt/burgen/bu_*` (Serie `bu_ziel_burg[_2.._4]`), `templates/gelernt/freunde/fr_*`, `templates/gelernt/heldenset/hs_*` (+ nachschub) — fuer bu_/fr_ KEIN nachschub-Fallback.
- `bag_*`, `chat_*` in `templates/dungeon`; `cw_nahrung_*`, `cw_video_*`, `cw_previous_squad` in `templates/clanwar`.
- Serien (`_1`, `_2`, ... ohne Obergrenze) ueber `Matcher.serie(name)`: neue Aufnahme wirkt sofort.
- Masse sind auf 1159x770 (Fenster ohne Titelleiste) gemessen und werden mit `ctx.width()/1159.0` skaliert.

## 9. Rezepte
- **Neue Uhrzeit fuer Clan-Laden/Freunde**: `ZEITEN` in `core/Ladenplan` bzw. `core/Freundeplan` (+ FreundeplanTest). Fenster-Laenge `Ladenplan.FENSTER`.
- **Burgen-Intervall/Staerke-Schwelle**: `Burgenplan.ABSTAND_MS`, `BurgenMode.ZU_STARK_UEBER`, `WECHSEL_MAX`, `ANGRIFFE_JE_INSEL_MAX`.
- **Neuer Inseltyp, Burg nicht erkannt**: neue Vorlage `bu_ziel_burg_N` (nur Burgkoerper, kein Gras) — kein Code.
- **Neue Nahrungskisten-Sorte**: Vorlage `cw_nahrung_kiste_N`; neue Kathedralen-Kiste: `bag_chest_N`.
- **Kistenauswahl aendern**: `Nahrungskisten.planen()` / `bestePlanung*` / `wenigerKisten` + `GROSS_UEBER_HOECHSTENS`; Tests anpassen.
- **Nahrung falsch gelesen**: `Nahrungskisten.nahrungLesen` (Stabilitaet), `Burgstand.nahrung`/`burgZahl` (Ziffern), `ziffernGlaetten`.
- **Clan-Laden findet Kiste/Preis nicht**: Scroll-Parameter in `ClanLaden.laden()` (`MAX_SCROLLS`, `dragLangsam`-Strecken), Spaltenbreite `spalte`.
- **Freunde-Zahl falsch**: `FreundeSenden.zaehlerLesen`/`gruenMaske`/`zahlAusText`; Plausibilitaet `zahlGlaubhaft`.
- **Einschub-Reihenfolge**: `DiscordBridge.nebenbeiPruefen()`; Auto-Start nach Reset: `resetStartPruefen()`.
- **Modus fuer ein Konto sperren**: `Thronsaal.Teil` (Stufe) oder Datei `<modus>-aus.txt`.
- **Helden-Set-Fensterlagen**: Konstanten `P_*`, `ZEILE_*` in `HeldenSetWechsel`.
