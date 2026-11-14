package de.hustlebot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Das Einrichtungsfenster: Bot-Token, Kanal-ID, Not-Aus-Taste (alles freiwillig).
 *
 * WARUM ES DAS GIBT. Die Freunde sollten config.properties von Hand im
 * Editor ausfuellen. Schon Autor selbst blieb dabei haengen: Windows kennt
 * die Endung .properties nicht und bot zum Oeffnen Word und den Media Player
 * an. Word haette die Datei beim Speichern womoeglich unbrauchbar gemacht.
 *
 * Jetzt fragt der Bot selbst. Das Fenster geht jederzeit ueber Settings.exe auf.
 *
 * NEBEN JEDEM FELD EIN BLAUES "i". Es oeffnet eine Bildanleitung, wie man
 * an genau diesen Wert kommt. Die Schritte stehen unten in SCHRITTE; die
 * Bilder dazu liegen im Programm unter
 *
 *     src/main/resources/hilfe/<thema>-<nummer>.png   (oder .jpg)
 *
 * also z. B. hilfe/token-3.png fuer den dritten Schritt beim Token. Fehlt ein
 * Bild, steht dort nur der Text -- das Fenster funktioniert auch ganz ohne.
 *
 * DIE DATEI BLEIBT LESBAR. Geschrieben werden nur die betroffenen Zeilen;
 * alle Erklaerungen in config.properties bleiben stehen. Properties.store()
 * haette sie geloescht und die Reihenfolge durcheinandergebracht.
 */
public final class Einrichtung {
    // Windows-Skalierung (16.10.): bei 125/150 % rechnete Java in umgerechneten Punkten, die Fensterposition
    // kommt von Windows aber in echten Pixeln -- Fotos und Klicks lagen daneben, der Bot erkannte nichts (Freundin
    // mit grossem Bildschirm: "es steht, dass er laeuft, aber es passiert nichts"). Darum immer echte Pixel.
    // Muss vor dem ersten Grafik-Zugriff laufen -- deshalb als erstes in der Klasse.
    static {
        System.setProperty("sun.java2d.uiScale", "1");
    }


    static final String K_TOKEN      = "discord.botToken";
    static final String K_KANAL      = "discord.channelId";
    static final String K_STATS      = "discord.statsChannelId";
    public static final String K_NOTAUS = "notaus.taste";

    /** Ein Bot-Token hat drei Teile mit zwei Punkten dazwischen. */
    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** Eine Discord-Kanal-ID ist eine lange Zahl. */
    private static final Pattern KANAL = Pattern.compile("\\d{17,20}");

    private static final Color ROT  = new Color(0xB00020);
    private static final Color GRAU = new Color(0x5F6368);
    private static final Color BLAU = new Color(0x1A73E8);

    /** Breite des Fliesstexts im Einrichtungsfenster, in Bildpunkten. */
    private static final int TEXTBREITE = 470;

    /** Breite von Text und Bildern im Hilfefenster. Groessere Bilder werden verkleinert. */
    private static final int HILFEBREITE = 620;

    // ------------------------------------------------------------------
    //  Die Bildanleitungen hinter dem "i"
    // ------------------------------------------------------------------

    /** Ueberschrift des Hilfefensters je Thema. */
    private static final Map<String, String> TITEL = Map.of(
            "token",      "Woher bekomme ich den Discord-Bot-Token?",
            "kanal",      "Woher bekomme ich die Kanal-Nummer?",
            "notaus",     "Was ist die Not-Aus-Taste?");

