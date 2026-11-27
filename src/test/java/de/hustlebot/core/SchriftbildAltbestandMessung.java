package de.hustlebot.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Probe am echten Notizblock (Autor 21.11.): was passiert beim ersten Laden mit Schriftbildern? Arbeitet auf KOPIEN von
 * C:\HustleBot\arena-daten.txt und C:\HustleBot\glyphen (nur lesen dort). Einmal ohne Namensbilder (wie bisher), einmal
 * mit -- der Unterschied sind die Zusammenlegungen per Schriftbild. Von Hand: mvn test -Dtest=SchriftbildAltbestandMessung
 * -Dmessung.aus=DATEI.
 */
public class SchriftbildAltbestandMessung {

    @TempDir
    Path tmp;

    @Test
    void probe() throws Exception {
        Path bot = Path.of("C:/HustleBot");
        Assumptions.assumeTrue(Files.exists(bot.resolve("arena-daten.txt")) && Files.isDirectory(bot.resolve("glyphen")));
        String ohne = laden(bot, false), mit = laden(bot, true);
        StringBuilder out = new StringBuilder("== OHNE Schriftbilder ==\n").append(ohne).append("\n== MIT Schriftbildern ==\n").append(mit);
        String ziel = System.getProperty("messung.aus", "");
        if (!ziel.isBlank()) Files.writeString(Path.of(ziel), out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    private String laden(Path bot, boolean mitBildern) throws Exception {
        Path d = Files.createDirectories(tmp.resolve(mitBildern ? "mit" : "ohne"));
        Files.copy(bot.resolve("arena-daten.txt"), d.resolve("arena-daten.txt"), StandardCopyOption.REPLACE_EXISTING);
        Path g = Files.createDirectories(d.resolve("glyphen"));
        try (Stream<Path> s = Files.walk(bot.resolve("glyphen"))) {
            for (Path p : s.toList()) {
                Path rel = bot.resolve("glyphen").relativize(p);
                if (!mitBildern && rel.startsWith("namen") && p.toString().endsWith(".png")) continue;
                Path z = g.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(z);
                else Files.copy(p, z, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        PrintStream alt = System.out;
        ByteArrayOutputStream puffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(puffer, true, StandardCharsets.UTF_8));
        Gegnerbuch b;
        try {
            b = new Gegnerbuch(d.resolve("arena-daten.txt"), d.resolve("arena-notizblock.txt"), new Glyphenschrift(g));
            b.anzahl();
        } finally {
            System.setOut(alt);
        }
        StringBuilder r = new StringBuilder();
        for (String z : puffer.toString(StandardCharsets.UTF_8).split("\n")) {
            if (z.contains("Notizblock") || z.contains("Notebook")) r.append(z.strip()).append('\n');
        }
        // Wie lange dauert das Wiedererkennen per Schriftbild bei vollem Notizblock? (je Schild in der Pyramide einmal)
        if (mitBildern) {
            Path probe = bot.resolve("glyphen/namen/201757a4.png");
            Schriftbild sb = Files.exists(probe) ? Schriftbild.aus(javax.imageio.ImageIO.read(probe.toFile()), Schriftbild.SCHILD) : null;
            if (sb != null) {
                PrintStream vorher = System.out;
                System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
                String[] nc;
                long t0 = System.nanoTime();
                try {
                    for (int i = 0; i < 99; i++) b.wiedererkennen("x" + i, "Myth Gods", -1, Glyphenschrift.Ergebnis.nurBild(sb));
                    nc = b.wiedererkennen("-ЦИРИ-", "Myth Gods", 35_465_161, Glyphenschrift.Ergebnis.nurBild(sb));
                } finally {
                    System.setOut(vorher);
                }
                r.append(String.format(java.util.Locale.ROOT, "Wiedererkennen per Schriftbild: %.2f ms je Aufruf; -ЦИРИ- -> %s / %s%n",
                        (System.nanoTime() - t0) / 1e6 / 100, nc[0], nc[1]));
            }
        }
        List<Gegnerbuch.Notiz> alle = b.alle();
        long mitBild = alle.stream().filter(n -> !n.schriftbilder().isBlank()).count();
        long zeichen = alle.stream().mapToLong(n -> n.schriftbilder().length()).sum();
        r.append("Eintraege: ").append(alle.size()).append(", mit Schriftbild: ").append(mitBild)
         .append(", Zeichen Schriftbilder gesamt: ").append(zeichen).append('\n');
        for (Gegnerbuch.Notiz n : alle) {
            if (n.name().contains("ЦИРИ") || n.name().contains("UMPM") || n.clan().contains("Myth")) {
                r.append("  ").append(n.name()).append(" / ").append(n.clan()).append(" ").append(n.verlauf())
                 .append(" ").append(n.staerke()).append(n.nameIstClan() ? " (Clan als Name)" : "").append('\n');
            }
        }
        return r.toString();
    }
}
