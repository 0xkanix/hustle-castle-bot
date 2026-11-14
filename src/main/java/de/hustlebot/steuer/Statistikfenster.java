package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import de.hustlebot.core.Laufstatistik;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Das Statistik-Fenster (Auftrag 4): oben die Statistik-Karte des Bots (wie !stats), darunter drei
 * kleine Diagramme -- Durchlaeufe je Stunde, Arena-Siegquote je Tag, Nahrung im Verlauf.
 *
 * Die Zahlen kommen aus dem, was es schon gibt (Statistikdaten): Laufgeschichte des Motors,
 * arena-lernen.txt, Burg-Stand-Karten dieser Sitzung. Nichts wird neu mitgeschrieben.
 */
final class Statistikfenster extends JFrame implements Steuerprogramm.Abmeldbar {

    private static final String[] TITEL = {"Statistik", "Statistics", "Stats"};

    private final Meldungen meldungen;
    private final Steuerprogramm steuer;
    private final JLabel kopfzeile = Holzstil.kopfzeile(" ");
    private final JLabel kartentitel = new JLabel(Sprache.t("Nothing fetched yet", "Noch nichts geholt"));
    private final JTextArea inhalt = Holzstil.lesebereich();
    /** Autor 21.10.: wie oft Top 1 / 2 / 3, insgesamt und je Tag (arena-plaetze.txt). */
    private final JTextArea plaetze = Holzstil.lesebereich();
    private final JLabel stand = Holzstil.hinweis(" ");
    // Autor 24.10. (F29): die Stunden von HEUTE, dauerhaft -- nach einem Neustart steht dort nicht mehr "nichts".
    private final Diagramm laeufe = new Diagramm(Sprache.t("Runs per hour (today)", "Durchläufe je Stunde (heute)"), Diagramm.Art.BALKEN, Holzstil.GUT)
            .leerText(Sprache.t("no run today yet", "heute noch kein Durchlauf"));
    /** Autor 24.10. (F29): Heute / 7 Tage / Gesamt aus statistik-tage.txt. */
    private final JTextArea tageText = Holzstil.lesebereich();
    private final Diagramm tageDiagramm = new Diagramm(Sprache.t("Runs per day", "Durchläufe je Tag"), Diagramm.Art.BALKEN, Holzstil.GUT)
            .leerText(Sprache.t("no run recorded yet", "noch kein Durchlauf aufgezeichnet"));
    /** Autor 24.10. (F30): wohin die Zeit eines Kathedralen-Runs geht, in Klartext. */
    private final JTextArea zeitText = Holzstil.lesebereich();
    // Autor 22.10.: gezaehlt werden Turniere -- Top 1-3 = Sieg, Top 4-10 = Niederlage (nicht mehr die einzelnen Kaempfe).
    private final Diagramm arena = new Diagramm(Sprache.t("Top 1-3 rate per day", "Top-1-3-Quote je Tag"), Diagramm.Art.BALKEN, Holzstil.KLICK)
            .einheit(" %").hoechstens(100).leerText(Sprache.t("no arena tournament finished yet", "noch kein Arena-Turnier beendet"));
    private final Diagramm nahrung = new Diagramm(Sprache.t("Food (castle status)", "Nahrung (Burg-Stand)"), Diagramm.Art.LINIE, Holzstil.WARNUNG)
            .format(Statistikdaten::kurz).leerText(Sprache.t("no castle status read yet", "noch kein Burg-Stand abgelesen (Fenster Vorrat)"));
    private final Consumer<Meldung> zuhoerer;
    /** Nur fuer die Vorschau gesetzt; sonst arena-plaetze.txt ueber ArenaPlaetze.lesen(). */
    private Path plaetzeDatei;
    private Timer takt;

