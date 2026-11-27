package de.hustlebot.tools;

import com.sun.jna.platform.win32.WinDef;
import de.hustlebot.core.GameWindow;
import de.hustlebot.core.DxgiFoto;
import de.hustlebot.core.GdiFoto;
import de.hustlebot.core.ScreenCapture;
import org.opencv.core.Mat;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Misst, wie schnell und sparsam ein Foto des Spielfensters je Aufnahme-Art ist (Sparsam 20.11.: in der VirtualBox-VM
 * dauerte ein Bot-Foto im Median 72 ms statt 16 ms auf Autors PC).
 *
 * Aufruf (Bot darf laufen; das Spielfenster sollte sichtbar sein, die Maus wird nicht bewegt):
 * <pre>
 *   runtime\bin\java.exe -cp app\hustle-bot.jar de.hustlebot.tools.FotoMessung ["Hustle Castle"] [--n 50] [--bilder ordner]
 * </pre>
 * Je Art: 3 Fotos zum Aufwaermen, dann n Fotos (Standard 50) samt Umrechnung in das Bild, mit dem der Bot rechnet
 * (OpenCV-Matrix). Ausgabe je Art: Median/Mittel/90-%-Wert in ms, CPU-Zeit dieses Programms je Foto, und wie stark das
 * Bild vom Robot-Bild abweicht (0-255; schwarz/leer faellt so auf). Alles auch in foto-messung.txt im Arbeitsordner.
 *
 * Arten: Robot (so fotografiert der Bot bisher), GDI vom Bildschirm mit/ohne CAPTUREBLT (Schalter foto-gdi.txt nutzt
 * "ohne"), GDI vom Fenster-DC, PrintWindow, DXGI Desktop Duplication (Schalter foto-dxgi.txt; einmal nur der verlangte
 * Bereich, einmal der ganze Monitor kopiert), und ein kleiner Kasten 300x150 (Kastenblick) mit Robot, GDI und DXGI.
 *
 * Frische-Test (21.11.): je Art 2 s so schnell wie moeglich fotografieren und zaehlen, wie viele VERSCHIEDENE Bilder
 * herauskamen (Pruefsumme ueber alle Bildpunkte). Eine eingefrorene Art (wie der Fenster-DC) liefert 1 Bild, obwohl sich
 * das Spiel bewegt. Bei DXGI steht dabei, wie viele neue Bilder Windows in der Zeit gemeldet hat.
 */
public final class FotoMessung {

    private FotoMessung() { }

    private record Ergebnis(String art, double median, double mittel, double p90, double cpuJeFoto, double abweichung,
                            double helligkeit, int fehler) { }

    private record Frische(String art, int fotos, int fehler, int verschieden, int wechsel, double sekunden,
                           long systemNeu, long systemWechsel) { }

