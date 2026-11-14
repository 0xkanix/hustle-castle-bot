package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Texterkennung;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Liest Zahlen und Namen aus dem Arena-Bild.
 *
 * ------------------------------------------------------------------
 *  Warum hier keine Ziffernvorlagen benutzt werden
 * ------------------------------------------------------------------
 *
 * Verlies und Kathedrale lesen Zahlen ueber Bildvorlagen -- fuer jede
 * Ziffer eine. Das geht dort gut, weil alle Zahlen gleich aussehen.
 *
 * In der Arena stehen die Zahlen in mindestens drei Aufmachungen: die
 * Tickets dunkel auf grau, die Abzeichen hell auf grau, die Turnierpunkte
 * weiss auf farbigen Schildern. Das waeren drei Saetze zu je zehn
 * Ziffern, also dreissig Ausschnitte von Hand -- fuer Zahlen, die sich
 * ohnehin jede Runde aendern.
 *
 * Stattdessen liest die Texterkennung von Windows, die fuer den Clan-Chat
 * schon da ist. Sie kostet etwa eine Sekunde pro Aufruf. Das klingt viel,
 * ist es aber nicht: pro Runde hat der Bot zwei Minuten, und Autor sagt,
 * das Anklicken und Angreifen dauert nur Sekunden. Zeit zum Lesen ist
 * reichlich.
 *
 * ------------------------------------------------------------------
 *  Was die Texterkennung NICHT kann
 * ------------------------------------------------------------------
 *
 * Namen wie "ЈӇӁЄD ᴍυRЄΝA" bestehen aus zusammengesuchten Zierzeichen und
 * sind keine echte Schrift. Die liest sie nicht, auch nicht mit
 * Sprachpaket. Deshalb bleibt die Sperrliste aus Bildvorlagen bestehen --
 * beides zusammen, nicht das eine statt des anderen.
 */
final class ArenaLeser {

    private ArenaLeser() {
    }

    /**
     * Schneidet einen Bereich aus dem Bild und liest ihn vor.
     *
     * Alle Werte in Bildpunkten. Was ausserhalb liegt, wird zurechtgestutzt
     * -- ein Ausschnitt am Bildrand soll nichts zum Absturz bringen.
     */
    static List<String> lies(BufferedImage bild, int x, int y, int breite, int hoehe) {
        if (bild == null) return List.of();

        int x1 = Math.max(0, x);
        int y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(),  x + breite);
        int y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return List.of();