    Statistikfenster(Meldungen meldungen, Steuerprogramm steuer, boolean obenauf) {
        this.meldungen = meldungen;
        this.steuer = steuer;
        setTitle(Sprache.t("Statistics", "Statistik"));
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setAlwaysOnTop(obenauf);
        setAutoRequestFocus(false);
        getContentPane().setBackground(Holzstil.GRUND);

        JPanel kopf = Holzstil.kopfleiste(Sprache.t("Statistics", "Statistik"));
        kopf.add(kopfzeile, BorderLayout.SOUTH);

        kartentitel.setFont(Holzstil.KNOPF);
        kartentitel.setForeground(Holzstil.LEISE);
        inhalt.setOpaque(false);
        inhalt.setMargin(new Insets(4, 0, 4, 0));
        inhalt.setText(Sprache.t("Press \"Refresh\" -- the bot\'s numbers appear here.", "\"Neu holen\" druecken -- die Zahlen des Bots erscheinen hier."));
        JPanel karte = Holzstil.karte(new BorderLayout(0, Holzstil.LUFT_KLEIN));
        karte.add(kartentitel, BorderLayout.NORTH);
        JScrollPane rollen = Holzstil.rollen(inhalt);
        rollen.setBorder(BorderFactory.createEmptyBorder());
        karte.add(rollen, BorderLayout.CENTER);
        karte.setPreferredSize(new Dimension(100, 150));

        JPanel diagramme = Holzstil.flaeche(new GridLayout(1, 3, Holzstil.LUFT, 0));
        diagramme.add(laeufe);
        diagramme.add(arena);
        diagramme.add(nahrung);
        diagramme.setPreferredSize(new Dimension(100, 120));

        plaetze.setOpaque(false);
        plaetze.setMargin(new Insets(2, 0, 2, 0));
        JLabel plaetzeTitel = new JLabel(Sprache.t("Arena places (top 1 / 2 / 3) -- total and per day",
                "Arena-Plaetze (Top 1 / 2 / 3) -- gesamt und je Tag"));
        plaetzeTitel.setFont(Holzstil.KNOPF);
        plaetzeTitel.setForeground(Holzstil.LEISE);
        JPanel plaetzeKarte = Holzstil.karte(new BorderLayout(0, Holzstil.LUFT_KLEIN));
        plaetzeKarte.add(plaetzeTitel, BorderLayout.NORTH);
        JScrollPane plaetzeRollen = Holzstil.rollen(plaetze);
        plaetzeRollen.setBorder(BorderFactory.createEmptyBorder());
        plaetzeKarte.add(plaetzeRollen, BorderLayout.CENTER);
        plaetzeKarte.setPreferredSize(new Dimension(100, 110));

        // Autor 24.10. (F29, F30): zwei weitere Karten als Reiter neben den Arena-Plaetzen -- das Fenster bleibt gleich gross.
        tageText.setOpaque(false);
        tageText.setMargin(new Insets(2, 0, 2, 0));
        JPanel tageKarte = Holzstil.karte(new BorderLayout(Holzstil.LUFT, 0));
        JScrollPane tageRollen = Holzstil.rollen(tageText);
        tageRollen.setBorder(BorderFactory.createEmptyBorder());
        tageKarte.add(tageRollen, BorderLayout.CENTER);
        tageDiagramm.setPreferredSize(new Dimension(200, 80));
        tageKarte.add(tageDiagramm, BorderLayout.EAST);
        zeitText.setOpaque(false);
        zeitText.setMargin(new Insets(2, 0, 2, 0));
        JPanel zeitKarte = Holzstil.karte(new BorderLayout());
        JScrollPane zeitRollen = Holzstil.rollen(zeitText);
        zeitRollen.setBorder(BorderFactory.createEmptyBorder());
        zeitKarte.add(zeitRollen, BorderLayout.CENTER);
        JTabbedPane texte = Holzstil.reiter();
        texte.addTab(Sprache.t("Arena places", "Arena-Plätze"), plaetzeKarte);
        texte.addTab(Sprache.t("Today / 7 days / total", "Heute / 7 Tage / Gesamt"), tageKarte);
        texte.addTab(Sprache.t("Where the time goes", "Wohin die Zeit geht"), zeitKarte);
        texte.setPreferredSize(new Dimension(100, 170));

        JPanel untenMitte = Holzstil.flaeche(new BorderLayout(0, Holzstil.LUFT));
        untenMitte.add(texte, BorderLayout.NORTH);
        untenMitte.add(diagramme, BorderLayout.SOUTH);

        JPanel mitte = Holzstil.grund(new BorderLayout(0, Holzstil.LUFT), Holzstil.LUFT);
        mitte.add(karte, BorderLayout.CENTER);
        mitte.add(untenMitte, BorderLayout.SOUTH);

        JButton neu = Holzstil.kleinerKnopf(Sprache.t("Refresh", "Neu holen"), Sprache.t("Sends ", "Schickt ") + Befehlsbau.STATS + Sprache.t(" -- like in Discord.", " -- wie in Discord."), Symbol.Art.NEU);
        neu.addActionListener(e -> steuer.senden(Befehlsbau.STATS, Sprache.t("Statistics window", "Fenster Statistik")));
        JButton zu = Holzstil.kleinerKnopf(Sprache.t("Hide", "Ausblenden"), Sprache.t("Closes the window. Shortcut or button bring it back.", "Macht das Fenster zu. Kuerzel oder Knopf holen es wieder."), Symbol.Art.KREUZ);
        zu.addActionListener(e -> setVisible(false));
        JPanel unten = Holzstil.grund(new BorderLayout(), 0);
        unten.setBorder(BorderFactory.createEmptyBorder(0, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));
        JPanel knoepfe = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0));
        knoepfe.add(neu);
        knoepfe.add(zu);
        unten.add(stand, BorderLayout.WEST);
        unten.add(knoepfe, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(kopf, BorderLayout.NORTH);
        add(mitte, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        setSize(600, 590);   // Autor 11.10.: nicht zu gross (21.10.: +120 fuer die Arena-Plaetze)

        zuhoerer = m -> SwingUtilities.invokeLater(() -> {
            if (m.titelEnthaelt(TITEL)) zeigen(m);
            if (m.titelEnthaelt("Burg-Stand", "Castle status")) diagrammeNeu();
        });
        meldungen.zuhoeren(zuhoerer);
        Meldung letzte = meldungen.letzteMit(TITEL);
        if (letzte != null) zeigen(letzte);
        diagrammeNeu();
    }

    /** Fuer die Vorschau: eine andere Platz-Datei und gestellte Laeufe (es gibt dort keinen Motor). */
    void vorschau(Path plaetzeDatei, List<Laufstatistik.RunRecord> laeufe) {
        this.plaetzeDatei = plaetzeDatei;
        this.vorschauLaeufe = laeufe;
        diagrammeNeu();
    }

    private List<Laufstatistik.RunRecord> vorschauLaeufe;

    @Override
    public void abmelden() {
        meldungen.nichtMehrZuhoeren(zuhoerer);
        if (takt != null) takt.stop();
    }

    private void zeigen(Meldung m) {
        kartentitel.setText(m.titelLesbar());
        kartentitel.setForeground(Holzstil.kartenfarbe(m.farbe()));
        inhalt.setText(m.textLesbar());
        inhalt.setCaretPosition(0);
        stand.setText(Sprache.t("As of ", "Stand ") + m.uhrzeit());
    }

    /** Diagramme aus den Quellen neu rechnen. */
    void diagrammeNeu() {
        try {
            Laufstatistik t = steuer.laufstatistik();
            List<Laufstatistik.RunRecord> laufliste = t != null ? t.history() : vorschauLaeufe != null ? vorschauLaeufe : List.of();
            // Autor 24.10. (F29): erst die neuen Durchlaeufe in die Tageswerte, dann die Stunden von heute (auch nach einem Neustart)
            java.time.LocalDate heute = de.hustlebot.core.Zeitzone.heute();
            steuer.tage.nachtragen(laufliste, heute);
            List<Diagramm.Wert> stunden = steuer.tage.laeufeJeStunde(heute);
            laeufe.setzen(stunden.isEmpty() ? Statistikdaten.laeufeJeStunde(laufliste) : stunden);
            tageDiagramm.setzen(steuer.tage.laeufeJeTag(heute, 7));
            String plaetzeText = plaetzeDatei != null ? (Files.exists(plaetzeDatei) ? Files.readString(plaetzeDatei, StandardCharsets.UTF_8) : "")
                    : de.hustlebot.core.ArenaPlaetze.lesen();
            arena.setzen(Statistikdaten.top3QuoteJeTag(plaetzeText, 7));
            int[] gesamt = de.hustlebot.core.ArenaPlaetze.gesamt(de.hustlebot.core.ArenaPlaetze.jeTag(plaetzeText));
            nahrung.setzen(Statistikdaten.nahrungAusMeldungen(meldungen.alle(), 12));
            plaetze.setText(de.hustlebot.core.ArenaPlaetze.text(plaetzeText));
            plaetze.setCaretPosition(0);
            tageText.setText(steuer.tage.text(heute, plaetzeText));
            tageText.setCaretPosition(0);
            // F30: der Zeitbericht der Kathedralen-Runs (kathedrale-zeiten.txt), die letzten 10
            zeitText.setText(Zeitverteilung.text(Zeitverteilung.lesen(de.hustlebot.core.Ordner.bei("kathedrale-zeiten.txt"), 10)));
            zeitText.setCaretPosition(0);
            StringBuilder zeile = new StringBuilder();
            if (!laufliste.isEmpty()) zeile.append(laufliste.size()).append(laufliste.size() == 1 ? Sprache.t(" run", " Durchlauf") : Sprache.t(" runs", " Durchläufe")).append(Sprache.t(" in this session", " in dieser Sitzung"));
            if (gesamt[0] > 0) {
                // Autor 22.10.: oben nicht alle Arena-Kaempfe, sondern Turniere -- Top 1-3 = Sieg, Top 4-10 = Niederlage.
                int siege = gesamt[1] + gesamt[2] + gesamt[3];
                if (zeile.length() > 0) zeile.append("   ·   ");
                zeile.append(Sprache.t("Arena total: ", "Arena gesamt: ")).append(gesamt[0])
                        .append(Sprache.t(gesamt[0] == 1 ? " played -- " : " played -- ", gesamt[0] == 1 ? " gespielt -- " : " gespielt -- "))
                        .append(siege).append(Sprache.t(" wins (top 1-3), ", " Siege (Top 1-3), ")).append(gesamt[0] - siege)
                        .append(Sprache.t(" losses (top 4-10) -- ", " Niederlagen (Top 4-10) -- ")).append(Math.round(100.0 * siege / gesamt[0])).append(" %");
            }
            kopfzeile.setText(zeile.length() == 0 ? Sprache.t("Bot numbers and three small charts", "Zahlen des Bots und drei kleine Diagramme") : zeile.toString());
        } catch (Exception e) {
            kopfzeile.setText(Sprache.t("Charts: ", "Diagramme: ") + e.getMessage());
        }
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) {
            if (takt == null) {
                takt = new Timer(30_000, e -> diagrammeNeu());
                takt.start();
            }
            diagrammeNeu();
        } else if (takt != null) {
            takt.stop();
            takt = null;
        }
    }
}
