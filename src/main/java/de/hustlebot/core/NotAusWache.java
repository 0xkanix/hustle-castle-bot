package de.hustlebot.core;

import com.sun.jna.platform.win32.User32;

import java.util.Locale;

/**
 * Der Not-Aus: eine frei waehlbare Taste haelt den Bot ueberall im Rechner
 * sofort an -- auch dann, wenn Discord gerade nicht erreichbar ist.
 *
 * Bewusst NUR Anhalten, nie Starten. Die alten Tasten ^ und Q wirkten im
 * ganzen Rechner und starteten den Bot aus Versehen dutzendfach (12.09.).
 * Ein versehentliches ANHALTEN ist dagegen harmlos: der Bot steht dann eben,
 * und ein Befehl in Discord laesst ihn weiterlaufen.
 *
 * Erlaubt ist inzwischen JEDE Taste (Autor, 19.09.): Funktionstasten, normale
 * Buchstaben wie g, Umlaute wie oe und ue, Ziffern und ein paar Sondertasten.
 * Bei einem Buchstaben ist die Warnung angebracht, dass er ueberall wirkt --
 * auch beim Tippen in einem anderen Programm. Wer das nicht will, nimmt eine
 * Funktionstaste.
 *
 * Die Wache liest die Taste selbst ab (GetAsyncKeyState) und braucht dafuer
 * keinen Fokus. Sie laeuft als eigener, kleiner Faden.
 */
public final class NotAusWache implements Runnable {

    private final int vk;
    private final String tasteName;
    private final Runnable beiNotAus;
    /** Wofuer die Taste ist ("Not-Aus", "Not-Pause", "Weiter") -- fuers Protokoll. */
    private final String zweck;
    private final String beschreibung;

    private volatile boolean laufen = true;
    private boolean warGedrueckt = false;

    private NotAusWache(int vk, String name, String zweck, String beschreibung, Runnable beiNotAus) {
        this.vk = vk;
        this.tasteName = name;
        this.zweck = zweck;
        this.beschreibung = beschreibung;
        this.beiNotAus = beiNotAus;
        // 18.11.: Der Bot tippt jetzt auch Text (Clan-Chat) -- diese Taste dabei nie druecken, sonst haelt er sich selbst an.
        Tastatur.nichtDruecken(vk);
    }

    /**
     * Baut die Wache aus einem Tastennamen wie "F9", "g" oder "ue". Liefert
     * null, wenn keine oder eine unbekannte Taste angegeben ist -- dann gibt
     * es einfach keinen Not-Aus.
     */
    public static NotAusWache aus(String taste, Runnable beiNotAus) {
        return aus(taste, "Not-Aus", Sprache.t("stops the bot everywhere at once", "haelt den Bot ueberall sofort an"), beiNotAus);
    }

    /** Dieselbe Wache fuer andere Zwecke (Not-Pause F10, Weiter F11 -- Autor, 30.09.). */
    public static NotAusWache aus(String taste, String zweck, String beschreibung, Runnable beiTaste) {
        int vk = vkFuer(taste);
        if (vk == 0 || beiTaste == null) return null;
        return new NotAusWache(vk, anzeigeName(taste), zweck, beschreibung, beiTaste);
    }

    public String taste() { return tasteName; }

    public void stoppen() { laufen = false; }

