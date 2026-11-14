package de.hustlebot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sicherheit (16.10.): gefaehrliche Befehle nur fuer den Besitzer des Discord-Servers
 * und die IDs aus discord.erlaubteNutzer; unbekannter Besitzer = gesperrt.
 */
class RechteTest {

    private static final String GUILD = "100000000000000001";
    private static final String BESITZER = "200000000000000002";
    private static final String FREMDER = "300000000000000003";
    private static final String ERLAUBTER = "400000000000000004";

    @Test
    void besitzerDarfFremderNicht() {
        Rechte r = new Rechte(Set.of());
        r.besitzerSetzen(GUILD, BESITZER);
        assertTrue(r.darf(GUILD, BESITZER));
        assertFalse(r.darf(GUILD, FREMDER));
        assertNull(r.sperrtext(GUILD, BESITZER, "!pc"));
        assertNotNull(r.sperrtext(GUILD, FREMDER, "!pc"));
        assertTrue(r.sperrtext(GUILD, FREMDER, "!pc").contains("owner"));
    }

    @Test
    void unbekannterBesitzerSperrtAlle() {
        Rechte r = new Rechte(Set.of());
        assertFalse(r.besitzerBekannt(GUILD));
        assertFalse(r.darf(GUILD, BESITZER));   // noch niemand bekannt: auch der echte Besitzer kommt nicht durch
        assertFalse(r.darf(GUILD, FREMDER));
        String text = r.sperrtext(GUILD, FREMDER, "!off");
        assertNotNull(text);
        assertTrue(text.contains("could not find out"), text);
        // sobald der Besitzer nachgeladen ist, darf er
        r.besitzerSetzen(GUILD, BESITZER);
        assertTrue(r.darf(GUILD, BESITZER));
    }

    @Test
    void erlaubteListeGiltAuchOhneBesitzer() {
        Rechte r = new Rechte(Rechte.idsAus(" " + ERLAUBTER + ", abc ,, 12 ;" + FREMDER));
        assertEquals(Set.of(ERLAUBTER, FREMDER), r.erlaubte());
        assertTrue(r.darf(GUILD, ERLAUBTER));
        assertTrue(r.darf(GUILD, FREMDER));
        assertFalse(r.darf(GUILD, BESITZER));
    }

    @Test
    void eigenerRechnerDarfImmerLeereIdNie() {
        Rechte r = new Rechte(Set.of());
        assertTrue(r.darf(GUILD, Rechte.EIGENER_RECHNER));
        assertTrue(r.darf("", Rechte.EIGENER_RECHNER));
        assertFalse(r.darf(GUILD, ""));
        assertFalse(r.darf(GUILD, null));
    }

    @Test
    void besitzerJeServerUndOhneGuildNurBeiEinemServer() {
        Rechte r = new Rechte(Set.of());
        r.besitzerSetzen(GUILD, BESITZER);
        assertTrue(r.darf("", BESITZER));          // einziger bekannter Server
        r.besitzerSetzen("100000000000000009", FREMDER);
        assertFalse(r.darf("", BESITZER));         // zwei Server: ohne Guild-ID nichts raten
        assertTrue(r.darf(GUILD, BESITZER));
        assertFalse(r.darf(GUILD, FREMDER));       // Besitzer des ANDEREN Servers darf hier nicht
        assertTrue(r.darf("100000000000000009", FREMDER));
    }

    @Test
    void gefaehrlicheBefehleInJederSchreibweise() {
        for (String v : new String[]{"!pc", "!rechner", "!notaus", "!kill", "!emergency", "!off", "!aus", "!shutdown",
                "!settings", "!einstellungen", "!config", "!clear", "!leeren", "!screenshot", "!bild", "!live", "!livebild", "!PC", "!watch", "!wach"}) {
            assertTrue(Rechte.gefaehrlich(v), v);
        }
        for (String v : new String[]{"!help", "!status", "!start", "!stop", "!pause", "!resume", "!log", "!fix", "!stats", "", null}) {
            assertFalse(Rechte.gefaehrlich(v), String.valueOf(v));
        }
        Rechte r = new Rechte(Set.of());
        assertNull(r.sperrtext(GUILD, FREMDER, "!help"));   // harmlos: fuer alle
    }

    @Test
    void ungueltigeIdsWerdenNichtBesitzer() {
        Rechte r = new Rechte(Set.of());
        r.besitzerSetzen(GUILD, "");
        r.besitzerSetzen(GUILD, "eigener-rechner");
        r.besitzerSetzen("", BESITZER);
        assertFalse(r.besitzerBekannt(GUILD));
        assertFalse(r.irgendeinBesitzerBekannt());
    }

    @Test
    void ausConfigLiestNurDenSchluessel(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("config.properties");
        Files.writeString(datei, "discord.botToken=geheim\ndiscord.erlaubteNutzer=" + ERLAUBTER + "," + FREMDER + "\n");
        Rechte r = Rechte.ausConfig(datei);
        assertEquals(Set.of(ERLAUBTER, FREMDER), r.erlaubte());
        assertEquals("discord.botToken=geheim\ndiscord.erlaubteNutzer=" + ERLAUBTER + "," + FREMDER + "\n",
                Files.readString(datei));   // nur gelesen
        assertTrue(Rechte.ausConfig(ordner.resolve("gibt-es-nicht")).erlaubte().isEmpty());
        Files.writeString(datei, "discord.botToken=geheim\n");
        assertTrue(Rechte.ausConfig(datei).erlaubte().isEmpty());
    }
}
