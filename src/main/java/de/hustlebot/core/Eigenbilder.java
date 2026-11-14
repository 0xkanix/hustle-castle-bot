package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Autor 24.10.: Alles, was der Spieler selbst aufnimmt (Capture-Images) und was der Bot in der Arena selbst an Gegnern
 * aufnimmt, liegt sichtbar im Ordner "My pictures" -- getrennt von Autors mitgelieferten Bildern in templates\, die
 * versteckt bleiben. Der Bot laedt beide Ordner (Matcher); bei gleichem Namen gilt die neuere Aufnahme.
 *
 * Aufbau:
 *   My pictures\Arena lists\safe | doable | avoid | never | clans | names   (ar_sicher_ ... ar_name_)
 *   My pictures\Arena\...            weitere Arena-Bilder (Vorlagenordner)
 *   My pictures\Event rewards\       ev_kiste_... (eigene Event-Belohnungen)
 *   My pictures\Cathedral\, Clan war\, Dungeon\
 */
public final class Eigenbilder {
    public static final String ORDNER = "My pictures";
    public static final String LISTEN = "Arena lists";

    private Eigenbilder() {
    }

    public static Path wurzel(Path botOrdner) {
        return botOrdner.resolve(ORDNER);
    }

    /** Der Ordner im laufenden Bot (Bot-Ordner aus Ordner.basis()). */
    public static Path wurzel() {
        return wurzel(Ordner.basis());
    }

    /** Arena-Liste (Praefix ar_sicher_ ...) -> Unterordner unter "Arena lists"; null, wenn es keine Liste ist. */
    static String listenUnterordner(String name) {
        if (name.startsWith("ar_sicher_")) return "safe";
        if (name.startsWith("ar_machbar_")) return "doable";
        if (name.startsWith("ar_fastsicher_")) return "almost-safe";
        if (name.startsWith("ar_pause_")) return "pause";
        if (name.startsWith("ar_ungern_")) return "avoid";
        if (name.startsWith("ar_block_")) return "never";
        if (name.startsWith("ar_clan_")) return "clans";
        if (name.startsWith("ar_name_")) return "names";
        return null;
    }

    /** Wohin ein selbst aufgenommenes Bild (Vorlagenname ohne .png) im Bot-Ordner gehoert. */
    public static Path ordnerFuer(Path botOrdner, String name) {
        Path w = wurzel(botOrdner);
        String liste = listenUnterordner(name);
        if (liste != null) return w.resolve(LISTEN).resolve(liste);
        if (name.startsWith("ev_kiste_")) return w.resolve("Event rewards");
        if (name.startsWith("ar_")) {
            String unter = Vorlagenordner.fuer(name);   // z. B. arena/knoepfe
            if (unter.startsWith("arena/")) unter = unter.substring("arena/".length());
            return w.resolve("Arena").resolve(unter);
        }
        if (name.startsWith("cw_")) return w.resolve("Clan war");
        if (name.startsWith("dc_") || name.startsWith("key_")) return w.resolve("Cathedral");
        return w.resolve("Dungeon");
    }

    public static Path ordnerFuer(String name) {
        return ordnerFuer(Ordner.basis(), name);
    }

    /** Der Ordner einer Arena-Liste ("ar_sicher_" ...) bzw. "Arena lists" selbst (liste null oder leer). */
    public static Path listenOrdner(Path botOrdner, String liste) {
        Path l = wurzel(botOrdner).resolve(LISTEN);
        if (liste == null || liste.isEmpty()) return l;
        String u = listenUnterordner(liste);
        return u == null ? l : l.resolve(u);
    }

