package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listen-Netz (Autor 28.10.): ein zweiter Weg, einen Spieler von einer der vier Arena-Listen (niemals, meiden, machbar,
 * sicher) zu erkennen, wenn sein Namensbild (ar_block_/ar_ungern_/ar_machbar_/ar_sicher_*.png) im Schild oder im
 * Vergleichsfenster nicht trifft.
 *
 * Messung (Logs 19.-24.10., Zeilen "Attackable:" mit dem Namen eines Listenbildes, Bild zu dem Zeitpunkt geladen):
 * das Bild fehlte bei niemals in 9 von 35 Sichtungen (26 %; "Xenos" 7 von 14 Mal -- der Bot griff ihn zweimal an und verlor
 * beide Male, 21.10. 01:19 und 22.10. 03:38), bei meiden in 1 von 10, bei machbar in 11 von 77 (14 %), bei sicher in 6 von
 * 71 (8 %). Der Name steckt im Dateinamen des Bildes ("ar_block_Xenos.png"), und die Texterkennung las ihn meist richtig.
 *
 * Dieser Teil ist rein (ohne Bot, ohne Dateien) und darum testbar: Namen aus den Vorlagen-Namen holen, einen gelesenen
 * Namen damit vergleichen, die Kraft gegenpruefen. Eingehaengt wird er in ArenaMode nur mit {@link ArenaRegelnNeu#an()}.
 */
public final class ArenaListenNetz {

    private ArenaListenNetz() {}

    public static final String NIEMALS = "ar_block_";
    public static final String MEIDEN = "ar_ungern_";
    public static final String MACHBAR = "ar_machbar_";
    public static final String SICHER = "ar_sicher_";
    /** Autor 31.10.: die beiden Zwischenstufen. */
    public static final String FAST = "ar_fastsicher_";
    public static final String PAUSE = "ar_pause_";
    /** Die strengste Liste zuerst: bei einem Namen auf zwei Listen gilt niemals > Pause > meiden > machbar > fast sicher > sicher (Autor 28.10.). */
    public static final List<String> STRENG = List.of(NIEMALS, PAUSE, MEIDEN, MACHBAR, FAST, SICHER);

    /** Kuerzere Namen sind oft Lesefehler oder gewoehnliche Namen ("i", "lord"): nie benutzen. */
    static final int MIN_NAME = 4;
    /**
     * Gleicher Name ohne weitere Pruefung: niemals/meiden ab 4 Zeichen, machbar/sicher ab 5 -- ein falscher Treffer
     * macht dort einen Fremden zum "sicheren Sieg" (Chance ab 90 %, 225er erlaubt), bei niemals nur zu vorsichtig.
     */
    static final int MIN_EXAKT_LEICHT = 5;
    /** Gleiches Skelett (kyrillisch = lateinisch) erst ab dieser Laenge. */
    static final int MIN_SKELETT = 5;
    /** Ein Lesefehler (ein Zeichen anders, dazu oder weg) erst ab dieser Laenge. */
    static final int MIN_FEHLER = 6;
    /** Die Kraft aus dem Notizblock darf so weit abweichen (Autor 28.10.: +-1,5 %). */
    static final double KRAFT_ABWEICHUNG = 0.015;

    /** Ein Treffer: auf welcher Liste (Vorsilbe) und unter welchem Bildnamen. */
    public record Treffer(String liste, String name) {}

    /**
     * Die Spielernamen aller Listenbilder, je Liste (Schluessel = Vorsilbe): aus Vorlagen-Namen wie "ar_block_Xenos",
     * "ar_block_JkNXED_1017-0002" oder "ar_sicher_Denis_auto_1028-1430". Zu kurze Namen und reine Zahlen
     * ("ar_block_1") zaehlen nicht.
     */
    public static Map<String, List<String>> listenNamen(Collection<String> vorlagenNamen) {
        Map<String, Set<String>> tmp = new LinkedHashMap<>();
        for (String l : STRENG) tmp.put(l, new LinkedHashSet<>());
        if (vorlagenNamen != null) {
            for (String v : vorlagenNamen) {
                if (v == null) continue;
                String liste = ArenaVorlagenDoppelt.liste(v);
                if (liste == null) continue;
                String name = ArenaVorlagenDoppelt.nameAusDatei(v);
                String form = NamensVergleich.form(name);
                if (form.length() < MIN_NAME || form.chars().allMatch(Character::isDigit)) continue;
                tmp.get(liste).add(name);
            }
        }
        Map<String, List<String>> aus = new LinkedHashMap<>();
        for (var e : tmp.entrySet()) aus.put(e.getKey(), new ArrayList<>(e.getValue()));
        return aus;
    }

    /** Schon gelesene Listenbilder: Pfad -> {Aenderungszeit, Lesung}. */
    private static final Map<Path, Object[]> LESUNGEN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 29.10.: zu den Namen aus den DATEINAMEN kommt, was PaddleOCR im Listenbild SELBST liest -- genau so wie der Bot das
     * Namensfeld in der Pyramide liest (doppelt gross). Viele Dateinamen sind alte Lesefehler ("JtNXED", "KingRiéhard",
     * "VIPFR" fuer "Raphy 2.0"); und bei Zierschrift liest PaddleOCR Liste und Schild GLEICH falsch ("JENXED MUREN" fuer
     * JINXED MURENA) -- gleicher Leser, gleicher Fehler, der Name passt trotzdem. Jedes Bild wird nur einmal gelesen
     * (neu nur, wenn es sich aendert).
     */
    public static Map<String, List<String>> mitLesungen(Map<String, List<String>> ausDateinamen, Collection<Path> listenBilder) {
        Map<String, List<String>> aus = new LinkedHashMap<>();
        for (var e : ausDateinamen.entrySet()) aus.put(e.getKey(), new ArrayList<>(e.getValue()));
        if (listenBilder == null || !PaddleLeser.an() || !PaddleLeser.verfuegbar()) return aus;
        for (Path p : listenBilder) {
            String liste = ArenaVorlagenDoppelt.liste(p.getFileName().toString());
            if (liste == null || !aus.containsKey(liste)) continue;
            String gelesen = lesung(p);
            String form = NamensVergleich.form(gelesen);
            if (form.length() < MIN_NAME || form.chars().allMatch(Character::isDigit)) continue;
            if (!aus.get(liste).contains(gelesen)) aus.get(liste).add(gelesen);
        }
        return aus;
    }

    /** Was PaddleOCR in diesem Listenbild liest (erste Zeile, doppelt gross), gemerkt je Aenderungszeit. */
    static String lesung(Path p) {
        try {
            long stand = Files.getLastModifiedTime(p).toMillis();
            Object[] alt = LESUNGEN.get(p);
            if (alt != null && (long) alt[0] == stand) return (String) alt[1];
            java.awt.image.BufferedImage b;
            try (var in = Files.newInputStream(p)) {
                b = javax.imageio.ImageIO.read(in);
            }
            String text = "";
            if (b != null) {
                java.awt.image.BufferedImage gross = new java.awt.image.BufferedImage(b.getWidth() * 2, b.getHeight() * 2,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = gross.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(b, 0, 0, gross.getWidth(), gross.getHeight(), null);
                g.dispose();
                for (var l : PaddleLeser.lesungen(gross, "")) {
                    if (!l.leer()) {
                        text = l.text().strip();
                        break;
                    }
                }
            }
            LESUNGEN.put(p, new Object[]{stand, text});
            return text;
        } catch (Exception e) {
            Log.debug("Arena", "Listenbild " + p.getFileName() + " nicht gelesen: " + e.getMessage());
            return "";
        }
    }

    /**
     * Alle Listen, auf denen dieser gelesene Name passt, strengste zuerst (niemals, meiden, machbar, sicher); je Liste der
     * erste passende Bildname. Leer, wenn keine passt. Die Kraft pruefen: {@link #kraftVertraeglich}.
     */
    public static List<Treffer> treffer(Map<String, List<String>> listen, String gelesen) {
        List<Treffer> aus = new ArrayList<>();
        if (listen == null || listen.isEmpty() || gelesen == null) return aus;
        for (String liste : STRENG) {
            List<String> namen = listen.get(liste);
            if (namen == null || namen.isEmpty()) continue;
            // 29.10. (Messung 1315 Schilder): "новый" las PaddleOCR als "HOBBIM" -- ein Zeichen von "Hobbit" (sicher). Fuer
            // sicher/machbar zaehlt der TEXT darum nur bei gleichem Namen (ein Bildtreffer zaehlt sowieso); fuer
            // niemals/meiden bleibt er tolerant -- im Zweifel lieber nicht angreifen.
            String n = SICHER.equals(liste) || MACHBAR.equals(liste) || FAST.equals(liste)
                    ? exakt(namen, gelesen, minExakt(liste))
                    : treffer(namen, gelesen, minExakt(liste));
            if (n != null) aus.add(new Treffer(liste, n));
        }
        return aus;
    }

    /** Nur gleicher Name (gleiche Form, ab {@code minExakt} Zeichen). @return der Bildname, sonst null */
    static String exakt(Collection<String> bildNamen, String gelesen, int minExakt) {
        if (bildNamen == null || gelesen == null) return null;
        String f = NamensVergleich.form(gelesen);
        if (f.length() < Math.max(MIN_NAME, minExakt)) return null;
        for (String b : bildNamen) if (NamensVergleich.form(b).equals(f)) return b;
        return null;
    }

    static int minExakt(String liste) {
        return NIEMALS.equals(liste) || MEIDEN.equals(liste) || PAUSE.equals(liste) ? MIN_NAME : MIN_EXAKT_LEICHT;
    }

    /**
     * Gehoert dieser gelesene Name zu einem der Bildnamen? Gleiche Form (ab {@code minExakt} Zeichen), gleiches Skelett
     * kyrillisch/lateinisch (ab 5) oder ein Lesefehler (ab 6). @return der Bildname, sonst null
     */
    public static String treffer(Collection<String> bildNamen, String gelesen, int minExakt) {
        if (bildNamen == null || bildNamen.isEmpty() || gelesen == null) return null;
        String f = NamensVergleich.form(gelesen);
        if (f.length() < MIN_NAME) return null;
        String s = NamensVergleich.skelett(gelesen);
        for (String b : bildNamen) {
            String fb = NamensVergleich.form(b);
            if (fb.length() < MIN_NAME) continue;
            int kurz = Math.min(fb.length(), f.length());
            if (fb.equals(f) && f.length() >= minExakt) return b;
            String sb = NamensVergleich.skelett(b);
            if (kurz >= MIN_SKELETT && !s.isEmpty() && s.equals(sb)) return b;
            if (kurz >= MIN_FEHLER && abstandHoechstens1(fb, f)) return b;
        }
        return null;
    }

    /** Ein Bruchstueck zaehlt erst ab so vielen Zeichen ("enos" von "Xenos"), und es fehlen ihm hoechstens so viele Zeichen vorn. */
    static final int MIN_TEIL = 4, MAX_FEHLT_VORN = 2;

    /**
     * Autor 01.11. (Pruefung 3.137): ist der gelesene Name nur der HINTERE TEIL eines der Namen? Das Protokoll liegt links ueber den Schildern und
     * verdeckt den Anfang -- "Xenos" (gesperrt) wurde zu "enos". Dann ist es dieser Spieler, nicht ein Fremder: wer so auf "niemals" steht, darf durch
     * eine Teil-Lesung nie weniger streng werden. Nur das Ende zaehlt (verdeckt wird der Anfang), mindestens {@link #MIN_TEIL} Zeichen, und es fehlen
     * hoechstens {@link #MAX_FEHLT_VORN} Zeichen. @return der vollstaendige Name, sonst null
     */
    public static String teilVon(Collection<String> namen, String gelesen) {
        if (namen == null || gelesen == null) return null;
        String f = NamensVergleich.form(gelesen);
        if (f.length() < MIN_TEIL) return null;
        for (String n : namen) {
            if (n == null) continue;
            String g = NamensVergleich.form(n);
            int fehlt = g.length() - f.length();
            if (fehlt >= 1 && fehlt <= MAX_FEHLT_VORN && g.endsWith(f)) return n;
        }
        return null;
    }

    /**
     * Autor 01.11. (Pruefung 3.137, Abweichung 13): die weisse Schrift von "wither" traf das Listenbild "wicho" (sicher) mit 0,88 -- zwei verschiedene
     * Spieler, die Namen lesbar und verschieden. Die Bildvergleiche der weissen Schrift messen die Form, nicht den Namen; bei einem kurzen lateinischen
     * Namen reichen zwei, drei gleiche Buchstaben fuer 0,86. Ein Listenbild, das den Spieler WENIGER streng macht (sicher, fast sicher, machbar), gilt
     * darum nicht, wenn der gelesene Name und der Name des Bildes beide eindeutig lateinisch lesbar sind und nicht zusammenpassen. Niemals, meiden und
     * Pause bleiben immer (im Zweifel nicht angreifen), und ist einer der Namen nicht klar lesbar (kyrillisch, chinesisch, "Bild-..."), zaehlt allein das Bild.
     *
     * @return true = das Bild gehoert nicht zu diesem Namen, bitte nicht beachten
     */
    public static boolean fremderName(String liste, String bildName, String gelesen) {
        if (!SICHER.equals(liste) && !FAST.equals(liste) && !MACHBAR.equals(liste)) return false;
        // 01.11., 15:19: "Антон" (46,5 Mio) galt durch das Bild "Артем" als machbar, 88 %, verloren: auch rein kyrillische Namen vergleichen.
        boolean lateinisch = klarLateinisch(bildName) && klarLateinisch(gelesen);
        boolean kyrillisch = klarKyrillisch(bildName) && klarKyrillisch(gelesen);
        if (!lateinisch && !kyrillisch) return false;
        return !NamensVergleich.gleicherName(bildName, gelesen);
    }

    /** Mindestens {@link #MIN_NAME} Zeichen, nur lateinische Buchstaben und Ziffern, kein Platzhalter ("Bild-1019-151644"). */
    static boolean klarLateinisch(String name) {
        if (name == null || name.startsWith("Bild-")) return false;
        String f = NamensVergleich.form(name);
        if (f.length() < MIN_NAME) return false;
        int buchstaben = 0;
        for (char c : f.toCharArray()) {
            if (c >= 'a' && c <= 'z') buchstaben++;
            else if (c < '0' || c > '9') return false;   // kyrillisch, chinesisch, Akzente: nicht klar lesbar
        }
        return buchstaben * 10 >= f.length() * 7;
    }

    /** Mindestens {@link #MIN_NAME} Zeichen, nur kyrillische Buchstaben, kein Platzhalter. */
    static boolean klarKyrillisch(String name) {
        if (name == null || name.startsWith("Bild-")) return false;
        String f = NamensVergleich.form(name);
        if (f.length() < MIN_NAME) return false;
        for (char c : f.toCharArray()) if (Character.UnicodeBlock.of(c) != Character.UnicodeBlock.CYRILLIC) return false;
        return true;
    }

    /** Zwei bekannte Kraefte gleich (bis 1,5 % Abweichung)? */
    public static boolean kraftPasst(long a, long b) {
        return a > 0 && b > 0 && Math.abs(a - b) <= Math.max(a, b) * KRAFT_ABWEICHUNG;
    }

    /**
     * Vertraegt sich die Kraft des Spielers hier mit den Kraeften, die der Notizblock zum Bildnamen kennt? Ist hier keine
     * Kraft bekannt oder kennt der Notizblock keine, gilt der Name allein (wahr). Sind beide da, muss eine passen --
     * sonst ist es ein anderer Spieler mit gleichem Namen.
     */
    public static boolean kraftVertraeglich(long seine, Collection<Long> bekannte) {
        if (seine <= 0 || bekannte == null || bekannte.isEmpty()) return true;
        boolean eineBekannt = false;
        for (Long k : bekannte) {
            if (k == null || k <= 0) continue;
            eineBekannt = true;
            if (kraftPasst(seine, k)) return true;
        }
        return !eineBekannt;
    }

    /** Hoechstens ein Zeichen anders, dazu oder weg (Levenshtein bis 1). */
    static boolean abstandHoechstens1(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0;
        while (i < la && i < lb && a.charAt(i) == b.charAt(i)) i++;
        if (i == la || i == lb) return true;   // gleich, oder eines am Ende laenger (Unterschied hoechstens 1 durch die Laengenpruefung)
        if (la == lb) return a.substring(i + 1).equals(b.substring(i + 1));   // ein Zeichen anders
        if (la > lb) return a.substring(i + 1).equals(b.substring(i));        // in a ein Zeichen zu viel
        return a.substring(i).equals(b.substring(i + 1));                      // in b ein Zeichen zu viel
    }
}
