package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;
import de.hustlebot.core.Stigmaplan;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Das Fenster "Warteschlange" (Autor 15.11.: "Der Knopf heisst Warteschlange und oeffnet ein eigenes, schoenes Fenster"): bis
 * zu fuenf nummerierte Zeilen, jede ein Modus mit allen Einstellungen aus dem Modus-Menue. Nummer 1 laeuft zuerst, die anderen
 * folgen der Reihe nach; mit den Pfeilen rutscht eine Zeile hoch oder runter. Darunter die Schalter "Zwischendurch"
 * (Leuchtturm, Kolosseum, Titanen-Turm, Clan-Krieg), eine Endzeit fuer die ganze Liste und Profile zum Speichern und Laden.
 *
 * "Start" schickt die Befehle aus {@link Warteschlange#befehle} ueber den einen Befehlsweg (Steuerprogramm.senden) -- die
 * ganze Liste geht in EINEM Befehl zum Bot ("!queue start A | B | C"). Nichts hier greift am Bot vorbei.
 */
final class Warteschlangenfenster extends JDialog {

    /** Der Eintrag "leer" ganz oben in jeder Modus-Liste. */
    private static final Object LEER = new Object() {
        @Override public String toString() { return Sprache.t("– empty –", "– leer –"); }
    };

    private final Steuerprogramm steuer;
    private final Runnable profileGeaendert;
    private final List<Zeile> zeilen = new ArrayList<>();
    private final Map<Warteschlange.Schalter, JCheckBox> schalter = new EnumMap<>(Warteschlange.Schalter.class);
    private final JTextField endeUm = Holzstil.feld("", 6);
    private final JTextArea vorschau = Holzstil.lesebereich();
    private final JLabel antwort = Holzstil.hinweis(" ");
    private final JComboBox<String> profilWahl = Holzstil.auswahl(new String[0]);

    /**
     * @param profileGeaendert laeuft nach "Als Profil speichern" (das Steuerfenster traegt den Namen in seine Profil-Liste ein)
     */
    Warteschlangenfenster(Window owner, Steuerprogramm steuer, Runnable profileGeaendert) {
        super(owner, Sprache.t("Queue", "Warteschlange"), ModalityType.MODELESS);
        this.steuer = steuer;
        this.profileGeaendert = profileGeaendert == null ? () -> { } : profileGeaendert;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        getContentPane().setBackground(Holzstil.GRUND);
        setLayout(new BorderLayout());

        JPanel kopf = Holzstil.kopfleiste(Sprache.t("Queue", "Warteschlange"));
        JLabel unterzeile = new JLabel(Sprache.t("Up to 5 modes, one after another – number 1 starts first.",
                "Bis zu 5 Modi nacheinander – Nummer 1 startet zuerst."));
        unterzeile.setFont(Holzstil.KLEIN);
        unterzeile.setForeground(Holzstil.leiseAufKopf());
        kopf.add(unterzeile, BorderLayout.SOUTH);
        add(kopf, BorderLayout.NORTH);

        JPanel inhalt = new Holzstil.Rollflaeche(new BorderLayout());
        JPanel stapel = Holzstil.flaeche(null);
        stapel.setLayout(new BoxLayout(stapel, BoxLayout.Y_AXIS));
        stapel.setBorder(BorderFactory.createEmptyBorder(Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));

        JPanel liste = Holzstil.karte(Sprache.t("Modes one after another", "Modi nacheinander"), null);
        liste.setLayout(new BoxLayout(liste, BoxLayout.Y_AXIS));
        for (int i = 0; i < Warteschlange.MAX; i++) {
            Zeile z = new Zeile(i);
            zeilen.add(z);
            z.flaeche.setAlignmentX(LEFT_ALIGNMENT);
            liste.add(z.flaeche);
        }
        hinzu(stapel, liste);
        hinzu(stapel, zwischendurchKarte());
        hinzu(stapel, endeKarte());
        hinzu(stapel, profilKarte());
        hinzu(stapel, vorschauKarte());
        inhalt.add(stapel, BorderLayout.NORTH);
        add(Holzstil.rollenOhneRahmen(inhalt), BorderLayout.CENTER);
        add(knopfLeiste(), BorderLayout.SOUTH);

        // Vorgabe: Zeile 1 wie das Modus-Menue (Kathedrale, 10), der Rest leer.
        zeilen.get(0).setzen(Warteschlange.Eintrag.von(Befehlsbau.Modus.KATHEDRALE, 10));
        for (int i = 1; i < zeilen.size(); i++) zeilen.get(i).setzen(Warteschlange.Eintrag.von(null, 10));
        profileNeu();
        vorschauNeu();
        pack();
        Dimension d = getSize();
        Rectangle schirm = GraphicsEnvironment.isHeadless() ? new Rectangle(0, 0, 1400, 1000)
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(Math.max(d.width, Holzstil.px(820)), schirm.width), Math.min(d.height, schirm.height - 40));
        setLocationRelativeTo(owner);
    }

    private static void hinzu(JPanel stapel, JComponent k) {
        if (stapel.getComponentCount() > 0) stapel.add(Box.createVerticalStrut(Holzstil.LUFT_KLEIN));
        k.setAlignmentX(LEFT_ALIGNMENT);
        stapel.add(k);
    }

    // ==================================================================
    //  Eine Zeile
    // ==================================================================

    /** Eine nummerierte Zeile: Modus links, darunter genau die Einstellungen, die zu ihm gehoeren. */
    private final class Zeile {
        final int nr;
        final JPanel flaeche;
        final JComboBox<Object> modus = Holzstil.auswahl(new Object[0]);
        final JTextField anzahl = Holzstil.feld("10", 5);
        final JComboBox<String> weg = Holzstil.auswahl(new String[]{Sprache.t("c – turbo", "c – Turbo"), Sprache.t("b – all waypoints", "b – alle Wegpunkte")});
        final JComboBox<Befehlsbau.Bezahlart> bezahl = Holzstil.auswahl(Befehlsbau.Bezahlart.values());
        final JComboBox<Warteschlange.Ablauf> ablauf = Holzstil.auswahl(Warteschlange.Ablauf.values());
        final JTextField freund = Holzstil.feld("", 14);
        final JTextField bis = Holzstil.feld("135", 4);
        final JTextField l1 = Holzstil.feld("", 4), n1 = Holzstil.feld("", 7), l2 = Holzstil.feld("", 4), n2 = Holzstil.feld("", 7);
        final JComboBox<String> titan = Holzstil.auswahl(Steuerfenster.titanNamen());
        final JPanel einstellungen = Holzstil.flaeche(null);
        private boolean setztGerade;

        Zeile(int nr) {
            this.nr = nr;
            DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<>();
            m.addElement(LEER);
            for (Befehlsbau.Modus mo : Befehlsbau.Modus.values()) {
                m.addElement(mo);
            }
            m.addElement(Befehlsbau.Stigma.LOESCHEN);
            m.addElement(Befehlsbau.Stigma.LEVELN);
            m.addElement(Befehlsbau.Stigma.HUNDERT);
            modus.setModel(m);
            breiteNachInhalt(modus, 0);
            modus.setToolTipText(Sprache.t("What this step plays. Empty = skip the step.", "Was dieser Schritt spielt. Leer = Schritt auslassen."));
            anzahl.setToolTipText(Sprache.t("How often. Event and titan excavation: empty = until everything is used up.",
                    "Wie oft. Event und Titan-Grabung: leer = bis alles aufgebraucht ist."));
            freund.setToolTipText(Sprache.t("Click in: your teammates (several possible). Empty = random players.",
                    "Reinklicken: deine Mitspieler (mehrere möglich). Leer = zufällige Spieler."));
            freund.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { mitspielerListe(freund); }
            });
            bis.setToolTipText(Sprache.t("Portal: play level by level up to this level (empty = no climbing).",
                    "Portal: Level für Level bis zu diesem Level spielen (leer = kein Aufstieg)."));
            for (JTextField f : new JTextField[]{l1, l2}) f.setToolTipText(Sprache.t("Level to farm in auto battle (empty = none).", "Level zum Farmen im Autokampf (leer = keins)."));
            for (JTextField f : new JTextField[]{n1, n2}) f.setToolTipText(Sprache.t("At most this much food for the level, e.g. 2 mio, 500k.",
                    "Höchstens so viel Nahrung für das Level, z. B. 2 Mio, 500k."));
            titan.setToolTipText(Sprache.t("At which titan.", "Bei welchem Titan."));

            JLabel nummer = new JLabel(String.valueOf(nr + 1), SwingConstants.CENTER);
            nummer.setFont(Holzstil.GROSS);
            nummer.setForeground(Holzstil.KOPF);
            nummer.setPreferredSize(new Dimension(Holzstil.px(28), Holzstil.px(28)));
            nummer.setVerticalAlignment(SwingConstants.TOP);

            JButton hoch = Holzstil.symbolKnopf(Symbol.Art.PFEIL_OBEN, Sprache.t("Move up (plays earlier)", "Nach oben (spielt früher)"));
            JButton runter = Holzstil.symbolKnopf(Symbol.Art.PFEIL_UNTEN, Sprache.t("Move down (plays later)", "Nach unten (spielt später)"));
            JButton leeren = Holzstil.symbolKnopf(Symbol.Art.KREUZ, Sprache.t("Empty this step", "Schritt leeren"));
            hoch.addActionListener(e -> verschieben(nr, -1));
            runter.addActionListener(e -> verschieben(nr, +1));
            leeren.addActionListener(e -> { setzen(Warteschlange.Eintrag.von(null, 10)); vorschauNeu(); });
            JPanel knoepfe = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            knoepfe.add(hoch);
            knoepfe.add(runter);
            knoepfe.add(leeren);

            JPanel oben = Holzstil.flaeche(new BorderLayout(Holzstil.LUFT_KLEIN, 0));
            JPanel modusReihe = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
            modusReihe.add(modus);
            oben.add(modusReihe, BorderLayout.CENTER);
            oben.add(knoepfe, BorderLayout.EAST);
            einstellungen.setLayout(new BoxLayout(einstellungen, BoxLayout.Y_AXIS));

            JPanel rechts = Holzstil.flaeche(new BorderLayout(0, 2));
            rechts.add(oben, BorderLayout.NORTH);
            rechts.add(einstellungen, BorderLayout.CENTER);

            flaeche = Holzstil.flaeche(new BorderLayout(Holzstil.LUFT_KLEIN, 0));
            flaeche.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, nr < Warteschlange.MAX - 1 ? 1 : 0, 0, Holzstil.LINIE),
                    BorderFactory.createEmptyBorder(2, 0, 6, 0)));
            flaeche.add(nummer, BorderLayout.WEST);
            flaeche.add(rechts, BorderLayout.CENTER);

            modus.addActionListener(e -> {
                if (setztGerade) return;
                // Event und Titan-Grabung laufen ohne Zahl bis leer -- beim Wechsel dorthin das Feld leeren.
                if (Warteschlange.bisLeer(modus.getSelectedItem())) anzahl.setText("");
                else if (anzahl.getText().isBlank()) anzahl.setText("10");
                aufbauen();
                vorschauNeu();
            });
            for (JComboBox<?> c : new JComboBox<?>[]{weg, bezahl, ablauf, titan}) c.addActionListener(e -> vorschauNeu());
            for (JTextField f : new JTextField[]{anzahl, freund, bis, l1, n1, l2, n2}) f.getDocument().addDocumentListener(dokument(Warteschlangenfenster.this::vorschauNeu));
        }

        /** Die Einstellungen unter dem Modus neu zusammenstellen -- nur, was zu ihm gehoert. */
        void aufbauen() {
            einstellungen.removeAll();
            Object w = modus.getSelectedItem();
            if (w instanceof Befehlsbau.Modus m) {
                List<JComponent> r1 = new ArrayList<>();
                if (m.mitAnzahl) {
                    r1.add(Holzstil.beschriftung(Sprache.t("Number", "Anzahl")));
                    r1.add(anzahl);
                    if (Warteschlange.bisLeer(m)) r1.add(Holzstil.hinweis(Sprache.t("(empty = until used up)", "(leer = bis aufgebraucht)")));
                }
                switch (m) {
                    case KATHEDRALE -> {
                        r1.add(Holzstil.beschriftung(Sprache.t("Route", "Weg")));
                        r1.add(weg);
                        reihe(r1);
                        reihe(List.of(Holzstil.beschriftung(Sprache.t("Pay with", "Bezahlen")), bezahl, ablauf));
                    }
                    case VERLIES -> {
                        r1.add(Holzstil.beschriftung(Sprache.t("Friend", "Mitspieler")));
                        r1.add(freund);
                        reihe(r1);
                        reihe(List.of(Holzstil.beschriftung(Sprache.t("Pay with", "Bezahlen")), bezahl));
                    }
                    case ARENA -> {
                        r1.add(Holzstil.beschriftung(Sprache.t("Pay with", "Bezahlen")));
                        r1.add(bezahl);
                        reihe(r1);
                    }
                    case PORTAL -> {
                        reihe(List.of(Holzstil.beschriftung(Sprache.t("Up to level", "Bis Level")), bis,
                                Holzstil.hinweis(Sprache.t("then farm with food:", "danach farmen mit Nahrung:"))));
                        reihe(List.of(Holzstil.beschriftung(Sprache.t("1st level", "1. Level")), l1,
                                Holzstil.beschriftung(Sprache.t("max. food", "max. Nahrung")), n1,
                                Holzstil.beschriftung(Sprache.t("2nd level", "2. Level")), l2,
                                Holzstil.beschriftung(Sprache.t("max. food", "max. Nahrung")), n2));
                    }
                    default -> {
                        if (r1.isEmpty()) r1.add(Holzstil.hinweis(Sprache.t("Runs once – nothing to set.", "Läuft einmal – nichts einzustellen.")));
                        reihe(r1);
                    }
                }
            } else if (w == Befehlsbau.Stigma.LOESCHEN) {
                JButton welche = Holzstil.kleinerKnopf(Sprache.t("Which ones …", "Welche …"),
                        Sprache.t("Choose which stigma sets are deleted (level 1-79). Saved for every delete run.",
                                "Wählen, welche Stigma-Sets gelöscht werden (Level 1-79). Gilt für jedes Löschen."), Symbol.Art.HAKEN);
                welche.addActionListener(e -> {
                    Stigmafenster.loeschen(Warteschlangenfenster.this, () -> { });
                    aufbauen();
                    vorschauNeu();
                });
                reihe(List.of(welche, Holzstil.hinweis(Sprache.t("deletes: ", "löscht: ") + loeschText())));
            } else if (w == Befehlsbau.Stigma.LEVELN || w == Befehlsbau.Stigma.HUNDERT) {
                reihe(List.of(Holzstil.beschriftung("Titan"), titan, Holzstil.hinweis(w == Befehlsbau.Stigma.HUNDERT
                        ? Sprache.t("uses rays and Stones of Creation", "verbraucht Strahlen und Stones of Creation")
                        : Sprache.t("checks, equips and levels stigmas", "prüft, setzt ein und levelt Stigmas"))));
            } else {
                reihe(List.of(Holzstil.hinweis(Sprache.t("Empty – this step is skipped.", "Leer – dieser Schritt wird ausgelassen."))));
            }
            einstellungen.revalidate();
            einstellungen.repaint();
        }

        private void reihe(List<? extends JComponent> teile) {
            JPanel r = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
            for (JComponent k : teile) r.add(k);
            r.setAlignmentX(LEFT_ALIGNMENT);
            einstellungen.add(r);
        }

        /** Was die Zeile gerade zeigt. */
        Warteschlange.Eintrag lesen() {
            Object w = modus.getSelectedItem();
            Object art = w == LEER ? null : w;
            int n = anzahl.getText().isBlank() ? (Warteschlange.bisLeer(art) ? 0 : 1) : Befehlsbau.zahl(anzahl.getText(), 1);
            return new Warteschlange.Eintrag(art, n, weg.getSelectedIndex() == 1 ? "b" : "c",
                    (Befehlsbau.Bezahlart) bezahl.getSelectedItem(), (Warteschlange.Ablauf) ablauf.getSelectedItem(),
                    freund.getText(), bis.getText(), l1.getText(), n1.getText(), l2.getText(), n2.getText(),
                    Warteschlange.titanWort(String.valueOf(titan.getSelectedItem())));
        }

        /** Einen Eintrag in die Zeile schreiben (Profil laden, Hoch/Runter). */
        void setzen(Warteschlange.Eintrag e) {
            setztGerade = true;
            try {
                modus.setSelectedItem(e.leer() ? LEER : e.art());
                if (modus.getSelectedIndex() < 0) modus.setSelectedItem(LEER);   // z. B. Edelsteinbucht ohne Schluessel
                anzahl.setText(e.anzahl() > 0 ? String.valueOf(e.anzahl()) : Warteschlange.bisLeer(e.art()) ? "" : "10");
                weg.setSelectedIndex("b".equals(e.weg()) ? 1 : 0);
                bezahl.setSelectedItem(e.bezahl());
                ablauf.setSelectedItem(e.ablauf());
                freund.setText(e.freund());
                bis.setText(e.art() == Befehlsbau.Modus.PORTAL || !e.portalBis().isEmpty() ? e.portalBis() : "135");
                l1.setText(e.level1());
                n1.setText(e.nahrung1());
                l2.setText(e.level2());
                n2.setText(e.nahrung2());
                titan.setSelectedIndex(0);
                for (int i = 0; i < titan.getItemCount(); i++) {
                    if (Warteschlange.titanWort(titan.getItemAt(i)).equals(e.titan())) {
                        titan.setSelectedIndex(i);
                        break;
                    }
                }
            } finally {
                setztGerade = false;
            }
            aufbauen();
        }
    }

    /** "Bull, Dragon ..." -- welche Sets das Loeschen zerlegt (alle ohne Haken im Auswahlfenster bleiben). */
    private static String loeschText() {
        java.util.Set<String> behalten = Stigmaplan.behalten();
        List<String> weg = new ArrayList<>();
        for (String s : Stigmaplan.SETS) if (!behalten.contains(s)) weg.add(Stigmaplan.gross(s));
        if (weg.isEmpty()) return Sprache.t("nothing", "nichts");
        if (behalten.isEmpty()) return Sprache.t("all sets", "alle Sets");
        return weg.size() > 6 ? String.join(", ", weg.subList(0, 6)) + " …" : String.join(", ", weg);
    }

    // ==================================================================
    //  Karten unter der Liste
    // ==================================================================

    private JComponent zwischendurchKarte() {
        JPanel k = Holzstil.karte(Sprache.t("In between", "Zwischendurch"), new BorderLayout(0, Holzstil.LUFT_KLEIN));
        JPanel reihe = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT, 0));
        for (Warteschlange.Schalter s : Warteschlange.Schalter.values()) {
            JCheckBox c = Holzstil.kaestchen(s.anzeige, aktuell(s), s == Warteschlange.Schalter.CLANKRIEG
                    ? Sprache.t("On: the bot deploys for the clan war by itself at the war times, then carries on with the queue.",
                            "An: der Bot stellt zu den Kriegszeiten selbst für den Clan-Krieg auf und macht dann mit der Warteschlange weiter.")
                    : Sprache.t("On: the bot does this on the side whenever it is due, then carries on with the queue.",
                            "An: der Bot macht das nebenbei, sobald es fällig ist, und dann mit der Warteschlange weiter."));
            c.addActionListener(e -> vorschauNeu());
            schalter.put(s, c);
            reihe.add(c);
        }
        k.add(reihe, BorderLayout.CENTER);
        k.add(Holzstil.hinweis(Sprache.t("The same switches as in Settings – they apply to every mode.",
                "Dieselben Schalter wie in den Einstellungen – sie gelten für jeden Modus.")), BorderLayout.SOUTH);
        return k;
    }

    /** Der jetzige Stand eines Schalters (wie die Anzeige "jetzt: an" in den Einstellungen); unbekannt = an. */
    private static boolean aktuell(Warteschlange.Schalter s) {
        Einstellungswerte.Wert w = Einstellungswerte.finde(s.basis);
        String roh = w == null ? null : w.aktuellRoh();
        return roh == null || roh.equals("an") || roh.equals("on");
    }

    private JComponent endeKarte() {
        JPanel k = Holzstil.karte(Sprache.t("Stop time", "Endzeit"), new BorderLayout(0, 2));
        endeUm.setToolTipText(Sprache.t("e.g. 06:30 – empty = no stop time", "z. B. 06:30 – leer = keine Endzeit"));
        endeUm.getDocument().addDocumentListener(dokument(this::vorschauNeu));
        JPanel reihe = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        reihe.add(Holzstil.beschriftung(Sprache.t("Stop at", "Aufhören um")));
        reihe.add(endeUm);
        reihe.add(Holzstil.hinweis(Sprache.t("HH:MM – empty = no stop time", "HH:MM – leer = keine Endzeit")));
        k.add(reihe, BorderLayout.CENTER);
        k.add(Holzstil.hinweis(Sprache.t("Counts for the whole queue: at that time the running run is finished, then everything stops.",
                "Gilt für die ganze Warteschlange: um diese Zeit wird der laufende Durchlauf fertig gespielt, dann hört alles auf.")), BorderLayout.SOUTH);
        return k;
    }

    private JComponent profilKarte() {
        JPanel k = Holzstil.karte(Sprache.t("Profiles", "Profile"), new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        profilWahl.setToolTipText(Sprache.t("Your saved profiles.", "Deine gespeicherten Profile."));
        JButton laden = Holzstil.kleinerKnopf(Sprache.t("Load", "Laden"),
                Sprache.t("Puts the queue of the chosen profile into the lines above.", "Holt die Warteschlange des gewählten Profils in die Zeilen oben."), Symbol.Art.ORDNER);
        laden.addActionListener(e -> profilLaden());
        JButton speichern = Holzstil.kleinerKnopf(Sprache.t("Save as profile", "Als Profil speichern"),
                Sprache.t("Saves the queue, the switches and the stop time as a profile. It then also appears in the mode list.",
                        "Speichert Warteschlange, Schalter und Endzeit als Profil. Es steht dann auch in der Modus-Liste."), Symbol.Art.HAKEN);
        speichern.addActionListener(e -> profilSpeichern());
        k.add(profilWahl);
        k.add(laden);
        k.add(Box.createHorizontalStrut(Holzstil.LUFT));
        k.add(speichern);
        return k;
    }

    private JComponent vorschauKarte() {
        JPanel k = Holzstil.karte(Sprache.t("Commands to the bot", "Befehle an den Bot"), new BorderLayout());
        vorschau.setFont(Holzstil.KLEIN);
        vorschau.setLineWrap(true);
        vorschau.setWrapStyleWord(true);
        vorschau.setRows(3);
        k.add(vorschau, BorderLayout.CENTER);
        return k;
    }

    private JComponent knopfLeiste() {
        JPanel p = Holzstil.grund(new BorderLayout(Holzstil.LUFT, 0), Holzstil.LUFT);
        JButton start = Holzstil.knopf("Start", Sprache.t("Plays the steps one after another. If the bot is busy, they follow the running mode.",
                "Spielt die Schritte der Reihe nach. Ist der Bot beschäftigt, folgen sie dem laufenden Modus."), Holzstil.GUT, Symbol.Art.START);
        start.addActionListener(e -> starten());
        JButton zu = Holzstil.knopf(Sprache.t("Close", "Schließen"), Sprache.t("Closes the window – the queue stays as it is.",
                "Schließt das Fenster – die Warteschlange bleibt, wie sie ist."), Symbol.Art.KREUZ);
        zu.addActionListener(e -> setVisible(false));
        JPanel knoepfe = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        knoepfe.add(start);
        knoepfe.add(zu);
        p.add(knoepfe, BorderLayout.WEST);
        p.add(antwort, BorderLayout.CENTER);
        return p;
    }

    // ==================================================================
    //  Handeln
    // ==================================================================

    /** Die belegten Zeilen in ihrer Reihenfolge. */
    List<Warteschlange.Eintrag> eintraege() {
        List<Warteschlange.Eintrag> l = new ArrayList<>();
        for (Zeile z : zeilen) {
            Warteschlange.Eintrag e = z.lesen();
            if (!e.leer()) l.add(e);
        }
        return l;
    }

    private Map<Warteschlange.Schalter, Boolean> alleSchalter() {
        Map<Warteschlange.Schalter, Boolean> m = new EnumMap<>(Warteschlange.Schalter.class);
        schalter.forEach((s, c) -> m.put(s, c.isSelected()));
        return m;
    }

    /** Nur die Schalter, die anders stehen als jetzt im Bot -- beim Start keine Meldung fuer nichts. */
    private Map<Warteschlange.Schalter, Boolean> geaenderteSchalter() {
        Map<Warteschlange.Schalter, Boolean> m = new EnumMap<>(Warteschlange.Schalter.class);
        schalter.forEach((s, c) -> {
            if (c.isSelected() != aktuell(s)) m.put(s, c.isSelected());
        });
        return m;
    }

    private static boolean nichtsBehalten() {
        return Stigmaplan.behalten().isEmpty();
    }

    void vorschauNeu() {
        if (zeilen.size() < Warteschlange.MAX || schalter.isEmpty()) return;   // noch im Aufbau
        String u = Warteschlange.uhrzeit(endeUm.getText());
        List<String> b = Warteschlange.befehle(eintraege(), geaenderteSchalter(), u == null ? "" : u, nichtsBehalten());
        vorschau.setText(b.isEmpty() ? Sprache.t("Choose at least one mode.", "Wähle mindestens einen Modus.") : String.join("\n", b));
        if (u == null) antwort.setText(Sprache.t("Stop time not understood – e.g. 06:30.", "Endzeit nicht verstanden – z. B. 06:30."));
    }

    private void verschieben(int i, int richtung) {
        List<Warteschlange.Eintrag> l = new ArrayList<>();
        for (Zeile z : zeilen) l.add(z.lesen());
        Warteschlange.verschieben(l, i, richtung);
        for (int k = 0; k < zeilen.size(); k++) zeilen.get(k).setzen(l.get(k));
        vorschauNeu();
    }

    private void starten() {
        List<Warteschlange.Eintrag> l = eintraege();
        if (l.isEmpty()) {
            antwort.setText(Sprache.t("Choose at least one mode.", "Wähle mindestens einen Modus."));
            return;
        }
        String u = Warteschlange.uhrzeit(endeUm.getText());
        if (u == null) {
            antwort.setText(Sprache.t("Stop time not understood – e.g. 06:30.", "Endzeit nicht verstanden – z. B. 06:30."));
            return;
        }
        // Wie im Steuerfenster (Code-Pruefung 16.10.): Leveln verbraucht wirklich Strahlen und Stones of Creation -- einmal fragen.
        boolean leveln = l.stream().anyMatch(e -> e.art() == Befehlsbau.Stigma.LEVELN || e.art() == Befehlsbau.Stigma.HUNDERT);
        if (leveln && !steuer.fragen(Sprache.t("The queue really levels stigmas and uses up rays and Stones of Creation.\n\nStart now?",
                "Die Warteschlange levelt Stigmas wirklich und verbraucht Strahlen und Stones of Creation.\n\nJetzt starten?"),
                Sprache.t("Queue", "Warteschlange"))) {
            return;
        }
        mitspielerMerken(l);
        List<String> befehle = Warteschlange.befehle(l, geaenderteSchalter(), u, nichtsBehalten());
        String quelle = Sprache.t("Queue window", "Fenster Warteschlange");
        antwort.setText(Sprache.t("Sent – waiting for the bot …", "Geschickt – warte auf den Bot …"));
        for (int i = 0; i < befehle.size(); i++) {
            if (i < befehle.size() - 1) steuer.senden(befehle.get(i), quelle);
            else steuer.senden(befehle.get(i), quelle, false, e -> antwort.setText(e.kurz()));
        }
    }

    private void profilSpeichern() {
        List<Warteschlange.Eintrag> l = eintraege();
        String u = Warteschlange.uhrzeit(endeUm.getText());
        if (l.isEmpty() || u == null) {
            antwort.setText(l.isEmpty() ? Sprache.t("Choose at least one mode.", "Wähle mindestens einen Modus.")
                    : Sprache.t("Stop time not understood – e.g. 06:30.", "Endzeit nicht verstanden – z. B. 06:30."));
            return;
        }
        String titel = Sprache.t("Save as profile", "Als Profil speichern");
        Object vorschlag = profilWahl.getSelectedItem();
        String n = (String) JOptionPane.showInputDialog(this, Sprache.t("Name of the profile", "Name des Profils"), titel,
                JOptionPane.PLAIN_MESSAGE, null, null, vorschlag == null ? "" : vorschlag);
        if (n == null || n.isBlank()) return;
        n = n.trim();
        if (steuer.profile.namen().contains(n) && !steuer.fragen(Sprache.t("A profile called \"" + n + "\" exists. Replace it?",
                "Ein Profil „" + n + "“ gibt es schon. Ersetzen?"), titel)) return;
        // Im Profil stehen alle vier Schalter -- es soll beim Anwenden genau diesen Stand herstellen.
        if (!steuer.profile.setzen(n, Warteschlange.befehle(l, alleSchalter(), u, nichtsBehalten()))) return;
        profileNeu();
        profilWahl.setSelectedItem(n);
        profileGeaendert.run();
        antwort.setText(Sprache.t("Profile saved: " + n + " – also in the mode list.", "Profil gespeichert: " + n + " – steht auch in der Modus-Liste."));
    }

    private void profilLaden() {
        Object n = profilWahl.getSelectedItem();
        if (n == null) return;
        Warteschlange.Geladen g = Warteschlange.lesen(steuer.profile.befehle(String.valueOf(n)));
        if (g.eintraege().isEmpty()) {
            antwort.setText(Sprache.t("This profile has no modes to play.", "Dieses Profil hat keine Modi zum Spielen."));
            return;
        }
        laden(g);
        antwort.setText(Sprache.t("Profile loaded: " + n + ". Press Start to play it.", "Profil geladen: " + n + ". Mit Start geht es los."));
    }

    /** Ein gelesenes Profil in die Zeilen, Schalter und die Endzeit schreiben. */
    void laden(Warteschlange.Geladen g) {
        for (int i = 0; i < zeilen.size(); i++) {
            zeilen.get(i).setzen(i < g.eintraege().size() ? g.eintraege().get(i) : Warteschlange.Eintrag.von(null, 10));
        }
        g.schalter().forEach((s, an) -> schalter.get(s).setSelected(an));
        endeUm.setText(g.endeUm());
        vorschauNeu();
    }

    /** Die Profil-Auswahl neu fuellen (beim Oeffnen und nach dem Speichern). */
    void profileNeu() {
        Object vorher = profilWahl.getSelectedItem();
        DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
        for (String n : steuer.profile.namen()) m.addElement(n);
        profilWahl.setModel(m);
        if (vorher != null) profilWahl.setSelectedItem(vorher);
        breiteNachInhalt(profilWahl, Holzstil.px(180));
        profilWahl.revalidate();
    }

    /** Holzstil.auswahl legt die Breite beim Bauen fest -- nach dem Fuellen neu ausmessen (sonst ein leerer schmaler Kasten). */
    private static void breiteNachInhalt(JComboBox<?> c, int mindestens) {
        c.setPreferredSize(null);
        c.setPreferredSize(new Dimension(Math.max(mindestens, c.getPreferredSize().width + 8), Holzstil.HOEHE_KLEIN));
    }

    // ==================================================================
    //  Mitspieler (wie im Steuerfenster)
    // ==================================================================

    private List<String> bekannteMitspieler() {
        List<String> l = new ArrayList<>();
        for (String n : steuer.einst.text(Steuerfenster.MITSPIELER, "").split(",")) {
            String t = n.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && !l.contains(t)) l.add(t);
        }
        return l;
    }

    private static LinkedHashSet<String> namenAus(String feld) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (String n : (feld == null ? "" : feld).split("[,\\s]+")) {
            String t = n.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    private void mitspielerMerken(List<Warteschlange.Eintrag> l) {
        List<String> bekannt = bekannteMitspieler();
        boolean neu = false;
        for (Warteschlange.Eintrag e : l) {
            if (e.art() != Befehlsbau.Modus.VERLIES) continue;
            for (String n : namenAus(e.freund())) {
                if (!bekannt.contains(n)) {
                    bekannt.add(n);
                    neu = true;
                }
            }
        }
        if (neu) steuer.einst.setzen(Steuerfenster.MITSPIELER, String.join(",", bekannt));
    }

    /** Beim Reinklicken: alle bekannten Mitspieler zum Anhaken, dazu "zufaellig". */
    private void mitspielerListe(JTextField feld) {
        JPopupMenu menue = new JPopupMenu();
        menue.setFocusable(false);
        JMenuItem zufall = new JMenuItem(Sprache.t("Random players (field empty)", "Zufällige Spieler (Feld leer)"));
        zufall.addActionListener(e -> feld.setText(""));
        menue.add(zufall);
        menue.addSeparator();
        List<String> bekannt = bekannteMitspieler();
        if (bekannt.isEmpty()) {
            JMenuItem leer = new JMenuItem(Sprache.t("No names yet – type them in once and start, then they show up here.",
                    "Noch keine Namen – einmal eintippen und starten, dann stehen sie hier."));
            leer.setEnabled(false);
            menue.add(leer);
        }
        for (String n : bekannt) {
            JCheckBoxMenuItem i = new JCheckBoxMenuItem(n, namenAus(feld.getText()).contains(n));
            i.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true);
            i.addActionListener(e -> {
                LinkedHashSet<String> s = namenAus(feld.getText());
                if (i.isSelected()) s.add(n); else s.remove(n);
                feld.setText(String.join(",", s));
            });
            menue.add(i);
        }
        menue.show(feld, 0, feld.getHeight());
    }

    private static javax.swing.event.DocumentListener dokument(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }
}