        try {
            BufferedImage teil = bild.getSubimage(x1, y1, x2 - x1, y2 - y1);
            return gemerktLesen(teil, "", () -> Texterkennung.lies(teil));   // A29: gleiche Bildpunkte = gleiche Lesung
        } catch (Exception e) {
            Log.debug("Arena", "Ausschnitt liess sich nicht lesen: " + e.getMessage());
            return List.of();
        }
    }

    /** Wie {@link #lies}, aber alles zu einer Zeile zusammengezogen. */
    static String liesText(BufferedImage bild, int x, int y, int breite, int hoehe) {
        return String.join(" ", lies(bild, x, y, breite, hoehe)).trim();
    }

    /**
     * Eine kurze WEISSE Zahl (eine Ziffer, "7s", "3") lesen. Die Windows-Erkennung liest einzelne
     * Ziffern fuer sich gar nicht -- mit einem Wort davor schon (01.10.: "7s" unter "Battle time"
     * war nie lesbar, als "Dauer 7s" sofort). Die Zahl wird eng ausgeschnitten, schwarz auf weiss
     * gemacht und auf die Grundlinie hinter das gezeichnete Wort gesetzt.
     */
    static OptionalLong zahlHinterWort(BufferedImage bild, int x0, int y0, int breite, int hoehe, String wort) {
        String t = hinterWort(bild, x0, y0, breite, hoehe, wort, "");
        if (t == null) return OptionalLong.empty();
        // Die schmale "1" kommt oft als "i", "l" oder "|" ("i28" fuer 128, "13 i" fuer 131, 02.10.):
        // zwischen bzw. neben Ziffern als 1 werten, Leerzeichen zwischen Ziffern weg.
        String z = t.strip();
        if (z.matches("[0-9iIl|!\\s]+") && z.chars().anyMatch(Character::isDigit)) {
            z = z.replaceAll("[iIl|!]", "1").replaceAll("\\s+", "");
            return zahlAus(z);
        }
        return zahlAus(t);
    }

    /** Wie {@link #zahlHinterWort}, aber Text (ein kurzer Name) -- auch kyrillisch. */
    static String textHinterWort(BufferedImage bild, int x0, int y0, int breite, int hoehe, String wort) {
        String t = hinterWort(bild, x0, y0, breite, hoehe, wort, "");
        if (t != null && Texterkennung.siehtKyrillischAus(t) && Texterkennung.kannSprache("ru")) {
            String ru = hinterWort(bild, x0, y0, breite, hoehe, wort, "ru");
            if (ru != null && !ru.isBlank()) t = ru;
        }
        return t == null ? "" : t.strip();
    }

    /** Liest das zusammengesetzte Bild und gibt den Text NACH dem Vorsatzwort zurueck (null = nichts). */
    private static String hinterWort(BufferedImage bild, int x0, int y0, int breite, int hoehe, String wort, String sprache) {
        if (bild == null) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        int x1 = Math.max(0, x0), y1 = Math.max(0, y0);
        int x2 = Math.min(bild.getWidth(), x0 + breite), y2 = Math.min(bild.getHeight(), y0 + hoehe);
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int p = bild.getRGB(x, y);
                // > 235: die Schrift ist reinweiss, der Grund der Schilder hellgrau (200-215, 01.10.)
                if (Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 235) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return null;
        int zw = maxX - minX + 1, zh = maxY - minY + 1;
        if (zh < 4 || zw < 2) return null;
        // Autor 24.10. (Ideenliste A29): gleiche weisse Punkte = gleiche Lesung. Aus dem Bild geht nur diese Punktmaske
        // in die Texterkennung ein; wurde dieselbe Maske (mit demselben Vorsatzwort und derselben Sprache) schon gelesen,
        // gilt die alte Lesung -- ohne das Bild neu zusammenzusetzen und ohne Texterkennung. Die Pyramide wird alle paar
        // Sekunden neu gelesen, die "+225"/"+150" der neun Schilder stehen dabei unveraendert da.
        long gemerktSchluessel = 1469598103934665603L;
        gemerktSchluessel = (gemerktSchluessel ^ wort.hashCode()) * 1099511628211L;
        gemerktSchluessel = (gemerktSchluessel ^ sprache.hashCode()) * 1099511628211L;
        gemerktSchluessel = (gemerktSchluessel ^ zw) * 1099511628211L;
        gemerktSchluessel = (gemerktSchluessel ^ zh) * 1099511628211L;
        for (int y = 0; y < zh; y++) {
            for (int x = 0; x < zw; x++) {
                int p = bild.getRGB(minX + x, minY + y);
                gemerktSchluessel = (gemerktSchluessel ^ (Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 235 ? 1 : 0))
                        * 1099511628211L;
            }
        }
        String gemerkt = HINTERWORT_GEMERKT.get(gemerktSchluessel);
        if (gemerkt != null) return gemerkt;
        int ziel = 60;
        int sw = Math.max(1, (int) Math.round(zw * ziel / (double) zh));
        java.awt.Font font = new java.awt.Font("Arial", java.awt.Font.BOLD, 80);
        java.awt.FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(font);
        int wortB = fm.stringWidth(wort), luecke = 28, grund = 100;
        BufferedImage aus = new BufferedImage(20 + wortB + luecke + sw + 30, 140, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = aus.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, aus.getWidth(), aus.getHeight());
        g.setColor(java.awt.Color.BLACK);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.drawString(wort, 20, grund);
        BufferedImage zahl = new BufferedImage(zw, zh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < zh; y++) {
            for (int x = 0; x < zw; x++) {
                int p = bild.getRGB(minX + x, minY + y);
                zahl.setRGB(x, y, Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 235 ? 0x000000 : 0xFFFFFF);
            }
        }
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(zahl, 20 + wortB + luecke, grund - ziel, sw, ziel, null);
        g.dispose();
        String text;
        try {
            text = String.join(" ", Texterkennung.lies(aus, sprache)).strip();
        } catch (Exception e) {
            return null;
        }
        // Das erste Wort ist das gezeichnete -- alles danach ist der gesuchte Text.
        int leer = text.indexOf(' ');
        String ergebnis = leer < 0 ? null : text.substring(leer + 1).strip();
        // Nur Gelesenes merken: "nichts gelesen" kann an einem ueberlasteten Rechner liegen und soll es beim naechsten
        // Mal noch einmal versuchen duerfen.
        if (ergebnis != null && !ergebnis.isBlank()) {
            if (HINTERWORT_GEMERKT.size() > 400) HINTERWORT_GEMERKT.clear();
            HINTERWORT_GEMERKT.put(gemerktSchluessel, ergebnis);
        }
        return ergebnis;
    }

    /**
     * Wie {@link #liesText}, aber der Ausschnitt wird vorher dreifach vergroessert.
     * Kleine Schrift ("Players found: 2/10" in der Warteschlange) liest die
     * Texterkennung sonst gar nicht -- vergroessert stand die Zahl sofort da (01.10.).
     */
    static String liesTextGross(BufferedImage bild, int x, int y, int breite, int hoehe) {
        if (bild == null) return "";
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return "";
        int f = 3;
        BufferedImage gross = new BufferedImage((x2 - x1) * f, (y2 - y1) * f, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bild.getSubimage(x1, y1, x2 - x1, y2 - y1), 0, 0, gross.getWidth(), gross.getHeight(), null);
        g.dispose();
        try {
            return String.join(" ", gemerktLesen(gross, "", () -> Texterkennung.lies(gross))).trim();
        } catch (Exception e) {
            Log.debug("Arena", "Vergroesserter Ausschnitt liess sich nicht lesen: " + e.getMessage());
            return "";
        }
    }

    /**
     * Englischen Text (Set-Namen) lesen: doppelt vergroessert und mit dem ENGLISCHEN Modell.
     * Das deutsche Modell (Windows-Standard bei Autor) verdreht die Set-Namen gern
     * ("vøø•rmor øreaxer Mail", "uetenaer ot Nature•s Outfit", 11.10.). Leer = nichts gelesen.
     */
    static String liesTextEnglisch(BufferedImage bild, int x, int y, int breite, int hoehe) {
        if (bild == null) return "";
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return "";
        int f = 2;
        BufferedImage gross = new BufferedImage((x2 - x1) * f, (y2 - y1) * f, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bild.getSubimage(x1, y1, x2 - x1, y2 - y1), 0, 0, gross.getWidth(), gross.getHeight(), null);
        g.dispose();
        try {
            String sprache = Texterkennung.kannSprache("en-US") ? "en-US" : Texterkennung.kannSprache("en") ? "en" : "";
            return String.join(" ", gemerktLesen(gross, sprache, () -> Texterkennung.lies(gross, sprache))).trim();
        } catch (Exception e) {
            Log.debug("Arena", "Englisch nicht lesbar: " + e.getMessage());
            return "";
        }
    }

    /**
     * Liest einen NAMEN -- und sieht kyrillisch nach, wenn nichts kam.
     *
     * Nur fuer Namen, nicht fuer Zahlen. Zahlen liest das lateinische
     * Modell zuverlaessig; ein zweiter Versuch waere dort verschenkte Zeit.
     * Bei Namen ist es umgekehrt: kommt nichts heraus, ist der Gegner heute
     * ein Totalausfall -- er landet unter keinem Namen im Notizblock und
     * ist beim naechsten Turnier wieder voellig unbekannt. Da kann ein
     * zweiter Versuch nur gewinnen.
     */
    static String liesName(BufferedImage bild, int x, int y, int breite, int hoehe) {
        return String.join(" ", liesNamenZeilen(bild, x, y, breite, hoehe)).trim();
    }

    /**
     * Schon gelesene Namensbilder (gleiche Bildpunkte -> gleiche Lesung). Die Pyramide wird alle paar Sekunden
     * neu gelesen; seit 11.10. fragt der Bot bei unsauberen Namen bis zu 20 weitere Sprachen -- das soll nur
     * einmal je Bild Zeit kosten.
     */
    private static final java.util.Map<Long, List<String>> NAMEN_GEMERKT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Autor 24.10. (Ideenliste A29): schon gelesene Zahlen/Texte hinter einem Vorsatzwort, Schluessel = die weissen Punkte. */
    private static final java.util.Map<Long, String> HINTERWORT_GEMERKT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Dasselbe fuer Bilder, die unveraendert in die Texterkennung gehen (Schluessel = alle Bildpunkte + Sprache). */
    private static final java.util.Map<Long, List<String>> LESUNG_GEMERKT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Autor 24.10. (Ideenliste A29): gleiche Bildpunkte = gleiche Lesung. Die Pyramide wird alle paar Sekunden neu
     * gelesen, aber nur der NAME war zwischengespeichert -- Punkte ("+225"), Pokale und Zeiten wurden jedes Mal neu
     * an die Texterkennung geschickt, obwohl die Schilder unveraendert dastanden. Hier gilt der Schluessel aus ALLEN
     * Bildpunkten (exakt, nicht gerundet) plus der Sprache; gemerkt wird nur ein Ergebnis mit Text -- "nichts gelesen"
     * (etwa auf einem ueberlasteten Rechner) wird beim naechsten Mal wieder versucht.
     */
    static List<String> gemerktLesen(BufferedImage bild, String sprache, java.util.function.Supplier<List<String>> leser) {
        long schluessel = 1469598103934665603L;
        schluessel = (schluessel ^ (sprache == null ? 0 : sprache.hashCode())) * 1099511628211L;
        schluessel = (schluessel ^ bild.getWidth()) * 1099511628211L;
        schluessel = (schluessel ^ bild.getHeight()) * 1099511628211L;
        int[] punkte = bild.getRGB(0, 0, bild.getWidth(), bild.getHeight(), null, 0, bild.getWidth());
        for (int p : punkte) schluessel = (schluessel ^ p) * 1099511628211L;
        List<String> gemerkt = LESUNG_GEMERKT.get(schluessel);
        if (gemerkt != null) return new ArrayList<>(gemerkt);   // eine Kopie: der Aufrufer darf sie veraendern
        List<String> r = leser.get();
        if (r != null && r.stream().anyMatch(z -> z != null && !z.isBlank())) {
            if (LESUNG_GEMERKT.size() > 400) LESUNG_GEMERKT.clear();
            LESUNG_GEMERKT.put(schluessel, List.copyOf(r));
        }
        return r;
    }

    private static long bildSchluessel(BufferedImage b) {
        long h = 1125899906842597L;
        h = 31 * h + b.getWidth();
        h = 31 * h + b.getHeight();
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) h = 31 * h + (b.getRGB(x, y) & 0xF0F0F0);
        }
        return h;
    }

    private static BufferedImage vergroessert(BufferedImage teil, int f) {
        BufferedImage gross = new BufferedImage(teil.getWidth() * f, teil.getHeight() * f, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(teil, 0, 0, gross.getWidth(), gross.getHeight(), null);
        g.dispose();
        return gross;
    }

    /**
     * Autor 25.10.: Schmuckschrift sammeln -- jedes Namensschild, bei dem PaddleOCR in einer Zeile unsicher ist (unter
     * 0,75), einmal als Bild nach logs\schmuckschrift\ (mit Lesung in liste.tsv). Daraus wird die Seite, auf der Autor den
     * Namen eintippt, und spaeter das Training fuer PaddleOCR. Hoechstens 600 Bilder.
     */
    private static void unsicherSammeln(BufferedImage teil, long schluessel) {
        if (!de.hustlebot.core.Beleg.autorsSammlung()) return;   // 01.11.: das Training macht nur Autor, bei den Freunden las das niemand
        try {
            if (!de.hustlebot.core.PaddleLeser.an() || !de.hustlebot.core.PaddleLeser.verfuegbar()) return;
            java.nio.file.Path ordner = de.hustlebot.core.Ordner.bei("logs").resolve("schmuckschrift");
            String name = Long.toHexString(schluessel) + ".png";
            if (java.nio.file.Files.exists(ordner.resolve(name))) return;
            List<de.hustlebot.core.PaddleLeser.Lesung> l = de.hustlebot.core.PaddleLeser.lesungen(vergroessert(teil, 2), "");
            if (l.isEmpty() || l.stream().allMatch(z -> z.sicher() >= 0.75)) return;
            java.nio.file.Files.createDirectories(ordner);
            try (var s = java.nio.file.Files.list(ordner)) {
                if (s.count() > 600) return;
            }
            javax.imageio.ImageIO.write(teil, "png", ordner.resolve(name).toFile());
            StringBuilder z = new StringBuilder(name);
            for (var x : l) z.append('\t').append(x.text().replace('\t', ' ')).append('\t').append(String.format(java.util.Locale.ROOT, "%.2f", x.sicher()));
            java.nio.file.Files.writeString(ordner.resolve("liste.tsv"), z.append('\n').toString(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug("Arena", "Schmuckschrift nicht gesammelt: " + e.getMessage());
        }
    }

    /**
     * Autor 01.11. (Pruefung 3.137, Abweichung 11 und 13): der Name einer Einheit steht gross und dunkel auf Weiss oben im Einheitenfenster ("Bogen 1", "КАЛАНТЭ").
     * Die Lesung ueber den ganzen Streifen in Originalgroesse verlor Zeichen: "Bogen 1" -> "c Bogen", "Magier 3" -> "Magier", "КАЛАНТЭ" -> "АЛАНТЭ", "ЦИРИЛЛА" -> "ИРИЛЛА",
     * "ГЕРАЛЬТ" -> "ЕРАЛЬТ", "КОТЯ" -> "cY". Hier wird erst die dunkle Schrift mit einem Rand von 3 Punkten umschnitten (nichts vom Herz, von der Pille oder vom
     * Fensterrand) und dann wie ein Spielername gelesen (PaddleOCR doppelt gross, sonst die Windows-Lesung doppelt gross). Leer, wenn keine Schrift da ist.
     */
    static String einheitNameLesen(BufferedImage bild, int x, int y, int breite, int hoehe) {
        if (bild == null) return "";
        int x1 = Math.min(bild.getWidth(), x + breite), y1 = Math.min(bild.getHeight(), y + hoehe);
        x = Math.max(0, x);
        y = Math.max(0, y);
        if (x1 - x < 12 || y1 - y < 8) return "";
        // Zeile fuer Zeile die dunklen Punkte zaehlen; die Schrift ist das ERSTE zusammenhaengende Zeilenband von oben -- darunter beginnt der Rand der Pille.
        int[] je = new int[y1 - y];
        for (int yy = y; yy < y1; yy++) {
            for (int xx = x; xx < x1; xx++) if (dunkleSchrift(bild.getRGB(xx, yy))) je[yy - y]++;
        }
        int von = -1, bis = -1, leer = 0;
        for (int r = 0; r < je.length; r++) {
            if (je[r] >= 2) {
                if (von < 0) von = r;
                bis = r;
                leer = 0;
            } else if (von >= 0 && ++leer >= 3) {
                break;
            }
        }
        if (von < 0) return "";
        int minX = Integer.MAX_VALUE, maxX = -1, n = 0;
        for (int yy = y + von; yy <= y + bis; yy++) {
            for (int xx = x; xx < x1; xx++) {
                if (dunkleSchrift(bild.getRGB(xx, yy))) {
                    n++;
                    minX = Math.min(minX, xx);
                    maxX = Math.max(maxX, xx);
                }
            }
        }
        int minY = y + von, maxY = y + bis;
        if (n < 25 || maxX - minX < 12 || maxY - minY < 8 || maxY - minY > 34) return "";
        // Die vier Lesarten (siehe waehleName): jede hat ihre Schwaechen -- Paddle verliert bei weissem Rand manchmal den letzten Buchstaben ("КАЛАНТ"), Windows liest
        // kyrillische Namen manchmal lateinisch ("Hectop" fuer "Нестор"), der Ausschnitt reagiert auf einen Bildpunkt Breite ("Bogen 1" -> "1"). Zusammen stimmen sie.
        java.util.List<String> kandidaten = new java.util.ArrayList<>();
        int ex = Math.max(0, minX - 3), ey = Math.max(0, minY - 3);
        int eb = Math.min(bild.getWidth() - ex, maxX - minX + 1 + 6), eh = Math.min(bild.getHeight() - ey, maxY - minY + 1 + 6);
        java.awt.Rectangle eng = new java.awt.Rectangle(ex, ey, eb, eh);
        List<String> w1 = liesNamenZeilen(bild, eng.x, eng.y, eng.width, eng.height);
        if (!w1.isEmpty()) kandidaten.add(w1.get(0).strip());
        int kx = Math.max(0, minX - 6), ky = Math.max(0, minY - 6);
        int kb = Math.min(bild.getWidth() - kx, maxX - minX + 1 + 12), kh = Math.min(bild.getHeight() - ky, maxY - minY + 1 + 12);
        BufferedImage blatt = weissBlatt(bild.getSubimage(kx, ky, kb, kh), 2, 12);
        List<String> w2 = Texterkennung.liesAuchKyrillisch(blatt);
        if (!w2.isEmpty()) kandidaten.add(w2.get(0).strip());
        if (de.hustlebot.core.PaddleLeser.an() && de.hustlebot.core.PaddleLeser.verfuegbar()) {
            try {
                var l = de.hustlebot.core.PaddleLeser.lesenBeste(blatt, true);
                if (l != null && !l.leer()) kandidaten.add(l.text().strip());
            } catch (Exception e) {
                Log.debug("Arena", "Einheiten-Name (Paddle) nicht gelesen: " + e.getMessage());
            }
            String band = nameImKastenPaddle(bild, eng);
            if (!band.isBlank()) kandidaten.add(band.strip());
        }
        return waehleName(kandidaten);
    }

    /**
     * Aus mehreren Lesungen desselben Namens die, die den anderen am meisten gleicht (Summe der Aehnlichkeiten der Formen, ohne Leerzeichen und Zeichen); bei Gleichstand
     * die laengere. Leere und Ein-Zeichen-Lesungen zaehlen nur, wenn es nichts anderes gibt. "" = keine.
     */
    static String waehleName(List<String> lesungen) {
        List<String> k = new ArrayList<>();
        for (String l : lesungen) if (l != null && de.hustlebot.core.NamensVergleich.form(l).length() >= 2) k.add(l.strip());
        if (k.isEmpty()) return lesungen.stream().filter(l -> l != null && !l.isBlank()).findFirst().map(String::strip).orElse("");
        String beste = k.get(0);
        double besterWert = -1;
        for (int i = 0; i < k.size(); i++) {
            String c = k.get(i);
            double summe = 0;
            for (int j = 0; j < k.size(); j++) {
                if (i == j) continue;
                summe += de.hustlebot.core.NamensVergleich.aehnlichkeit(de.hustlebot.core.NamensVergleich.form(c), de.hustlebot.core.NamensVergleich.form(k.get(j)));
            }
            summe += de.hustlebot.core.NamensVergleich.form(c).length() * 0.01;   // bei Gleichstand die laengere
            if (summe > besterWert) {
                besterWert = summe;
                beste = c;
            }
        }
        return beste;
    }

    /** Das Bild {@code f}-fach vergroessert auf einem weissen Blatt mit {@code rand} Punkten Rand. */
    static BufferedImage weissBlatt(BufferedImage teil, int f, int rand) {
        int w = teil.getWidth() * f + 2 * rand, h = teil.getHeight() * f + 2 * rand;
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = o.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(teil, rand, rand, teil.getWidth() * f, teil.getHeight() * f, null);
        g.dispose();
        return o;
    }

    /** Dunkles Blaugruen: die Schrift der Einheitennamen (nicht das Rot von Herz und Pille, nicht das helle Fenster). */
    private static boolean dunkleSchrift(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r < 100 && g < 130 && b < 150 && b >= r;
    }

    /** Schon gelesene Namens-Kaesten (gleiche Bildpunkte -> gleiche Lesung). */
    private static final java.util.Map<Long, String> KASTEN_GEMERKT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 29.10.: den NAMEN nur aus seinem Kasten lesen (weisse Schrift, ohne Wappen, Clan und Punkte), doppelt gross, mit
     * PaddleOCR. Gemessen an 40 von Hand gepruefte Schilder: der Name, den der Bot bisher am Ende benutzte
     * (liesNamenZeilen + Windows-Ersatz + Kasten-Regel), stimmte 19x, dieser Weg 29x -- und 0,11 s statt 3,9 s je Schild.
     * Bisher kamen Clan statt Name ("Exiled Elites"), Salat bei Kyrillisch ("nohMHa" statt "Полина") oder nichts (IDKFA).
     * Eine Schwelle auf die Sicherheit half nicht (auch die Fehler waren "sicher"). "" = nichts gelesen / PaddleOCR fehlt.
     */
    static String nameImKastenPaddle(BufferedImage bild, java.awt.Rectangle k) {
        if (bild == null || k == null) return "";
        if (!de.hustlebot.core.PaddleLeser.an() || !de.hustlebot.core.PaddleLeser.verfuegbar()) return "";
        int x1 = Math.max(0, k.x - 2), y1 = Math.max(0, k.y - 2);
        int x2 = Math.min(bild.getWidth(), k.x + k.width + 2), y2 = Math.min(bild.getHeight(), k.y + k.height + 2);
        if (x2 - x1 < 4 || y2 - y1 < 4) return "";
        try {
            BufferedImage teil = bild.getSubimage(x1, y1, x2 - x1, y2 - y1);
            long schluessel = bildSchluessel(teil);
            String gemerkt = KASTEN_GEMERKT.get(schluessel);
            if (gemerkt != null) return gemerkt;
            String text = "";
            for (var l : de.hustlebot.core.PaddleLeser.lesungen(vergroessert(teil, 2), "namen")) {
                if (!l.leer()) {
                    text = l.text().strip();
                    break;
                }
            }
            if (KASTEN_GEMERKT.size() > 400) KASTEN_GEMERKT.clear();
            KASTEN_GEMERKT.put(schluessel, text);
            return text;
        } catch (Exception e) {
            Log.debug("Arena", "Namens-Kasten nicht gelesen: " + e.getMessage());
            return "";
        }
    }

    /** Wie {@link #liesName}, aber Zeile fuer Zeile -- Name oben, Clan darunter. */
    static List<String> liesNamenZeilen(BufferedImage bild, int x, int y,
                                        int breite, int hoehe) {
        if (bild == null) return List.of();

        int x1 = Math.max(0, x);
        int y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(),  x + breite);
        int y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return List.of();

        try {
            // 11.10. gemessen an 129 Namensbildern: in Originalgroesse las KEINE Texterkennung etwas
            // (deutsch, englisch, russisch), doppelt so gross die allermeisten -- und kyrillische richtig.
            // Dreifach las weitere 25 Namen, die doppelt leer blieben (kyrillische wie lateinische),
            // aber manche schlechter ("Heu@" statt "Hella") -- darum dreifach nur, wenn doppelt schwach war.
            BufferedImage teil = bild.getSubimage(x1, y1, x2 - x1, y2 - y1);
            long schluessel = bildSchluessel(teil);
            List<String> gemerkt = NAMEN_GEMERKT.get(schluessel);
            if (gemerkt != null) return gemerkt;
            List<String> r = Texterkennung.liesAuchKyrillisch(vergroessert(teil, 2));
            if (Texterkennung.schwacheLesung(r)) {
                List<String> r3 = Texterkennung.liesAuchKyrillisch(vergroessert(teil, 3));
                if (Texterkennung.lesungsWert(r3) > Texterkennung.lesungsWert(r)) {
                    Log.debug("Arena", "Name dreifach vergroessert besser gelesen: " + r + " -> " + r3);
                    r = r3;
                }
            }
            synchronized (NAMEN_GEMERKT) {
                if (NAMEN_GEMERKT.size() > 400) NAMEN_GEMERKT.clear();
                NAMEN_GEMERKT.put(schluessel, r);
            }
            unsicherSammeln(teil, schluessel);
            return r;
        } catch (Exception e) {
            Log.debug("Arena", "Name liess sich nicht lesen: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Die erste Zahl in einem Bereich.
     *
     * Leerzeichen und Punkte innerhalb einer Zahl werden entfernt -- das
     * Spiel schreibt "14 398" und "34 773 762". Ohne das waere aus 14 398
     * die Zahl 14 geworden, und der Bot haette gemeldet, Autor habe
     * vierzehn Abzeichen.
     */
    static OptionalLong zahl(BufferedImage bild, int x, int y, int breite, int hoehe) {
        String text = liesText(bild, x, y, breite, hoehe);
        return zahlAus(text);
    }

    /**
     * Alle Zahlen aus einem Text, von links nach rechts.
     *
     * DAS PROBLEM, DAS DAMIT GELOEST WIRD. Das Spiel schreibt grosse
     * Zahlen mit Luecken: "24 956 401". Wer nur bis zur ersten Luecke
     * liest, bekommt 24. Wer eine Luecke ueberspringt, bekommt 24956 --
     * und genau das ist passiert: der Bot hielt einen Gegner mit
     * 24.956.401 fuer einen mit 24.956, also "unter 22 Mio, sicherer
     * Sieg". Er hat dann Leute angegriffen, die dreimal so stark waren.
     *
     * Die Regel dagegen ist einfach und stammt aus der Schreibweise
     * selbst: eine Gruppe gehoert zur vorigen Zahl, wenn sie GENAU DREI
     * Ziffern hat und nur durch eine Luecke getrennt ist. "24 956 401"
     * ist damit eine Zahl, "150 70" sind zwei.
     */
    static List<Long> alleZahlen(String text) {
        List<Long> zahlen = new ArrayList<>();
        if (text == null || text.isBlank()) return zahlen;

        List<String> gruppen = new ArrayList<>();
        List<Boolean> mitLuecke = new ArrayList<>();   // stand davor nur eine Luecke?

        int i = 0;
        boolean luecke = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                int j = i;
                while (j < text.length() && Character.isDigit(text.charAt(j))) j++;
                gruppen.add(text.substring(i, j));
                mitLuecke.add(luecke);
                luecke = false;
                i = j;
            } else {
                // Nur Luecke und Punkt gliedern eine Zahl. Alles andere
                // trennt zwei Zahlen voneinander.
                luecke = (c == ' ' || c == '.' || c == ' ' || c == ' ');
                i++;
            }
        }

        StringBuilder aktuell = new StringBuilder();
        for (int g = 0; g < gruppen.size(); g++) {
            String gruppe = gruppen.get(g);
            // Pruefung 29.10.: "7 940373" (14x, Einheiten-Kraft 7.940.373) -- die Luecke zwischen 940 und 373 fehlte, die Zahl
            // wurde verworfen und der +2-Bonus ab 7 Mio fehlte. Sechs Ziffern direkt nach der ERSTEN Gruppe (1-3 Ziffern)
            // sind zwei zusammengerutschte Dreiergruppen.
            boolean gehoertDazu = aktuell.length() > 0
                    && mitLuecke.get(g)
                    && (gruppe.length() == 3 || (gruppe.length() == 6 && aktuell.length() <= 3));
            if (gehoertDazu) {
                aktuell.append(gruppe);
            } else {
                if (aktuell.length() > 0) fuegeEin(zahlen, aktuell.toString());
                aktuell.setLength(0);
                aktuell.append(gruppe);
            }
        }
        if (aktuell.length() > 0) fuegeEin(zahlen, aktuell.toString());
        return zahlen;
    }

    private static void fuegeEin(List<Long> ziel, String ziffern) {
        try {
            ziel.add(Long.parseLong(ziffern));
        } catch (NumberFormatException ignored) {
            // Laenger als ein long -- sicher verlesen, weglassen.
        }
    }

    /**
     * Autor 01.11. (Pruefung 3.137, Abweichung 8): die Leben einer Einheit ("2 201 814/2 201 814") stehen in WEISS in einer roten Pille hinter dem Herz. Die
     * gewoehnliche Lesung ueber den ganzen Ausschnitt (Herz, Einheitenname darueber, Rand der Pille) las von 154 Fotos nur 1 sauber -- 11 von 31 Einheiten
     * hiessen "nicht sicher gelesen". Hier wird erst die Pille gesucht (die rote Flaeche im Ausschnitt), dann nur ihr Inneres, als weisse Schrift, gelesen:
     * an denselben 154 Fotos 152 Mal beide Zahlen, gleich und richtig (die 2 uebrigen waren kein Einheiten-Fenster). Faellt die weisse Lesung aus, bleibt die
     * alte. Leer, wenn nichts gelesen wurde.
     */
    static List<Long> lebenZahlen(BufferedImage bild, int x, int y, int breite, int hoehe) {
        java.awt.Rectangle pille = rotePille(bild, x, y, breite, hoehe);
        if (pille != null) {
            for (int grenze : new int[] {225, 200, 170}) {
                List<Long> z = zahlenInWeiss(bild, pille.x + 3, pille.y + 3, pille.width - 6, pille.height - 6, grenze);
                if (z.size() >= 2) return z;
            }
        }
        return zahlenIn(bild, x, y, breite, hoehe);
    }

    /** Die rote Pille (kraeftiges Rot) im Ausschnitt, hoechstens {@link #PILLE_MAX_HOEHE} hoch; null, wenn dort keine ist. */
    static java.awt.Rectangle rotePille(BufferedImage bild, int x0, int y0, int breite, int hoehe) {
        if (bild == null) return null;
        int x1 = Math.min(bild.getWidth(), x0 + breite), y1 = Math.min(bild.getHeight(), y0 + hoehe);
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (r > 170 && g < 120 && b < 110 && r - g > 80) {
                    n++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (n < 100 || maxX - minX < 60) return null;
        int h = Math.min(maxY - minY + 1, PILLE_MAX_HOEHE);   // darunter kann ein rotes Bild haengen (Einheit): nur die oberen Zeilen sind die Pille
        return new java.awt.Rectangle(minX, minY, maxX - minX + 1, h);
    }

    static final int PILLE_MAX_HOEHE = 26;

    /** Alle Zahlen in einem Bildausschnitt. */
    static List<Long> zahlenIn(BufferedImage bild, int x, int y, int breite, int hoehe) {
        return alleZahlen(liesText(bild, x, y, breite, hoehe));
    }

    /**
     * Eine WEISSE Zahl mit dunklem Rand auf hellem, farbigem Grund -- die
     * Kampfkraft im Vergleichsfenster (hellgruen links, lachsfarben rechts).
     * Als Schrift zaehlt nur, was in allen drei Farben hell ist; die uebliche
     * Vorstufe hielt den hellen Grund fuer Schrift und las nichts.
     * Gemessen am Bild vom 29.09., 23:40: roh "37472sssD" (Kraft "nicht
     * lesbar", der Bot blaetterte jedes Mal neunmal), so "37 472 535" und
     * "29 065 901" -- mit Grenze 190 und 210 gleich.
     */
    static OptionalLong weisseZahl(BufferedImage bild, int x, int y, int breite, int hoehe) {
        BufferedImage aus = weisseVorstufe(bild, x, y, breite, hoehe, 200);
        if (aus == null) return OptionalLong.empty();
        String text = String.join(" ", gemerktLesen(aus, "", () -> Texterkennung.lies(aus)));
        OptionalLong gruppen = dreierGruppen(text);
        return gruppen.isPresent() ? gruppen : zahlAus(text);
    }

    /**
     * Alle Zahlen in einem Ausschnitt mit WEISSER Schrift auf farbigem Grund -- die Lebensleiste im Einheitenfenster
     * ("3 786 671/3 786 671", weiss auf rot). Dieselbe Vorstufe wie {@link #weisseZahl}: nur was in allen drei Farben hell ist,
     * gilt als Schrift. Bugjagd 29.10.: ohne sie las die Texterkennung die Lebensleiste in 63 von 115 gespeicherten
     * Einheiten-Fotos (logsuge\steine-*.png) gar nicht.
     */
    static List<Long> zahlenInWeiss(BufferedImage bild, int x, int y, int breite, int hoehe, int grenze) {
        BufferedImage aus = weisseVorstufe(bild, x, y, breite, hoehe, grenze);
        if (aus == null) return List.of();
        return alleZahlen(String.join(" ", gemerktLesen(aus, "", () -> Texterkennung.lies(aus))));
    }

    /** Ausschnitt dreifach vergroessert, hell (alle drei Farben ueber {@code grenze}) = schwarze Schrift auf Weiss; null = zu klein. */
    private static BufferedImage weisseVorstufe(BufferedImage bild, int x, int y, int breite, int hoehe, int grenze) {
        if (bild == null) return null;
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 8 || y2 - y1 < 8) return null;
        int f = 3, rand = 12, w = (x2 - x1) * f, h = (y2 - y1) * f;
        BufferedImage gross = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bild.getSubimage(x1, y1, x2 - x1, y2 - y1), 0, 0, w, h, null);
        g.dispose();
        BufferedImage aus = new BufferedImage(w + 2 * rand, h + 2 * rand, BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < aus.getHeight(); yy++) {
            for (int xx = 0; xx < aus.getWidth(); xx++) {
                boolean schrift = false;
                if (xx >= rand && yy >= rand && xx < rand + w && yy < rand + h) {
                    int p = gross.getRGB(xx - rand, yy - rand);
                    int hellste = Math.min((p >> 16) & 0xff, Math.min((p >> 8) & 0xff, p & 0xff));
                    schrift = hellste > grenze;
                }
                aus.setRGB(xx, yy, schrift ? 0x000000 : 0xFFFFFF);
            }
        }
        return aus;
    }

    /**
     * Pruefung 29.10.: "28 438 060 1" -- rechts neben der Kraft sitzt das orange "i", die Weiss-Vorstufe macht daraus
     * eine "1", und zahlAus haengte sie an (284.380.601). Gegliederte Zahl: erste Gruppe 1-3 Ziffern, jede weitere
     * GENAU 3 -- was danach kommt, gehoert nicht dazu. Ohne Gliederung (nur eine Gruppe) leer: dann gilt zahlAus.
     */
    static OptionalLong dreierGruppen(String text) {
        if (text == null) return OptionalLong.empty();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<![\\d])(\\d{1,3})((?:[ .\\u00a0]+\\d{3}(?!\\d))+)").matcher(text);
        if (!m.find()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong((m.group(1) + m.group(2)).replaceAll("[^\\d]", "")));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** Zieht die erste Zahl aus einem Text. Oeffentlich, damit sie sich pruefen laesst. */
    static OptionalLong zahlAus(String text) {
        if (text == null || text.isBlank()) return OptionalLong.empty();

        // Zusammenhaengende Ziffern sammeln. Ein Leerzeichen oder Punkt
        // ZWISCHEN Ziffern trennt keine Zahl, sondern gliedert sie.
        // Mia 21.10.: Die Texterkennung liest die Nahrung auch als "-90-ooo" / "90-000" (statt
        // "90 000") -- das wurde 90 und beendete den Auftrag ("Not enough food left (90 ...)"). Darum
        // gliedert auch ein "-" zwischen Ziffern, aber NUR vor genau drei Ziffern (o/O zaehlen als 0);
        // "1-2" bleibt 1. Ein fuehrendes "-" ist kein Minus und wird wie bisher uebergangen.
        StringBuilder ziffern = new StringBuilder();
        boolean begonnen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                ziffern.append(c);
                begonnen = true;
            } else if (begonnen && (c == 'o' || c == 'O') && oAlsNull(text, i)) {
                ziffern.append('0');   // Mia 21.10.: ein o/O mitten oder am Ende einer begonnenen Zahl ist eine 0
            } else if (begonnen && c == '-' && gliederungsMinus(text, i)) {
                continue;   // gliedert nur, die drei Ziffern danach kommen in den naechsten Runden
            } else if (begonnen && (c == ' ' || c == '.' || c == ' ')) {
                // Weiterschauen: folgt noch eine Ziffer, gehoert sie dazu.
                boolean nochZiffer = false;
                for (int j = i + 1; j < text.length(); j++) {
                    char n = text.charAt(j);
                    if (Character.isDigit(n)) { nochZiffer = true; break; }
                    if ((n == 'o' || n == 'O') && oAlsNull(text, j)) { nochZiffer = true; break; }
                    if (n != ' ' && n != '.' && n != ' ') break;
                }
                if (!nochZiffer) break;
            } else if (begonnen) {
                break;
            }
        }
        if (ziffern.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(ziffern.toString()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();   // laenger als ein long -- sicher verlesen
        }
    }

    /** Ziffer oder die Buchstaben, die die Texterkennung gern fuer eine 0 liest (o/O). */
    private static boolean zifferOderO(char c) {
        return Character.isDigit(c) || c == 'o' || c == 'O';
    }

    /** Wie viele Buchstaben/Ziffern am Stueck ab i stehen, und ob davon jedes eine Ziffer oder o/O ist. */
    private static int nurZifferRun(String t, int i) {
        int j = i;
        while (j < t.length() && Character.isLetterOrDigit(t.charAt(j))) {
            if (!zifferOderO(t.charAt(j))) return -1;
            j++;
        }
        return j - i;
    }

    /**
     * Mia 21.10.: Ist das o/O an Stelle i (in einer schon begonnenen Zahl) eine 0? Nur wenn der ganze
     * Wortlauf ab hier aus Ziffern und o/O besteht ("90ooo" ja, "90 oder" nein) und -- steht eine
     * Gliederung davor (Leerzeichen, Punkt, "-") -- genau drei Zeichen lang ist.
     */
    private static boolean oAlsNull(String t, int i) {
        int lauf = nurZifferRun(t, i);
        if (lauf <= 0) return false;
        if (i == 0) return false;
        char davor = t.charAt(i - 1);
        if (Character.isDigit(davor) || davor == 'o' || davor == 'O') return true;
        return lauf == 3;
    }

    /** Mia 21.10.: Ein "-" an Stelle i gliedert nur, wenn danach GENAU drei Ziffern (o/O = 0) folgen. */
    private static boolean gliederungsMinus(String t, int i) {
        if (i == 0 || !Character.isDigit(t.charAt(i - 1))) return false;
        return nurZifferRun(t, i + 1) == 3;
    }

    /**
     * Die Zahl rechts neben einem gefundenen Zeichen.
     *
     * Gedacht fuer die Zaehler oben rechts: Ticketsymbol, dann die Zahl.
     * Die Hoehe wird vom Zeichen selbst genommen und grosszuegig
     * aufgerundet, damit Ober- und Unterlaengen mitkommen.
     */
    static OptionalLong zahlRechtsVon(BufferedImage bild, Matcher.Hit anker, int breite) {
        if (anker == null) return OptionalLong.empty();
        int rand = anker.height() / 3;
        return zahl(bild,
                anker.x() + anker.width(),
                anker.y() - rand,
                breite,
                anker.height() + 2 * rand);
    }

    /**
     * Die Zahl links neben einem gefundenen Zeichen.
     *
     * Gedacht fuer das Vergleichsfenster: links steht Autors Kampfstaerke,
     * rechts die des Gegners, dazwischen die gekreuzten Schwerter.
     */
    static OptionalLong zahlLinksVon(BufferedImage bild, Matcher.Hit anker, int breite) {
        if (anker == null) return OptionalLong.empty();
        int rand = anker.height() / 3;
        return zahl(bild,
                anker.x() - breite,
                anker.y() - rand,
                breite,
                anker.height() + 2 * rand);
    }

    /**
     * Die Zahl unter einer gefundenen Zeile.
     *
     * Gedacht fuer "Tournament points scored" und "Battle time" -- dort
     * steht die Beschriftung oben und der Wert gross darunter.
     */
    static OptionalLong zahlUnter(BufferedImage bild, Matcher.Hit anker, int hoehe) {
        if (anker == null) return OptionalLong.empty();
        return zahl(bild,
                anker.x() - anker.width() / 4,
                anker.y() + anker.height(),
                anker.width() + anker.width() / 2,
                hoehe);
    }

    /**
     * Die Zahl rechts neben einer Zeile, auf gleicher Hoehe.
     *
     * Gedacht fuer "Your position: 5" und "Stage: 3/5".
     */
    static OptionalLong zahlNeben(BufferedImage bild, Matcher.Hit anker, int breite) {
        return zahlRechtsVon(bild, anker, breite);
    }

    /**
     * Liest "m:ss" als Sekunden.
     *
     * Die Restzeit steht als "01:50". Als reine Zahl gelesen waere daraus
     * 150 geworden -- und der Bot haette bei einer Minute fuenfzig geglaubt,
     * er habe noch zweieinhalb Minuten.
     */
    static OptionalLong sekunden(BufferedImage bild, int x, int y, int breite, int hoehe) {
        OptionalLong r = sekundenAus(liesText(bild, x, y, breite, hoehe));
        if (r.isPresent()) return r;
        // 02.10.: Die Rundenuhr war in jeder Runde mehrmals "?" -- "01:54" las erst die
        // vergroesserte Lesung, "01:04" ("Otto'") nur mit Vorsatzwort.
        r = sekundenAus(liesTextGross(bild, x, y, breite, hoehe));
        if (r.isPresent()) return r;
        return sekundenAus(textHinterWort(bild, x, y, breite, hoehe, "Zeit"));
    }

    private static OptionalLong sekundenAus(String text) {
        if (text == null || text.isBlank()) return OptionalLong.empty();

        // Der Doppelpunkt wird gern als Punkt oder Komma gelesen.
        String sauber = text.replace('.', ':').replace(',', ':').replace(';', ':');
        int trenner = sauber.indexOf(':');
        if (trenner <= 0) {
            // Kein Trenner erkannt -- dann ist es vermutlich nur die
            // Sekundenzahl, etwa "7s" bei der Kampfdauer.
            return zahlAus(sauber);
        }
        OptionalLong min = zahlAus(sauber.substring(0, trenner));
        OptionalLong sek = zahlAus(sauber.substring(trenner + 1));
        if (min.isEmpty() || sek.isEmpty()) return OptionalLong.empty();
        if (sek.getAsLong() > 59) return OptionalLong.empty();   // sicher verlesen
        return OptionalLong.of(min.getAsLong() * 60 + sek.getAsLong());
    }
}
