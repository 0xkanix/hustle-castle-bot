package de.hustlebot.core;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * Steht ein Arena-Name schon in einer Liste (sicher, machbar, meiden, niemals)?
 *
 * Autor, 11.10.: "Ein Name soll nicht zweimal in der gleichen Liste stehen -- das soll der Bot auch schon merken,
 * wenn ich den Namen aufnehmen will, ob er schon im Template-Ordner vorkommt." Vorher standen einzelne Spieler
 * in zwei Listen oder doppelt in derselben.
 *
 * Verglichen wird wie bei der Bilderkennung: das kleinere Bild im groesseren gesucht (Graustufen), ab 0,90
 * Uebereinstimmung ist es derselbe Name -- ein anders gezogener Rand stoert so nicht. Die Hoehen muessen
 * aehnlich sein (Schrift gleich gross), sonst waere ein kurzer Name in einem langen "enthalten".
 */
public final class ArenaVorlagenDoppelt {

    /** Die Arena-Listen; Clan-Bilder und Namensbilder sind keine Listen. */
    public static final String[] LISTEN = {"ar_block_", "ar_ungern_", "ar_sicher_", "ar_machbar_", "ar_fastsicher_", "ar_pause_"};

    private static final double SCHWELLE = 0.90;

    private ArenaVorlagenDoppelt() { }

    /** Ein Treffer: die vorhandene Datei und ihre Liste (Vorsilbe). */
    public record Treffer(Path datei, String liste) { }

    /** Zu welcher Liste gehoert der Dateiname? null, wenn zu keiner. */
    public static String liste(String dateiname) {
        for (String l : LISTEN) if (dateiname.startsWith(l)) return l;
        return null;
    }

    /**
     * Wohin eine aussortierte Vorlage kommt: templates-aussortiert im Bot-Ordner, darunter derselbe Weg wie
     * bisher (z. B. templates-aussortiert/templates/arena/gegner/sicher/ar_sicher_3.png oder
     * templates-aussortiert/gelernt/arena/gegner/machbar/...). Liegt die Datei ausserhalb des Bot-Ordners, nur
     * der Dateiname. Geloescht wird nie etwas.
     */
    public static Path aussortiertZiel(Path botOrdner, Path alt) {
        Path wurzel = botOrdner.toAbsolutePath().normalize();
        Path datei = alt.toAbsolutePath().normalize();
        Path relativ;
        try {
            relativ = wurzel.relativize(datei);
            if (relativ.toString().startsWith("..")) relativ = datei.getFileName();
        } catch (IllegalArgumentException e) {
            relativ = datei.getFileName();
        }
        return wurzel.resolve("templates-aussortiert").resolve(relativ);
    }

    /**
     * Alle Listen-Bilder: templates/arena/gegner, templates/gelernt/arena/gegner (mitgeliefert) und der Ordner
     * gelernt/arena/gegner im Bot-Ordner (Matcher.GELERNT -- dort legt der Bot selbst gelernte Namensbilder ab).
     */
    public static List<Path> listenBilder(Path templates) {
        return listenBilder(templates, Matcher.GELERNT);
    }

    /** Wie oben, mit ausdruecklichem gelernt-Ordner (Aufraeumen beim Start, Tests). */
    static List<Path> listenBilder(Path templates, Path gelernt) {
        List<Path> alle = new ArrayList<>();
        java.util.Set<Path> gesehen = new java.util.HashSet<>();
        // Autor 24.10.: die Listen liegen jetzt sichtbar in "My pictures\Arena lists" (Eigenbilder); die alten Orte
        // werden weiter gelesen, falls dort noch etwas liegt.
        Path botOrdner = templates.toAbsolutePath().normalize().getParent();
        for (Path wurzel : List.of(Eigenbilder.listenOrdner(botOrdner, null),
                templates.resolve("arena").resolve("gegner"),
                templates.resolve("gelernt").resolve("arena").resolve("gegner"),
                gelernt.resolve("arena").resolve("gegner"))) {
            if (!Files.isDirectory(wurzel)) continue;
            if (!gesehen.add(wurzel.toAbsolutePath().normalize())) continue;
            try (Stream<Path> s = Files.walk(wurzel)) {
                s.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                 .filter(p -> liste(p.getFileName().toString()) != null)
                 .forEach(alle::add);
            } catch (Exception e) {
                Log.debug("Vorlagen", "Arena-Listen nicht lesbar: " + e.getMessage());
            }
        }
        return alle;
    }

