package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Autor 28.10.: Die Arena-Listen sollen sich wie normale Ordner verhalten.
 *
 * 1. Der ORDNER entscheidet: zieht Autor ar_sicher_X.png von "safe" nach "never", heisst es beim naechsten Start
 *    ar_block_X.png und zaehlt als niemals. Vorher entschied nur die Vorsilbe -- das Bild zaehlte weiter als sicher.
 * 2. Bilder, die der Bot von selbst eintraegt, heissen ar_(liste)_(Name)_auto_MMdd-HHmm.png (autoDateiname).
 * 3. Loescht Autor so ein _auto-Bild, merkt sich der Bot das in arena-entfernt.txt ("Name | Liste | Datum"), damit er
 *    den Spieler nicht von selbst wieder eintraegt (vonAutorEntfernt). Dazu steht in arena-auto-bilder.txt, welche
 *    _auto-Bilder es beim letzten Start gab (und welche der Bot seitdem dazugelegt hat, autoBildGemerkt).
 *
 * Geloescht wird nie etwas: gibt es den neuen Namen schon, kommt das verschobene Bild nach templates-aussortiert.
 * Bilder in "clans", "names" oder eigenen Ordnern bleiben, wie sie sind.
 */
public final class ArenaListenOrdner {

    /** Spieler, deren _auto-Bild Autor geloescht hat -- im Bot-Ordner. */
    public static final Path ENTFERNT = Path.of("arena-entfernt.txt");
    /** Welche _auto-Bilder es beim letzten Start gab (ein Dateiname je Zeile) -- im Bot-Ordner. */
    static final Path MERKER = Path.of("arena-auto-bilder.txt");

    private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("MMdd-HHmm");
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ArenaListenOrdner() { }

    /**
     * Ergebnis von ordnerEntscheidet: die alten Dateinamen (klein; umbenannt oder aussortiert), die neuen Dateien
     * (absolut) und wie viele je Art.
     */
    record Umbenennung(Set<String> alteNamen, Set<Path> neueDateien, int umbenannt, int aussortiert) {
        /** Hat Autor dieses Bild gerade in eine andere Liste verschoben? */
        boolean neu(Path p) {
            return neueDateien.contains(p.toAbsolutePath().normalize());
        }
    }

    // ---- 2. Kennzeichen "_auto" ---------------------------------------------------------------------------------