    /**
     * Beim Start, vor dem Laden der Vorlagen: Ordner anlegen (mit Erklaerung) und die Arena-Listen aus
     * templates\arena\gegner hierher ziehen (dort lagen bis 3.87 die eigenen Listen; Autor liefert sie leer aus).
     * Auch die 3.87-Verknuepfung "Arena lists"/"Arena-Listen" im Bot-Ordner entfernen (nur die Verknuepfung).
     * @return wie viele Bilder umgezogen sind
     */
    public static int einrichten(Path botOrdner) {
        Path w = wurzel(botOrdner);
        int umgezogen = 0;
        try {
            for (String u : new String[]{"safe", "almost-safe", "doable", "avoid", "pause", "never", "clans"}) Files.createDirectories(w.resolve(LISTEN).resolve(u));
            Path erklaerung = w.resolve("README.txt");
            if (!Files.exists(erklaerung)) {
                Files.writeString(erklaerung, String.join("\r\n",
                        "My pictures -- everything YOU (or the bot for you) captured",
                        "===========================================================",
                        "Arena lists\\  your arena opponents (a picture of the NAME only -- no crest, no clan, no frame):",
                        "  safe   = sure win, preferred       doable = can be beaten",
                        "  avoid  = rather not attack          never  = NEVER attack        clans = clan names",
                        "The bot adds opponents here by itself, too. To take someone off a list, delete his picture.",
                        "Everything else here comes from Capture-Images.exe (buttons, event rewards ...).",
                        "Restart the bot after a change.",
                        "",
                        "Meine Bilder -- alles, was DU (oder der Bot fuer dich) aufgenommen hast:",
                        "Arena lists\\: safe = sicher, doable = machbar, avoid = ungern, never = niemals (blockieren), clans = Clans.",
                        "Der Bot traegt hier auch selbst Gegner ein. Spieler von einer Liste nehmen = sein Bild loeschen.",
                        "Danach den Bot neu starten.", ""), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.debug("Eigenbilder", "Ordner nicht angelegt: " + e.getMessage());
            return 0;
        }
        // 3.87: "Arena lists" war eine Verknuepfung auf templates\arena\gegner -- nur die Verknuepfung weg (rmdir ohne /s).
        for (String alt : new String[]{"Arena lists", "Arena-Listen"}) {
            Path link = botOrdner.resolve(alt);
            try {
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) && istVerknuepfung(link)) {
                    Process p = new ProcessBuilder("cmd", "/c", "rmdir", link.toAbsolutePath().toString()).redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    p.waitFor();
                }
            } catch (Exception e) {
                Log.debug("Eigenbilder", "Alte Verknuepfung " + alt + " nicht entfernt: " + e.getMessage());
            }
        }
        Path altListen = botOrdner.resolve("templates").resolve("arena").resolve("gegner");
        if (Files.isDirectory(altListen)) {
            List<Path> bilder = new ArrayList<>();
            try (Stream<Path> s = Files.walk(altListen)) {
                s.filter(p -> p.toString().toLowerCase().endsWith(".png")).forEach(bilder::add);
            } catch (Exception e) {
                Log.debug("Eigenbilder", "templates/arena/gegner nicht lesbar: " + e.getMessage());
            }
            for (Path p : bilder) {
                String name = p.getFileName().toString();
                Path ziel = ordnerFuer(botOrdner, name.substring(0, name.length() - 4)).resolve(name);
                try {
                    Files.createDirectories(ziel.getParent());
                    if (Files.exists(ziel)) {
                        Path weg = ArenaVorlagenDoppelt.aussortiertZiel(botOrdner, p);
                        Files.createDirectories(weg.getParent());
                        Files.move(p, weg, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(p, ziel);
                        umgezogen++;
                    }
                } catch (Exception e) {
                    Log.debug("Eigenbilder", "Nicht umgezogen: " + name + " -- " + e.getMessage());
                }
            }
            if (umgezogen > 0) Log.info("Eigenbilder", Sprache.t(umgezogen + " pictures of your arena lists moved to \"" + ORDNER + "\\" + LISTEN + "\".",
                    umgezogen + " Bilder deiner Arena-Listen nach \"" + ORDNER + "\\" + LISTEN + "\" umgezogen."));
        }
        return umgezogen;
    }

    /** Ordner-Verknuepfung (Junction/Symlink) statt echter Ordner? */
    static boolean istVerknuepfung(Path p) {
        try {
            if (Files.isSymbolicLink(p)) return true;
            var a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isOther();   // Junctions meldet Java unter Windows als "other"
        } catch (Exception e) {
            return false;
        }
    }
}