    /** Das erste vorhandene Listen-Bild mit demselben Namen wie {@code neu} (ausser {@code ausser} selbst). */
    public static Optional<Treffer> finde(BufferedImage neu, Path templates, Path ausser) {
        if (neu == null) return Optional.empty();
        Mat a = grau(neu);
        try {
            for (Path p : listenBilder(templates)) {
                if (ausser != null && p.toAbsolutePath().normalize().equals(ausser.toAbsolutePath().normalize())) continue;
                BufferedImage b;
                try { b = ImageIO.read(p.toFile()); } catch (Exception e) { continue; }
                if (b == null) continue;
                Mat m = grau(b);
                try {
                    if (gleich(a, m)) return Optional.of(new Treffer(p, liste(p.getFileName().toString())));
                } finally {
                    m.release();
                }
            }
        } finally {
            a.release();
        }
        return Optional.empty();
    }

    /** Derselbe Name? Das kleinere Bild im groesseren suchen. */
    static boolean gleich(Mat a, Mat b) {
        double hoehen = (double) Math.max(a.rows(), b.rows()) / Math.max(1, Math.min(a.rows(), b.rows()));
        if (hoehen > 1.3) return false;
        Mat klein = a.rows() * a.cols() <= b.rows() * b.cols() ? a : b, gross = klein == a ? b : a;
        // Darf nicht viel breiter sein als das grosse Bild -- sonst ist es ein anderer (laengerer) Name.
        if (klein.cols() > gross.cols() || klein.rows() > gross.rows()) {
            // gleiche Groessenordnung, aber in einer Richtung groesser: auf das grosse Bild zuschneiden
            int w = Math.min(klein.cols(), gross.cols()), h = Math.min(klein.rows(), gross.rows());
            Mat k2 = klein.submat(0, h, 0, w);
            boolean r = gleichInnen(k2, gross);
            k2.release();
            return r && Math.abs(a.cols() - b.cols()) <= Math.max(a.cols(), b.cols()) / 6;
        }
        // Ein viel kuerzerer Name ("Liw") steckt sonst in einem laengeren -- Breiten muessen passen.
        if (klein.cols() < gross.cols() * 0.75) return false;
        return gleichInnen(klein, gross);
    }