    /**
     * Die Schritte je Thema. Schritt n bekommt das Bild hilfe/<thema>-<n>.png.
     *
     * Deutsch wie das ganze Programm. Die Knoepfe auf der Discord-Entwicklerseite
     * heissen aber englisch -- die stehen deshalb in Anfuehrungszeichen genau so
     * da, wie man sie auf dem Bildschirm sieht.
     */
    private static final Map<String, String[]> SCHRITTE = Map.of(
            "token", new String[]{
                "Öffne im Browser discord.com/developers/applications und melde dich "
                + "mit deinem Discord-Konto an.",
                "Klick auf \"New Application\", gib einen Namen ein (zum Beispiel "
                + "\"Mein Hustle Bot\"), setz den Haken und klick auf \"Create\".",
                "Klick links auf \"Bot\".",
                "Scroll runter bis \"Privileged Gateway Intents\" und schalte NUR "
                + "MESSAGE CONTENT INTENT ein, die anderen zwei bleiben aus. Dann "
                + "\"Save Changes\". Ohne das kann der Bot deine Befehle nicht lesen. "
                + "In der langen Liste \"Bot Permissions\" darunter brauchst du nichts "
                + "anzukreuzen – die ist nur ein Rechner.",
                "Scroll wieder hoch zu \"Token\", klick auf \"Reset Token\" und "
                + "bestätige mit \"Yes, do it\".",
                "Klick auf \"Copy\" und füge den Token sofort in Feld 1 ein. Discord "
                + "zeigt ihn nur ein einziges Mal.",
                "Jetzt lädst du den Bot auf deinen Server ein: links auf \"OAuth2\", "
                + "unter \"Scopes\" NUR den Haken bei \"bot\" setzen, sonst nichts. In "
                + "der Liste darunter dann View Channels, Send Messages, Embed Links, "
                + "Attach Files, Read Message History und Manage Messages ankreuzen. "
                + "WICHTIG: Ohne \"Embed Links\" kommen die Antworten des Bots leer an.",
                "Öffne ganz unten die \"Generated URL\" im Browser, wähle deinen Server "
                + "aus und klick auf \"Autorisieren\"."
            },
            "kanal", new String[]{
                "Öffne in Discord die Einstellungen: das Zahnrad unten links.",
                "Geh auf \"Erweitert\" und schalte den \"Entwicklermodus\" ein.",
                "Schließ die Einstellungen. Rechtsklick auf deinen Kanal, zum Beispiel "
                + "#bot-ueberwachung, dann auf \"Kanal-ID kopieren\".",
                "Füge sie oben in Feld 2 ein."
            },
            "notaus", new String[]{
                "Die Not-Aus-Taste hält den Bot überall auf dem Rechner sofort an – "
                + "auch dann, wenn Discord gerade nicht antwortet. Praktisch, wenn "
                + "etwas schiefläuft und es schnell gehen muss.",
                "Am besten eine Funktionstaste wie F9, die du beim normalen Tippen "
                + "nicht triffst. Erlaubt sind F1 bis F12 sowie Pause, Ende, Rollen, "
                + "Einfg und Pos1. Die Taste startet nie etwas – sie hält nur an.",
                "Trag die Taste in Feld 3 ein, zum Beispiel F9. Willst du keinen "
                + "Not-Aus, lass das Feld leer. Später änderbar in Discord mit "
                + "!settings notaus F9."
            });

    /** Dieselben Hilfen auf Englisch (seit 2.1, fuer das englische Paket). */
    private static final Map<String, String> TITEL_EN = Map.of(
            "token",      "Where do I get the Discord bot token?",
            "kanal",      "Where do I get the channel number?",
            "notaus",     "What is the emergency key?");

    private static final Map<String, String[]> SCHRITTE_EN = Map.of(
            "token", new String[]{
                "Open discord.com/developers/applications in your browser and log in "
                + "with your Discord account.",
                "Click \"New Application\", enter a name (for example \"My Hustle Bot\"), "
                + "tick the box and click \"Create\".",
                "Click \"Bot\" on the left.",
                "Scroll down to \"Privileged Gateway Intents\" and switch on ONLY "
                + "MESSAGE CONTENT INTENT, leave the other two off. Then \"Save Changes\". "
                + "Without it the bot cannot read your commands. You do not need to tick "
                + "anything in the long \"Bot Permissions\" list below – it is only a calculator.",
                "Scroll back up to \"Token\", click \"Reset Token\" and confirm with "
                + "\"Yes, do it\".",
                "Click \"Copy\" and paste the token into field 1 right away. Discord shows "
                + "it only once.",
                "Now invite the bot to your server: \"OAuth2\" on the left, under \"Scopes\" "
                + "tick ONLY \"bot\". In the list below tick View Channels, Send Messages, "
                + "Embed Links, Attach Files, Read Message History and Manage Messages. "
                + "IMPORTANT: without \"Embed Links\" the bot's answers arrive empty.",
                "Open the \"Generated URL\" at the bottom in your browser, choose your "
                + "server and click \"Authorize\"."
            },
            "kanal", new String[]{
                "Open the Discord settings: the cog at the bottom left.",
                "Go to \"Advanced\" and switch on \"Developer Mode\".",
                "Close the settings. Right-click your channel, for example "
                + "#bot-monitoring, then \"Copy Channel ID\".",
                "Paste it into field 2 above."
            },
            "notaus", new String[]{
                "The emergency key stops the bot anywhere on the PC at once – even when "
                + "Discord does not answer. Handy when something goes wrong and it has "
                + "to be quick.",
                "Best is a function key like F9 that you do not hit while typing. "
                + "Allowed are F1 to F12 and Pause, End, Scroll Lock, Insert and Home. "
                + "The key never starts anything – it only stops.",
                "Enter the key in field 3, for example F9. If you want no emergency key, "
                + "leave the field empty. You can change it later in Discord with "
                + "!settings emergency F9."
            });

