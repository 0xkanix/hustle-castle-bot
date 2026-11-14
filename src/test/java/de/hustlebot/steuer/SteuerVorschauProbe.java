package de.hustlebot.steuer;

import de.hustlebot.bot.BotEngine;
import de.hustlebot.core.Laufstatistik;
import de.hustlebot.discord.DiscordClient;
import de.hustlebot.tools.LogFenster;
import de.hustlebot.tools.NotizFenster;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Malt die Vorschaubilder des Steuerprogramms (Auftrag 4): je Reiter und je Anzeige ein PNG nach
 * doku/steuerprogramm-bilder/, einmal hell und die wichtigsten auch dunkel. So sieht Autor das Aussehen,
 * ohne den Bot zu starten.
 *
 * Laeuft nur auf Wunsch: mvn test -Dtest=SteuerVorschauProbe -Dsteuer.vorschau=1
 * Braucht einen Bildschirm (auf Autors PC ist einer da; in der Cloud xvfb-run). Die Fenster werden
 * gebaut, aber nicht gezeigt -- gemalt wird in ein Bild. Kein Bot, keine Bruecke: Steuerprogramm.vorschau.
 * Anderer Zielordner: -Dsteuer.vorschau.ordner=...
 */
class SteuerVorschauProbe {

    private static final int TITELLEISTE = 30;