    private record Art(String name, Supplier<BufferedImage> foto, DxgiFoto dxgi) { }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale", "1");   // wie der Bot: Bildschirmpunkte = Fensterpunkte
        nu.pattern.OpenCV.loadLocally();
        String titel = "Hustle Castle";
        int n = 50;
        Path bilder = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--n") && i + 1 < args.length) n = Integer.parseInt(args[++i]);
            else if (args[i].equals("--bilder") && i + 1 < args.length) bilder = Path.of(args[++i]);
            else titel = args[i];
        }

        WinDef.HWND fenster = GameWindow.Ext.INSTANCE.FindWindow(null, titel);
        Rectangle innen;
        if (fenster != null) {
            WinDef.RECT r = new WinDef.RECT();
            GameWindow.Ext.INSTANCE.GetClientRect(fenster, r);
            WinDef.POINT p = new WinDef.POINT(0, 0);
            GameWindow.Ext.INSTANCE.ClientToScreen(fenster, p);
            innen = new Rectangle(p.x, p.y, r.right - r.left, r.bottom - r.top);
        } else {
            innen = new Rectangle(0, 0, 1159, 770);
            ausgeben("Kein Fenster \"" + titel + "\" gefunden -- gemessen wird der Bildschirmausschnitt " + innen
                    + " (ohne Fenster-DC/PrintWindow).");
        }
        ausgeben("FotoMessung " + java.time.LocalDateTime.now().withNano(0) + ": Fenster \"" + titel + "\" " + innen
                + ", " + n + " Fotos je Art, " + Runtime.getRuntime().availableProcessors() + " Kerne, Java "
                + System.getProperty("java.version"));

        Robot robot = new Robot();
        robot.setAutoWaitForIdle(false);
        GdiFoto gdi = new GdiFoto();
        Rectangle ganz = new Rectangle(0, 0, innen.width, innen.height);
        Rectangle kasten = new Rectangle(innen.x + innen.width / 2 - 150, innen.y + innen.height / 2 - 75, 300, 150);
        Rectangle kastenImFenster = new Rectangle(kasten.x - innen.x, kasten.y - innen.y, 300, 150);
        final Rectangle innenF = innen;
        final WinDef.HWND fensterF = fenster;

        BufferedImage vergleich = robot.createScreenCapture(innen);
        List<Ergebnis> alle = new ArrayList<>();
        alle.add(messen("Robot (bisher)", n, () -> robot.createScreenCapture(innenF), vergleich, bilder));
        alle.add(messen("GDI Bildschirm mit CAPTUREBLT", n,
                () -> gdi.foto(innenF, GdiFoto.Quelle.BILDSCHIRM_CAPTUREBLT, null), vergleich, bilder));
        alle.add(messen("GDI Bildschirm ohne CAPTUREBLT", n,
                () -> gdi.foto(innenF, GdiFoto.Quelle.BILDSCHIRM, null), vergleich, bilder));
        DxgiFoto dxgi = new DxgiFoto(), dxgiGanz = new DxgiFoto(true);
        // je Programm nur EIN Duplikat je Monitor (ein zweites bekommt E_INVALIDARG): nach jeder Art schliessen
        alle.add(messen("DXGI Duplikat", n, () -> dxgi.foto(innenF), vergleich, bilder));
        dxgi.close();
        alle.add(messen("DXGI Duplikat ganzer Monitor", n, () -> dxgiGanz.foto(innenF), vergleich, bilder));
        dxgiGanz.close();
        if (fenster != null) {
            alle.add(messen("GDI Fenster-DC", n, () -> gdi.foto(ganz, GdiFoto.Quelle.FENSTER_DC, fensterF), vergleich, bilder));
            alle.add(messen("PrintWindow", n, () -> gdi.foto(ganz, GdiFoto.Quelle.PRINTWINDOW, fensterF), vergleich, bilder));
        }
        BufferedImage kastenVergleich = robot.createScreenCapture(kasten);
        alle.add(messen("Kasten 300x150 Robot", n, () -> robot.createScreenCapture(kasten), kastenVergleich, bilder));
        alle.add(messen("Kasten 300x150 GDI ohne CAPTUREBLT", n,
                () -> gdi.foto(kasten, GdiFoto.Quelle.BILDSCHIRM, null), kastenVergleich, bilder));
        DxgiFoto dxgiKasten = new DxgiFoto();
        alle.add(messen("Kasten 300x150 DXGI", n, () -> dxgiKasten.foto(kasten), kastenVergleich, bilder));
        dxgiKasten.close();
        if (fenster != null) {
            alle.add(messen("Kasten 300x150 Fenster-DC", n,
                    () -> gdi.foto(kastenImFenster, GdiFoto.Quelle.FENSTER_DC, fensterF), kastenVergleich, bilder));
        }
        // Frische: 2 s je Art so schnell wie moeglich
        List<Art> arten = new ArrayList<>();
        arten.add(new Art("Robot (bisher)", () -> robot.createScreenCapture(innenF), null));
        arten.add(new Art("GDI Bildschirm mit CAPTUREBLT",
                () -> gdi.foto(innenF, GdiFoto.Quelle.BILDSCHIRM_CAPTUREBLT, null), null));
        arten.add(new Art("GDI Bildschirm ohne CAPTUREBLT", () -> gdi.foto(innenF, GdiFoto.Quelle.BILDSCHIRM, null), null));
        arten.add(new Art("DXGI Duplikat", () -> dxgi.foto(innenF), dxgi));
        arten.add(new Art("DXGI Duplikat ganzer Monitor", () -> dxgiGanz.foto(innenF), dxgiGanz));
        if (fenster != null) {
            arten.add(new Art("GDI Fenster-DC", () -> gdi.foto(ganz, GdiFoto.Quelle.FENSTER_DC, fensterF), null));
            arten.add(new Art("PrintWindow", () -> gdi.foto(ganz, GdiFoto.Quelle.PRINTWINDOW, fensterF), null));
        }
        List<Frische> frisch = new ArrayList<>();
        for (Art a : arten) {
            frisch.add(frische(a, 2000));
            if (a.dxgi() != null) a.dxgi().close();
        }
        // DXGI gegen GDI im selben Augenblick (beide ohne Mauszeiger): kleinste Abweichung aus 5 Paaren, 0 = gleiches Bild
        double dxgiGegenGdi = -1;
        for (int i = 0; i < 5; i++) {
            BufferedImage d = dxgi.foto(innenF), g = gdi.foto(innenF, GdiFoto.Quelle.BILDSCHIRM, null);
            if (d == null || g == null) continue;
            double a = abweichung(d, g)[0];
            if (dxgiGegenGdi < 0 || a < dxgiGegenGdi) dxgiGegenGdi = a;
        }
        dxgi.close();
        gdi.close();

        ausgeben(String.format("%-36s %8s %8s %8s %10s %11s %6s %6s", "Art", "Median", "Mittel", "90%", "CPU/Foto",
                "Abweichung", "Hell", "Fehler"));
        for (Ergebnis e : alle) {
            ausgeben(String.format("%-36s %6.1fms %6.1fms %6.1fms %8.1fms %11.1f %6.0f %6d", e.art(), e.median(), e.mittel(),
                    e.p90(), e.cpuJeFoto(), e.abweichung(), e.helligkeit(), e.fehler()));
        }
        ausgeben("Abweichung = mittlerer Unterschied je Farbwert zum Robot-Bild (das Spiel bewegt sich -- kleine Werte sind"
                + " normal; ueber ~40 oder Hell nahe 0 = falsches/schwarzes Bild). CPU/Foto = Rechenzeit dieses Programms"
                + " (in einer VM zaehlt auch die Grafik-Arbeit des Gastes mit, die steckt bei GDI teils im Fenster-Manager).");
        ausgeben(String.format(Locale.ROOT, "DXGI gegen GDI im selben Augenblick: Abweichung %.2f (kleinste aus 5 Paaren;"
                + " 0 = gleiches Bild, -1 = kein Bildpaar)", dxgiGegenGdi));
        for (DxgiFoto d : List.of(dxgi, dxgiGanz, dxgiKasten)) {
            DxgiFoto.Zaehler z = d.zaehler();
            ausgeben("DXGI-Zaehler: " + z + (d.letzterFehler().isEmpty() ? "" : ", letzter Fehler: " + d.letzterFehler()));
        }

        ausgeben("");
        ausgeben("Frische (je Art 2 s so schnell wie moeglich fotografiert):");
        ausgeben(String.format("%-36s %6s %8s %11s %10s %7s %12s %15s", "Art", "Fotos", "Fotos/s", "verschieden",
                "Wechsel/s", "Fehler", "System neu/s", "Systemwechsel/s"));
        for (Frische f : frisch) {
            String sys = f.systemNeu() < 0 ? "-" : String.format(Locale.ROOT, "%.1f", f.systemNeu() / f.sekunden());
            String sysW = f.systemWechsel() < 0 ? "-" : String.format(Locale.ROOT, "%.1f", f.systemWechsel() / f.sekunden());
            ausgeben(String.format(Locale.ROOT, "%-36s %6d %8.1f %11d %10.1f %7d %12s %15s", f.art(), f.fotos(),
                    f.fotos() / f.sekunden(), f.verschieden(), f.wechsel() / f.sekunden(), f.fehler(), sys, sysW));
        }
        ausgeben("verschieden = wie viele unterschiedliche Bilder herauskamen; Wechsel/s = wie oft sich das Bild gegenueber"
                + " dem vorigen Foto geaendert hat. Bewegt sich das Spiel und eine Art hat 1 verschiedenes Bild, ist sie"
                + " eingefroren. System neu/s = neue Bilder, die DXGI gemeldet und kopiert hat; Systemwechsel/s = alle"
                + " Bildwechsel laut Windows (auch die zwischen zwei Fotos).");
        dxgi.close();
        dxgiGanz.close();
        dxgiKasten.close();
    }

    /** {@code dauerMs} lang so schnell wie moeglich fotografieren und verschiedene Bilder zaehlen. */
    private static Frische frische(Art art, long dauerMs) {
        for (int i = 0; i < 3; i++) art.foto().get();   // aufwaermen (DXGI: Anlegen)
        DxgiFoto.Zaehler vorher = art.dxgi() == null ? null : art.dxgi().zaehler();
        Set<Long> gesehen = new HashSet<>();
        Long letzte = null;
        int fotos = 0, fehler = 0, wechsel = 0;
        long t0 = System.nanoTime(), ende = t0 + dauerMs * 1_000_000;
        long jetzt;
        do {
            BufferedImage b = art.foto().get();
            fotos++;
            if (b == null) {
                fehler++;
            } else {
                long p = pruefsumme(b);
                gesehen.add(p);
                if (letzte != null && p != letzte) wechsel++;
                letzte = p;
            }
            jetzt = System.nanoTime();
        } while (jetzt < ende);
        double s = (jetzt - t0) / 1e9;
        long neu = -1, alleWechsel = -1;
        if (vorher != null) {
            DxgiFoto.Zaehler z = art.dxgi().zaehler();
            neu = z.neueBilder() - vorher.neueBilder();
            alleWechsel = z.bildwechsel() - vorher.bildwechsel();
        }
        return new Frische(art.name(), fotos, fehler, gesehen.size(), wechsel, s, neu, alleWechsel);
    }

    /** Pruefsumme ueber alle Bildpunkte (Hash oben, Laenge und Stichproben unten -- Zufallstreffer praktisch nie). */
    static long pruefsumme(BufferedImage b) {
        DataBuffer d = b.getRaster().getDataBuffer();
        if (d instanceof DataBufferByte db) {
            byte[] a = db.getData();
            return ((long) Arrays.hashCode(a) << 32) ^ (a.length * 31L + a[a.length / 2] * 7L + a[a.length / 3]);
        }
        if (d instanceof DataBufferInt di) {
            int[] a = di.getData();
            return ((long) Arrays.hashCode(a) << 32) ^ (a.length * 31L + a[a.length / 2] * 7L + a[a.length / 3]);
        }
        int w = b.getWidth(), h = b.getHeight();
        return Arrays.hashCode(b.getRGB(0, 0, w, h, null, 0, w));
    }

    private static Ergebnis messen(String art, int n, Supplier<BufferedImage> foto, BufferedImage vergleich, Path bilder) {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        int fehler = 0;
        for (int i = 0; i < 3; i++) {
            BufferedImage b = foto.get();
            if (b != null) ScreenCapture.toMat(b).release();
        }
        double[] ms = new double[n];
        BufferedImage letztes = null;
        long cpu0 = os.getProcessCpuTime();
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            BufferedImage b = foto.get();
            if (b == null) {
                fehler++;
            } else {
                Mat m = ScreenCapture.toMat(b);   // wie der Bot: erst die Matrix ist "fertig zum Suchen"
                m.release();
                letztes = b;
            }
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        long cpu = os.getProcessCpuTime() - cpu0;
        Arrays.sort(ms);
        double mittel = Arrays.stream(ms).average().orElse(0);
        double[] abw = letztes == null ? new double[]{-1, -1} : abweichung(letztes, vergleich);
        if (bilder != null && letztes != null) {
            try {
                Files.createDirectories(bilder);
                ImageIO.write(letztes, "png", bilder.resolve(art.replaceAll("[^A-Za-z0-9]+", "_") + ".png").toFile());
            } catch (Exception e) {
                ausgeben("Bild fuer " + art + " nicht gespeichert: " + e.getMessage());
            }
        }
        return new Ergebnis(art, ms[n / 2], mittel, ms[Math.min(n - 1, (int) Math.ceil(n * 0.9) - 1)],
                cpu / 1e6 / n, abw[0], abw[1], fehler);
    }

    /** {mittlerer Unterschied je Farbwert zum Vergleichsbild, mittlere Helligkeit}. */
    private static double[] abweichung(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return new double[]{-1, -1};
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w), pb = b.getRGB(0, 0, w, h, null, 0, w);
        long summe = 0, hell = 0;
        for (int i = 0; i < pa.length; i++) {
            int x = pa[i], y = pb[i];
            summe += Math.abs(((x >> 16) & 255) - ((y >> 16) & 255)) + Math.abs(((x >> 8) & 255) - ((y >> 8) & 255))
                    + Math.abs((x & 255) - (y & 255));
            hell += ((x >> 16) & 255) + ((x >> 8) & 255) + (x & 255);
        }
        return new double[]{summe / (3.0 * pa.length), hell / (3.0 * pa.length)};
    }

    private static void ausgeben(String zeile) {
        System.out.println(zeile);
        try {
            Files.writeString(Path.of("foto-messung.txt"), zeile + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // nur Komfort -- die Konsole hat alles
        }
    }
}