    /**
     * Dateiname fuer ein Bild, das der Bot von selbst in eine Liste eintraegt:
     * ("ar_sicher_", "Denis", 28.10. 14:30) -> "ar_sicher_Denis_auto_1028-1430.png".
     * Die Liste darf auch "sicher" oder der Ordnername ("safe") sein. Im Namen bleiben nur Buchstaben und Ziffern
     * (wie bisher in ArenaMode, auch kyrillisch), leer -> "gegner".
     */
    public static String autoDateiname(String liste, String name, LocalDateTime wann) {
        String l = vorsilbe(liste);
        if (l == null) throw new IllegalArgumentException("Keine Arena-Liste: " + liste);
        String kurz = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N}]", "");
        if (kurz.isEmpty()) kurz = "gegner";
        return l + kurz + "_auto_" + (wann == null ? LocalDateTime.now() : wann).format(STEMPEL) + ".png";
    }

    /** Ein Listen-Bild, das der Bot von selbst eingetragen hat ("ar_..._Name_auto_MMdd-HHmm.png")? */
    public static boolean istAuto(String dateiname) {
        return dateiname != null && ArenaVorlagenDoppelt.liste(dateiname) != null && ArenaVorlagenDoppelt.autoEndung(dateiname);
    }

    /** "ar_sicher_", "sicher" oder "safe" -> "ar_sicher_"; null, wenn es keine Arena-Liste ist. */
    static String vorsilbe(String liste) {
        if (liste == null || liste.isBlank()) return null;
        for (String l : ArenaVorlagenDoppelt.LISTEN) {
            if (l.equals(liste) || l.equals("ar_" + liste + "_")) return l;
        }
        return listeFuerOrdner(liste);
    }

    /** Ordnername unter "Arena lists" ("safe", "doable", "avoid", "never") -> Vorsilbe; sonst null (clans, names ...). */
    static String listeFuerOrdner(String ordner) {
        if (ordner == null) return null;
        for (String l : ArenaVorlagenDoppelt.LISTEN) {
            if (ordner.equalsIgnoreCase(Eigenbilder.listenUnterordner(l))) return l;
        }
        return null;
    }

    // ---- 1. Der Ordner entscheidet ------------------------------------------------------------------------------

    /**
     * Beim Start: jedes Listen-Bild in "My pictures\Arena lists\safe|doable|avoid|never", dessen Vorsilbe nicht zum
     * Ordner passt, bekommt die Vorsilbe des Ordners (der Rest des Namens bleibt). Gibt es den neuen Namen schon, kommt
     * das verschobene Bild nach templates-aussortiert. Das umbenannte Bild bekommt die aktuelle Zeit, damit beim
     * Aussortieren doppelter Spieler Autors Verschieben als neueste Entscheidung gilt.
     */
    static Umbenennung ordnerEntscheidet(Path botOrdner) {
        Set<String> alteNamen = new HashSet<>();
        Set<Path> neueDateien = new HashSet<>();
        int umbenannt = 0, aussortiert = 0;
        Path wurzel = Eigenbilder.listenOrdner(botOrdner, null);
        if (!Files.isDirectory(wurzel)) return new Umbenennung(alteNamen, neueDateien, 0, 0);
        List<Path> bilder = new ArrayList<>();
        try (Stream<Path> s = Files.walk(wurzel)) {
            s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).forEach(bilder::add);
        } catch (Exception e) {
            Log.debug("Vorlagen", "Arena lists nicht lesbar: " + e.getMessage());
            return new Umbenennung(alteNamen, neueDateien, 0, 0);
        }
        for (Path p : bilder) {
            String datei = p.getFileName().toString();
            String alt = ArenaVorlagenDoppelt.liste(datei);
            if (alt == null) continue;
            Path relativ = wurzel.relativize(p);
            if (relativ.getNameCount() < 2) continue;                  // liegt direkt in "Arena lists"
            String neu = listeFuerOrdner(relativ.getName(0).toString());
            if (neu == null || neu.equals(alt)) continue;              // clans, names, eigener Ordner -- oder passt schon
            String von = Eigenbilder.listenUnterordner(alt), nach = Eigenbilder.listenUnterordner(neu);
            // Review 28.10.: ein Bild, das Autor in eine andere Liste schiebt, ist ab jetzt SEINE Entscheidung -- das
            // Bot-Kennzeichen ("_auto_MMdd-HHmm" oder "_MMdd-HHmm") faellt ganz weg, sonst duerfte der Bot es spaeter ersetzen.
            String neuerName = neu + datei.substring(alt.length()).replaceFirst("(?i)(_auto)?_\\d{4}-\\d{4,6}(\\.png)$", "$2");
            Path ziel = p.resolveSibling(neuerName);
            try {
                if (Files.exists(ziel)) {
                    Path weg = ArenaVorlagenDoppelt.aussortiertZiel(botOrdner, p);
                    Files.createDirectories(weg.getParent());
                    Files.move(p, weg, StandardCopyOption.REPLACE_EXISTING);
                    aussortiert++;
                    alteNamen.add(datei.toLowerCase(Locale.ROOT));
                    Log.info("Vorlagen", Sprache.t("Arena lists: " + datei + " was moved by you from " + von + " to " + nach
                                    + ", but " + neuerName + " is already there -- this one went to templates-aussortiert.",
                            "Arena-Listen: " + datei + " hast du von " + von + " nach " + nach + " verschoben, aber "
                                    + neuerName + " liegt dort schon -- dieses kam nach templates-aussortiert."));
                } else {
                    Files.move(p, ziel);
                    try {
                        Files.setLastModifiedTime(ziel, FileTime.from(Instant.now()));
                    } catch (Exception e) {
                        Log.debug("Vorlagen", "Zeit nicht gesetzt: " + neuerName + " -- " + e.getMessage());
                    }
                    umbenannt++;
                    alteNamen.add(datei.toLowerCase(Locale.ROOT));
                    neueDateien.add(ziel.toAbsolutePath().normalize());
                    Log.info("Vorlagen", Sprache.t("Arena lists: " + datei + " was moved by you from " + von + " to " + nach
                                    + " -- now counts as " + nach + " (" + neuerName + ").",
                            "Arena-Listen: " + datei + " hast du von " + von + " nach " + nach + " verschoben -- zaehlt jetzt als "
                                    + nach + " (" + neuerName + ")."));
                }
            } catch (Exception e) {
                Log.debug("Vorlagen", "Listen-Bild nicht umbenannt: " + datei + " -- " + e.getMessage());
            }
        }
        return new Umbenennung(alteNamen, neueDateien, umbenannt, aussortiert);
    }

    // ---- 3. "Autor hat es geloescht" ----------------------------------------------------------------------------

    /**
     * Beim Start, nach dem Aufraeumen: jedes _auto-Bild aus arena-auto-bilder.txt, das es jetzt nicht mehr gibt, hat
     * Autor geloescht -> Zeile in arena-entfernt.txt. Nicht gezaehlt wird, was nur umbenannt/verschoben wurde (gleicher
     * Name hinter der Vorsilbe), was der Bot aussortiert hat (liegt in templates-aussortiert) oder was beim Kontowechsel
     * in den Konto-Ordner kam. Danach steht in arena-auto-bilder.txt der jetzige Stand.
     * @param bilder alle Listen-Bilder jetzt (ArenaVorlagenDoppelt.listenBilder)
     * @param umbenannt alte Dateinamen, die ordnerEntscheidet gerade umbenannt oder aussortiert hat (klein geschrieben)
     * @return die Namen, die neu in arena-entfernt.txt stehen
     */
    static synchronized List<String> geloeschteMerken(Path botOrdner, List<Path> bilder, Set<String> umbenannt) {
        List<String> neu = new ArrayList<>();
        // Ohne Listen-Ordner (z. B. nicht lesbar) nichts vergleichen -- sonst saehe alles "geloescht" aus.
        if (!Files.isDirectory(Eigenbilder.listenOrdner(botOrdner, null))) return neu;
        Path merker = botOrdner.resolve(MERKER), entfernt = botOrdner.resolve(ENTFERNT);
        Set<String> jetzt = new TreeSet<>(), jetztKlein = new HashSet<>(), restJetzt = new HashSet<>();
        for (Path p : bilder) {
            String d = p.getFileName().toString();
            if (istAuto(d)) {
                jetzt.add(d);
                jetztKlein.add(d.toLowerCase(Locale.ROOT));
            }
            String r = rest(d);
            if (r != null) restJetzt.add(r);
        }
        List<String> vorher = Files.exists(merker) ? zeilen(merker) : null;
        if (vorher != null) {
            Set<String> anderswo = null;
            for (String d : vorher) {
                if (!istAuto(d)) continue;
                String klein = d.toLowerCase(Locale.ROOT);
                if (jetztKlein.contains(klein) || (umbenannt != null && umbenannt.contains(klein))) continue;
                if (restJetzt.contains(rest(d))) continue;                     // nur in eine andere Liste verschoben
                if (anderswo == null) anderswo = pngNamen(botOrdner.resolve("templates-aussortiert"), botOrdner.resolve(Konten.ORDNER));
                if (anderswo.contains(klein)) continue;                        // vom Bot aussortiert / Kontowechsel
                // Review 28.10.: liegt die Datei doch noch in einem Listen-Ordner (Lesefehler beim Durchsuchen), ist sie
                // nicht geloescht -- eine unvollstaendige Liste darf nie als "Autor hat geloescht" gelten.
                if (liegtNochDa(botOrdner, d)) continue;
                String name = ArenaVorlagenDoppelt.nameAusDatei(d);
                String ordner = Eigenbilder.listenUnterordner(ArenaVorlagenDoppelt.liste(d));
                if (name.isBlank() || vonAutorEntfernt(entfernt, name)) continue;
                try {
                    eintragen(entfernt, name + " | " + ordner + " | " + LocalDateTime.now().format(DATUM));
                    neu.add(name);
                    Log.info("Vorlagen", Sprache.t("Arena lists: you deleted " + d + " -- the bot will not put " + name
                                    + " on a list by itself again (arena-entfernt.txt).",
                            "Arena-Listen: " + d + " hast du geloescht -- der Bot traegt " + name
                                    + " nicht mehr von selbst ein (arena-entfernt.txt)."));
                } catch (Exception e) {
                    Log.debug("Vorlagen", "arena-entfernt.txt nicht geschrieben: " + e.getMessage());
                }
            }
        }
        try {
            Gegnerbuch.sicherSchreiben(merker, jetzt.isEmpty() ? "" : String.join("\r\n", jetzt) + "\r\n");
        } catch (Exception e) {
            Log.debug("Vorlagen", "arena-auto-bilder.txt nicht geschrieben: " + e.getMessage());
        }
        return neu;
    }

    /** Liegt diese Datei (Name) in einem der vier Listen-Ordner? */
    private static boolean liegtNochDa(Path botOrdner, String dateiname) {
        for (String l : ArenaVorlagenDoppelt.LISTEN) {
            try {
                if (Files.exists(Eigenbilder.listenOrdner(botOrdner, l).resolve(dateiname))) return true;
            } catch (Exception e) {
                return true;   // im Zweifel: nicht als geloescht werten
            }
        }
        return false;
    }

    /**
     * Review 28.10.: hat Autor ein _auto-Bild dieses Spielers WAEHREND des Laufs geloescht? Der Merker kennt alle
     * _auto-Bilder seit dem letzten Start; fehlt eines davon jetzt in allen Listen-Ordnern, gilt es als geloescht --
     * Zeile in arena-entfernt.txt, und der Bot legt den Spieler nicht wieder von selbst hinein.
     */
    public static boolean geloeschtWaehrendLauf(String name) {
        return geloeschtWaehrendLauf(Ordner.basis(), name);
    }

    static synchronized boolean geloeschtWaehrendLauf(Path bot, String name) {
        if (name == null || name.isBlank()) return false;
        Path merker = bot.resolve(MERKER);
        if (!Files.exists(merker)) return false;
        for (String d : zeilen(merker)) {
            if (!istAuto(d) || !ArenaVorlagenDoppelt.derselbeName(ArenaVorlagenDoppelt.nameAusDatei(d), name)) continue;
            if (liegtNochDa(bot, d)) continue;
            Path entfernt = bot.resolve(ENTFERNT);
            if (!vonAutorEntfernt(entfernt, name)) {
                try {
                    eintragen(entfernt, ArenaVorlagenDoppelt.nameAusDatei(d) + " | "
                            + Eigenbilder.listenUnterordner(ArenaVorlagenDoppelt.liste(d)) + " | " + LocalDateTime.now().format(DATUM));
                } catch (Exception e) {
                    Log.debug("Vorlagen", "arena-entfernt.txt nicht geschrieben: " + e.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    /** Der Bot hat gerade ein _auto-Bild in eine Liste gelegt: fuer den Vergleich beim naechsten Start merken. */
    public static void autoBildGemerkt(Path datei) {
        autoBildGemerkt(Ordner.basis().resolve(MERKER), datei);   // im Bot-Ordner, wie beim Start gelesen
    }

    static synchronized void autoBildGemerkt(Path merker, Path datei) {
        if (datei == null || datei.getFileName() == null) return;
        String d = datei.getFileName().toString();
        if (!istAuto(d)) return;
        try {
            List<String> alle = Files.exists(merker) ? zeilen(merker) : new ArrayList<>();
            if (alle.stream().anyMatch(z -> z.equalsIgnoreCase(d))) return;
            alle.add(d);
            Gegnerbuch.sicherSchreiben(merker, String.join("\r\n", alle) + "\r\n");
        } catch (Exception e) {
            Log.debug("Vorlagen", "arena-auto-bilder.txt nicht ergaenzt: " + e.getMessage());
        }
    }

    /** Hat Autor ein _auto-Bild dieses Spielers geloescht (steht in arena-entfernt.txt)? */
    public static boolean vonAutorEntfernt(String name) {
        return vonAutorEntfernt(Ordner.bei(ENTFERNT.toString()), name);   // wie beim Start: im Bot-Ordner (Review 28.10.)
    }

    static boolean vonAutorEntfernt(Path entfernt, String name) {
        if (name == null || name.isBlank()) return false;
        for (String n : entfernteNamen(entfernt)) {
            if (ArenaVorlagenDoppelt.derselbeName(n, name)) return true;
        }
        return false;
    }

    // Die Datei wird je Gegner gefragt -- nur neu lesen, wenn sie sich geaendert hat.
    private static Path zuletztDatei;
    private static long zuletztZeit = -1, zuletztGroesse = -1;
    private static List<String> zuletztNamen = List.of();

    /** Die Namen aus arena-entfernt.txt (erste Spalte), Zeilen mit "#" sind Erklaerung. */
    static synchronized List<String> entfernteNamen(Path entfernt) {
        long zeit, groesse;
        try {
            zeit = Files.getLastModifiedTime(entfernt).toMillis();
            groesse = Files.size(entfernt);
        } catch (Exception e) {
            return List.of();                                           // keine Datei = niemand entfernt
        }
        if (entfernt.equals(zuletztDatei) && zeit == zuletztZeit && groesse == zuletztGroesse) return zuletztNamen;
        List<String> namen = new ArrayList<>();
        for (String z : zeilen(entfernt)) {
            if (z.startsWith("#")) continue;
            String n = z.split("\\|", 2)[0].strip();
            if (!n.isEmpty()) namen.add(n);
        }
        zuletztDatei = entfernt;
        zuletztZeit = zeit;
        zuletztGroesse = groesse;
        zuletztNamen = List.copyOf(namen);
        return zuletztNamen;
    }

    /** Eine Zeile anhaengen (nie die Datei neu schreiben -- Autors Aenderungen bleiben); neu mit Erklaerung oben. */
    private static void eintragen(Path entfernt, String zeile) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        // Review 28.10.: hat Autor die letzte Zeile samt Zeilenumbruch geloescht, sonst verschmoelzen zwei Eintraege.
        if (Files.exists(entfernt) && Files.size(entfernt) > 0) {
            byte[] alles = Files.readAllBytes(entfernt);
            if (alles[alles.length - 1] != '\n') sb.append("\r\n");
        }
        if (!Files.exists(entfernt)) {
            sb.append("# Arena lists: pictures the bot had added by itself and you deleted. The bot will not put these players\r\n")
              .append("# on a list by itself again. Delete a line to allow it again.\r\n")
              .append("# Arena-Listen: Bilder, die der Bot selbst eingetragen und du geloescht hast. Zeile loeschen = wieder erlaubt.\r\n");
        }
        sb.append(zeile).append("\r\n");
        Files.writeString(entfernt, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Der Dateiname hinter der Vorsilbe (klein): "ar_block_Denis_auto_1028-1430.png" -> "denis_auto_1028-1430.png". */
    private static String rest(String dateiname) {
        String l = ArenaVorlagenDoppelt.liste(dateiname);
        return l == null ? null : dateiname.substring(l.length()).toLowerCase(Locale.ROOT);
    }

    /** Alle .png-Dateinamen (klein) unter diesen Ordnern. */
    private static Set<String> pngNamen(Path... ordner) {
        Set<String> namen = new HashSet<>();
        for (Path o : ordner) {
            if (!Files.isDirectory(o)) continue;
            try (Stream<Path> s = Files.walk(o)) {
                s.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)).filter(n -> n.endsWith(".png")).forEach(namen::add);
            } catch (Exception e) {
                Log.debug("Vorlagen", o.getFileName() + " nicht lesbar: " + e.getMessage());
            }
        }
        return namen;
    }

    /** Zeilen einer Textdatei (UTF-8, kaputte Zeichen werden ersetzt statt abzubrechen), leere weggelassen. */
    private static List<String> zeilen(Path datei) {
        List<String> z = new ArrayList<>();
        try {
            byte[] roh = Files.readAllBytes(datei);
            String text = new String(roh, StandardCharsets.UTF_8);
            // Review 28.10.: im Editor als ANSI gespeichert ("Jörg") -> wie Namensbilder.zeilenLesen windows-1252 nehmen.
            if (text.indexOf('�') >= 0) text = new String(roh, java.nio.charset.Charset.forName("windows-1252"));
            for (String s : text.split("\\R")) {
                String t = s.strip();
                if (t.startsWith("﻿")) t = t.substring(1).strip();
                if (!t.isEmpty()) z.add(t);
            }
        } catch (Exception e) {
            Log.debug("Vorlagen", datei.getFileName() + " nicht lesbar: " + e.getMessage());
        }
        return z;
    }
}
