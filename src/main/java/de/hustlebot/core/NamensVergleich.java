package de.hustlebot.core;

/**
 * Sind zwei gelesene Namen derselbe Spieler?
 *
 * Die Texterkennung liest Zierschrift jedes Mal ein wenig anders: in der
 * Arena-Liste vom 25.09., 05:32 stand "KiUshot", "Killshot" und "Kinshot" --
 * dreimal derselbe Gegner, und "Cxa30LUHHKH" sogar in sechs Schreibweisen.
 * Jede Variante wurde ein eigener Gegner: 22 Eintraege fuer 9 Spieler, die
 * Bilanz im Notizblock zerfiel, und Punkte landeten beim falschen Namen.
 *
 * Verglichen wird deshalb nicht Buchstabe fuer Buchstabe, sondern
 * "ungefaehr gleich":
 *
 *   - gleich nach dem Entfernen von Leer- und Sonderzeichen, oder
 *   - der kuerzere (ab 5 Zeichen) steckt ganz im laengeren
 *     ("rob420" in "jjrob420" -- ein Symbol davor wurde als "JJ" gelesen), oder
 *   - ab 6 Zeichen hoechstens knapp ein Drittel der Zeichen anders.
 *
 * Kurze Namen (unter 5 Zeichen) muessen genau stimmen -- bei "Rina" und
 * "Rino" waere "ungefaehr" schon ein anderer Mensch.
 */
public final class NamensVergleich {

    private NamensVergleich() { }

    /** Nur Buchstaben und Ziffern, klein. */
    public static String form(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** Derselbe Spieler? Auch, wenn er einmal lateinisch und einmal kyrillisch gelesen wurde (11.10.). */
    public static boolean gleicherName(String a, String b) {
        if (gleicheForm(form(a), form(b), 0.70)) return true;
        String x = skelett(a), y = skelett(b);
        return !x.equals(form(a)) || !y.equals(form(b)) ? gleicheForm(x, y, 0.70) : false;
    }

    /**
     * Autor, 11.10.: derselbe Gegner stand z. B. einmal kyrillisch (etwa "Sascha" in russischer Schrift) und
     * zweimal lateinisch verlesen ("Cawa", "CaL.Ua") da -- dreimal ein anderer Spieler, seine Kraft kannte der
     * Bot darum beim Angriff nicht (Niederlage). Das Skelett schreibt beide Schriften in dieselben
     * Zwillingszeichen: kyrillisch wie das lateinische Modell es hinschreibt (ka->k, scha->w, el->n, y->bl,
     * de->a ...), dazu die typischen Verwechslungen des lateinischen Modells (0->o, 1->l, "lu"/"uu" -> w fuer
     * scha, "jl" -> n fuer el). Alle drei Lesungen oben ergeben "cawa". Andere Schriften (Chinesisch ...)
     * bleiben, wie sie sind.
     */
    public static String skelett(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            int k = KYRILLISCH.indexOf(c);
            if (k >= 0) { sb.append(ZWILLING[k]); continue; }
            if (!Character.isLetterOrDigit(c)) continue;
            sb.append(switch (c) {
                case '0' -> 'o';
                case '1', '|' -> 'l';
                default -> c;
            });
        }
        return sb.toString().replace("jl", "n").replace("lu", "w").replace("uu", "w").replace("ll", "n");
    }

    private static final String KYRILLISCH = "\u0430\u0431\u0432\u0433\u0434\u0435\u0451\u0436\u0437\u0438\u0439\u043a\u043b\u043c\u043d\u043e\u043f\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044a\u044b\u044c\u044d\u044e\u044f\u0456\u0457\u0454";
    private static final String[] ZWILLING = {"a", "6", "b", "r", "a", "e", "e", "x", "3", "u", "u", "k", "n", "m", "h", "o", "n", "p", "c", "t", "y", "f", "x", "u", "4", "w", "w", "b", "bl", "b", "3", "io", "r", "i", "i", "e"};

