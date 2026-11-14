package de.hustlebot.bot;

/**
 * Die Regel, welche Liste ein Gegner nach einem Arena-Kampf bekommt (Autor 29.10.): EINE reine Funktion, die der Bot
 * (nach seinen eigenen Kaempfen, ArenaMode.namensbildVormerken) und der Beobachter (nach Autors Kaempfen,
 * ArenaMode.beobZaehlen) gemeinsam benutzen -- "die gleichen Regeln wie beim normalen Bot, nur dass ich spiele".
 *
 * Niederlage (Autor 11.10., 19.10., 29.10.): 3 und mehr Kaempfe ohne einen Sieg (alle Schreibweisen des Namens, Bot und
 * Autor zusammen) -> niemals; unter 7 s -> niemals; viel staerker -> meiden (ausser ueber 20 s); bis 14 s -> meiden;
 * 15-19 s -> meiden erst ab der 2. Niederlage; ab 20 s nichts; Dauer unbekannt -> meiden ab der 3. Niederlage; stand er auf
 * "sicher" (auch die automatische Liste), wird er nur auf "machbar" abgestuft.
 * Sieg: der 3. Sieg -> sicher (egal wie lange); unter 7 s -> sicher; bis 14 s -> machbar; sonst nichts.
 */
final class ArenaListenRegel {

    private ArenaListenRegel() { }

    /** Welche Liste faellig ist ("KEINE" = nichts eintragen). */
    enum Liste { KEINE, NIEMALS, PAUSE, MEIDEN, MACHBAR, FAST_SICHER, SICHER }

    /**
     * Autor 30.10.: Spieler, die der Bot nicht kannte und nur Autor selbst angegriffen hat: "3 mal angegriffen, 2 mal gewonnen,
     * 1 mal verloren: machbar. 3 mal gewonnen: sicher. 2 mal verloren, 1 mal gewonnen: ungern. 3 Verluste: blockieren."
     * Vor dem dritten Kampf keine Liste; danach zaehlen die letzten drei Kaempfe.
     * @param ergebnisse S = Sieg, N = Niederlage, der aelteste vorn
     */
    static Liste nachSelbstKaempfen(String ergebnisse) {
        if (ergebnisse == null || ergebnisse.length() < 3) return Liste.KEINE;
        long siege = ergebnisse.substring(ergebnisse.length() - 3).chars().filter(c -> c == 'S').count();
        return siege == 3 ? Liste.SICHER : siege == 2 ? Liste.MACHBAR : siege == 1 ? Liste.MEIDEN : Liste.NIEMALS;
    }

    /** Autor 29.10.: 7 s fuer ALLE Kaempfe (vorher 8, davor 7). Unter dieser Grenze: Sieg = sicher, Niederlage = niemals. */
    static final int SIEG_SICHER_S = 7, NIEDERLAGE_NIEMALS_S = 7;
    /** Ab dieser Dauer bringt eine Niederlage nichts mehr (neutral). */
    static final int NIEDERLAGE_NEUTRAL_S = 20;
    /** Autor 11.10.: gegen 20 Mio (oder mehr) Staerkere verloren -> immer meiden (ausser der Kampf dauerte ueber 20 s). */
    static final long VIEL_STAERKER = 20_000_000L;
    /** Bis zu dieser Dauer ist ein Sieg "machbar", eine Niederlage "meiden". */
    static final int BIS_MACHBAR_S = 14;

    /**
     * Der Kampf, um den es geht -- die Zahlen enthalten diesen Kampf SCHON.
     *
     * @param sieg              gewonnen?
     * @param dauerS            Kampfdauer in Sekunden, 0 oder weniger = unbekannt
     * @param vielStaerker      war der Gegner 20 Mio oder mehr staerker?
     * @param siegeGesamt       Siege gegen den Spieler (alle Schreibweisen), mit diesem Kampf
     * @param kaempfeGesamt     Kaempfe gegen den Spieler (alle Schreibweisen), mit diesem Kampf
     * @param niederlagenNotiz  Niederlagen in seiner Notiz, mit diesem Kampf
     * @param siegeNotiz        Siege in seiner Notiz, mit diesem Kampf
     */
    record Kampf(boolean sieg, long dauerS, boolean vielStaerker, int siegeGesamt, int kaempfeGesamt,
                 int niederlagenNotiz, int siegeNotiz) { }

    /** Auf welcher Liste der Gegner VOR diesem Kampf stand (Listenbild oder automatische Liste). */
    record Stand(boolean gesperrt, boolean meiden, boolean sicher, boolean machbar, boolean fastSicher, boolean pause) {
        static final Stand LEER = new Stand(false, false, false, false);

        /** Ohne die beiden Zwischenstufen. */
        Stand(boolean gesperrt, boolean meiden, boolean sicher, boolean machbar) {
            this(gesperrt, meiden, sicher, machbar, false, false);
        }
    }