    @Override
    public void run() {
        Log.info(zweck, Sprache.t(zweck + " active: key " + tasteName + " " + beschreibung + ".",
                zweck + " aktiv: Taste " + tasteName + " " + beschreibung + "."));
        while (laufen) {
            try {
                boolean gedrueckt = (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
                if (gedrueckt && !warGedrueckt) {
                    warGedrueckt = true;
                    Log.warn(zweck, Sprache.t("Key " + tasteName + " pressed (" + zweck + ").",
                            "Taste " + tasteName + " gedrueckt (" + zweck + ")."));
                    try {
                        beiNotAus.run();
                    } catch (Exception e) {
                        Log.error(zweck, Sprache.t(zweck + " via the key failed.", zweck + " ueber die Taste fehlgeschlagen."), e);
                    }
                } else if (!gedrueckt) {
                    warGedrueckt = false;
                }
                Thread.sleep(80);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.debug("Not-Aus", "Tastenabfrage: " + e.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * Tastenname -> Windows-Virtual-Key. 0 heisst "keine/ungueltig".
     *
     * Erlaubt: F1-F12, einzelne Buchstaben A-Z, Ziffern 0-9, die Umlaute und
     * ein paar Sondertasten. Die Umlaute haben auf der deutschen Tastatur
     * feste Codes (OEM_1/OEM_3/OEM_7), die nichts mit ihrem Buchstaben zu tun
     * haben -- deshalb stehen sie einzeln da.
     */
    public static int vkFuer(String taste) {
        if (taste == null) return 0;
        String t = taste.trim().toUpperCase(Locale.GERMAN);
        if (t.isEmpty() || t.equals("AUS") || t.equals("OFF") || t.equals("KEINE")
                || t.equals("NEIN") || t.equals("NONE")) {
            return 0;
        }

        // Funktionstasten F1 bis F12.
        if (t.matches("F([1-9]|1[0-2])")) {
            return 0x70 + (Integer.parseInt(t.substring(1)) - 1);   // VK_F1 = 0x70
        }

        // Umlaute und ss -- als Zeichen oder ausgeschrieben.
        switch (t) {
            case "Ü", "UE"  -> { return 0xBA; }   // VK_OEM_1
            case "Ö", "OE"  -> { return 0xC0; }   // VK_OEM_3
            case "Ä", "AE"  -> { return 0xDE; }   // VK_OEM_7
            case "SS"       -> { return 0xDB; }   // VK_OEM_4 (toUpperCase macht aus ß bereits SS)
            case "+"        -> { return 0xBB; }   // VK_OEM_PLUS
            case "-"        -> { return 0xBD; }   // VK_OEM_MINUS
            case ","        -> { return 0xBC; }   // VK_OEM_COMMA
            case "."        -> { return 0xBE; }   // VK_OEM_PERIOD
            case "#"        -> { return 0xBF; }   // VK_OEM_2
            case "<"        -> { return 0xE2; }   // VK_OEM_102
            case "PAUSE", "PAUSA"                 -> { return 0x13; }
            case "END", "ENDE"                    -> { return 0x23; }
            case "ROLLEN", "SCROLL", "SCROLLLOCK" -> { return 0x91; }
            case "EINFG", "INSERT", "INS"         -> { return 0x2D; }
            case "POS1", "HOME"                   -> { return 0x24; }
            case "ENTF", "DELETE", "DEL"          -> { return 0x2E; }
            case "BILDAUF", "PAGEUP"              -> { return 0x21; }
            case "BILDAB", "PAGEDOWN"             -> { return 0x22; }
            default -> { }
        }

        // Ein einzelner Buchstabe oder eine Ziffer.
        if (t.length() == 1) {
            char c = t.charAt(0);
            if (c >= 'A' && c <= 'Z') return c;          // VK_A..VK_Z = 'A'..'Z'
            if (c >= '0' && c <= '9') return c;          // VK_0..VK_9 = '0'..'9'
        }
        return 0;
    }

    /** Ob dieser Tastenname erlaubt ist -- fuer die Rueckmeldung bei !settings. */
    public static boolean gueltig(String taste) {
        return vkFuer(taste) != 0;
    }

    /**
     * Wirkt die Taste auch beim normalen Tippen? Dann lohnt ein Hinweis:
     * ein Buchstabe haelt den Bot auch an, wenn man ihn in einem anderen
     * Programm tippt.
     */
    public static boolean imAlltagGefaehrlich(String taste) {
        int vk = vkFuer(taste);
        if (vk == 0) return false;
        if (vk >= 0x70 && vk <= 0x7B) return false;      // F1-F12 sind sicher
        return vk != 0x13 && vk != 0x91 && vk != 0x21 && vk != 0x22;
    }

    /** Die erlaubten Tasten als Text, fuer Hilfe und Fehlermeldungen. */
    public static String erlaubteTasten() {
        return Sprache.t("F1 to F12, any letter (a-z), any digit, ä ö ü ß, " + "Pause, End, Scroll Lock, Insert, Home, Delete, Page up/down", "F1 bis F12, jeder Buchstabe (a-z), jede Ziffer, ä ö ü ß, "
             + "Pause, Ende, Rollen, Einfg, Pos1, Entf, Bild auf/ab");
    }

    /** Schoene Schreibweise eines Tastennamens ("ue" -> "Ü", "pos1" -> "Pos1"). Auch fuer die Tastenkuerzel (11.10.). */
    public static String anzeigeName(String taste) {
        String t = taste.trim().toUpperCase(Locale.GERMAN);
        return switch (t) {
            case "ENDE"                   -> "Ende";
            case "PAUSA", "PAUSE"         -> "Pause";
            case "ROLLEN", "SCROLL",
                 "SCROLLLOCK"             -> "Rollen";
            case "EINFG", "INSERT", "INS" -> "Einfg";
            case "POS1", "HOME"           -> "Pos1";
            case "ENTF", "DELETE", "DEL"  -> "Entf";
            case "BILDAUF", "PAGEUP"      -> "Bild auf";
            case "BILDAB", "PAGEDOWN"     -> "Bild ab";
            case "UE"                     -> "Ü";
            case "OE"                     -> "Ö";
            case "AE"                     -> "Ä";
            case "SS"                     -> "ß";
            default                       -> t;
        };
    }
}
