package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Autor 22.10. abends: "Wenn ich gegen einen Spieler dreimal verliere, soll er automatisch blockiert werden. Bau die
 * Sperre nach Kraft, Namen, Clan-Tag, Clan-Banner und den Namen der Einheiten." Beim chinesischen Spieler (Kraft
 * 30.497.325) griff die Namens-Sperre nicht: in der Pyramide "蛎茶多妨走冰加妨峯", im Kampffenster "響蛎茶多", frueher
 * "회한奶茶多바" -- viermal verloren, die Zaehlung im Notizblock fing zwischendurch wieder bei null an.
 *
 * Diese Liste merkt sich je Spieler {Kraft, Name, Clan, Sets, Namen der Einheiten} mit Siegen und Niederlagen, seit
 * 24.10. auch sein Aussehen aus dem Vergleichsfenster (Einheiten-Portraets und Clan-Banner, {@link ArenaSperrBilder}).
 * Drei Niederlagen ohne einen Sieg = gesperrt.
 *
 * Derselbe Spieler ist, wer
 * (1) auf die Einerstelle dieselbe Kraft hat (ab 1 Mio, keine runden Tausender),
 * (2) gleich AUSSIEHT -- Autor 24.10.: "Erkennen vor allem an Merkmalen, die sich fast nie aendern: die Skins der
 *     Einheiten, die Namen der Einheiten, der Clan-Tag und das Clan-Banner. Die Kraft aendert sich oft (anderes
 *     Ausruestungsstueck, andere Einheiten) und darf einen Treffer nicht ausschliessen." Passt der Clan-Name oder das
 *     Banner, reichen 4 gleiche Einheiten (Skin oder Name), sonst muessen es 5 sein. Zwei Spieler aus demselben Clan
 *     unterscheiden die Einheiten: gemessen hatten zwei verschiedene Spieler hoechstens 2 gleiche Portraets.
 * (3) fast denselben Namen und keinen anderen Clan hat, oder (4) im selben Clan dieselben Sets auf allen Einheiten
 *     traegt -- bei (3) und (4) ist eine sicher gelesene andere Kraft weiter ein anderer Spieler.
 *
 * Datei arena-sperre.txt: Kraft TAB Siege TAB Niederlagen TAB Name TAB Clan TAB Sets TAB Einheiten (Name|Name|...)
 * TAB Bild (Name der Bilddatei in arena-sperre-bilder/, leer = noch kein Aussehen gemerkt) TAB Fingerabdruecke
 * (fp|fp|...). Bilder gibt es nur fuer Spieler ohne Sieg -- nur die koennen gesperrt werden.
 *
 * Autor 24.10.: dazu die Fingerabdruecke des Namensbildes aus der eigenen Schrifterkennung ({@link Glyphenschrift}).
 * "OMYth Gods" las Windows als "-UMPM-", "Gods" und "Myth 5Qds" -- das Namensbild ist aber jedes Mal (fast) dasselbe.
 * Passt ein Fingerabdruck verwandt ({@link Glyphenschrift#derselbeNameVerwandt}) zu einem Profil, ist es derselbe
 * Spieler, auch bei anderer Kraft. Jede neue Variante wird dazugemerkt (hoechstens {@link #MAX_FINGERABDRUECKE}).
 */
public final class ArenaSperre {

    private ArenaSperre() { }

    public static final int NIEDERLAGEN_BIS_SPERRE = 3;
    /** So viele gleiche Einheiten (Skin oder Name) machen denselben Spieler, wenn Clan-Name oder Banner passt. */
    static final int GLEICHE_EINHEITEN = 4;
    /** Ohne passenden Clan (Clan-Name verlesen und Banner unbekannt oder anders) muessen es so viele sein. */
    static final int GLEICHE_EINHEITEN_OHNE_CLAN = 5;
    static Path datei = Path.of("arena-sperre.txt");
    static final String BILDER_ORDNER = "arena-sperre-bilder";
    /** So viele Fingerabdruck-Varianten je Spieler (die neuesten bleiben). */
    static final int MAX_FINGERABDRUECKE = 8;
    /** Kuerzere Namensbilder (1-2 Zeichen) erkennen keinen Spieler -- wie beim gelesenen Namen (ab 3 Zeichen). */
    static final int MIN_GLYPHEN = 3;

    /**
     * Ein Spieler mit seiner Bilanz; bild = Name seiner Bilddatei (oder leer); fingerabdruecke = Fingerabdruecke seines
     * Namensbildes, mit '|' getrennt, neueste zuerst (oder leer).
     */
    record Profil(long kraft, int siege, int niederlagen, String name, String clan, String sets, String einheiten,
                  String bild, String fingerabdruecke) {
        Profil(long kraft, int siege, int niederlagen, String name, String clan, String sets, String einheiten) {
            this(kraft, siege, niederlagen, name, clan, sets, einheiten, "", "");
        }

        Profil(long kraft, int siege, int niederlagen, String name, String clan, String sets, String einheiten,
               String bild) {
            this(kraft, siege, niederlagen, name, clan, sets, einheiten, bild, "");
        }

        boolean gesperrt() {
            return siege == 0 && niederlagen >= NIEDERLAGEN_BIS_SPERRE;
        }
    }

    private static List<Profil> profile;
    /** Die Glyphen-Bibliothek fuer den Fingerabdruck-Vergleich (null = die im Bot-Ordner). */
    private static Glyphenschrift schrift;

    private static Glyphenschrift schrift() {
        if (schrift == null) schrift = Glyphenschrift.standard();
        return schrift;
    }
    /** Geladene Bilder je Bilddatei (LEER = keine da), damit nicht jeder Vergleich die Platte liest. */
    private static final Map<String, ArenaSperrBilder.Merkmale> bilder = new HashMap<>();

    /** Taugt diese Kraft als Erkennungszeichen? (Autors Regel: ab 1 Mio, keine runden Zahlen.) */
    public static boolean kraftGueltig(long kraft) {
        return kraft >= 1_000_000 && kraft % 1000 != 0;
    }

    /** Ein Kampfergebnis eintragen (Sieg oder Niederlage). */
    public static void ergebnis(String name, String clan, long kraft, String sets, boolean sieg) {
        ergebnis(name, clan, kraft, sets, "", sieg);
    }

    /** Wie oben, mit den Namen der Einheiten ("Name|Name|..."). */
    public static void ergebnis(String name, String clan, long kraft, String sets, String einheiten, boolean sieg) {
        ergebnis(name, clan, kraft, sets, einheiten, null, sieg);
    }

    /**
     * Wie oben, mit dem Aussehen aus dem Vergleichsfenster vor diesem Kampf (null/leer = keins). Das Aussehen ersetzt
     * das gemerkte -- so bleibt es aktuell, wenn der Spieler einen Skin wechselt.
     */
    public static synchronized void ergebnis(String name, String clan, long kraft, String sets, String einheiten,
                                             ArenaSperrBilder.Merkmale aussehen, boolean sieg) {
        ergebnis(name, clan, kraft, sets, einheiten, aussehen, null, sieg);
    }

    /**
     * Wie oben, mit dem Fingerabdruck des Namensbildes (Glyphenschrift, null/leer = keiner). Autor 24.10.: jede neue
     * Variante wird zum Profil dazugemerkt -- so erkennt die Sperre den Spieler beim naechsten Mal am Namensbild, egal
     * wie Windows den Namen liest.
     */
    public static synchronized void ergebnis(String name, String clan, long kraft, String sets, String einheiten,
                                             ArenaSperrBilder.Merkmale aussehen, String fingerabdruck, boolean sieg) {
        laden();
        int i = finden(name, clan, kraft, sets, einheiten, aussehen, fingerabdruck);
        String n = text(name), c = text(clan), s = text(sets), e = text(einheiten);
        // Bilder nur fuer Spieler ohne Sieg -- nur die koennen gesperrt werden (spart Platte, Speicher und Zeit).
        boolean mitBild = aussehen != null && !aussehen.leer();
        if (i < 0) {
            String bild = mitBild && !sieg ? neueBildDatei() : "";
            if (!bild.isEmpty()) bildAblegen(bild, aussehen);
            profile.add(new Profil(kraftGueltig(kraft) ? kraft : 0, sieg ? 1 : 0, sieg ? 0 : 1, n, c, s, e, bild,
                    dazu("", fingerabdruck)));
        } else {
            Profil p = profile.get(i);
            String bild = p.bild();
            if (mitBild && !sieg && p.siege() == 0) {
                if (bild.isEmpty()) bild = neueBildDatei();
                bildAblegen(bild, aussehen);
            }
            profile.set(i, new Profil(kraftGueltig(kraft) ? kraft : p.kraft(), p.siege() + (sieg ? 1 : 0),
                    p.niederlagen() + (sieg ? 0 : 1), n.isEmpty() ? p.name() : n, c.isEmpty() ? p.clan() : c,
                    s.isEmpty() ? p.sets() : s, e.isEmpty() ? p.einheiten() : e, bild,
                    dazu(p.fingerabdruecke(), fingerabdruck)));
        }
        speichern();
    }

    /** Gesperrt (3 Niederlagen ohne Sieg)? Liefert den Grund fuers Protokoll, sonst null. */
    public static String sperrgrund(String name, String clan, long kraft, String sets) {
        return sperrgrund(name, clan, kraft, sets, "");
    }

    /** Wie oben, mit den Namen der Einheiten. */
    public static String sperrgrund(String name, String clan, long kraft, String sets, String einheiten) {
        return sperrgrund(name, clan, kraft, sets, einheiten, null);
    }

    /**
     * Wie oben, mit dem Aussehen aus dem Vergleichsfenster. Autor 24.10.: steht derselbe Spieler doppelt in der Liste
     * (einmal unter einem falsch gelesenen Namen, noch nicht gesperrt), zaehlt das gesperrte Profil -- gleiche Kraft
     * oder gleiches Aussehen genuegt. Wurde er am Aussehen erkannt und hat inzwischen eine andere Kraft, wird die neue
     * Kraft gemerkt: dann erkennt ihn auch die Pyramide (dort gibt es keine Bilder) wieder an der Kraft.
     */
    public static synchronized String sperrgrund(String name, String clan, long kraft, String sets, String einheiten,
                                                 ArenaSperrBilder.Merkmale aussehen) {
        return sperrgrund(name, clan, kraft, sets, einheiten, aussehen, null);
    }

    /**
     * Wie oben, mit dem Fingerabdruck des Namensbildes (null/leer = keiner; in der Pyramide gibt es kein Aussehen, dann
     * aussehen = null). Autor 24.10.: passt der Fingerabdruck zu einem gesperrten Profil, ist er gesperrt -- auch bei
     * anderer Kraft und verlesenem Namen.
     */
    public static synchronized String sperrgrund(String name, String clan, long kraft, String sets, String einheiten,
                                                 ArenaSperrBilder.Merkmale aussehen, String fingerabdruck) {
        laden();
        int i = finden(name, clan, kraft, sets, einheiten, aussehen, fingerabdruck);
        if (i >= 0 && profile.get(i).gesperrt()) return grund(i, name, clan, kraft, sets, einheiten, aussehen, fingerabdruck);
        for (int j = 0; j < profile.size(); j++) {
            Profil p = profile.get(j);
            if (j == i || !p.gesperrt()) continue;
            if ((kraftGueltig(kraft) && p.kraft() == kraft) || aussehenGleich(p, clan, einheiten, aussehen) > 0
                    || fingerabdruckPasst(p, fingerabdruck) > 0 || merkmale(p, name, clan, kraft, einheiten, aussehen).size() >= 2
                    // Autor 28.10. (nur mit Arena-Schalter): erstes Namenswort + fast gleiche Kraft, siehe ArenaZusammenfuehren
                    || (ArenaSchalter.an() && ArenaZusammenfuehren.gleicherSpieler(p.name(), p.clan(), p.kraft(), name, clan, kraft))) {
                return grund(j, name, clan, kraft, sets, einheiten, aussehen, fingerabdruck);
            }
        }
        return null;
    }

    private static String grund(int i, String name, String clan, long kraft, String sets, String einheiten,
                                ArenaSperrBilder.Merkmale aussehen, String fingerabdruck) {
        Profil p = profile.get(i);
        String m = merkmal(p, name, clan, kraft, sets, einheiten, aussehen, fingerabdruck);
        boolean amAussehen = aussehenGleich(p, clan, einheiten, aussehen) > 0;
        boolean amNamensbild = fingerabdruckPasst(p, fingerabdruck) > 0;
        boolean amKraft = kraftGueltig(kraft) && p.kraft() == kraft;
        // Am Aussehen oder Namensbild erkannt, andere Kraft: die neue Kraft merken (dann erkennt ihn auch die Pyramide).
        long neueKraft = kraftGueltig(kraft) && p.kraft() != kraft && (amAussehen || amNamensbild) ? kraft : p.kraft();
        // Autor 24.10.: gesperrte Spieler kaempfen nie wieder (kein ergebnis() mehr) -- darum hier die neue Variante des
        // Namensbildes dazumerken, aber nur bei sicherem Treffer (Kraft, Aussehen oder Namensbild), nicht bei aehnlichem Namen.
        String fps = amKraft || amAussehen || amNamensbild ? dazu(p.fingerabdruecke(), fingerabdruck) : p.fingerabdruecke();
        if (neueKraft != p.kraft() || !fps.equals(p.fingerabdruecke())) {
            profile.set(i, new Profil(neueKraft, p.siege(), p.niederlagen(), p.name(), p.clan(), p.sets(), p.einheiten(),
                    p.bild(), fps));
            speichern();
        }
        return p.niederlagen() + "x verloren, nie gewonnen (" + m + ")";
    }

    /** Wie unten, ohne Aussehen. */
    static int finden(String name, String clan, long kraft, String sets, String einheiten) {
        return finden(name, clan, kraft, sets, einheiten, null, null);
    }

    /** Wie unten, ohne Fingerabdruck. */
    static int finden(String name, String clan, long kraft, String sets, String einheiten, ArenaSperrBilder.Merkmale aussehen) {
        return finden(name, clan, kraft, sets, einheiten, aussehen, null);
    }

    /**
     * Index des Profils, das zu diesem Spieler passt, oder -1. Zuerst die Kraft (auf die Einerstelle), dann das
     * Namensbild (Fingerabdruck, Autor 24.10.), dann das Aussehen (das Profil mit den meisten gleichen Einheiten),
     * zuletzt Name und Sets.
     */
    static int finden(String name, String clan, long kraft, String sets, String einheiten, ArenaSperrBilder.Merkmale aussehen,
                      String fingerabdruck) {
        String n = zeichen(name), c = clan == null ? "" : clan;
        if (kraftGueltig(kraft)) {
            for (int i = 0; i < profile.size(); i++) if (profile.get(i).kraft() == kraft) return i;
        }
        // Autor 24.10.: das Namensbild -- eine andere Kraft schliesst hier nichts aus. Mehrere: das aehnlichste.
        if (glyphenAnzahl(fingerabdruck) >= MIN_GLYPHEN) {
            int bester = -1;
            double besterWert = 0;
            for (int i = 0; i < profile.size(); i++) {
                double w = fingerabdruckPasst(profile.get(i), fingerabdruck);
                if (w > besterWert) {
                    besterWert = w;
                    bester = i;
                }
            }
            if (bester >= 0) return bester;
        }
        // Autor 24.10.: Skins, Einheiten-Namen, Clan und Banner -- eine andere Kraft schliesst hier nichts aus.
        int beste = -1, besteZahl = 0;
        for (int i = 0; i < profile.size(); i++) {
            int z = aussehenGleich(profile.get(i), c, einheiten, aussehen);
            if (z > besteZahl) {
                besteZahl = z;
                beste = i;
            }
        }
        if (beste >= 0) return beste;
        // Autor 24.10.: zwei passende Merkmale reichen (eins falsch gelesen) -- das Profil mit den meisten.
        int besteM = -1, besteMZahl = 1;
        for (int i = 0; i < profile.size(); i++) {
            int z = merkmale(profile.get(i), name, c, kraft, einheiten, aussehen).size();
            if (z > besteMZahl) {
                besteMZahl = z;
                besteM = i;
            }
        }
        if (besteM >= 0) return besteM;
        for (int i = 0; i < profile.size(); i++) {
            Profil p = profile.get(i);
            boolean clanPasst = c.isBlank() || p.clan().isBlank() || Gegnerbuch.clanAehnlich(p.clan(), c)
                    || zeichen(p.clan()).equals(zeichen(c));
            // Nur noch fuer Name und Sets (Skins und Einheiten passten oben ja nicht): eine andere gelesene Kraft (beide
            // sicher) ist ein anderer Spieler -- auch bei aehnlichem Namen.
            boolean kraftWiderspricht = kraftGueltig(kraft) && kraftGueltig(p.kraft())
                    && !Gegnerbuch.gleicheKraft(p.kraft(), kraft);
            if (kraftWiderspricht) continue;
            if (n.length() >= 3 && clanPasst && Gegnerbuch.derselbe(zeichen(p.name()), n, p.kraft(), kraft)) return i;
            if (!c.isBlank() && !p.clan().isBlank() && clanPasst && sets != null && sets.strip().length() >= 10
                    && sets.strip().equals(p.sets())) return i;
        }
        // Autor 28.10. (nur mit Arena-Schalter): kein neues Profil fuer "JkNXED MURENA", wenn "JtNXED" mit fast derselben
        // Kraft schon drinsteht -- erstes Namenswort mit den Verwechslern der Texterkennung gleich, siehe ArenaZusammenfuehren.
        return ArenaSchalter.an() ? gleicherSpielerIndex(name, c, kraft) : -1;
    }

    /** Autor 28.10.: Index des Profils nach {@link ArenaZusammenfuehren#gleicherSpieler} (mehrere: naechste Kraft), oder -1. */
    private static int gleicherSpielerIndex(String name, String clan, long kraft) {
        int beste = -1;
        long besterAbstand = Long.MAX_VALUE;
        for (int i = 0; i < profile.size(); i++) {
            Profil p = profile.get(i);
            if (!ArenaZusammenfuehren.gleicherSpieler(p.name(), p.clan(), p.kraft(), name, clan, kraft)) continue;
            long d = kraftGueltig(p.kraft()) && kraftGueltig(kraft) ? Math.abs(p.kraft() - kraft) : Long.MAX_VALUE - 1;
            if (beste < 0 || d < besterAbstand) {
                beste = i;
                besterAbstand = d;
            }
        }
        return beste;
    }

    /** Derselbe Spieler (Autor 28.10.)? Gleiches Namensbild (der Clan widerspricht nicht) oder ArenaZusammenfuehren-Regel. */
    private static boolean gleicherSpieler(Profil a, Profil b) {
        if (!a.fingerabdruecke().isBlank() && !b.fingerabdruecke().isBlank()
                && !Gegnerbuch.namensbildWiderspricht(a.clan(), a.kraft(), b.clan(), b.kraft())) {
            for (String f : b.fingerabdruecke().split("[|]")) if (passt(schrift(), a.fingerabdruecke(), f) > 0) return true;
        }
        return ArenaZusammenfuehren.gleicherSpieler(a.name(), a.clan(), a.kraft(), b.name(), b.clan(), b.kraft());
    }

    /**
     * Autor 28.10. (ArenaZusammenfuehren, nur mit Arena-Schalter): alle Profile desselben Spielers zu einem, auch ueber
     * Ketten (A=B und B=C: alle drei). Siege und Niederlagen werden addiert, die Fingerabdruecke vereinigt; Kraft, Name,
     * Clan, Sets, Einheiten und Bild vom Profil mit den meisten Kaempfen (fehlt dort etwas: vom naechsten). Speichert
     * selbst. @return je zusammengelegtem Profil eine Zeile "alt -> bleibt"
     */
    static synchronized List<String> gleicheSpielerZusammenlegen() {
        laden();
        int[] kette = new int[profile.size()];
        for (int i = 0; i < kette.length; i++) kette[i] = i;
        for (int i = 0; i < profile.size(); i++) {
            for (int j = i + 1; j < profile.size(); j++) {
                if (ArenaZusammenfuehren.wurzel(kette, i) == ArenaZusammenfuehren.wurzel(kette, j)) continue;
                if (gleicherSpieler(profile.get(i), profile.get(j))) ArenaZusammenfuehren.verbinden(kette, i, j);
            }
        }
        List<String> bericht = new ArrayList<>();
        boolean[] weg = new boolean[profile.size()];
        for (List<Integer> gruppe : ArenaZusammenfuehren.gruppen(kette)) {
            if (gruppe.size() < 2) continue;
            // Es bleibt: meiste Kaempfe, dann mit Kraft (gleich: das vordere in der Datei).
            List<Integer> reihe = new ArrayList<>(gruppe);
            reihe.sort(Comparator.comparingInt((Integer i) -> profile.get(i).siege() + profile.get(i).niederlagen())
                    .thenComparing(i -> kraftGueltig(profile.get(i).kraft())).reversed());
            int bleibt = reihe.get(0);
            Profil h = profile.get(bleibt);
            long kraft = kraftGueltig(h.kraft()) ? h.kraft() : 0;
            int siege = 0, niederlagen = 0;
            String name = h.name(), clan = h.clan(), sets = h.sets(), einheiten = h.einheiten(), bild = h.bild();
            String fps = h.fingerabdruecke();
            for (int i : reihe) {
                Profil p = profile.get(i);
                siege += p.siege();
                niederlagen += p.niederlagen();
                if (kraft == 0 && kraftGueltig(p.kraft())) kraft = p.kraft();
                if (name.isBlank()) name = p.name();
                if (clan.isBlank()) clan = p.clan();
                if (sets.isBlank()) sets = p.sets();
                if (einheiten.isBlank()) einheiten = p.einheiten();
                if (bild.isBlank()) bild = p.bild();
                fps = Gegnerbuch.vereint(fps, p.fingerabdruecke());
                if (i == bleibt) continue;
                weg[i] = true;
                bericht.add(p.name() + " (" + p.clan() + (kraftGueltig(p.kraft()) ? ", " + p.kraft() : "") + ", "
                        + p.siege() + "/" + p.niederlagen() + ") -> " + h.name() + " (" + h.clan()
                        + (kraftGueltig(h.kraft()) ? ", " + h.kraft() : "") + ")");
            }
            profile.set(bleibt, new Profil(kraft, siege, niederlagen, name, clan, sets, einheiten, bild, fps));
        }
        if (bericht.isEmpty()) return bericht;
        List<Profil> rest = new ArrayList<>();
        for (int i = 0; i < profile.size(); i++) if (!weg[i]) rest.add(profile.get(i));
        profile = rest;
        speichern();
        Log.info("Arena", Sprache.t("Block list: " + bericht.size() + " duplicate players merged.",
                "Sperrliste: " + bericht.size() + " doppelte Spieler zusammengelegt."));
        return bericht;
    }

    /**
     * Sieht der Spieler aus wie dieses Profil? Liefert die Zahl gleicher Einheiten (Skin-Bilder oder gelesene Namen,
     * die groessere), 0 = nicht derselbe. Autor 24.10.: "Clan-Tag (oder Banner) passt UND mind. 4 von 6 Einheiten-Skins
     * oder -Namen passen -> derselbe Spieler. Ohne Clan: mind. 5 von 6."
     */
    static int aussehenGleich(Profil p, String clan, String einheiten, ArenaSperrBilder.Merkmale aussehen) {
        int namen = gleicheEinheiten(einheiten, p.einheiten());
        ArenaSperrBilder.Merkmale alt = aussehen == null || aussehen.leer() ? null : bilderVon(p);
        if (namen < GLEICHE_EINHEITEN && alt == null) return 0;
        int skins = alt == null ? 0 : ArenaSperrBilder.gleicheEinheiten(alt, aussehen, GLEICHE_EINHEITEN);
        int gleich = Math.max(namen, skins);
        if (gleich >= GLEICHE_EINHEITEN_OHNE_CLAN) return gleich;
        if (gleich < GLEICHE_EINHEITEN) return 0;
        if (clanGleich(p.clan(), clan)) return gleich;
        // Das Banner nur bei Spielern mit Clan: ohne Clan steht dort fuer alle derselbe graue Schild.
        boolean banner = alt != null && !ohneClan(clan) && !ohneClan(p.clan())
                && Boolean.TRUE.equals(ArenaSperrBilder.gleichesBanner(alt, aussehen));
        return banner ? gleich : 0;
    }

    /**
     * Autor 24.10.: die Merkmale decken sich gegenseitig ab -- ist eins falsch gelesen (Clan, Kraft, Einheiten-Namen),
     * reichen zwei andere, die passen. Liefert die passenden Merkmale: Name, Clan oder Banner, Kraft (+-3 %), 4 gleiche
     * Einheiten-Namen, 4 gleiche Einheiten-Bilder. Mindestens eins muss Name, Einheiten-Namen oder -Bilder sein (sonst
     * leer) -- Clan und aehnliche Kraft allein trifft auch einen anderen Spieler aus demselben Clan.
     */
    static List<String> merkmale(Profil p, String name, String clan, long kraft, String einheiten,
                                 ArenaSperrBilder.Merkmale aussehen) {
        List<String> m = new ArrayList<>();
        String n = zeichen(name);
        if (n.length() >= 3 && Gegnerbuch.derselbe(zeichen(p.name()), n, 0, 0)) m.add(Sprache.t("name", "Name"));
        if (gleicheEinheiten(einheiten, p.einheiten()) >= GLEICHE_EINHEITEN) m.add(Sprache.t("unit names", "Einheiten-Namen"));
        ArenaSperrBilder.Merkmale alt = aussehen == null || aussehen.leer() ? null : bilderVon(p);
        if (alt != null && ArenaSperrBilder.gleicheEinheiten(alt, aussehen, GLEICHE_EINHEITEN) >= GLEICHE_EINHEITEN) {
            m.add(Sprache.t("unit pictures", "Einheiten-Bilder"));
        }
        if (m.isEmpty()) return m;
        if (clanGleich(p.clan(), clan)) {
            m.add("Clan");
        } else if (alt != null && !ohneClan(clan) && !ohneClan(p.clan())
                && Boolean.TRUE.equals(ArenaSperrBilder.gleichesBanner(alt, aussehen))) {
            m.add(Sprache.t("clan banner", "Clan-Banner"));
        }
        if (kraftGueltig(kraft) && kraftGueltig(p.kraft()) && Gegnerbuch.gleicheKraft(p.kraft(), kraft)) {
            m.add(Sprache.t("power", "Kraft"));
        }
        return m;
    }

    /** Beide Clan-Namen gelesen, beide ein echter Clan, und sie passen (auch mit einem Lesefehler davor/dahinter). */
    static boolean clanGleich(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank() || ohneClan(a) || ohneClan(b)) return false;
        return Gegnerbuch.clanAehnlich(a, b) || (zeichen(a).length() >= 3 && zeichen(a).equals(zeichen(b)));
    }

    private static boolean ohneClan(String c) {
        return c != null && (Gegnerbuch.keinClan(c) || c.strip().equalsIgnoreCase(Gegnerbuch.OHNE_CLAN));
    }

    /** Wie viele Einheiten-Namen beider Listen gleich gelesen wurden (ohne Leer- und Sonderzeichen). */
    static int gleicheEinheiten(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0;
        List<String> rest = new ArrayList<>();
        for (String t : b.split("[|]")) if (zeichen(t).length() >= 2) rest.add(zeichen(t));
        int gleich = 0;
        for (String t : a.split("[|]")) if (rest.remove(zeichen(t))) gleich++;
        return gleich;
    }

    /**
     * Passt der Fingerabdruck zu einem der gemerkten des Profils? Liefert die beste Aehnlichkeit (0 = passt nicht,
     * sonst 0,75..1). Beide Namensbilder brauchen mindestens {@link #MIN_GLYPHEN} Zeichen.
     */
    static double fingerabdruckPasst(Profil p, String fingerabdruck) {
        if (p.fingerabdruecke() == null || p.fingerabdruecke().isBlank() || glyphenAnzahl(fingerabdruck) < MIN_GLYPHEN) return 0;
        return passt(schrift(), p.fingerabdruecke(), fingerabdruck);
    }

    /**
     * Beste Aehnlichkeit des Fingerabdrucks zu einer Liste "fp|fp|..." (auch fuer den Notizblock): 0 = keiner passt
     * (derselbeNameVerwandt), sonst 0,75..1. Beide brauchen mindestens {@link #MIN_GLYPHEN} Zeichen.
     */
    static double passt(Glyphenschrift gs, String liste, String fingerabdruck) {
        int n = glyphenAnzahl(fingerabdruck);
        if (liste == null || liste.isBlank() || n < MIN_GLYPHEN) return 0;
        String fp = fingerabdruck.strip();
        double best = 0;
        for (String alt : liste.split("[|]")) {
            int m = glyphenAnzahl(alt);
            // Vorauswahl: derselbe Name heisst hoechstens ein Fehler je vier Zeichen -- mehr Laengenunterschied passt nie.
            if (m < MIN_GLYPHEN || Math.abs(n - m) > Math.max(n, m) / 4) continue;
            String a = alt.strip();
            if (a.equals(fp)) return 1;
            // Code-Review 26.10.: bei bis zu 4 Zeichen reichte ein beliebiges falsches Zeichen (1 - 1/4 = 0,75) -- "Kira"
            // sperrte "Kiro" und "Mira" mit. Wie die alte Text-Regel: ein Fehler erst ab 5 Zeichen.
            if (Math.min(n, m) <= 4) continue;
            if (gs.derselbeNameVerwandt(a, fp)) best = Math.max(best, Math.max(0.75, gs.aehnlichkeitVerwandt(a, fp)));
        }
        return best;
    }

    /** Zahl der Zeichen eines Fingerabdrucks ("4.17.9" = 3), 0 bei leer. */
    static int glyphenAnzahl(String fingerabdruck) {
        if (fingerabdruck == null || fingerabdruck.isBlank()) return 0;
        String f = fingerabdruck.strip();
        int n = 1;
        for (int i = 0; i < f.length(); i++) if (f.charAt(i) == '.') n++;
        return n;
    }

    /** Die Liste "fp|fp|..." mit dem neuen Fingerabdruck vorn (schon vorhanden: nach vorn), hoechstens MAX_FINGERABDRUECKE. */
    static String dazu(String liste, String fingerabdruck) {
        String alt = liste == null ? "" : liste.strip();
        if (fingerabdruck == null || fingerabdruck.isBlank() || !fingerabdruck.strip().matches("\\d+(\\.\\d+)*")) return alt;
        String neu = fingerabdruck.strip();
        List<String> l = new ArrayList<>();
        l.add(neu);
        for (String f : alt.split("[|]")) if (!f.isBlank() && !f.strip().equals(neu) && l.size() < MAX_FINGERABDRUECKE) l.add(f.strip());
        return String.join("|", l);
    }

    private static String merkmal(Profil p, String name, String clan, long kraft, String sets, String einheiten,
                                  ArenaSperrBilder.Merkmale aussehen, String fingerabdruck) {
        if (kraftGueltig(kraft) && p.kraft() == kraft) return "gleiche Kraft " + kraft;
        if (fingerabdruckPasst(p, fingerabdruck) > 0) {
            return Sprache.t("same name picture as " + p.name(), "gleiches Namensbild wie " + p.name());
        }
        ArenaSperrBilder.Merkmale alt = aussehen == null || aussehen.leer() ? null : bilderVon(p);
        int skins = alt == null ? 0 : ArenaSperrBilder.gleicheEinheiten(alt, aussehen, 0);
        int namen = gleicheEinheiten(einheiten, p.einheiten());
        if (skins >= GLEICHE_EINHEITEN && skins >= namen) {
            boolean banner = Boolean.TRUE.equals(ArenaSperrBilder.gleichesBanner(alt, aussehen));
            return Sprache.t(skins + " of " + aussehen.anzahl() + " unit pictures like " + p.name() + (banner ? ", same clan banner" : ""),
                    skins + " von " + aussehen.anzahl() + " Einheiten-Bildern wie " + p.name() + (banner ? ", gleiches Clan-Banner" : ""));
        }
        if (namen >= GLEICHE_EINHEITEN) return namen + " gleiche Einheiten-Namen wie " + p.name();
        List<String> m = merkmale(p, name, clan, kraft, einheiten, aussehen);
        if (m.size() >= 2) return Sprache.t(String.join(" + ", m) + " like " + p.name(), String.join(" + ", m) + " wie " + p.name());
        if (sets != null && !sets.isBlank() && sets.strip().equals(p.sets()) && !zeichen(p.name()).equals(zeichen(name))) {
            return "gleicher Clan und gleiche Sets wie " + p.name();
        }
        return "Name wie " + p.name() + (p.clan().isBlank() ? "" : " / " + p.clan());
    }

    private static String text(String s) {
        return s == null ? "" : s.strip().replace('\t', ' ');
    }

    private static String zeichen(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) if (Character.isLetterOrDigit(ch)) b.append(ch);
        return b.toString();
    }

    // ------------------------------------------------------------------ Bilder

    /** Ordner der Bilder neben arena-sperre.txt (ein Bild je Spieler: Banner + Portraets nebeneinander). */
    static Path bilderOrdner() {
        return datei.toAbsolutePath().resolveSibling(BILDER_ORDNER);
    }

    private static Path bildDatei(String bild) {
        return bilderOrdner().resolve(bild + ".png");
    }

    /** Das gemerkte Aussehen -- nur bei Spielern ohne Sieg (nur die koennen gesperrt werden), sonst null. */
    private static ArenaSperrBilder.Merkmale bilderVon(Profil p) {
        if (p.siege() > 0 || p.bild() == null || p.bild().isBlank()) return null;
        ArenaSperrBilder.Merkmale m = bilder.computeIfAbsent(bildDatei(p.bild()).toString(), d -> {
            ArenaSperrBilder.Merkmale l = ArenaSperrBilder.laden(Path.of(d));
            return l == null ? ArenaSperrBilder.Merkmale.LEER : l;
        });
        return m.leer() ? null : m;
    }

    private static void bildAblegen(String bild, ArenaSperrBilder.Merkmale m) {
        ArenaSperrBilder.speichern(bildDatei(bild), m);
        bilder.put(bildDatei(bild).toString(), m);
    }

    /**
     * Die Bilder im Hintergrund vorladen, damit der erste Vergleich im Vergleichsfenster nicht hunderte Dateien lesen
     * muss (die Runde hat nur ~2 Minuten).
     */
    private static void bilderVorladen() {
        List<Path> dateien = new ArrayList<>();
        for (Profil p : profile) if (p.siege() == 0 && !p.bild().isBlank()) dateien.add(bildDatei(p.bild()));
        if (dateien.isEmpty()) return;
        Thread t = new Thread(() -> {
            for (Path d : dateien) {
                String k = d.toString();
                synchronized (ArenaSperre.class) {
                    if (bilder.containsKey(k)) continue;
                }
                ArenaSperrBilder.Merkmale m = ArenaSperrBilder.laden(d);
                synchronized (ArenaSperre.class) {
                    bilder.putIfAbsent(k, m == null ? ArenaSperrBilder.Merkmale.LEER : m);
                }
            }
        }, "arena-sperre-bilder");
        t.setDaemon(true);
        t.start();
    }

    /** Ein neuer, noch freier Dateiname (Zeit in 36er-Schrift, kurz und eindeutig). */
    private static String neueBildDatei() {
        String basis = Long.toString(System.currentTimeMillis(), 36);
        for (int n = 0; ; n++) {
            String b = n == 0 ? basis : basis + "-" + n;
            boolean vergeben = Files.exists(bildDatei(b));
            for (Profil p : profile) if (b.equals(p.bild())) vergeben = true;
            if (!vergeben) return b;
        }
    }

    // ------------------------------------------------------------------ Datei

    private static void laden() {
        if (profile != null) return;
        profile = new ArrayList<>();
        try {
            if (!Files.exists(datei)) return;
            for (String z : Dateien.zeilen(datei)) {
                if (z.isBlank() || z.startsWith("#")) continue;
                String[] t = z.split("\t", -1);
                if (t.length < 3) continue;
                // Spalte 9 (Fingerabdruecke) seit 24.10. -- aeltere Zeilen haben sie nicht.
                profile.add(new Profil(Long.parseLong(t[0].strip()), Integer.parseInt(t[1].strip()),
                        Integer.parseInt(t[2].strip()), t.length > 3 ? t[3] : "", t.length > 4 ? t[4] : "",
                        t.length > 5 ? t[5] : "", t.length > 6 ? t[6] : "", t.length > 7 ? t[7].strip() : "",
                        t.length > 8 ? t[8].strip() : ""));
            }
            bilderVorladen();
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("arena-sperre.txt not readable: ", "arena-sperre.txt nicht lesbar: ") + e.getMessage());
        }
    }

    private static void speichern() {
        StringBuilder b = new StringBuilder(Sprache.t(
                "# Arena: players by power, name picture, look (unit pictures, clan banner), unit names, clan, name and sets -- 3 losses without a win = never attacked again.",
                "# Arena: Spieler nach Kraft, Namensbild, Aussehen (Einheiten-Bilder, Clan-Banner), Einheiten-Namen, Clan, Name und Sets -- 3 Niederlagen ohne Sieg = nie mehr angreifen."))
                .append(System.lineSeparator())
                .append("# Kraft\tSiege\tNiederlagen\tName\tClan\tSets\tEinheiten\tBild\tFingerabdruecke").append(System.lineSeparator());
        for (Profil p : profile) {
            b.append(p.kraft()).append('\t').append(p.siege()).append('\t').append(p.niederlagen()).append('\t')
                    .append(p.name()).append('\t').append(p.clan()).append('\t').append(p.sets()).append('\t')
                    .append(p.einheiten()).append('\t').append(p.bild()).append('\t').append(p.fingerabdruecke())
                    .append(System.lineSeparator());
        }
        try {
            Path neu = datei.resolveSibling(datei.getFileName() + ".neu");
            Files.writeString(neu, b.toString(), StandardCharsets.UTF_8);
            Files.move(neu, datei, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("arena-sperre.txt not written: ", "arena-sperre.txt nicht geschrieben: ") + e.getMessage());
        }
    }

    /** Nur fuer Tests: Datei setzen und neu laden. */
    static synchronized void fuerTest(Path neu) {
        fuerTest(neu, null);
    }

    /** Nur fuer Tests: Datei und Glyphen-Bibliothek setzen (null = die im Bot-Ordner) und neu laden. */
    static synchronized void fuerTest(Path neu, Glyphenschrift gs) {
        datei = neu;
        profile = null;
        bilder.clear();
        schrift = gs;
    }
}