    /**
     * Das Ergebnis.
     *
     * @param liste         welche Liste faellig ist
     * @param dreiOhneSieg  Grund "niemals": 3 oder mehr Kaempfe, kein Sieg
     * @param dritterSieg   Grund "sicher": der dritte Sieg
     * @param abstufen      er stand auf "sicher" und hat gewonnen -> in arena-abgestuft.txt ("machbar")
     */
    record Entscheidung(Liste liste, boolean dreiOhneSieg, boolean dritterSieg, boolean abstufen) {
        static final Entscheidung NICHTS = new Entscheidung(Liste.KEINE, false, false, false);
    }

    static Entscheidung entscheide(Kampf k, Stand s) {
        if (s.gesperrt()) return Entscheidung.NICHTS;   // steht schon auf "niemals"
        return k.sieg() ? sieg(k, s) : niederlage(k, s);
    }

    /**
     * Autor 31.10.: "Noch bevor der Bot sie in sicher steckt, erstmal in fast sicher, und beim naechsten Mal, wenn er sie angreift, erst in sicher.
     * Das gleiche beim Blockieren." Laeuft nach entscheide(), nur mit arena-regeln-neu.txt. Autors eigene Listenbilder (ohne Datums-Endung) werden
     * dadurch nicht ueberschrieben: das stellt das Speichern sicher ("deine Liste gilt").
     */
    static Entscheidung mitZwischenstufen(Kampf k, Stand s, Entscheidung e) {
        if (s.fastSicher()) return k.sieg() ? new Entscheidung(Liste.SICHER, false, false, false) : new Entscheidung(Liste.MACHBAR, false, false, false);
        if (s.pause()) return k.sieg() ? new Entscheidung(Liste.MEIDEN, false, false, false) : new Entscheidung(Liste.NIEMALS, e.dreiOhneSieg(), false, false);
        if (e.liste() == Liste.SICHER) return new Entscheidung(Liste.FAST_SICHER, false, e.dritterSieg(), e.abstufen());
        if (e.liste() == Liste.NIEMALS) return new Entscheidung(Liste.PAUSE, e.dreiOhneSieg(), false, e.abstufen());
        return e;
    }

    private static Entscheidung niederlage(Kampf k, Stand s) {
        Liste meiden = s.meiden() ? Liste.KEINE : Liste.MEIDEN;   // steht er schon auf "meiden": nicht noch einmal
        long dauer = k.dauerS();
        if (s.sicher()) {
            // "sicher" verloren -> "machbar": Bilder werden nie geloescht, der Name kommt in arena-abgestuft.txt
            return new Entscheidung(Liste.KEINE, false, false, true);
        }
        // Autor 19.10.: ab der dritten Niederlage ohne einen Sieg -> "niemals"
        if (k.siegeGesamt() == 0 && k.kaempfeGesamt() >= 3) return new Entscheidung(Liste.NIEMALS, true, false, false);
        Liste art;
        if (dauer <= 0) art = k.niederlagenNotiz() >= 3 ? meiden : Liste.KEINE;
        else if (dauer < NIEDERLAGE_NIEMALS_S) art = Liste.NIEMALS;
        else if (k.vielStaerker()) art = dauer > NIEDERLAGE_NEUTRAL_S ? Liste.KEINE : meiden;
        else if (dauer <= BIS_MACHBAR_S) art = meiden;
        else if (dauer < NIEDERLAGE_NEUTRAL_S) art = k.niederlagenNotiz() >= 2 ? meiden : Liste.KEINE;
        else art = Liste.KEINE;   // ab 20 s: neutral lassen
        return new Entscheidung(art, false, false, false);
    }

    private static Entscheidung sieg(Kampf k, Stand s) {
        if (s.sicher()) return Entscheidung.NICHTS;   // steht schon auf einer Liste
        boolean dritter = k.siegeNotiz() >= 3;
        long dauer = k.dauerS();
        Liste art = dritter ? Liste.SICHER
                : dauer <= 0 ? Liste.KEINE
                : dauer < SIEG_SICHER_S ? Liste.SICHER
                : dauer <= BIS_MACHBAR_S ? Liste.MACHBAR : Liste.KEINE;
        if (art == Liste.KEINE) return Entscheidung.NICHTS;
        if (art == Liste.MACHBAR && s.machbar()) return Entscheidung.NICHTS;
        return new Entscheidung(art, false, dritter, false);
    }

    /**
     * Die automatische Liste (2 Siege -> machbar, 3 -> sicher) VOR dem Kampf, so wie der Bot sie im Schild sah
     * (ArenaMode.mitAutoListe): war der letzte Kampf eine Niederlage, faellt sie sofort eine Stufe.
     * @return null = keine automatische Liste
     */
    static ArenaMode.AutoListe autoStufe(int siege, int kaempfe, char letzterKampf) {
        ArenaMode.AutoListe a = ArenaMode.autoListe(siege, kaempfe);
        if (a != null && letzterKampf == 'N') a = a != ArenaMode.AutoListe.MACHBAR ? ArenaMode.AutoListe.MACHBAR : null;
        return a;
    }
}
