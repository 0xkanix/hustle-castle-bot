package de.hustlebot.core;

import org.opencv.core.Rect;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Feste Suchbereiche je Vorlage (Sparsam 20.11., Autor: "mehr Positionserkennung, nicht das ganze Bild anschauen").
 *
 * WOHER. Jeder Bot merkt sich, wo er eine Vorlage gefunden hat (vorlagen-stellen.txt). 1544 dieser Dateien aus den
 * Freunde-Berichten und Autors Bot zeigen: die meisten Knoepfe sassen IMMER an derselben Stelle (update_button in 1529
 * Berichten bei x 507, y 452-479) oder wenigstens in derselben Zeile (chat_close in 879 Berichten bei y 323, x 329-673).
 * Die Spanne steht in suchbereiche.txt (Liste der Regeln dort).
 *
 * WOZU. Statt im ganzen Bild (1159x770) wird nur in diesem Bereich plus Rand gesucht. Gerechnet wird dasselbe, nur auf
 * einem Ausschnitt -- jede Stelle im Bereich bekommt denselben Wert wie bei der Suche im ganzen Bild. Wie die Kaestchen
 * der Arena, aber mit Rueckfall: siehe Matcher.ganzesBild (jede 25. Suche, und jedes "gruendliche" Bild, sucht weiter
 * das ganze Bild ab; findet sie die Vorlage ausserhalb, gilt der Bereich fuer diese Vorlage bis zum Neustart nicht mehr).
 *
 * Nur fuer Fenster in der gemessenen Groesse (+-16 Bildpunkte); sonst wie bisher das ganze Bild.
 */
public final class Suchbereiche {

    private Suchbereiche() { }

    /** So viel Rand um die belegte Spanne (Bildpunkte je Seite). */
    static final int RAND = 60;
    static final int BREITE = 1159, HOEHE = 770, TOLERANZ = 16;
    /** Ein Bereich, der mehr als diesen Anteil des Bildes bedeckt, lohnt nicht. */
    static final double HOECHSTENS_ANTEIL = 0.6;

    /** {minX, minY, maxX, maxY}, -1 = Achse frei. */
    private static volatile Map<String, int[]> tabelle;

    static Map<String, int[]> tabelle() {
        Map<String, int[]> t = tabelle;
        if (t != null) return t;
        synchronized (Suchbereiche.class) {
            if (tabelle != null) return tabelle;
            Map<String, int[]> neu = new HashMap<>();
            try (InputStream in = Suchbereiche.class.getResourceAsStream("/suchbereiche.txt")) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    for (String z; (z = r.readLine()) != null; ) {
                        z = z.trim();
                        int gleich = z.indexOf('=');
                        if (z.isEmpty() || z.startsWith("#") || gleich <= 0) continue;
                        String[] w = z.substring(gleich + 1).split(",");
                        if (w.length != 4) continue;
                        int[] b = new int[4];
                        for (int i = 0; i < 4; i++) b[i] = w[i].trim().equals("*") ? -1 : Integer.parseInt(w[i].trim());
                        neu.put(z.substring(0, gleich).trim(), b);
                    }
                }
            } catch (Exception e) {
                Log.debug("Vorlagen", "Suchbereiche nicht lesbar: " + e.getMessage());
            }
            tabelle = neu;
            return neu;
        }
    }

    /** Hat diese Vorlage einen festen Bereich? */
    public static boolean hat(String name) {
        return tabelle().containsKey(name);
    }

    /**
     * Der Suchbereich fuer eine Vorlage der Groesse vb x vh in einem Bild der Groesse bb x bh -- oder null: keiner
     * bekannt, andere Fenstergroesse, oder der Bereich waere fast so gross wie das Bild.
     */
    public static Rect bereich(String name, int bb, int bh, int vb, int vh) {
        int[] s = tabelle().get(name);
        if (s == null) return null;
        if (Math.abs(bb - BREITE) > TOLERANZ || Math.abs(bh - HOEHE) > TOLERANZ) return null;
        int x0 = s[0] < 0 ? 0 : Math.max(0, s[0] - RAND);
        int y0 = s[1] < 0 ? 0 : Math.max(0, s[1] - RAND);
        int x1 = s[2] < 0 ? bb : Math.min(bb, s[2] + vb + RAND);
        int y1 = s[3] < 0 ? bh : Math.min(bh, s[3] + vh + RAND);
        if (x1 - x0 < vb || y1 - y0 < vh) return null;
        if ((double) (x1 - x0) * (y1 - y0) > HOECHSTENS_ANTEIL * bb * bh) return null;
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }
}
