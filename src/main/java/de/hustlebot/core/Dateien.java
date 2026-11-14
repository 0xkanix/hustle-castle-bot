package de.hustlebot.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Eine Stelle, die kleine Einstellungs- und Listendateien liest, wie Windows sie schreibt.
 *
 * WARUM (Bugjagd 29.10.): Dateien, die mit Windows PowerShell 5.1 geschrieben werden (Set-Content, Out-File, ">"), tragen am
 * Anfang eine Kennung (BOM: UTF-8 EF BB BF, UTF-16 FF FE), oder sind gar nicht UTF-8 (ANSI-Umlaute). Files.readAllLines(UTF_8)
 * liess die Kennung als Zeichen stehen -- eine "# Kommentar"-Zeile war dann kein Kommentar mehr, der Schalter galt als "weder
 * an noch aus" und blieb aus (event-lupen-alle.txt, 28.10. 17:05 und 20:04). Dasselbe war schon an acht Stellen einzeln
 * geflickt worden (Kriegsplan, Eventautomatik, ClanWarMode, TitanRauswurf, Rechnertempo, Tempolerner, Reiniger, Schalterdatei):
 * jedes Mal erst, nachdem eine Datei so geschrieben wurde. Eine ANSI-Datei warf sogar eine MalformedInputException.
 *
 * Hier: UTF-8- und UTF-16-Kennung weg, UTF-16 richtig gelesen, ungueltiges UTF-8 als Windows-1252 gelesen.
 */
public final class Dateien {

    private Dateien() {}

    private static final Charset ANSI = Charset.forName("windows-1252");

    /** Der ganze Inhalt als Text, ohne Kennung. */
    public static String text(Path datei) throws IOException {
        return dekodieren(Files.readAllBytes(datei));
    }

    /**
     * Alle Zeilen (wie Files.readAllLines), ohne Kennung. Eine VERAENDERLICHE Liste, genau wie readAllLines sie liefert --
     * manche Aufrufer fuegen etwas an oder sortieren (Stream.toList() waere unveraenderlich und warf erst zur Laufzeit).
     */
    public static List<String> zeilen(Path datei) throws IOException {
        return new java.util.ArrayList<>(text(datei).lines().toList());
    }

    /** Bytes -> Text: Kennung erkennen, UTF-16 lesen, sonst UTF-8, bei Fehlern Windows-1252. */
    static String dekodieren(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        int ab = (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, ab, b.length - ab)).toString();
            return s;
        } catch (CharacterCodingException e) {
            return new String(b, ab, b.length - ab, ANSI);
        }
    }
}
