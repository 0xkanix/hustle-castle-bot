package de.hustlebot.schicht;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Vorschau fuer Seitenfenster und Schicht (29.10. nachts) -- NICHT Teil des Bots. Die Schicht ist vor
 * Bildschirmfotos versteckt; damit man sieht, wie beides aussieht, malt dieses Programm beides auf ein echtes
 * Spielbild (ohne Fensterrahmen) mit erfundenen Gegnern:
 *
 *   java -cp target/classes;target/test-classes de.hustlebot.schicht.SchichtVorschau spiel.png aus.png [oben]
 *
 * oben = Hoehe der Titelleiste im Spielbild (Standard 31), die wird abgeschnitten.
 */
public final class SchichtVorschau {

    private SchichtVorschau() {}

    public static void main(String[] args) throws Exception {
        BufferedImage spiel = ImageIO.read(new File(args[0]));
        int oben = args.length > 2 ? Integer.parseInt(args[2]) : 31;
        BufferedImage innen = spiel.getSubimage(1, oben, Math.min(1159, spiel.getWidth() - 2),
                Math.min(770, spiel.getHeight() - oben - 1));
        ArenaStand.Stand s = beispiel(innen.getWidth(), innen.getHeight());
        SchichtSteuerung.Anzeige a = new SchichtSteuerung.Anzeige(5, "sicher", null, false, false,
                SchichtSteuerung.Modus.NORMAL, 0, "", false, "", null, false, Map.of(), Map.of(), "");
        if (args.length > 3 && args[3].equals("leer")) a = SchichtSteuerung.Anzeige.LEER;

        BufferedImage seite = SeitenFenster.vorschau(s, a, innen.getHeight());
        BufferedImage aus = new BufferedImage(seite.getWidth() + 10 + innen.getWidth(), innen.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = aus.createGraphics();
        g.setColor(new Color(40, 40, 40));
        g.fillRect(0, 0, aus.getWidth(), aus.getHeight());
        g.drawImage(seite, 0, 0, null);
        g.translate(seite.getWidth() + 10, 0);
        g.drawImage(innen, 0, 0, null);
        SchichtFenster.vorschauZeichnen(g, ArenaSchicht.rahmen(s, innen.getWidth(), innen.getHeight(), a), innen.getWidth());
        g.dispose();
        ImageIO.write(aus, "png", new File(args[1]));
        System.out.println("Vorschau: " + args[1] + " (" + aus.getWidth() + "x" + aus.getHeight() + ")");
    }

    /** Neun Gegner an den echten Schild-Lagen (Protokoll 29.10.), gemischte Schriften, ein Ziel. */
    static ArenaStand.Stand beispiel(int breite, int hoehe) {
        int[][] lage = {{579, 244}, {462, 378}, {696, 377}, {345, 512}, {579, 512}, {812, 512},
                {228, 645}, {462, 645}, {696, 645}, {929, 645}};
        String[] name = {"zevs7", "Dimon", "паша", "صقر الليل", "Bäldr", "", "ᴋᴀʀɢᴀ", "龙之刃", "G4nawa", "별빛★"};
        String[] clan = {"MythG", "Wolves", "", "Falcons", "Asgard", "", "Legion", "NightWatch", "", "Rosen"};
        String[] stufe = {"niemals", "meiden", "", "sicher", "machbar", "", "sicher", "", "machbar", "meiden"};
        int[] chance = {12, 35, 58, 81, 66, -1, 74, 49, 61, 40};
        List<ArenaStand.Gegner> gegner = new ArrayList<>();
        List<ArenaStand.Platz> plaetze = new ArrayList<>();
        for (int i = 0; i < lage.length; i++) {
            int platz = i + 1;
            if (i == 5) continue;   // das eigene Schild
            plaetze.add(new ArenaStand.Platz(platz, lage[i][0], lage[i][1], stufe[i]));
            if (i == 0) continue;   // schon besiegt
            gegner.add(new ArenaStand.Gegner(lage[i][0], lage[i][1], platz, name[i], clan[i], clan[i].isEmpty(),
                    stufe[i], false, i == 6, 150, chance[i],
                    i == 3 ? "Kraft 18 % unter mir (+20), Bilanz 3:0 (+12), Clan schwach (-4), Sets: 2 von 6 ohne Set (+4)"
                            : "Kraft gleich (0)",
                    i == 3 ? "SICHERER SIEG (-40)" : "", i == 3));
        }
        return new ArenaStand.Stand(System.currentTimeMillis(), 2, breite, hoehe, gegner, plaetze);
    }
}
