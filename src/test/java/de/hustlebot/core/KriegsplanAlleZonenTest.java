package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Autor 26.10.: "Jede Zeitzone, die man beim Bot eingeben oder auswaehlen kann, soll im Clan-Krieg funktionieren -- mit
 * jeder Zeitumstellung." Geprueft wird JEDE Zone, die Java kennt (das nimmt Zeitzone.verstehen an), dazu feste
 * Abstaende UTC-12 bis UTC+14 (auch :30/:45): rund um jede Zeitumstellung 2026/2027 alle 10 Minuten, dazu ein
 * ganzer normaler Tag. Erwartet wird immer dasselbe Ergebnis wie direkt in Weltzeit gerechnet.
 */
class KriegsplanAlleZonenTest {

    /** Setz-Zeiten in Weltzeit (Minuten des Tages): 01:20, 07:20, 13:20, 19:20. */
    private static final int[] SETZEN = {80, 440, 800, 1160};
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @Test
    void jedeZoneMitJederZeitumstellung() {
        List<ZoneId> zonen = new ArrayList<>();
        for (String id : ZoneId.getAvailableZoneIds()) zonen.add(ZoneId.of(id));
        for (int h = -12; h <= 14; h++) zonen.add(ZoneId.ofOffset("UTC", ZoneOffset.ofHours(h)));
        zonen.add(ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(5, 30)));   // Indien
        zonen.add(ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(5, 45)));   // Nepal
        zonen.add(ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(-3, -30))); // Neufundland
        Instant von = Instant.parse("2026-01-01T00:00:00Z"), bis = Instant.parse("2028-01-01T00:00:00Z");
        int geprueft = 0, umstellungen = 0;
        for (ZoneId z : zonen) {
            List<Instant> proben = new ArrayList<>();
            ZoneOffsetTransition tr = z.getRules().nextTransition(von);
            while (tr != null && tr.getInstant().isBefore(bis)) {
                umstellungen++;
                for (int m = -180; m <= 180; m += 10) proben.add(tr.getInstant().plusSeconds(m * 60L));
                tr = z.getRules().nextTransition(tr.getInstant());
            }
            Instant tag = Instant.parse("2026-07-15T00:00:00Z");
            for (int m = 0; m < 24 * 60; m += 10) proben.add(tag.plusSeconds(m * 60L));
            for (Instant i : proben) {
                pruefe(z, i);
                geprueft++;
            }
        }
        assertTrue(zonen.size() > 500, "Zonen: " + zonen.size());
        assertTrue(umstellungen > 300, "Zeitumstellungen: " + umstellungen);
        assertTrue(geprueft > 100_000, "Proben: " + geprueft);
    }

    private static void pruefe(ZoneId z, Instant i) {
        String wo = z + " @ " + i;
        // 1. naechste Kriegszeit = die naechste Setz-Zeit in Weltzeit, angezeigt in der Zone
        ZonedDateTime k = Kriegsplan.naechsteKriegszeitIn(i, z);
        Instant erwartet = naechsteSetzzeit(i);
        assertEquals(erwartet, k.toInstant(), wo);
        assertEquals(z, k.getZone(), wo);
        // 2. die Anzeige "HH:mm" stimmt mit der Ortszeit der Zone ueberein
        LocalDateTime intern = LocalDateTime.ofInstant(erwartet, Kriegsplan.INTERN);
        assertEquals(HHMM.format(erwartet.atZone(z)), Kriegsplan.zeitText(intern, z), wo);
        // 3. Setz-Fenster (100 min ab xx:20 Weltzeit) und Nachpruefen (15 min ab xx:45 eine Stunde spaeter) haengen
        //    nicht von der Zone ab -- die innere Uhr ist fuer alle gleich
        int minute = (int) (i.getEpochSecond() / 60 % 1440);
        int nachSetzen = ((minute - 80) % 360 + 360) % 360;
        LocalDateTime jetztIntern = LocalDateTime.ofInstant(i, Kriegsplan.INTERN);
        assertEquals(nachSetzen < 100, Kriegsplan.offenesFenster(jetztIntern) != null, wo);
        assertEquals(nachSetzen >= 85 && nachSetzen < 100, Kriegsplan.istKontrolle(jetztIntern), wo);
    }

    private static Instant naechsteSetzzeit(Instant i) {
        long tagStart = i.getEpochSecond() / 86_400 * 86_400;
        for (int tag = 0; tag <= 1; tag++) {
            for (int s : SETZEN) {
                Instant t = Instant.ofEpochSecond(tagStart + tag * 86_400L + s * 60L);
                if (t.isAfter(i)) return t;
            }
        }
        throw new IllegalStateException();
    }
}