    /**
     * Derselbe Clan? Etwas grosszuegiger als beim Namen, weil Clans oft
     * abgeschnitten gelesen werden ("RUSSIAS_" gegen "RUSSIA corp").
     */
    public static boolean gleicherClan(String a, String b) {
        String x = form(a), y = form(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String kurz = x.length() <= y.length() ? x : y, lang = kurz == x ? y : x;
        // 08.10. (Autor: "Hustle" ist nicht "Hustle Mafia" -- es gibt viele Clans, die mit Hustle anfangen):
        // nur ein ganz abgeschnittener Clan zaehlt als derselbe, nicht schon die gleichen 4 Anfangsbuchstaben.
        if (kurz.length() >= 4 && lang.startsWith(kurz)) return true;
        if (gleicheForm(x, y, 0.60)) return true;
        // 11.10.: ein kyrillischer Clan-Name und seine lateinische Verlesung ("J1blCblE KOTbl") sind derselbe
        // Clan -- ueber das Skelett vergleichen.
        String sx = skelett(a), sy = skelett(b);
        if (sx.equals(x) && sy.equals(y)) return false;
        String k2 = sx.length() <= sy.length() ? sx : sy, l2 = k2 == sx ? sy : sx;
        return (k2.length() >= 4 && l2.startsWith(k2)) || gleicheForm(sx, sy, 0.60);
    }

    /** Die Bearbeitungsdistanz (Levenshtein) von a und b, oder hoechstens+1, wenn sie groesser ist (bricht frueh ab). */
    public static int abstandBis(String a, String b, int hoechstens) {
        if (Math.abs(a.length() - b.length()) > hoechstens) return hoechstens + 1;
        int[] vorher = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vorher[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            int kleinste = jetzt[0];
            for (int j = 1; j <= b.length(); j++) {
                int kosten = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vorher[j] + 1), vorher[j - 1] + kosten);
                kleinste = Math.min(kleinste, jetzt[j]);
            }
            if (kleinste > hoechstens) return hoechstens + 1;
            int[] t = vorher;
            vorher = jetzt;
            jetzt = t;
        }
        return Math.min(vorher[b.length()], hoechstens + 1);
    }

    /** Wie gleicherName, aber fuer schon bereinigte Formen. */
    public static boolean gleicheForm(String x, String y, double schwelle) {
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String kurz = x.length() <= y.length() ? x : y, lang = kurz == x ? y : x;
        if (kurz.length() < 5) return false;
        if (lang.contains(kurz)) return true;
        // Fuenf Zeichen: eine Abweichung darf sein ("du5ty" auf dem Schild, "dusty" im
        // Vergleichsfenster -- 01.10., derselbe Gegner bekam zwei Schluessel).
        if (kurz.length() < 6) return lang.length() <= 6 && aehnlichkeit(x, y) >= 0.8;
        return aehnlichkeit(x, y) >= schwelle;
    }

    /** 1 = gleich, 0 = voellig anders (Levenshtein, auf die Laenge bezogen). */
    public static double aehnlichkeit(String x, String y) {
        int n = x.length(), m = y.length();
        if (n == 0 && m == 0) return 1;
        int[] vorher = new int[m + 1], jetzt = new int[m + 1];
        for (int j = 0; j <= m; j++) vorher[j] = j;
        for (int i = 1; i <= n; i++) {
            jetzt[0] = i;
            for (int j = 1; j <= m; j++) {
                int kosten = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vorher[j] + 1), vorher[j - 1] + kosten);
            }
            int[] t = vorher; vorher = jetzt; jetzt = t;
        }
        return 1.0 - (double) vorher[m] / Math.max(n, m);
    }

    /**
     * Ist das gar kein Name, sondern Clan- oder Punktetext? Kommt vor, wenn
     * die Namenszeile in Zierschrift nicht gelesen wurde und die Zeile
     * darunter nachrutscht ("Not in Clan", "Not", "286.").
     */
    /**
     * Sieht der Name nach einem echten Spieler aus? Autor, 08.10.: "Namen mit Fragezeichen,
     * Ausrufezeichen, Punkt, Zahlen ... das sind echte Spieler. Bots haben meistens keine Zahlen
     * oder komische Zeichen im Namen." Also: eine Ziffer, ein Zeichen ausser Buchstabe/Leerzeichen,
     * oder lateinische und kyrillische Buchstaben gemischt.
     */
    public static boolean siehtMenschlichAus(String name) {
        if (name == null || name.isBlank()) return false;
        boolean lateinisch = false, kyrillisch = false;
        for (char c : name.strip().toCharArray()) {
            if (Character.isDigit(c)) return true;
            if (Character.isLetter(c)) {
                Character.UnicodeScript sc = Character.UnicodeScript.of(c);
                if (sc == Character.UnicodeScript.LATIN) lateinisch = true;
                else if (sc == Character.UnicodeScript.CYRILLIC) kyrillisch = true;
                else return true;   // andere Schrift (Chinesisch, Arabisch ...)
            } else if (c != ' ') {
                return true;
            }
        }
        return lateinisch && kyrillisch;
    }

    public static boolean keinName(String s) {
        String f = form(s);
        if (f.isEmpty()) return true;
        if (f.equals("not") || f.startsWith("notin") || f.startsWith("nolin")) return true;
        // Reine Zahlen sind nur dann kein Name, wenn sie wie Punkte aussehen ("286.", "+150", "1,5"):
        // Autor, 08.10.: "206" ist ein Name -- er bekam sonst ein Namensbild "Bild-1008-..." statt "206".
        return f.chars().allMatch(Character::isDigit) && (s.contains(".") || s.contains("+") || s.contains(",") || f.length() > 6);
    }
}
