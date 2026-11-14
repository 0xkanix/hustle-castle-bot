package de.hustlebot.core;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/**
 * Liest Text aus einem Bild.
 *
 * Benutzt die Texterkennung, die in Windows schon eingebaut ist
 * (Windows.Media.Ocr). Kein Zusatzprogramm, nichts herunterzuladen, und
 * der Text verlaesst den Rechner nicht.
 *
 * Der uebliche Weg waere Tesseract gewesen -- rund 50 MB plus
 * Sprachdateien. Vor dem Herunterladen wurde die Windows-Erkennung an
 * Autors Clan-Chat gemessen, und sie las ihn Wort fuer Wort richtig:
 *
 *     "die Pokale die ma hatte... aus dem Game rausgehen, halbe
 *      Stunde warten und das wiederholen"
 *
 * Damit war die Entscheidung klar. Erst messen, dann installieren.
 *
 * Der Aufruf geht ueber ein kleines PowerShell-Skript, weil die
 * Windows-Erkennung von Java aus nicht direkt erreichbar ist. Das kostet
 * pro Aufruf etwa eine Sekunde -- fuer einen Chat, den Autor von Hand
 * abruft, ist das nicht der Rede wert.
 */
public final class Texterkennung {

    private static final Path SKRIPT = Path.of("texterkennung.ps1");

    /**
     * Freunde-Fassung: Das Skript steckt auch in der Jar (Ordner "skripte").
     * Im fertigen Paket wird es vor jedem Aufruf auf Jar-Stand gebracht (fehlt
     * oder weicht ab -> schreiben), beim Entwickeln nur angelegt, wenn es fehlt
     * -- siehe UpdateNachschub.skriptAufJarStand (Sicherheits-Update 16.10.).
     */
    private static void skriptSicherstellen() {
        UpdateNachschub.skriptAufJarStand(SKRIPT);
    }


    /** Wie lange die Erkennung hoechstens dauern darf. */
    private static final long ZEITGRENZE_SEKUNDEN = 40;

    private Texterkennung() {
    }

    /** Ist die Texterkennung ueberhaupt einsatzbereit? */
    public static boolean istDa() {
        return Files.exists(SKRIPT);
    }

    /**
     * Liest den Text aus einem Bild, Zeile fuer Zeile.
     *
     * Leere Liste heisst: nichts erkannt oder etwas ist schiefgegangen --
     * der Grund steht dann im Protokoll.
     */
    public static List<String> lies(BufferedImage bild) {
        return lies(bild, "");
    }