    @Test
    void bilderMalen() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getProperty("steuer.vorschau")), "nur auf Wunsch (-Dsteuer.vorschau=1)");
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm (xvfb-run)");
        Path ziel = Path.of(System.getProperty("steuer.vorschau.ordner", "doku/steuerprogramm-bilder"));
        Files.createDirectories(ziel);

        malen(ziel, false);
        malen(ziel, true);
    }

    private static void malen(Path ziel, boolean dunkel) throws Exception {
        Path ordner = Files.createTempDirectory("steuer-vorschau");
        Files.writeString(ordner.resolve("steuerprogramm.properties"), "aussehen=" + (dunkel ? "dunkel" : "hell") + "\n");
        Files.writeString(ordner.resolve("arena-lernen.txt"), arenaLernen(), StandardCharsets.UTF_8);
        Files.writeString(ordner.resolve("arena-plaetze.txt"), arenaPlaetze(), StandardCharsets.UTF_8);
        Path logs = ordner.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("bot_2026-10-11.log"), protokoll(), StandardCharsets.UTF_8);
        Path notizblock = ordner.resolve("arena-notizblock.txt");
        Files.writeString(notizblock, notizblock(), StandardCharsets.UTF_8);
        String endung = dunkel ? "-dunkel.png" : ".png";

        Steuerprogramm steuer = Steuerprogramm.vorschau(ordner);
        steuer.vorschauZustand(BotEngine.State.LAEUFT, "Kathedrale 12/100 – Etage 3, Kampf 2");
        meldungenFuellen(steuer.meldungen);

        SwingUtilities.invokeAndWait(() -> {
            try {
                steuer.fensterBauen();
                Steuerfenster f = feld(steuer, "steuerfenster");
                f.setSize(765, 800);   // Autors Groesse (Steuerfenster: links oben, 765 x 800)
                vorbereiten(f);
                String[] namen = {"steuerung", "anzeigen", "einstellungen", "tastenkuerzel", "profile", "werkzeuge", "support"};
                for (int i = 0; i < namen.length; i++) {
                    f.reiterWaehlen(i);
                    f.validate();
                    // Meldungen: nach unten rollen wie im Betrieb
                    schreiben(f, ziel.resolve(namen[i] + endung), "Hustle Castle Bot – Steuerung");
                    if (dunkel && i >= 2 && i != 3) break;   // dunkel nur die wichtigsten
                }

                Kartenfenster status = feld(steuer, "statusFenster");
                vorbereiten(status);
                schreiben(status, ziel.resolve("status" + endung), "Status");

                Statistikfenster statistik = feld(steuer, "statistikFenster");
                statistik.vorschau(ordner.resolve("arena-plaetze.txt"), laeufe());
                vorbereiten(statistik);
                schreiben(statistik, ziel.resolve("statistik" + endung), "Statistik");

                Vorratsfenster vorrat = feld(steuer, "vorratFenster");
                vorbereiten(vorrat);
                schreiben(vorrat, ziel.resolve("vorrat" + endung), "Vorrat (Burg-Stand)");

                Minileiste mini = feld(steuer, "minileiste");
                mini.pack();
                mini.addNotify();
                mini.validate();
                BufferedImage b = new BufferedImage(mini.getWidth() + 2, mini.getHeight() + 2, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = b.createGraphics();
                mini.getContentPane().print(g);
                g.dispose();
                ImageIO.write(b, "png", ziel.resolve("minileiste" + endung).toFile());

                LogFenster log = new LogFenster(logs);
                log.setSize(620, 560);
                vorbereiten(log);
                log.nachsehenJetzt();
                log.validate();
                schreiben(log, ziel.resolve("protokoll" + endung), "Protokoll – was gerade passiert");
                log.dispose();

                NotizFenster notiz = new NotizFenster(notizblock, ordner.resolve("arena-liste.txt"));
                notiz.setSize(840, 460);
                vorbereiten(notiz);
                schreiben(notiz, ziel.resolve("notizblock" + endung), "Notizblock Arena");
                notiz.dispose();

                // Auswahlfenster vor "Stigmas los" (11.10. abends)
                schreiben(Stigmafenster.loeschenBauen(f, () -> { }), ziel.resolve("stigmas-loeschen" + endung), "Stigmas löschen – Auswahl");

                for (Window w : Window.getWindows()) w.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void vorbereiten(Window f) {
        f.addNotify();
        f.validate();
    }

    @SuppressWarnings("unchecked")
    private static <T> T feld(Object o, String name) throws Exception {
        var feld = o.getClass().getDeclaredField(name);
        feld.setAccessible(true);
        return (T) feld.get(o);
    }

    /** Malt das Fenster mit einer kleinen Titelleiste in ein PNG. */
    private static void schreiben(Window f, Path datei, String titel) throws Exception {
        JRootPane wurzel = ((RootPaneContainer) f).getRootPane();
        // Rollbalken ans Ende, Rueckfragen sichtbar
        for (JScrollPane sp : alle(wurzel.getContentPane(), JScrollPane.class)) {
            JScrollBar s = sp.getVerticalScrollBar();
            if (sp.getViewport().getView() instanceof JComponent r && Boolean.TRUE.equals(r.getClientProperty("ansEnde"))) s.setValue(s.getMaximum());
        }
        f.validate();
        int w = f.getWidth(), h = f.getHeight();
        BufferedImage b = new BufferedImage(w, h + TITELLEISTE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xE6, 0xE6, 0xE6));
        g.fillRect(0, 0, w, TITELLEISTE);
        g.setColor(new Color(0x33, 0x33, 0x33));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(titel, 12, 20);
        g.drawString("–    ▢    ✕", w - 90, 20);
        g.translate(0, TITELLEISTE);
        wurzel.print(g);
        g.dispose();
        ImageIO.write(b, "png", datei.toFile());
    }

    private static <T extends Component> List<T> alle(Container c, Class<T> art) {
        List<T> l = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (art.isInstance(k)) l.add(art.cast(k));
            if (k instanceof Container k2) l.addAll(alle(k2, art));
        }
        return l;
    }

    // ------------------------------------------------------------------
    //  Beispiel-Daten
    // ------------------------------------------------------------------

    private static void meldungenFuellen(Meldungen m) {
        m.meldung("", "Bot bereit", "Hustle Castle Bot läuft. Steuerprogramm ohne Discord – alle Meldungen landen hier.", DiscordClient.FARBE_START, null);
        m.meldung("", "Zustand", "Der Bot spielt – Kathedrale 12/100\nEtage 3, Kampf 2 · Hustle Castle ist offen\nSeit 01:42 Stunden · Fertig gegen 09:30", DiscordClient.FARBE_INFO, null);
        m.meldung("", "Burg-Stand", "**Name:** Autor\n**Clan:** RUSSIA corp\n**Level:** 62\n**Nahrung:** 1 184 300\n**Holz:** 212 450\n**Eisen:** 98 110\n**Gold:** 4 820\n**Pokale:** 3 410\n**Tasche:** 17", DiscordClient.FARBE_INFO, null);
        m.meldung("", "Statistik", "**Sitzung:** 1:42 h · Kathedrale\nDurchläufe: **12** von 100 · im Schnitt 4:36 je Durchlauf\nDurchläufe pro Stunde: **13,0** · fertig gegen **09:30**\n\n__Schlüssel__\nJetzt: **488** · Am Anfang: 500 · Verbraucht: **12**\n\n__Particle of Light__\nJetzt: **12 300** · Dazugekommen: **+1 850**", DiscordClient.FARBE_INFO, null);
        m.meldung("", "Burg-Stand", "**Nahrung:** 1 236 900\n**Holz:** 214 000\n**Eisen:** 98 900", DiscordClient.FARBE_INFO, null);
        m.meldung("", "✅ Kathedrale fertig", "100 Durchläufe geschafft in 7:41 h. Particle of Light +14 200, Schlüssel verbraucht 100.", DiscordClient.FARBE_ERFOLG, null);
        m.meldung("", "Fehler", "Das Spiel hat sich beendet und ließ sich nicht neu starten. Der Bot wartet und versucht es in 2 Minuten wieder.", DiscordClient.FARBE_FEHLER, null);
        m.meldung("", "Burg-Stand", "**Nahrung:** 1 301 200\n**Holz:** 216 300\n**Eisen:** 99 500", DiscordClient.FARBE_INFO, null);
        m.meldung("", "Meintest du …?", "„!start cath 1000“ – Weg fehlt. Turbo (c) oder alle Wegpunkte (b)?", DiscordClient.FARBE_INFO,
                List.of(new String[]{"Turbo (c)", "!start cathedral 1000 c"}, new String[]{"Alle Wegpunkte (b)", "!start cathedral 1000 b"}));
    }

    private static List<Laufstatistik.RunRecord> laeufe() {
        List<Laufstatistik.RunRecord> l = new ArrayList<>();
        int[] jeStunde = {3, 11, 13, 12, 9, 13, 6};
        int nr = 1;
        for (int h = 0; h < jeStunde.length; h++) {
            for (int i = 0; i < jeStunde[h]; i++) {
                l.add(new Laufstatistik.RunRecord(nr++, 270 + i * 3, 0, 0, "", String.format("%02d:%02d:00", 8 + h, (i * 60 / Math.max(1, jeStunde[h])) % 60)));
            }
        }
        return l;
    }

    /** Platz je Turnier (arena-plaetze.txt), 7 Tage mit je 4-8 Turnieren. */
    private static String arenaPlaetze() {
        StringBuilder sb = new StringBuilder("# Arena: Platz jedes beendeten Turniers\n");
        int[][] plaetze = {{1, 3, 5, 2}, {8, 3, 6, 1, 2}, {2, 2, 4, 1, 7, 3}, {6, 8, 3, 1}, {1, 1, 2, 5, 3, 3, 9}, {4, 2, 3}, {3, 8, 1, 6, 5, 2, 2, 1}};
        for (int t = 0; t < plaetze.length; t++) {
            for (int i = 0; i < plaetze[t].length; i++) {
                sb.append("2026-10-").append(String.format("%02d", 5 + t)).append(" 1").append(i).append(":00\t").append(plaetze[t][i]).append("\n");
            }
        }
        return sb.toString();
    }

    private static String arenaLernen() {
        StringBuilder sb = new StringBuilder("# Jeder Arena-Kampf des Bots: Schaetzung vorher, Ergebnis nachher (Tab-getrennt).\n"
                + "# Zeit\tGegner\tClan\tSeineKraft\tMeineKraft\tPunkte\tMesslatte\tAbstand\tChance%\tTop3\tErgebnis\tDauer_s\tArenaPunkte\tRunde\n");
        String[] tage = {"2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11"};
        int[][] sn = {{14, 4}, {11, 7}, {16, 2}, {12, 6}, {15, 3}, {9, 9}, {13, 5}};
        for (int t = 0; t < tage.length; t++) {
            for (int i = 0; i < sn[t][0] + sn[t][1]; i++) {
                boolean sieg = i < sn[t][0];
                sb.append(tage[t]).append(" 1").append(i % 9).append(":1").append(i % 6).append(":00\tGegner").append(i).append("\tClan\t25000000\t26000000\t70\t68\t2\t80\tnein\t")
                        .append(sieg ? "S" : "N").append("\t17\t150\t1\n");
            }
        }
        return sb.toString();
    }

    private static String protokoll() {
        return """
                Hustle Castle Bot -- Protokoll vom 11.10.2026
                08:02:11.120  [INFO] Start: Bot gestartet, Fassung vom 11.10.
                08:02:14.455  [INFO] Steuerprogramm: Steuerprogramm bereit (ohne Discord).
                08:02:20.001  [ACTION] Kathedrale: Klick auf Participate (412, 688)
                08:02:31.870  [INFO] Kathedrale: Etage 1 -- Weg geplant: 3 Stuetzpunkte, 1 Boss
                08:03:05.310  [ACTION] Kathedrale: Klick auf Deploy best (980, 612)
                08:03:44.902  [INFO] Kathedrale: Kampf gewonnen (39 s)
                08:04:10.555  [DEBUG] Vorlagen: cath_segen_feuer 0.91 bei (640, 300)
                08:04:12.118  [WARN] Kathedrale: Segen-Fenster nicht erkannt -- noch einmal hinsehen.
                08:04:15.700  [ACTION] Kathedrale: Klick auf Segen Feuer (640, 300)
                08:06:48.002  [INFO] Kathedrale: Durchlauf 12 fertig (4:36) -- Particle of Light +148
                08:07:02.340  [ERROR] Spiel: Hustle Castle ist zu -- Neustart in 2 Minuten.
                08:07:05.000  [INFO] Spiel: Hustle Castle wird gestartet ...
                08:07:41.230  [INFO] Spiel: Hustle Castle ist offen.
                08:08:02.780  [ACTION] Kathedrale: Klick auf Participate (412, 688)
                """;
    }

    private static String notizblock() {
        return """
                Arena: 4 Gegner mit vollstaendigen Angaben (2 weitere im Hintergrund: nie gekaempft oder noch nicht alles gelesen)

                -> Druss (4/1)
                   Clan: Iron Hand
                   Kraft: 24,1 Mio
                   Zeit: 19 sek (Durchschnitt aus 5 Kaempfen)
                   Punkte: 66 (mit Kraft 71 -- wegen etwas schwaecher)
                   Arena-Punkte: 150, 150, 225, 150
                   zuletzt: S S N

                -> JLordissok (1/2)
                   Clan: RUSSIA corp
                   Kraft: 27,8 Mio
                   Zeit: 24 sek (Durchschnitt aus 3 Kaempfen)
                   Punkte: 81
                   Arena-Punkte: 150
                   zuletzt: N S N

                -> Rina (6/0)
                   Clan: ohne Clan
                   Kraft: 21,5 Mio
                   Zeit: 15 sek (Durchschnitt aus 6 Kaempfen)
                   Punkte: 58
                   Arena-Punkte: 150, 150, 150, 150, 225
                   zuletzt: S S S

                -> Sharon (2/2)
                   Clan: Sunrise
                   Kraft: 26,0 Mio
                   Zeit: 21 sek (Durchschnitt aus 4 Kaempfen)
                   Punkte: 74
                   Arena-Punkte: 150, 225
                   zuletzt: N S S
                """;
    }
}