    private static boolean gleichInnen(Mat klein, Mat gross) {
        Mat erg = new Mat();
        try {
            Imgproc.matchTemplate(gross, klein, erg, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(erg).maxVal >= SCHWELLE;
        } finally {
            erg.release();
        }
    }

    private static Mat grau(BufferedImage b) {
        BufferedImage rgb = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(b, 0, 0, null);
        Mat farbe = ScreenCapture.toMat(rgb);
        Mat g = new Mat();
        Imgproc.cvtColor(farbe, g, Imgproc.COLOR_BGR2GRAY);
        farbe.release();
        return g;
    }
    // ---- Nach dem NAMEN (Autor 21.10.: "ein Name soll nicht 3 mal in Doable stehen") ----------------------------
    // Das Bild allein reichte nicht: derselbe Spieler, anders ausgeschnitten oder mit anderem Hintergrund, rutschte
    // durch (bei Autor z. B. Denis zweimal in "sicher", Machtjanix in "machbar" UND "sicher", Loki und loki).

    /** Bot-Aufnahmen tragen hinten "_MMdd-HHmm" -- alles andere hat der Spieler selbst aufgenommen. */
    private static final java.util.regex.Pattern BOT_ENDUNG = java.util.regex.Pattern.compile("_\\d{4}-\\d{4,6}$");
    /** Autor 28.10.: was der Bot selbst eintraegt, heisst "..._Name_auto_MMdd-HHmm" (ArenaListenOrdner). */
    private static final java.util.regex.Pattern AUTO_ENDUNG = java.util.regex.Pattern.compile("_auto_\\d{4}-\\d{4,6}$");

    private static String ohnePng(String dateiname) {
        String n = dateiname == null ? "" : dateiname;
        return n.toLowerCase().endsWith(".png") ? n.substring(0, n.length() - 4) : n;
    }

    /**
     * Der Spielername aus dem Dateinamen: "ar_sicher_Denis_1019-0249.png" -> "Denis",
     * "ar_block_Denis_auto_1028-1430.png" -> "Denis" (ohne "_auto").
     */
    public static String nameAusDatei(String dateiname) {
        String n = ohnePng(dateiname);
        String l = liste(n);
        if (l != null) n = n.substring(l.length());
        java.util.regex.Matcher auto = AUTO_ENDUNG.matcher(n);
        if (auto.find()) return n.substring(0, auto.start());
        return BOT_ENDUNG.matcher(n).replaceFirst("");
    }

    /** Hat der Bot dieses Bild selbst aufgenommen (Endung "_MMdd-HHmm", auch "_auto_MMdd-HHmm")? */
    public static boolean vomBot(String dateiname) {
        String n = ohnePng(dateiname);
        return BOT_ENDUNG.matcher(n).find() || AUTO_ENDUNG.matcher(n).find();
    }

    /** Hinter der Vorsilbe: ein Name, dann "_auto_MMdd-HHmm". */
    private static final java.util.regex.Pattern AUTO_REST = java.util.regex.Pattern.compile("^.+_auto_\\d{4}-\\d{4,6}$");

    /**
     * Endet der Dateiname auf "_auto_MMdd-HHmm" (vom Bot von selbst eingetragen)? Review 28.10.: geprueft wird nur der
     * Teil HINTER der Vorsilbe, und davor muss ein Name stehen -- sonst galt das normale Bot-Bild eines Spielers, der
     * "auto" heisst ("ar_block_auto_1028-1430.png"), als reines Anzeige-Bild und wurde nicht mehr beachtet.
     */
    static boolean autoEndung(String dateiname) {
        String n = ohnePng(dateiname);
        String l = liste(n);
        if (l != null) n = n.substring(l.length());
        return AUTO_REST.matcher(n).matches();
    }

    /**
     * Derselbe Spieler? Gleiche Schreibweise (ohne Gross/Klein und Zeichen), gleiches Skelett (kyrillisch/lateinisch),
     * oder ab 6 Zeichen hoechstens ein Lesefehler ("Andercastle" / "Andercastl"). Strenger als beim Notizblock -- hier
     * geht es um das Aussortieren von Bildern.
     */
    public static boolean derselbeName(String a, String b) {
        String x = NamensVergleich.form(a), y = NamensVergleich.form(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String sx = NamensVergleich.skelett(a), sy = NamensVergleich.skelett(b);
        if (!sx.isEmpty() && sx.equals(sy)) return true;
        return Math.min(x.length(), y.length()) >= 6 && abstand(x, y) <= 1;
    }

    /** Wie viele Zeichen muessen geaendert, eingefuegt oder entfernt werden (Levenshtein)? */
    static int abstand(String a, String b) {
        int[] vorher = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vorher[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int k = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vorher[j] + 1), vorher[j - 1] + k);
            }
            int[] t = vorher; vorher = jetzt; jetzt = t;
        }
        return vorher[b.length()];
    }

    /** Alle Listen-Bilder desselben Spielers (nach dem Namen im Dateinamen). */
    public static List<Treffer> findeNachName(String name, Path templates) {
        List<Treffer> t = new ArrayList<>();
        if (name == null || name.isBlank()) return t;
        for (Path p : listenBilder(templates)) {
            String d = p.getFileName().toString();
            if (derselbeName(nameAusDatei(d), name)) t.add(new Treffer(p, liste(d)));
        }
        return t;
    }

    /** Wohin ein Listen-Bild gehoert: derselbe Ordner wie beim Capture-Programm (templates/arena/gegner/sicher ...). */
    public static Path ordnerFuer(Path templates, String liste) {
        // Autor 24.10.: sichtbar in "My pictures\Arena lists\safe" ... statt templates\arena\gegner\sicher
        return Eigenbilder.listenOrdner(templates.toAbsolutePath().normalize().getParent(), liste);
    }

    /**
     * Beim Start (Autor 21.10.): 1. Listen-Bilder, die der Bot frueher in gelernt/arena/gegner abgelegt hat, ziehen in
     * templates/arena/gegner/(liste) um -- derselbe Ordner wie beim Capture-Programm. 2. Jeder Spieler steht nur
     * noch EINMAL in den Listen: was der Spieler selbst aufgenommen hat, geht vor; sonst gilt die neueste Aufnahme.
     * Doppelte kommen nach templates-aussortiert, geloescht wird nichts.
     * Autor 28.10. (ArenaListenOrdner): vorher entscheidet der ORDNER ueber die Liste, danach werden _auto-Bilder, die
     * seit dem letzten Start fehlen, als "von Autor geloescht" gemerkt.
     * @return {umgezogen, aussortiert}
     */
    public static int[] aufraeumen(Path botOrdner, Path templates) {
        int umgezogen = 0, aussortiert = 0;
        // Autor 28.10.: ar_sicher_X in "never" heisst danach ar_block_X -- der Ordner entscheidet, nicht die Vorsilbe.
        // Nur mit den Arena-Neuerungen (arena-schicht.txt) -- bei den Freunden bleibt alles wie bisher.
        ArenaListenOrdner.Umbenennung ordner = ArenaSchalter.an() ? ArenaListenOrdner.ordnerEntscheidet(botOrdner)
                : new ArenaListenOrdner.Umbenennung(java.util.Set.of(), java.util.Set.of(), 0, 0);
        aussortiert += ordner.aussortiert();
        Path alt = botOrdner.resolve("gelernt").resolve("arena").resolve("gegner");
        if (Files.isDirectory(alt)) {
            List<Path> umziehen = new ArrayList<>();
            try (Stream<Path> s = Files.walk(alt)) {
                s.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                 .filter(p -> liste(p.getFileName().toString()) != null).forEach(umziehen::add);
            } catch (Exception e) {
                Log.debug("Vorlagen", "gelernt/arena/gegner nicht lesbar: " + e.getMessage());
            }
            for (Path p : umziehen) {
                try {
                    Path ziel = ordnerFuer(templates, liste(p.getFileName().toString())).resolve(p.getFileName().toString());
                    Files.createDirectories(ziel.getParent());
                    if (Files.exists(ziel)) {
                        Path weg = aussortiertZiel(botOrdner, p);
                        Files.createDirectories(weg.getParent());
                        Files.move(p, weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        aussortiert++;
                    } else {
                        Files.move(p, ziel);
                        umgezogen++;
                    }
                } catch (Exception e) {
                    Log.debug("Vorlagen", "Listen-Bild nicht umgezogen: " + p.getFileName() + " -- " + e.getMessage());
                }
            }
        }
        // Doppelte nach Namen: eigene Aufnahmen zuerst, dann die neueste.
        // Autor 28.10.: was er gerade in eine andere Liste verschoben hat, ist seine neueste Entscheidung -- geht ganz vor.
        List<Path> alle = new ArrayList<>(listenBilder(templates, botOrdner.resolve("gelernt")));
        alle.sort(java.util.Comparator.<Path>comparingInt(p -> ordner.neu(p) ? 0 : vomBot(p.getFileName().toString()) ? 2 : 1)
                .thenComparing(java.util.Comparator.<Path>comparingLong(ArenaVorlagenDoppelt::geaendert).reversed()));
        List<String> behalten = new ArrayList<>();
        for (Path p : alle) {
            String name = nameAusDatei(p.getFileName().toString());
            boolean doppelt = !name.isBlank() && behalten.stream().anyMatch(b -> derselbeName(b, name));
            if (!doppelt) {
                behalten.add(name);
                continue;
            }
            try {
                Path weg = aussortiertZiel(botOrdner, p);
                Files.createDirectories(weg.getParent());
                Files.move(p, weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                aussortiert++;
                Log.info("Vorlagen", Sprache.t("Arena lists: " + p.getFileName() + " sorted out -- " + name + " is already on a list.",
                        "Arena-Listen: " + p.getFileName() + " aussortiert -- " + name + " steht schon auf einer Liste."));
            } catch (Exception e) {
                Log.debug("Vorlagen", "Doppeltes Listen-Bild nicht aussortiert: " + p.getFileName() + " -- " + e.getMessage());
            }
        }
        if (umgezogen + aussortiert > 0) {
            Log.info("Vorlagen", Sprache.t("Arena lists tidied up: " + umgezogen + " pictures moved to My pictures\\Arena lists, "
                    + aussortiert + " duplicates to templates-aussortiert.",
                    "Arena-Listen aufgeraeumt: " + umgezogen + " Bilder nach My pictures\\Arena lists, "
                    + aussortiert + " doppelte nach templates-aussortiert."));
        }
        // Autor 28.10.: welche _auto-Bilder hat Autor seit dem letzten Start geloescht? -> arena-entfernt.txt
        if (ArenaSchalter.an()) {
            ArenaListenOrdner.geloeschteMerken(botOrdner, listenBilder(templates, botOrdner.resolve("gelernt")), ordner.alteNamen());
        }
        return new int[]{umgezogen, aussortiert};
    }

    private static long geaendert(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
