package de.hustlebot.tools;

import de.hustlebot.bot.CathedralMode;
import de.hustlebot.bot.ClanWarMode;
import de.hustlebot.bot.DungeonMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prueft vor jedem Paketbau, ob Bot, Kathedrale und Aufnahmewerkzeug dieselben
 * eigenen Bilder meinen -- und ob das Paket keins von Autors eigenen enthaelt.
 *
 * Warum es das gibt: Anfangs sagte die Anleitung den Freunden, sie sollten
 * ihre Bilder "name_zarina" nennen, der Bot suchte aber nach "name_magier1".
 * Uebersetzt und gepackt hat sich das tadellos; aufgefallen waere es erst
 * bei einem Freund im laufenden Verlies. Solche Fehler faengt diese Pruefung
 * jetzt beim Bauen ab.
 *
 * JUnit liegt auf Autors Rechner nicht vor und laesst sich ohne Netz nicht
 * holen. Deshalb ein schlichtes Programm: paket-bauen.ps1 ruft es nach dem
 * Uebersetzen auf und baut bei einem Fehler keine ZIP.
 *
 * Aufruf: java -cp target\hustle-bot.jar de.hustlebot.tools.Selbstpruefung templates
 */
public final class Selbstpruefung {

    /**
     * Autors eigene Bilder. Keins davon darf im Freunde-Paket stecken: seine
     * Kaempfer und seine Freunde (friend_lisa ...). friend_invite ist der
     * Knopf "Invite" und gehoert hinein.
     */
    private static final Pattern AUTORS_BILDER = Pattern.compile(
            "(unit|name)_(magier\\d*|bogen\\d*|ersatz|autor)\\.png"
            + "|friend_(?!invite\\.png)[a-z0-9]+\\.png"
            // 3.0: Autors Arena-Gegner (Sperrliste, meiden, sicher, machbar, Clans, Namensbilder)
            + "|ar_(block|ungern|sicher|machbar|clan|name)_.+\\.png");

    private final List<String> fehler = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        nu.pattern.OpenCV.loadLocally();
        Path vorlagen = Path.of(args.length > 0 ? args[0] : "templates");

        Selbstpruefung p = new Selbstpruefung();
        p.pruefePlaetze();
        p.pruefeWerkzeug();
        p.pruefePaket(vorlagen);

        if (p.fehler.isEmpty()) {
            System.out.println("Selbstpruefung bestanden.");
            System.exit(0);
        }
        System.out.println("Selbstpruefung FEHLGESCHLAGEN:");
        for (String f : p.fehler) System.out.println("  - " + f);
        System.exit(1);
    }

    /** Portraets, Namen, Opferaltar und Kathedrale meinen dieselben Plaetze. */
    private void pruefePlaetze() {
        List<String> portraets = DungeonMode.bilderKaempfer();
        List<String> namen = DungeonMode.bilderKaempferNamen();

        if (portraets.size() != namen.size()) {
            fehler.add(portraets.size() + " Kaempferportraets, aber " + namen.size() + " Namen.");
        }
        Set<String> plaetze = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(portraets.size(), namen.size()); i++) {
            String p = DungeonMode.platzVon(portraets.get(i));
            String n = DungeonMode.platzVon(namen.get(i));
            if (p.isEmpty() || !p.equals(n)) {
                fehler.add("Platz " + (i + 1) + ": " + portraets.get(i) + " und " + namen.get(i)
                        + " ergeben nicht denselben Platz.");
            }
            plaetze.add(p);
        }
        for (String platz : DungeonMode.altarPlaetze()) {
            if (!plaetze.contains(platz)) {
                fehler.add("Der Opferaltar schont Platz \"" + platz + "\", den es gar nicht gibt.");
            }
        }
        for (String bild : CathedralMode.bilderWiederbeleben()) {
            if (!portraets.contains(bild)) {
                fehler.add("Die Kathedrale belebt " + bild + " wieder, das Verlies kennt es nicht.");
            }
        }
    }

    /** Das Werkzeug bietet genau die eigenen Bilder an, die der Bot kennt. */
    private void pruefeWerkzeug() {
        Set<String> soll = new LinkedHashSet<>();
        soll.add(DungeonMode.bildNamensschild());
        soll.add(DungeonMode.bildOpferPortraet());
        soll.add(DungeonMode.bildOpferName());
        soll.addAll(DungeonMode.bilderKaempfer());
        soll.addAll(DungeonMode.bilderKaempferNamen());
        soll.add(DungeonMode.freundVorlage(""));   // "A friend's name" -- fragt beim Speichern
        soll.add(de.hustlebot.bot.ArenaMode.bildEigenerName());   // 3.0: der eigene Name in der Arena

        Set<String> ist = Bilderwerkzeug.eigeneBilder();
        for (String s : soll) {
            if (!ist.contains(s)) fehler.add("Capture-Images.exe bietet " + s + " nicht an.");
        }
        for (String s : ist) {
            if (!soll.contains(s)) {
                fehler.add("Capture-Images.exe bietet " + s + " an, der Bot sucht es nie.");
            }
        }
    }

    /**
     * Jede Pflichtvorlage ist entweder im Paket oder eine eigene Aufnahme --
     * und keine eigene Aufnahme liegt schon im Paket.
     */
    private void pruefePaket(Path vorlagen) throws IOException {
        if (!Files.isDirectory(vorlagen)) {
            fehler.add("Der Vorlagenordner fehlt: " + vorlagen.toAbsolutePath());
            return;
        }
        Set<String> imPaket = new LinkedHashSet<>();
        List<String> fremde = new ArrayList<>();
        try (Stream<Path> s = Files.walk(vorlagen)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String datei = p.getFileName().toString();
                if (datei.endsWith(".png")) imPaket.add(datei.substring(0, datei.length() - 4));
                if (AUTORS_BILDER.matcher(datei).matches()) fremde.add(vorlagen.relativize(p).toString());
            });
        }
        for (String f : fremde) fehler.add("Autors eigenes Bild liegt im Paket: " + f);

        Set<String> eigene = Bilderwerkzeug.eigeneBilder();
        for (String e : eigene) {
            if (imPaket.contains(e)) {
                fehler.add("Das eigene Bild " + e + " liegt schon im Paket -- dann nimmt der "
                        + "Freund es nicht selbst auf, und der Bot sucht nach fremden Kaempfern.");
            }
        }

        Set<String> pflicht = new LinkedHashSet<>(new DungeonMode().requiredTemplates());
        pflicht.addAll(new CathedralMode().requiredTemplates());
        pflicht.addAll(new ClanWarMode().requiredTemplates());
        // 10.10.: die Titan-Grabung bringt ihre Bilder im Paket mit (templates\gelernt\titan-event).
        pflicht.addAll(new de.hustlebot.bot.TitanEventMode().requiredTemplates());
        // 3.0: die Arena bringt ihre Bilder im Paket mit (templates\arena), nur ar_me nimmt jeder selbst auf.
        pflicht.addAll(new de.hustlebot.bot.ArenaMode().requiredTemplates());
        // Team-Scan (aus dem Hauptbot, 11.10.): seine Bilder bringt das Paket mit (templates\gelernt\team).
        pflicht.addAll(new de.hustlebot.bot.TeamScanMode().requiredTemplates());
        for (String p : pflicht) {
            if (!imPaket.contains(p) && !eigene.contains(p)) {
                fehler.add("Pflichtvorlage " + p + " ist weder im Paket noch in Capture-Images.exe.");
            }
        }
    }
}