    private static String titel(String thema) {
        return (Sprache.istEnglisch() ? TITEL_EN : TITEL)
                .getOrDefault(thema, Sprache.t("Help", "Hilfe"));
    }

    private static String[] schritte(String thema) {
        return (Sprache.istEnglisch() ? SCHRITTE_EN : SCHRITTE).getOrDefault(thema, new String[0]);
    }

    private Einrichtung() {
    }

    // ------------------------------------------------------------------
    //  Von aussen
    // ------------------------------------------------------------------

    /** Settings.exe: Fenster auf, speichern, zu. */
    public static void main(String[] args) throws Exception {
        Sprache.set(Main.spracheAusDatei());
        Path datei = Ordner.bei("config.properties");
        if (zeigen(datei, null)) {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                    Sprache.t("Saved.\n\nStart HustleBot.exe so the new settings apply.",
                              "Gespeichert.\n\nStarte HustleBot.exe, damit die neuen Einstellungen "
                              + "gelten."),
                    "Hustle Castle Bot", JOptionPane.INFORMATION_MESSAGE));
        }
        System.exit(0);
    }

    /**
     * Zeigt das Fenster und wartet, bis es wieder zu ist.
     *
     * @param grund was beim letzten Start schiefging, oder null
     * @return true, wenn gespeichert wurde
     */
    public static boolean zeigen(Path datei, String grund) {
        if (GraphicsEnvironment.isHeadless()) return false;

        AtomicBoolean gespeichert = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                systemAussehen();
                Fenster f = new Fenster(datei, lesen(datei), grund);
                f.oeffnen();                  // modal: kehrt erst zurueck, wenn zu
                gespeichert.set(f.gespeichert);
            });
        } catch (Exception e) {
            // NICHT MEHR STILL VERSCHLUCKEN. Hier stand nur "return false".
            // Stuerzte das Fenster ab, sah der Freund deshalb dieselbe Meldung
            // wie beim Zumachen ("ohne Speichern geschlossen") -- und niemand
            // konnte sehen, was wirklich los war. Genau daran haben wir am
            // 12.09. eine Viertelstunde gesucht.
            Log.error("Einrichtung", Sprache.t("The setup window crashed.", "Das Einrichtungsfenster ist abgestuerzt."), e);
            return false;
        }
        return gespeichert.get();
    }

    // ------------------------------------------------------------------
    //  Pruefen und Schreiben -- ohne Fenster, damit es sich testen laesst
    // ------------------------------------------------------------------

    /**
     * Prueft die Eingaben.
     *
     * Die Meldungen zielen auf die Fehler, die tatsaechlich passieren: Beim
     * Token kopiert man leicht die Anwendungs-ID oder das Client Secret von
     * derselben Discord-Seite. Die ID ist nur eine Zahl, das Secret hat keine
     * Punkte -- ein echter Token hat zwei.
     *
     * @return eine Meldung fuer den Benutzer, oder null wenn alles passt
     */
    static String pruefen(String token, String kanal) {
        // Discord ist FREIWILLIG (Autor 16.10.): der Bot laeuft auch ganz ohne Discord, nur ueber das
        // Steuerfenster. Token/Kanal werden NUR geprueft, wenn etwas drinsteht.
        // Code-Pruefung 16.10.: nur eines von beiden -> Discord blieb still aus, ohne Hinweis.
        if (token.isEmpty() != kanal.isEmpty()) {
            return Sprache.t("For Discord you need BOTH the bot token and the channel number -- or leave both empty "
                    + "(Discord is optional).", "Fuer Discord braucht es Bot-Token UND Kanal-Nummer -- oder beides leer lassen "
                    + "(Discord ist freiwillig).");
        }
        if (!token.isEmpty() && (token.length() < 50 || !TOKEN.matcher(token).matches())) {
            return Sprache.t("That does not look like a bot token. A token is about 70 characters long and has two dots in it. Copy it from the \"Bot\" page – not the Application ID and not the Client Secret. (Discord is optional -- leave field 1 and 2 empty to run without Discord, via the control window.)", "Das sieht nicht nach einem Bot-Token aus. Ein Token ist etwa 70 "
                 + "Zeichen lang und hat zwei Punkte darin. Kopier ihn von der Seite "
                 + "\"Bot\" – nicht die Application ID und nicht das Client Secret. (Discord ist freiwillig -- Feld 1 und 2 leer lassen fuer den Betrieb ohne Discord, ueber das Steuerfenster.)");
        }
        if (!kanal.isEmpty() && !KANAL.matcher(kanal).matches()) {
            return Sprache.t("The channel number is a long number, digits only. Right-click your channel in Discord, then \"Copy Channel ID\". (Discord is optional -- leave it empty to run without Discord.)", "Die Kanal-Nummer ist eine lange Zahl, nur Ziffern. Rechtsklick "
                 + "auf deinen Kanal in Discord, dann \"Kanal-ID kopieren\". (Discord ist freiwillig -- leer lassen fuer den Betrieb ohne Discord.)");
        }
        return null;
    }

    /**
     * Traegt Token und Kanal in config.properties ein.
     *
     * Der Statistik-Kanal bekommt dieselbe ID -- es sei denn, dort stand
     * bewusst eine andere. Die wird nicht ueberschrieben.
     *
     * Geschrieben wird erst in eine Nebendatei, dann umbenannt. So bleibt
     * nie eine halbe config.properties zurueck, wenn mittendrin etwas schief
     * geht.
     */
    static void schreiben(Path datei, String token, String kanal)
            throws IOException {
        List<String> zeilen = Files.exists(datei)
                ? new ArrayList<>(Files.readAllLines(datei, StandardCharsets.ISO_8859_1))
                : new ArrayList<>();

        String alterKanal = zeilenWert(zeilen, K_KANAL);
        String alteStats  = zeilenWert(zeilen, K_STATS);
        String stats = alteStats.isEmpty() || alteStats.equals(alterKanal) ? kanal : alteStats;

        setzen(zeilen, K_TOKEN, token);
        setzen(zeilen, K_KANAL, kanal);
        setzen(zeilen, K_STATS, stats);

        Path neu = datei.resolveSibling(datei.getFileName() + ".neu");
        Files.write(neu, zeilen, StandardCharsets.ISO_8859_1);
        try {
            Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Schreibt einen einzelnen Wert (z.B. die Not-Aus-Taste) in config.properties,
     * ohne die anderen Zeilen und Kommentare anzutasten. Dieselbe zeilen- und
     * atomweise Technik wie {@link #schreiben}.
     */
    public static void einzelnSchreiben(Path datei, String schluessel, String wert)
            throws IOException {
        List<String> zeilen = Files.exists(datei)
                ? new ArrayList<>(Files.readAllLines(datei, StandardCharsets.ISO_8859_1))
                : new ArrayList<>();
        setzen(zeilen, schluessel, wert);
        Path neu = datei.resolveSibling(datei.getFileName() + ".neu");
        Files.write(neu, zeilen, StandardCharsets.ISO_8859_1);
        try {
            Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Properties lesen(Path datei) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(datei)) {
            p.load(in);
        } catch (Exception ignored) {
            // keine Datei -- dann eben leer
        }
        return p;
    }

    static void systemAussehen() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Dann eben das Java-Aussehen. Funktioniert genauso.
        }
    }

    private static String wert(Properties p, String schluessel) {
        return p.getProperty(schluessel, "").trim();
    }

    private static void setzen(List<String> zeilen, String schluessel, String wert) {
        for (int i = 0; i < zeilen.size(); i++) {
            String z = zeilen.get(i).trim();
            if (z.startsWith(schluessel + "=") || z.startsWith(schluessel + " ")
                    || z.equals(schluessel)) {
                zeilen.set(i, schluessel + "=" + wert);
                return;
            }
        }
        zeilen.add(schluessel + "=" + wert);
    }

    private static String zeilenWert(List<String> zeilen, String schluessel) {
        for (String z : zeilen) {
            String t = z.trim();
            if (t.startsWith(schluessel + "=")) {
                return t.substring(schluessel.length() + 1).trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    //  Das Einrichtungsfenster
    // ------------------------------------------------------------------

    /**
     * Das Fenster selbst.
     *
     * Eine eigene Klasse, damit sich der Inhalt auch zeichnen laesst, ohne
     * das Fenster aufzumachen -- so kann man pruefen, wie es aussieht.
     */
    static final class Fenster {

        final JPanel inhalt = new JPanel();
        // Der Token steht wie ein Passwort nur als Punkte da -- ein Bildschirmfoto des Fensters verraet ihn nicht.
        // Einfuegen per Strg+V oder Rechtsklick geht weiter, herauskopieren nicht.
        final JPasswordField token = new JPasswordField(44);
        final JTextField kanal = new JTextField(44);
        final JTextField notaus = new JTextField(44);
        final JLabel meldung = new JLabel(" ");
        final JButton speichern = new JButton(Sprache.t("Save", "Speichern"));
        final JButton abbrechen = new JButton(Sprache.t("Cancel", "Abbrechen"));

        private final Path datei;
        private JDialog dialog;
        boolean gespeichert = false;

        private final Properties p;
        private final String grund;
        /** Sprachwahl oben rechts (3.1, Autor 11.10.): gilt sofort und wird in sprache.txt gemerkt. */
        private JComboBox<String> sprachwahl;

        Fenster(Path datei, Properties p, String grund) {
            this.datei = datei;
            this.p = p;
            this.grund = grund;

            inhalt.setLayout(new BoxLayout(inhalt, BoxLayout.Y_AXIS));
            inhalt.setBorder(new EmptyBorder(18, 22, 16, 22));

            token.setText(wert(p, K_TOKEN));
            token.setEchoChar('●');
            kanal.setText(wert(p, K_KANAL));
            // Not-Aus ist freiwillig. Steht nichts in der Konfig, F9 vorschlagen.
            notaus.setText(wert(p, K_NOTAUS).isEmpty() ? "F9" : wert(p, K_NOTAUS));
            meldung.setForeground(ROT);
            meldung.setFont(meldung.getFont().deriveFont(13f));

            speichern.addActionListener(e -> speichern());
            abbrechen.addActionListener(e -> schliessen());

            aufbauen();
        }

        /** Der Fenstertitel in der eingestellten Sprache. */
        private static String fensterTitel() {
            return Sprache.t("Hustle Castle Bot – Setup", "Hustle Castle Bot – Einrichtung");
        }

        /**
         * Baut den Inhalt in der eingestellten Sprache auf. Wird bei der Sprachwahl noch einmal
         * aufgerufen -- die Eingabefelder bleiben dieselben, eingetippte Werte gehen nicht verloren.
         */
        private void aufbauen() {
            inhalt.removeAll();

            JLabel titel = new JLabel(Sprache.t("Set up the bot", "Bot einrichten"));
            titel.setFont(titel.getFont().deriveFont(Font.BOLD, 20f));
            titel.setAlignmentY(Component.CENTER_ALIGNMENT);

            // Sprachwahl rechts neben dem Titel (Autor 11.10.: Englisch ist Standard, Deutsch umschaltbar).
            sprachwahl = new JComboBox<>(new String[] {"English", "Deutsch"});
            sprachwahl.setSelectedIndex(Sprache.istEnglisch() ? 0 : 1);
            sprachwahl.setMaximumSize(sprachwahl.getPreferredSize());
            sprachwahl.setAlignmentY(Component.CENTER_ALIGNMENT);
            sprachwahl.setToolTipText(Sprache.t("Language of the bot, Discord and all windows. Saved right away.",
                    "Sprache des Bots, in Discord und in allen Fenstern. Wird sofort gespeichert."));
            sprachwahl.addActionListener(e ->
                    spracheWaehlen(sprachwahl.getSelectedIndex() == 0 ? Sprache.Lang.EN : Sprache.Lang.DE));
            JLabel sprachText = new JLabel(Sprache.t("Language: ", "Sprache: "));
            sprachText.setForeground(GRAU);
            sprachText.setAlignmentY(Component.CENTER_ALIGNMENT);

            JPanel kopf = new JPanel();
            kopf.setLayout(new BoxLayout(kopf, BoxLayout.X_AXIS));
            kopf.setOpaque(false);
            kopf.add(titel);
            kopf.add(Box.createHorizontalGlue());
            kopf.add(sprachText);
            kopf.add(sprachwahl);
            links(kopf);
            kopf.setMaximumSize(new Dimension(Integer.MAX_VALUE, kopf.getPreferredSize().height));
            inhalt.add(kopf);
            inhalt.add(Box.createVerticalStrut(6));
            inhalt.add(text(Sprache.t("Everything here is optional – "
                    + "the bot also runs without Discord, with the control window. Don't know where to find something? "
                    + "Click the blue i next to it.", "Alles hier ist freiwillig – der Bot läuft auch ohne Discord, mit dem Steuerfenster. "
                    + "Du weißt nicht, wo du etwas findest? Klick auf das blaue i daneben."),
                    GRAU, 13f, TEXTBREITE));

            if (grund != null && !grund.isBlank()) {
                inhalt.add(Box.createVerticalStrut(12));
                inhalt.add(text(grund, ROT, 13f, TEXTBREITE));
            }

            abschnitt(Sprache.t("1.  Discord bot token  (optional)", "1.  Discord-Bot-Token  (freiwillig)"), "token",
                    Sprache.t("discord.com/developers → your application → Bot → Reset Token → Copy", "discord.com/developers → deine Anwendung → Bot "
                    + "→ Reset Token → Copy"),
                    token, token);
            abschnitt(Sprache.t("2.  Channel number  (optional)", "2.  Kanal-Nummer  (freiwillig)"), "kanal",
                    Sprache.t("In Discord: right-click your channel → Copy Channel ID", "In Discord: Rechtsklick auf deinen Kanal → Kanal-ID kopieren"),
                    kanal, kanal);
            abschnitt(Sprache.t("3.  Emergency key  (optional)", "3.  Not-Aus-Taste  (freiwillig)"), "notaus",
                    Sprache.t("A key that stops the bot everywhere at once – even if Discord is stuck. Best a function key (F1–F12). Leave empty = no emergency key. Change later with !settings emergency F9.", "Eine Taste, die den Bot überall sofort anhält – auch wenn Discord "
                    + "klemmt. Am besten eine Funktionstaste (F1–F12). Leer lassen = kein "
                    + "Not-Aus. Später änderbar mit !settings notaus F9."),
                    notaus, notaus);

            inhalt.add(Box.createVerticalStrut(10));
            links(meldung);
            inhalt.add(meldung);

            inhalt.add(Box.createVerticalStrut(10));
            speichern.setText(Sprache.t("Save", "Speichern"));
            abbrechen.setText(Sprache.t("Cancel", "Abbrechen"));
            JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            knoepfe.add(abbrechen);
            knoepfe.add(speichern);
            links(knoepfe);
            knoepfe.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    knoepfe.getPreferredSize().height));
            inhalt.add(knoepfe);

            inhalt.revalidate();
            inhalt.repaint();
        }

        /**
         * Sprache umstellen (3.1): sofort umschalten, in sprache.txt merken (gilt dann fuer den Bot,
         * Discord und Capture-Images.exe nach dem naechsten Start) und das Fenster neu beschriften.
         */
        private void spracheWaehlen(Sprache.Lang lang) {
            if (lang == null || lang == Sprache.get()) return;
            Sprache.set(lang);
            boolean gemerkt = Sprache.speichern(lang);
            aufbauen();
            meldung.setText(gemerkt ? " " : html(Sprache.t(
                    "The language could not be saved (sprache.txt) – it applies only until the next start.",
                    "Die Sprache konnte nicht gespeichert werden (sprache.txt) – sie gilt nur bis zum nächsten Start."),
                    TEXTBREITE));
            if (dialog != null) {
                dialog.setTitle(fensterTitel());
                dialog.pack();
            }
        }

        void oeffnen() {
            dialog = new JDialog((Frame) null, fensterTitel(), true);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setContentPane(inhalt);
            dialog.getRootPane().setDefaultButton(speichern);
            dialog.getRootPane().registerKeyboardAction(e -> schliessen(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.setResizable(false);
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            // Sonst verschwindet es hinter dem schwarzen Fenster des Bots.
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
        }

        private void speichern() {
            String t = new String(token.getPassword()).replaceAll("\\s+", "");
            String k = kanal.getText().replaceAll("\\s+", "");
            String na = notaus.getText().replaceAll("\\s+", "");

            String fehler = pruefen(t, k);
            if (fehler != null) {
                meldung.setText(html(fehler, TEXTBREITE));
                if (dialog != null) dialog.pack();
                return;
            }
            // Not-Aus ist freiwillig: leer ist erlaubt, sonst muss die Taste passen.
            if (!na.isEmpty() && !de.hustlebot.core.NotAusWache.gueltig(na)) {
                meldung.setText(html(Sprache.t("Unknown emergency key \"", "Die Not-Aus-Taste „") + na + Sprache.t("\". Allowed: ", "\" kenne ich nicht. Erlaubt: ")
                        + de.hustlebot.core.NotAusWache.erlaubteTasten()
                        + Sprache.t(". Or leave the field empty.", ". Oder das Feld leer lassen."), TEXTBREITE));
                if (dialog != null) dialog.pack();
                return;
            }
            try {
                schreiben(datei, t, k);
                einzelnSchreiben(datei, K_NOTAUS, na);
                gespeichert = true;
                schliessen();
            } catch (IOException e) {
                meldung.setText(html(Sprache.t("config.properties could not be saved: ", "config.properties konnte nicht gespeichert werden: ")
                        + e.getMessage(), TEXTBREITE));
                if (dialog != null) dialog.pack();
            }
        }

        private void schliessen() {
            // Festhalten, WIE es zuging: Wer das Fenster zumacht, sieht sonst
            // hinterher nur "ohne Speichern geschlossen" -- ohne zu wissen, ob
            // er selbst abgebrochen hat oder etwas anderes das Fenster
            // weggenommen hat.
            Log.info("Einrichtung", gespeichert
                    ? Sprache.t("Setup window is closed after saving.", "Einrichtungsfenster wird nach dem Speichern geschlossen.")
                    : Sprache.t("Setup window is closed without saving (Cancel, ESC or the X).",
                                "Einrichtungsfenster wird geschlossen, ohne zu speichern (Abbrechen, ESC oder das X)."));
            if (dialog != null) dialog.dispose();
        }

        private void abschnitt(String titel, String thema, String hinweis,
                               JComponent anzeige, JTextComponent feld) {
            inhalt.add(Box.createVerticalStrut(14));

            // Ueberschrift und daneben das "i".
            JLabel t = new JLabel(titel);
            t.setFont(t.getFont().deriveFont(Font.BOLD, 14f));
            t.setAlignmentY(Component.CENTER_ALIGNMENT);

            JButton info = infoKnopf(() -> zeigeHilfe(inhalt, thema));
            info.setAlignmentY(Component.CENTER_ALIGNMENT);

            JPanel zeile = new JPanel();
            zeile.setLayout(new BoxLayout(zeile, BoxLayout.X_AXIS));
            zeile.setOpaque(false);
            zeile.add(t);
            zeile.add(Box.createHorizontalStrut(6));
            zeile.add(info);
            zeile.add(Box.createHorizontalGlue());
            links(zeile);
            zeile.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    zeile.getPreferredSize().height));
            inhalt.add(zeile);

            inhalt.add(Box.createVerticalStrut(2));
            inhalt.add(text(hinweis, GRAU, 12f, TEXTBREITE));
            inhalt.add(Box.createVerticalStrut(5));

            feld.setFont(feld.getFont().deriveFont(13f));
            einfuegenMenue(feld);
            links(anzeige);
            anzeige.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    anzeige.getPreferredSize().height));
            inhalt.add(anzeige);
        }
    }

    // ------------------------------------------------------------------
    //  Das Hilfefenster hinter dem "i"
    // ------------------------------------------------------------------

    /** Oeffnet die Bildanleitung zu einem Thema. */
    static void zeigeHilfe(Component bezug, String thema) {
        Window besitzer = SwingUtilities.getWindowAncestor(bezug);
        JDialog d = new JDialog(besitzer, titel(thema),
                Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JScrollPane rahmen = new JScrollPane(hilfeInhalt(thema),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rahmen.setBorder(null);
        rahmen.getVerticalScrollBar().setUnitIncrement(24);

        JButton zu = new JButton(Sprache.t("Close", "Schließen"));
        zu.addActionListener(e -> d.dispose());
        JPanel unten = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        unten.add(zu);

        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(rahmen, BorderLayout.CENTER);
        d.getContentPane().add(unten, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(zu);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        d.pack();
        // Nie hoeher als der Bildschirm -- lange Anleitungen werden gescrollt.
        Rectangle platz = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int breite = d.getWidth() + rahmen.getVerticalScrollBar().getPreferredSize().width;
        int hoehe = Math.min(d.getHeight(), (int) (platz.height * 0.9));
        d.setSize(breite, hoehe);
        d.setLocationRelativeTo(besitzer);
        // Das Einrichtungsfenster steht immer oben -- dieses muss es auch,
        // sonst oeffnet es sich unsichtbar dahinter.
        d.setAlwaysOnTop(true);
        d.setVisible(true);
    }

    /** Der Inhalt des Hilfefensters: Schritt fuer Schritt, Text und Bild. */
    static JPanel hilfeInhalt(String thema) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(16, 20, 12, 20));

        JLabel titel = new JLabel(titel(thema));
        titel.setFont(titel.getFont().deriveFont(Font.BOLD, 18f));
        links(titel);
        p.add(titel);

        String[] schritte = schritte(thema);
        for (int i = 0; i < schritte.length; i++) {
            p.add(Box.createVerticalStrut(16));

            JLabel nr = new JLabel(Sprache.t("Step ", "Schritt ") + (i + 1));
            nr.setFont(nr.getFont().deriveFont(Font.BOLD, 13f));
            nr.setForeground(BLAU);
            links(nr);
            p.add(nr);

            p.add(Box.createVerticalStrut(3));
            p.add(text(schritte[i], Color.DARK_GRAY, 13f, HILFEBREITE));

            ImageIcon bild = bild(thema, i + 1);
            if (bild != null) {
                p.add(Box.createVerticalStrut(8));
                JLabel b = new JLabel(bild);
                b.setBorder(new LineBorder(new Color(0xDADCE0)));
                links(b);
                p.add(b);
            }
        }
        return p;
    }

    /**
     * Laedt hilfe/<thema>-<nr>.png (oder .jpg) aus dem Programm.
     *
     * Bildschirmfotos sind oft 1920 Punkte breit; so gross passt kein Fenster.
     * Breitere Bilder werden deshalb auf HILFEBREITE verkleinert -- mit
     * SCALE_SMOOTH, das beim Verkleinern die Schrift lesbar laesst.
     */
    static ImageIcon bild(String thema, int nr) {
        for (String endung : new String[]{".png", ".jpg", ".jpeg"}) {
            try (InputStream in = Einrichtung.class.getResourceAsStream(
                    "/hilfe/" + thema + "-" + nr + endung)) {
                if (in == null) continue;
                BufferedImage b = ImageIO.read(in);
                if (b == null) continue;
                if (b.getWidth() <= HILFEBREITE) return new ImageIcon(b);

                int h = b.getHeight() * HILFEBREITE / b.getWidth();
                return new ImageIcon(b.getScaledInstance(HILFEBREITE, h, Image.SCALE_SMOOTH));
            } catch (Exception ignored) {
                // naechste Endung versuchen
            }
        }
        return null;
    }

    /** Ein rundes blaues Knoepfchen mit einem "i". */
    private static JButton infoKnopf(Runnable aktion) {
        JButton b = new JButton(new InfoZeichen());
        b.setToolTipText(Sprache.t("Where do I get this? Step by step, with pictures.", "Woher bekomme ich das? Schritt für Schritt, mit Bildern."));
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> aktion.run());
        return b;
    }

    /** Das "i" selbst. Gezeichnet statt als Bild, damit es auf jedem Bildschirm scharf bleibt. */
    private static final class InfoZeichen implements Icon {
        private static final int D = 18;

        @Override public int getIconWidth()  { return D; }
        @Override public int getIconHeight() { return D; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(BLAU);
            g2.fillOval(x, y, D - 1, D - 1);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SERIF, Font.BOLD | Font.ITALIC, 14));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (D - 1 - fm.stringWidth("i")) / 2;
            int ty = y + (D - 1 - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString("i", tx, ty);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------
    //  Kleinkram
    // ------------------------------------------------------------------

    /**
     * Rechtsklick -> Paste.
     *
     * Java-Eingabefelder haben von Haus aus kein Rechtsklick-Menue. Wer nicht
     * weiss, dass Strg+V geht, stuende vor einem Feld, in das er nichts
     * hineinbekommt. Einfuegen ERSETZT den Inhalt -- beim zweiten Versuch soll
     * nicht der alte Schluessel davor kleben bleiben.
     */
    private static void einfuegenMenue(JTextComponent feld) {
        JPopupMenu menue = new JPopupMenu();

        JMenuItem einfuegen = new JMenuItem(Sprache.t("Paste", "Einfügen"));
        einfuegen.addActionListener(e -> {
            feld.selectAll();
            feld.paste();
        });
        JMenuItem leeren = new JMenuItem(Sprache.t("Clear", "Leeren"));
        leeren.addActionListener(e -> feld.setText(""));

        menue.add(einfuegen);
        menue.add(leeren);
        feld.setComponentPopupMenu(menue);
    }

    private static JLabel text(String text, Color farbe, float groesse, int breite) {
        JLabel l = new JLabel(html(text, breite));
        l.setForeground(farbe);
        l.setFont(l.getFont().deriveFont(groesse));
        links(l);
        return l;
    }

    private static String html(String text, int breite) {
        String sauber = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:" + breite + "px'>" + sauber + "</div></html>";
    }

    private static void links(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
    }
}
