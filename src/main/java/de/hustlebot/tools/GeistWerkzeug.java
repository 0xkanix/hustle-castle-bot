package de.hustlebot.tools;

import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Geist;
import de.hustlebot.core.Hintergrund;
import de.hustlebot.core.HintergrundSteuerung;
import de.hustlebot.core.Humanizer;
import de.hustlebot.core.Input;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Live-Werkzeug fuer den Hintergrund-Modus (Autor 20.11.): fuehrt genau den Weg des Bots aus (GameWindow +
 * HintergrundSteuerung + Input), ohne den Bot zu starten. Befehle werden der Reihe nach abgearbeitet:
 *
 * <pre>
 *   an                         Spiel wird Geist (unsichtbar, ausserhalb des Bildschirms)
 *   aus                        Geist aufheben (Spiel normal bei 0,0, nicht "immer oben")
 *   foto DATEI.png             Innenbereich per PrintWindow speichern -- genau das Bild, das der Bot sieht
 *   tipp X Y                   kurzer Tipp (wie tippSchnell, 25 ms) auf Innenpunkt X,Y (Bot-Koordinaten)
 *   klick X Y                  Standard-Klick des Bots (Input.click, mit Streuung)
 *   lang X Y MS                langer Klick (Input.klickLang)
 *   ziehen X1 Y1 X2 Y2 [N]     Zug mit N Schritten (Input.ziehenMit, Standard 12)
 *   text WORT                  WM_CHAR ans Spiel (UNGETESTET)
 *   warte MS
 *   status                     Stil, Lage, Zeiger, Maus belegt
 * </pre>
 *
 * Aufruf (Bot-Ordner C:\hustle-bot-freunde, nach "mvn -o -q compile"):
 * java -cp "target\classes;%USERPROFILE%\.m2\repository\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar;
 * %USERPROFILE%\.m2\repository\net\java\dev\jna\jna-platform\5.14.0\jna-platform-5.14.0.jar"
 * de.hustlebot.tools.GeistWerkzeug an foto vorher.png tipp 988 124 warte 1500 foto nachher.png aus
 */
public final class GeistWerkzeug {

    private GeistWerkzeug() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Befehle: an | aus | foto DATEI | tipp X Y | klick X Y | lang X Y MS | ziehen X1 Y1 X2 Y2 [N] | text WORT | warte MS | status");
            return;
        }
        Hintergrund.festlegen(true);
        GameWindow fenster = new GameWindow("Hustle Castle", 1175, 809);
        if (!fenster.locate()) {
            System.out.println("Kein Spielfenster 'Hustle Castle' gefunden.");
            return;
        }
        HintergrundSteuerung hinten = new HintergrundSteuerung(new Geist.Zugang(fenster::handle));
        Humanizer mensch = new Humanizer();
        mensch.setEnabled(false);
        mensch.setIntensity(0.0);
        Input eingabe = new Input(hinten, mensch);
        try {
            for (int i = 0; i < args.length; i++) {
                String b = args[i].toLowerCase(java.util.Locale.ROOT);
                long von = System.nanoTime();
                switch (b) {
                    case "an" -> fenster.prepare();
                    case "aus" -> Geist.aus(fenster.handle(), "tool", "Werkzeug");
                    case "foto" -> {
                        Rectangle innen = innen(fenster, eingabe);
                        BufferedImage bild = hinten.foto(innen);
                        File ziel = new File(args[++i]);
                        javax.imageio.ImageIO.write(bild, "png", ziel);
                        System.out.println("foto " + ziel.getAbsolutePath() + " " + bild.getWidth() + "x" + bild.getHeight());
                    }
                    case "tipp" -> {
                        innen(fenster, eingabe);
                        eingabe.tippSchnell(zahl(args[++i]), zahl(args[++i]), 10, 25);
                    }
                    case "klick" -> {
                        innen(fenster, eingabe);
                        eingabe.click(zahl(args[++i]), zahl(args[++i]));
                    }
                    case "lang" -> {
                        innen(fenster, eingabe);
                        eingabe.klickLang(zahl(args[++i]), zahl(args[++i]), zahl(args[++i]));
                    }
                    case "ziehen" -> {
                        innen(fenster, eingabe);
                        int x1 = zahl(args[++i]), y1 = zahl(args[++i]), x2 = zahl(args[++i]), y2 = zahl(args[++i]);
                        int n = i + 1 < args.length && args[i + 1].matches("\\d+") ? zahl(args[++i]) : 12;
                        eingabe.ziehenMit(x1, y1, x2, y2, n, 60, 120);
                    }
                    case "text" -> System.out.println("text: " + de.hustlebot.core.Tastatur.tippen(args[++i]));
                    case "warte" -> Thread.sleep(zahl(args[++i]));
                    case "status" -> System.out.println("status: Geist=" + Geist.istGeist(fenster.handle())
                            + ", minimiert=" + Geist.minimiert(fenster.handle()) + ", vorne=" + Geist.istVorne(fenster.handle())
                            + ", Fenster=" + fenster.windowRect() + ", Innen (Bot)=" + fenster.clientArea()
                            + ", Zeiger=" + Geist.zeiger() + ", Maus belegt=" + Geist.mausBelegt());
                    default -> System.out.println("unbekannter Befehl: " + args[i]);
                }
                System.out.printf("%s fertig in %d ms%n", b, (System.nanoTime() - von) / 1_000_000);
            }
        } finally {
            hinten.aufraeumen();
            if (Geist.istGeist(fenster.handle())) {
                System.out.println("Hinweis: das Spiel ist noch unsichtbar -- mit 'aus' zurueckholen.");
            }
        }
    }

    private static Rectangle innen(GameWindow fenster, Input eingabe) {
        Rectangle r = fenster.clientArea();
        eingabe.setClientArea(r);
        return r;
    }

    private static int zahl(String s) {
        return Integer.parseInt(s.trim());
    }
}