    /**
     * Weisse Schrift mit dunklem Rand auf farbigem Grund (Turmnamen im Clan-Krieg, Preis auf gruenen Knoepfen):
     * nur helle, fast farblose Punkte werden schwarz, alles andere weiss -- mit Rand und vergroessert (Hauptbot 17.10.).
     */
    public static BufferedImage weisseSchrift(BufferedImage bild, int zoom) {
        int w = bild.getWidth(), h = bild.getHeight(), rand = 8;
        zoom = Math.max(1, zoom);
        BufferedImage aus = new BufferedImage((w + 2 * rand) * zoom, (h + 2 * rand) * zoom, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = aus.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, aus.getWidth(), aus.getHeight());
        g.setColor(java.awt.Color.BLACK);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 255, gr = (p >> 8) & 255, b = p & 255;
                int min = Math.min(r, Math.min(gr, b)), max = Math.max(r, Math.max(gr, b));
                if (min > 190 && max - min < 45) g.fillRect((x + rand) * zoom, (y + rand) * zoom, zoom, zoom);
            }
        g.dispose();
        return aus;
    }

    /**
     * Liest ein Bild und laesst, wenn nichts herauskommt, Russisch nachsehen.
     *
     * ------------------------------------------------------------------
     *  Warum das noetig ist
     * ------------------------------------------------------------------
     *
     * Windows hat auf diesem Rechner zwei Erkennungen:
     *
     *     de-DE  Deutsch (Deutschland)
     *     ru     Russisch
     *
     * Benutzt wurde immer nur die erste. In der Arena heissen Autors
     * Gegner aber Dinge wie ~ЦИРИ~ -- und das deutsche Modell hat davon
     * nie ein Zeichen gelesen. Nicht weil Windows es nicht koennte,
     * sondern weil nie danach gefragt wurde. Im Notizblock blieben genau
     * die Gegner leer, die Autor am meisten interessieren.
     *
     * ------------------------------------------------------------------
     *  Warum erst Deutsch, dann Russisch
     * ------------------------------------------------------------------
     *
     * Beide Modelle laufen zu lassen wuerde jeden Namen doppelt so teuer
     * machen -- gemessen 335 Millisekunden je Lesevorgang. Die meisten
     * Namen sind lateinisch und werden gleich beim ersten Mal gelesen.
     *
     * Nachgesehen wird deshalb nur dann, wenn beim ersten Versuch KEIN
     * einziger Buchstabe herauskam. Das ist genau der Fall, der heute ein
     * Totalausfall ist -- da kostet ein zweiter Versuch nichts, er kann
     * nur gewinnen.
     */
    public static List<String> liesAuchKyrillisch(BufferedImage bild) {
        // PaddleOCR waehlt die Schrift selbst (lateinisch, kyrillisch, chinesisch, koreanisch, arabisch) -- wenn es sich
        // sicher ist. Sonst wie bisher Windows lateinisch + kyrillisch.
        if (PaddleLeser.an() && PaddleLeser.verfuegbar()) {
            List<PaddleLeser.Lesung> p = PaddleLeser.lesungen(bild, "namen");   // Namen: Schrift-Erkenner (29.10.)
            if (PaddleLeser.sicher(p)) return new ArrayList<>(p.stream().map(PaddleLeser.Lesung::text).toList());
        }
        List<String> ersteWahl = lies(bild, "");
        boolean nichts = !hatBuchstaben(ersteWahl);

        // Seit 01.10. auch dann kyrillisch nachlesen, wenn das lateinische Modell zwar
        // Buchstaben liefert, die aber fast nur aus Zwillingen bestehen ("HECnATb 2.0" fuer
        // "НЕСПАТЬ 2.0", "Kpacxaq nyxa" fuer "Красная Луна", "Bongapa" fuer "получара").
        // 11.10. (Autor: "jede Sprache richtig erkennen"): auch bei fremden Zeichen in der Zeile
        // ("EB>KeHhV1)" fuer "ЕвЖеНиЙ1", "Mepr.qaH" fuer "Мерман").
        boolean verdaechtig = false;
        for (String z : ersteWahl) verdaechtig |= siehtKyrillischAus(z) || unsauber(z) > 0;
        if (!nichts && !verdaechtig) return ersteWahl;

        List<String> ergebnis = ersteWahl;
        if (kannSprache("ru")) {
            List<String> kyrillisch = lies(bild, "ru");
            if (hatBuchstaben(kyrillisch)) {
                List<String> gemischt = nichts ? kyrillisch : waehleZeilen(ersteWahl, kyrillisch);
                if (!gemischt.equals(ersteWahl)) {
                    Log.debug("Text", (nichts ? "Lateinisch war nichts zu lesen, kyrillisch schon: " : "Kyrillisch nachgelesen: "
                            + ersteWahl + " -> ") + gemischt);
                }
                ergebnis = gemischt;
            }
        }
        // Autor, 11.10.: "alle Sprachen beruecksichtigen, die ich auf diesem PC habe". Fremde Zeichen in einer
        // Zeile ("Kara $övalye") liest oft eine andere Sprache derselben Schrift richtig ("Kara Şövalye", tr).
        ergebnis = verbessern(bild, ergebnis, LATEINISCHE, false);
        ergebnis = verbessern(bild, ergebnis, KYRILLISCHE, true);
        if (!hatBuchstaben(ergebnis) || ergebnis.stream().anyMatch(Texterkennung::schwacheZeile)) {
            // Andere Schriften (chinesisch, japanisch, koreanisch, arabisch, griechisch ...). Autor, 11.10.:
            // "chinesische / japanische sollen auch richtig uebernommen werden und genauso wie sie im Spiel sind
            // auch im Notizblock stehen." Probiert, wenn eine Zeile nur Zeichensalat oder hoechstens 4 Buchstaben
            // hat ("龙之怒" kam als "Ё2В"). Es gewinnt die Lesung mit den meisten Zeichen ihrer eigenen Schrift;
            // Kana/Hangul zaehlen etwas mehr ("タナカ" las das chinesische Modell als "夕 十 力").
            List<String> beste = null;
            double besteWert = 1.5;
            // Autor 24.10. (Ideenliste C02/C06): bis zu 14 Schriften nacheinander kosteten je Namensbild mehrere
            // Sekunden ("元素神" 62-mal komplett durchprobiert). Zeigt die bisherige Lesung schon eine Schrift (Han,
            // Kana, Hangul, Arabisch ...), zuerst nur die passenden Sprachen -- und aufhoeren, sobald eine sauber liest.
            List<String> reihe = new ArrayList<>(weitereSchriften());
            java.util.Set<String> passend = schriftHinweis(ersteWahl, ergebnis);
            if (!passend.isEmpty()) {
                List<String> vorn = new ArrayList<>(), hinten = new ArrayList<>();
                for (String sp : reihe) (passend.contains(sp) ? vorn : hinten).add(sp);
                reihe = new ArrayList<>(vorn);
                reihe.addAll(hinten);
            }
            int gelesen = 0;
            for (String sprache : reihe) {
                // Mit Hinweis: nach den passenden Sprachen Schluss, wenn sie etwas Sauberes geliefert haben.
                if (!passend.isEmpty() && gelesen >= passend.size() && beste != null) break;
                gelesen++;
                List<String> v = lies(bild, sprache);
                // Nur ganz saubere Zeilen zaehlen: "JINXED MURENA" in Zierschrift las die arabische Erkennung als
                // "» د كقوقا" -- Zeichensalat, kein arabischer Name.
                boolean sonstNichts = !hatBuchstaben(ergebnis);
                List<String> sauber = v.stream().filter(z -> unsauber(z) == 0 && echteFremdeZeile(z, sonstNichts)).toList();
                double w = fremdeSchriftZeichen(sauber) + (hatKanaOderHangul(sauber) ? 0.5 : 0);
                if (w > besteWert) {
                    besteWert = w;
                    beste = sauber;
                }
                // Autor 24.10. (Ideenliste C03): eine klar saubere Lesung (mind. 3 Zeichen der eigenen Schrift) reicht.
                if (besteWert >= 3) break;
            }
            if (beste != null) {
                List<String> fremd = new ArrayList<>();
                for (String z : beste) if (fremdeSchriftZeichen(List.of(z)) > 0) fremd.add(ohneLueckenZwischenZeichen(z));
                List<String> neu = new ArrayList<>();
                int f = 0;
                for (String z : ergebnis) neu.add(f < fremd.size() && schwacheZeile(z) ? fremd.get(f++) : z);
                if (f == 0) neu = fremd;   // nichts Schwaches zum Ersetzen: dann ist die fremde Lesung das Ganze
                Log.debug("Text", "In anderer Schrift gelesen: " + ergebnis + " -> " + neu);
                return neu;
            }
        }
        return ergebnis;
    }

    /**
     * Zeile fuer Zeile: lateinische oder kyrillische Lesung? Ohne Texterkennung pruefbar (11.10., gemessen an
     * 129 Namensbildern und 203 Lesungen aus den Protokollen). Gleich viele Zeilen: nach Reihenfolge; sonst je
     * lateinischer Zeile die kyrillische mit dem aehnlichsten Skelett ("gonza" bekam sonst "А рог eUos!").
     */
    public static List<String> waehleZeilen(List<String> lateinisch, List<String> kyrillisch) {
        List<String> gemischt = new ArrayList<>();
        boolean[] benutzt = new boolean[kyrillisch.size()];
        int[] paar = new int[lateinisch.size()];
        int[] urteil = new int[lateinisch.size()];
        boolean umfeldKyrillisch = false, umfeldLateinisch = false;
        for (int i = 0; i < lateinisch.size(); i++) {
            String z = lateinisch.get(i);
            int k = -1;
            if (kyrillisch.size() == lateinisch.size()) {
                k = i;
            } else {
                double beste = 0.5;
                for (int j = 0; j < kyrillisch.size(); j++) {
                    if (benutzt[j]) continue;
                    double a = NamensVergleich.aehnlichkeit(NamensVergleich.skelett(z), NamensVergleich.skelett(kyrillisch.get(j)));
                    if (a >= beste) { beste = a; k = j; }
                }
            }
            paar[i] = k;
            urteil[i] = k >= 0 ? urteil(z, kyrillisch.get(k)) : NEIN;
            if (urteil[i] == JA) benutzt[k] = true;
        }
        // Unklar (beide Lesungen gleich sauber, nur Zwillinge: "ocean"/"осеап", "Human"/"Нитап"): LATEINISCH.
        // Autor, 11.10. (Bild vom Schild): "wieso schreibst du den Namen in kyrillisch, im Spiel steht er in normaler
        // Schrift da." Kyrillisch nur mit echtem Hinweis (Buchstabe ohne lateinischen Zwilling, Kapitaelchen) -- siehe
        // urteil(). Der Clan entscheidet NICHT mit: "Chivas" (lateinisch) steht im Clan "Старая Крепость".
        for (int i = 0; i < lateinisch.size(); i++) {
            String z = lateinisch.get(i);
            boolean kyr = urteil[i] == JA;
            gemischt.add(kyr ? kyrillisch.get(paar[i]) : z);
        }
        return gemischt;
    }

    private static final int NEIN = 0, JA = 1, UNKLAR = -1;

    /** Lateinische Sprachen ausser der ersten (Deutsch), je eine Fassung -- nur die eingerichteten werden gefragt. */
    private static final String[] LATEINISCHE = {"en-US", "tr", "pl", "cs", "hu", "ro", "fr-FR", "es-ES", "pt-PT",
            "it-IT", "nl-NL", "sv-SE", "da", "nb", "fi", "sk", "sl", "hr", "bs", "sr-Latn"};
    private static final String[] KYRILLISCHE = {"bg", "sr-Cyrl", "uk"};

    /** Fremde Zeichen in einer Zeile (ohne den Rand), die nicht ostasiatisch o. ae. ist. */
    private static boolean unsaubereZeile(String z, boolean kyrillisch) {
        if (zeichensalat(z) == 0 || fremdeSchriftZeichen(List.of(z)) > 0) return false;
        return kyrillisch == hatKyrillisch(z);   // ob kyrillisch, hat vorher schon die russische Lesung entschieden
    }

    /**
     * Unsaubere Zeilen (z. B. "$" mitten im Namen) noch einmal mit den anderen Sprachen derselben Schrift lesen.
     * Ersetzt wird nur durch eine Lesung DESSELBEN Namens (Form zu 60 % gleich) mit weniger fremden Zeichen; bei
     * Gleichstand gewinnt, was die meisten Sprachen so gelesen haben. Gemessen 11.10.: "Kara $övalye" -> tr
     * "Kara Şövalye"; saubere Namen ("Irena1804") bleiben, wie das erste Modell sie las -- die Fremdsprachen setzen
     * dort gern falsche Zeichen ("İrena1804", "Kimała").
     */
    private static List<String> verbessern(BufferedImage bild, List<String> zeilen, String[] sprachen, boolean kyrillisch) {
        if (zeilen.stream().noneMatch(z -> unsaubereZeile(z, kyrillisch))) return zeilen;
        List<List<String>> lesungen = new ArrayList<>();
        for (String sp : sprachen) {
            if (!kannSprache(sp)) continue;
            List<String> v = lies(bild, sp);
            if (hatBuchstaben(v)) lesungen.add(v);
        }
        List<String> neu = new ArrayList<>(zeilen);
        for (int i = 0; i < zeilen.size(); i++) {
            String z = zeilen.get(i);
            if (!unsaubereZeile(z, kyrillisch)) continue;
            int u = zeichensalat(z);
            java.util.Map<String, Integer> stimmen = new java.util.LinkedHashMap<>();
            for (List<String> v : lesungen) {
                String k = passendeZeile(z, i, zeilen.size(), v);
                if (k == null || zeichensalat(k) >= u || kyrillisch != hatKyrillisch(k)) continue;
                if (NamensVergleich.aehnlichkeit(NamensVergleich.form(z), NamensVergleich.form(k)) < 0.6) continue;
                stimmen.merge(k, 1, Integer::sum);
            }
            String beste = null;
            for (var e : stimmen.entrySet()) {
                if (beste == null) { beste = e.getKey(); continue; }
                // Sauberste zuerst; dann die, die am wenigsten an der ersten Lesung aendert ("Kara Şövalye" statt
                // "Kara Șóvalye" -- nur das "$" war falsch, nicht das "ö"); dann die haeufigste.
                int ub = zeichensalat(beste), ue = zeichensalat(e.getKey());
                double ab = NamensVergleich.aehnlichkeit(z, beste), ae = NamensVergleich.aehnlichkeit(z, e.getKey());
                if (ue < ub || (ue == ub && (ae > ab || (ae == ab && e.getValue() > stimmen.get(beste))))) beste = e.getKey();
            }
            if (beste != null) {
                Log.debug("Text", "Mit anderer Sprache sauberer gelesen: \"" + z + "\" -> \"" + beste + "\"");
                neu.set(i, beste);
            }
        }
        return neu;
    }

    /** Die Zeile einer anderen Lesung, die zur Zeile i passt: gleiche Stelle, sonst das aehnlichste Skelett. */
    private static String passendeZeile(String z, int i, int anzahl, List<String> v) {
        if (v.size() == anzahl) return v.get(i);
        String beste = null;
        double b = 0.5;
        for (String k : v) {
            double a = NamensVergleich.aehnlichkeit(NamensVergleich.skelett(z), NamensVergleich.skelett(k));
            if (a >= b) { b = a; beste = k; }
        }
        return beste;
    }

    /** Fuer die Arena: hat die Lesung keinen Buchstaben, oder ist jede Zeile schwach (Zeichensalat, hoechstens 4 Buchstaben)? */
    public static boolean schwacheLesung(List<String> zeilen) {
        return !hatBuchstaben(zeilen) || zeilen.stream().allMatch(Texterkennung::schwacheZeile);
    }

    /** Buchstaben insgesamt, abzueglich fremder Zeichen -- zum Vergleich zweier Lesungen desselben Bildes. */
    public static int lesungsWert(List<String> zeilen) {
        int n = 0;
        for (String z : zeilen) n += (int) z.chars().filter(Character::isLetter).count() - 2 * unsauber(ohneRand(z));
        return n;
    }

    /**
     * Ist die kyrillische Lesung dieser Zeile die richtige?
     *   - Ohne kyrillischen Buchstaben nie: das russische Modell liest lateinische Namen schlechter
     *     ("Dark kitter", "FUMSY", "1cegammer"), das lateinische dann die bessere Wahl.
     *   - Nie, wenn sie Schriften in einer Zeile mischt und die lateinische sauber ist ("king ВоЬ" fuer "King Bob").
     *   - Nie, wenn sie mehr fremde Zeichen hat ("сае(е" fuer "caete").
     *   - Weniger fremde Zeichen: ja ("ЕвЖеНиЙ1" statt "EB>KeHhV1)").
     *   - Sonst, wenn die lateinische fast nur aus Zwillingen besteht ("CH6npAK" -> "Сибиряк", "TOM" -> "Том").
     */
    static boolean kyrillischBesser(String lateinisch, String kyrillisch) {
        return urteil(lateinisch, kyrillisch) == JA;
    }

    private static int urteil(String lateinisch, String kyrillisch) {
        if (kyrillisch == null || kyrillisch.isBlank()) return NEIN;
        if (lateinisch == null || lateinisch.isBlank()) return hatKyrillisch(kyrillisch) ? JA : NEIN;
        if (!hatKyrillisch(kyrillisch)) return NEIN;
        if (mischtSchriften(kyrillisch) && unsauber(lateinisch) == 0) return NEIN;
        // Zeichen am Rand zaehlen nicht ("Время феникс;" -- abgeschnitten, nicht verlesen).
        int uk = unsauber(ohneRand(kyrillisch)), ul = unsauber(ohneRand(lateinisch));
        if (uk > ul) return NEIN;
        if (uk < ul) return JA;
        if (!siehtKyrillischAus(lateinisch)) return NEIN;
        // Beide gleich sauber, die lateinische nur aus Zwillingen: Das russische Modell macht aus lateinischen
        // Namen gern kyrillische Zwillinge ("neon1211" -> "пеоп1211", n->п, m->т, u->и). Echt kyrillisch ist
        // es erst mit einem Buchstaben OHNE lateinischen Zwilling (б, в, г, д, л, н, я ...: "Гуся", "Павел",
        // "Тан") -- oder wenn die lateinische Lesung Kapitaelchen zeigt (kyrillisch klein т/н/в/м/к sieht wie
        // ein grosser Buchstabe aus: "TOM" fuer "том", "TypncT" fuer "Турист").
        return hatEchtKyrillisch(kyrillisch) || kapitaelchen(lateinisch) ? JA : UNKLAR;
    }

    private static final String NUR_KYRILLISCH = "бвгджзйлнфцчшщъыьэюяБГДЖЗИЙЛПФЦЧШЩЪЫЬЭЮЯ";

    private static boolean hatEchtKyrillisch(String z) {
        for (int i = 0; i < z.length(); i++) if (NUR_KYRILLISCH.indexOf(z.charAt(i)) >= 0) return true;
        return false;
    }

    /**
     * Grossbuchstaben mitten im Wort ("TaH", "CTap" fuer "Стар", "TypncT") oder ein ganzes Wort in Grossbuchstaben
     * ("TOM" fuer "том"). Nur bei reinen Zwillings-Zeilen gefragt -- echtes "PrimordialDragon" kommt nie hierher.
     */
    private static boolean kapitaelchen(String z) {
        for (String w : z.split("[^\\p{L}]+")) {
            if (w.length() >= 2 && w.chars().allMatch(Character::isUpperCase)) return true;
            boolean klein = w.chars().anyMatch(Character::isLowerCase);
            for (int i = 1; i < w.length(); i++) {
                if (klein && Character.isUpperCase(w.charAt(i))) return true;
            }
        }
        return false;
    }

    private static String ohneRand(String z) {
        int von = 0, bis = z.length();
        while (von < bis && !Character.isLetterOrDigit(z.charAt(von))) von++;
        while (bis > von && !Character.isLetterOrDigit(z.charAt(bis - 1))) bis--;
        return z.substring(von, bis);
    }

    private static boolean hatKyrillisch(String z) {
        return z.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
    }

    /** Ein lateinisches Wort (ab 3 Buchstaben) UND ein kyrillisches Wort in derselben Zeile? */
    private static boolean mischtSchriften(String z) {
        boolean lat = false, kyr = false;
        for (String w : z.split("\\s+")) {
            long l = w.chars().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN).count();
            long k = w.chars().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC).count();
            if (l >= 3 && k == 0) lat = true;
            if (k >= 1) kyr = true;
        }
        return lat && kyr;
    }

    /** Zeichensalat oder hoechstens 4 Buchstaben in lateinischer/kyrillischer Schrift? */
    private static boolean schwacheZeile(String z) {
        if (fremdeSchriftZeichen(List.of(z)) > 0) return false;
        int buchstaben = (int) z.chars().filter(Character::isLetter).count();
        // 11.10. (Test 18:18): "hoechstens 4 Buchstaben" war zu grob -- "Тан", "Fil", "Not" sind echte Zeilen und
        // bekamen japanischen/griechischen Unsinn ("しを Not ipAan", "Γ γεπ"). Schwach ist nur: nichts, Zeichensalat,
        // oder ein kurzes Stueck mit Ziffern zwischen Buchstaben ("Ё2В" fuer "龙之怒").
        return buchstaben == 0 || unsauber(z) * 2 > buchstaben
                || (buchstaben <= 4 && z.matches(".*\\p{L}\\d+\\p{L}.*"));
    }

    /**
     * Darf diese Zeile einer anderen Schrift eine schwache ersetzen? Mindestens zwei Zeichen ihrer Schrift und
     * fast nur solche (70 %), keine lateinischen Woerter dabei; Griechisch nur, wenn sonst gar nichts gelesen
     * wurde -- griechische Buchstaben sehen lateinischen und kyrillischen zu aehnlich.
     */
    private static boolean echteFremdeZeile(String z, boolean sonstNichts) {
        int fremd = fremdeSchriftZeichen(List.of(z));
        int buchstaben = (int) z.chars().filter(Character::isLetter).count();
        if (fremd < 2 || fremd < buchstaben * 0.7) return false;
        boolean griechisch = z.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.GREEK);
        return !griechisch || sonstNichts;
    }

    private static boolean hatKanaOderHangul(List<String> zeilen) {
        for (String z : zeilen) {
            for (int i = 0; i < z.length(); i++) {
                Character.UnicodeScript sc = Character.UnicodeScript.of(z.charAt(i));
                if (sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA
                        || sc == Character.UnicodeScript.HANGUL) return true;
            }
        }
        return false;
    }

    /** "さ く ら" -> "さくら": die Erkennung setzt Luecken zwischen ostasiatische Zeichen, die im Spiel nicht stehen. */
    static String ohneLueckenZwischenZeichen(String z) {
        return z.replaceAll("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])", "");
    }

    /** Buchstaben, die weder lateinisch noch kyrillisch sind (Han, Kana, Hangul, Arabisch ...). */
    static int fremdeSchriftZeichen(List<String> zeilen) {
        int n = 0;
        for (String z : zeilen) {
            for (int i = 0; i < z.length(); i++) {
                char c = z.charAt(i);
                if (!Character.isLetter(c)) continue;
                Character.UnicodeScript sc = Character.UnicodeScript.of(c);
                if (sc == Character.UnicodeScript.LATIN || sc == Character.UnicodeScript.CYRILLIC) continue;
                // Griechische Zwillinge zaehlen nicht: "том" kam sonst als "ΤΟΜ" heraus, "MPNo.1" als "ΜΡΝο.1".
                if (sc == Character.UnicodeScript.GREEK && GRIECHISCHE_ZWILLINGE.indexOf(c) >= 0) continue;
                n++;
            }
        }
        return n;
    }

    private static final String GRIECHISCHE_ZWILLINGE = "ΑΒΕΖΗΙΚΜΝΟΡΤΥΧαικνορτυχ";

    /**
     * Fremde Zeichen fuer den Sprachen-Vergleich: alles ausser Buchstaben, Ziffern, Leerzeichen, - _ . '
     * (ohne den Rand). Anders als unsauber() zaehlen ö, ä, 6, 0 hier NICHT -- "Jörmungandr" ist richtig gelesen
     * und bekam sonst von der polnischen Erkennung ein "ô" und ein "ą".
     */
    private static int zeichensalat(String z) {
        int n = 0;
        for (char c : ohneRand(z).toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != ' ' && c != '-' && c != '_' && c != '.' && c != '\'') n++;
        }
        return n;
    }

    /**
     * Lateinische Buchstaben, die ein kyrillisches Zwillingszeichen haben (A B C E H K M O P
     * T X Y, a c e o p x y) oder die das lateinische Modell fuer kyrillische Buchstaben
     * hinschreibt (n fuer п, u fuer и, b fuer ь, g fuer д, q fuer я).
     */
    private static final String ZWILLINGE = "ABCEHKMOPTXYaceopxynubgqr";   // r fuer г ("rycn" = "Гуся", 11.10.)

    private static boolean schonDabei(List<String> zeilen, String z) {
        for (String l : zeilen) if (l.equalsIgnoreCase(z)) return true;
        return false;
    }

    /** Besteht die Zeile fast nur aus Zwillingszeichen? Dann ist sie wohl kyrillisch. */
    public static boolean siehtKyrillischAus(String zeile) {
        int buchstaben = 0, zwillinge = 0, seltsam = 0;
        for (int i = 0; i < zeile.length(); i++) {
            char c = zeile.charAt(i);
            if (SELTSAM.indexOf(c) >= 0) { seltsam++; continue; }
            if (!Character.isLetter(c)) continue;
            buchstaben++;
            if (ZWILLINGE.indexOf(c) >= 0) zwillinge++;
        }
        if (buchstaben >= 3 && zwillinge >= buchstaben * 0.8) return true;
        // 08.10. (Autor: "dann muesste er doch Сибиряк erkennen"): das lateinische Modell schrieb
        // "C\"6oep9K" -- б als 6, я als 9, и als " oder oe. Ziffern und Zeichen MITTEN zwischen Zwillings-
        // buchstaben sind darum auch ein Zeichen fuer Kyrillisch. Die kyrillische Lesung ersetzt die
        // lateinische dann nur, wenn sie sauberer ist (siehe sauberer()).
        return zwillinge >= 2 && seltsam >= 1 && zwillinge + seltsam >= (buchstaben + seltsam) * 0.8;
    }

    /** Ziffern und Zeichen, die das lateinische Modell fuer kyrillische Buchstaben hinschreibt. */
    private static final String SELTSAM = "34690\"'øØöüäëÿ»«|";

    /** Weniger Ziffern/Zeichen, mehr Buchstaben -- die kyrillische Lesung ist die bessere. */
    static boolean sauberer(String kyrillisch, String lateinisch) {
        return unsauber(kyrillisch) < unsauber(lateinisch)
                || (unsauber(kyrillisch) == unsauber(lateinisch) && kyrillisch.chars().anyMatch(ch ->
                        Character.UnicodeScript.of(ch) == Character.UnicodeScript.CYRILLIC));
    }

    static int unsauber(String z) {
        int n = 0;
        for (int i = 0; i < z.length(); i++) {
            char c = z.charAt(i);
            if (c != ' ' && (SELTSAM.indexOf(c) >= 0 || !Character.isLetterOrDigit(c))) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    //  Welche Sprachen Windows lesen kann
    // ------------------------------------------------------------------

    /** Sprachkuerzel der in Windows eingerichteten Texterkennungen, einmal abgefragt. */
    private static volatile List<String> verfuegbar;

    /** Weitere Schriften, in dieser Reihenfolge -- nur die eingerichteten werden probiert. */
    private static final String[] WEITERE = {"zh-Hans", "zh-Hant", "zh", "ja", "ko", "ar", "el", "sr-Cyrl", "bg", "uk", "th", "he", "vi", "tr"};

    public static synchronized List<String> verfuegbareSprachen() {
        if (verfuegbar != null) return verfuegbar;
        List<String> liste = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null; "
                    + "[Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag }");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Zeitgrenze: haengt PowerShell, wuerde das Lesen unten nie enden -- und mit ihm
            // jede Namenslesung, die auf diese Methode wartet (Review 01.10.).
            Thread waechter = new Thread(() -> {
                try {
                    if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException ignored) {
                    p.destroyForcibly();
                }
            }, "ocr-sprachen");
            waechter.setDaemon(true);
            waechter.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String zeile;
                while ((zeile = r.readLine()) != null) {
                    String t = zeile.trim();
                    if (t.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]+)*")) liste.add(t);
                }
            }
            p.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.debug("Text", "Sprachen der Texterkennung nicht abfragbar: " + e.getMessage());
        }
        Log.info("Text", Sprache.t("Text recognition set up for: " + (liste.isEmpty() ? "(unknown)" : String.join(", ", liste)),
                "Texterkennung eingerichtet fuer: " + (liste.isEmpty() ? "(unbekannt)" : String.join(", ", liste))));
        verfuegbar = liste;
        return liste;
    }

    /** Ist fuer dieses Kuerzel (oder eine Spielart davon, "ru" fuer "ru-RU") eine Erkennung da? */
    public static boolean kannSprache(String kuerzel) {
        for (String t : verfuegbareSprachen()) {
            if (t.equalsIgnoreCase(kuerzel) || t.toLowerCase().startsWith(kuerzel.toLowerCase() + "-")) return true;
        }
        // Nichts abfragbar: wie bisher einfach probieren (ru war bei Autor immer da).
        return verfuegbareSprachen().isEmpty() && kuerzel.equals("ru");
    }

    /** Die eingerichteten weiteren Schriften (ohne lateinisch und russisch), als Sprachkuerzel. */
    /**
     * Autor 24.10. (Ideenliste C06): welche Sprachen passen zu den Zeichen, die schon gelesen wurden? Han -> chinesisch
     * + japanisch, Kana -> japanisch, Hangul -> koreanisch, Arabisch, Griechisch, Thai, Hebraeisch. Leer = kein Hinweis.
     */
    static java.util.Set<String> schriftHinweis(List<String> a, List<String> b) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (List<String> l : List.of(a == null ? List.<String>of() : a, b == null ? List.<String>of() : b)) {
            for (String z : l) {
                z.codePoints().forEach(c -> {
                    Character.UnicodeScript sc;
                    try { sc = Character.UnicodeScript.of(c); } catch (IllegalArgumentException e) { return; }
                    switch (sc) {
                        case HAN -> s.addAll(List.of("zh-Hans", "zh-Hant", "zh", "ja"));
                        case HIRAGANA, KATAKANA -> s.add("ja");
                        case HANGUL -> s.add("ko");
                        case ARABIC -> s.add("ar");
                        case GREEK -> s.add("el");
                        case THAI -> s.add("th");
                        case HEBREW -> s.add("he");
                        default -> { }
                    }
                });
            }
        }
        return passendeTags(s, weitereSchriften());
    }

    /**
     * Code-Review 26.10.: installiert sind volle Tags ("zh-Hans-CN", "ar-SA"), der Hinweis nennt Kurzformen ("zh-Hans",
     * "ar") -- der genaue Vergleich warf Chinesisch und Arabisch heraus, uebrig blieb nur "ja". Jetzt per Anfang.
     */
    static java.util.Set<String> passendeTags(java.util.Set<String> hinweise, List<String> installiert) {
        java.util.Set<String> aus = new java.util.LinkedHashSet<>();
        for (String h : hinweise) {
            for (String tag : installiert) {
                if (tag.equalsIgnoreCase(h) || tag.toLowerCase(java.util.Locale.ROOT).startsWith(h.toLowerCase(java.util.Locale.ROOT) + "-")) aus.add(tag);
            }
        }
        return aus;
    }

    static List<String> weitereSchriften() {
        List<String> aus = new ArrayList<>();
        for (String w : WEITERE) {
            for (String t : verfuegbareSprachen()) {
                if ((t.equalsIgnoreCase(w) || t.toLowerCase().startsWith(w.toLowerCase() + "-"))
                        && !t.toLowerCase().startsWith("ru") && !aus.contains(t)) {
                    aus.add(t);
                    break;
                }
            }
        }
        return aus;
    }

    /** Steht in diesen Zeilen ueberhaupt ein Buchstabe? */
    private static boolean hatBuchstaben(List<String> zeilen) {
        for (String z : zeilen) {
            for (int i = 0; i < z.length(); i++) {
                if (Character.isLetter(z.charAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * Liest ein Bild in einer bestimmten Sprache.
     *
     * @param sprache leer fuer die erste in Windows eingerichtete Sprache,
     *                sonst ein Kuerzel wie "ru"
     */
    public static List<String> lies(BufferedImage bild, String sprache) {
        List<String> zeilen = new ArrayList<>();
        if (bild == null) return zeilen;

        // Autor 25.10.: "PaddleOCR komplett ersetzen -- nur wenn es nicht geht, als Backup die Windows-Texterkennung."
        // Gemessen an 112 strittigen Arena-Namen: Windows 15 % genau richtig, PaddleOCR 28 %, beide gemischt 33 % --
        // PaddleOCR, wenn es sich sicher ist; sonst Windows, wenn Windows etwas liest; sonst PaddleOCR.
        List<String> paddle = null;
        if (PaddleLeser.an() && PaddleLeser.verfuegbar()) {
            List<PaddleLeser.Lesung> p = PaddleLeser.lesungen(bild, sprache);
            if (PaddleLeser.sicher(p)) return new ArrayList<>(p.stream().map(PaddleLeser.Lesung::text).toList());
            if (!p.isEmpty()) paddle = new ArrayList<>(p.stream().map(PaddleLeser.Lesung::text).toList());
        }
        List<String> windows = liesWindows(bild, sprache);
        if (windows.stream().anyMatch(z -> z != null && !z.isBlank()) || paddle == null) return windows;
        return paddle;
    }

    /** Die Windows-Texterkennung (bis 25.10. die einzige). */
    private static List<String> liesWindows(BufferedImage bild, String sprache) {
        List<String> zeilen = new ArrayList<>();

        if (!istDa()) {
            Log.warn("Text", Sprache.t("The script texterkennung.ps1 is missing in the bot folder.",
                    "Das Skript texterkennung.ps1 fehlt im Bot-Ordner."));
            return zeilen;
        }

        Path temp = null;
        Path fehler = null;
        try {
            temp = Files.createTempFile("hustle-chat-", ".png");
            ImageIO.write(bild, "png", temp.toFile());

            // ERST DAS OFFENE POWERSHELL FRAGEN.
            //
            // Es spart den Start -- gemessen 160 der 335 Millisekunden.
            // Kommt null zurueck, ist es gerade nicht zu gebrauchen; dann
            // geht es unten weiter wie immer, mit einem eigenen Vorgang.
            // Langsamer als frueher kann es dadurch nie werden.
            List<String> schnell = DauerLeser.lies(temp, sprache);
            if (schnell != null) {
                Log.debug("Text", schnell.size() + " Zeilen erkannt (offene Erkennung).");
                return schnell;
            }

            // -ExecutionPolicy Bypass, weil PowerShell-Skripte auf diesem
            // Rechner gesperrt sind. Gilt nur fuer diesen einen Aufruf, an
            // den Einstellungen aendert sich nichts.
            skriptSicherstellen();
            List<String> befehl = new ArrayList<>(List.of(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", SKRIPT.toAbsolutePath().toString(),
                    "-Bild", temp.toAbsolutePath().toString()));
            if (sprache != null && !sprache.isBlank()) {
                befehl.add("-Sprache");
                befehl.add(sprache);
            }
            ProcessBuilder pb = new ProcessBuilder(befehl);
            // Die Fehlerausgabe wird in eine Datei geleitet, nicht in ein
            // Rohr, das niemand leert.
            //
            // Vorher stand hier redirectErrorStream(false), und die
            // Fehlerausgabe wurde nie gelesen. Schreibt PowerShell dort viel
            // hinein -- etwa wenn die Texterkennung fehlt und mehrere
            // Fehlerbloecke kommen --, laeuft der Puffer voll, PowerShell
            // blockiert beim Schreiben, und Java wartet derweil auf die
            // normale Ausgabe. Beide warten aufeinander, bis nach vierzig
            // Sekunden die Zeitgrenze greift.
            //
            // Zusammenlegen waere die Alternative gewesen, haette aber die
            // Fehlertexte mitten zwischen die erkannten Chatzeilen gesetzt.
            fehler = Files.createTempFile("hustle-ocr-", ".txt");
            pb.redirectError(fehler.toFile());
            Process p = pb.start();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String zeile;
                while ((zeile = r.readLine()) != null) {
                    String sauber = zeile.trim();
                    if (!sauber.isEmpty()) zeilen.add(sauber);
                }
            }

            if (!p.waitFor(ZEITGRENZE_SEKUNDEN, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.warn("Text", Sprache.t("Text recognition took too long.", "Die Texterkennung hat zu lange gebraucht."));
            } else if (p.exitValue() != 0) {
                Log.warn("Text", Sprache.t("Text recognition reported an error (code " + p.exitValue() + "). " + fehlertext(fehler),
                        "Die Texterkennung meldete einen Fehler (Code "
                        + p.exitValue() + "). " + fehlertext(fehler)));
            }

            Log.debug("Text", zeilen.size() + " Zeilen erkannt.");

        } catch (Exception e) {
            Log.warn("Text", Sprache.t("Text recognition failed: " + e.getMessage(),
                    "Texterkennung fehlgeschlagen: " + e.getMessage()));
        } finally {
            loesche(temp);
            loesche(fehler);
        }
        return zeilen;
    }

    /** Die erste Zeile der Fehlerausgabe, fuer das Protokoll. */
    private static String fehlertext(Path datei) {
        if (datei == null) return "";
        try {
            for (String z : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                if (!z.isBlank()) return z.trim();
            }
        } catch (Exception ignored) {
            // Ohne Zusatztext ist die Meldung immer noch brauchbar.
        }
        return "";
    }

    private static void loesche(Path datei) {
        if (datei == null) return;
        try {
            Files.deleteIfExists(datei);
        } catch (Exception ignored) {
            // Eine liegengebliebene Datei im Temp-Ordner ist kein Grund,
            // irgendetwas abzubrechen.
        }
    }
}
