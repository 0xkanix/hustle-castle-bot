package de.hustlebot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Sicherheit (16.10.): Die Nutzer-ID und der Server des Absenders kommen bei Nachrichten
 * und Knoepfen bis zum Empfaenger durch -- geprueft mit eingespeistem Discord-JSON, ohne Netz.
 * (Interaktionen ohne id/token loesen keine Antwort an Discord aus.)
 */
class DiscordGatewayTest {

    private static final String KANAL = "500000000000000005";
    private static final String GUILD = "100000000000000001";
    private static final String BESITZER = "200000000000000002";
    private static final String FREMDER = "300000000000000003";

    private record Empfangen(String kanal, String inhalt, DiscordGateway.Absender absender) { }

    private final List<Empfangen> empfangen = new ArrayList<>();
    private final Rechte rechte = new Rechte(Set.of());
    private final DiscordGateway gateway = new DiscordGateway("", List.of(KANAL),
            (k, t, a) -> empfangen.add(new Empfangen(k, t, a)), rechte, new Flutbremse());

    private static String nachricht(String kanal, String inhalt, String autorId, String name, String guild) {
        return "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"s\":7,\"d\":{\"channel_id\":\"" + kanal + "\",\"guild_id\":\"" + guild
                + "\",\"content\":\"" + inhalt + "\",\"author\":{\"id\":\"" + autorId + "\",\"username\":\"" + name
                + "\",\"bot\":false},\"attachments\":[]}}";
    }

    @Test
    void nachrichtBringtIdUndServerMit() {
        gateway.verarbeiten(nachricht(KANAL, "!pc off", BESITZER, "Autor", GUILD));
        assertEquals(1, empfangen.size());
        Empfangen e = empfangen.get(0);
        assertEquals(KANAL, e.kanal());
        assertEquals("!pc off", e.inhalt());
        assertEquals("Autor", e.absender().name());
        assertEquals(BESITZER, e.absender().id());
        assertEquals(GUILD, e.absender().guild());
        assertTrue(e.absender().vonDiscord());
    }

    @Test
    void fremderKanalUndBotsBleibenDraussen() {
        gateway.verarbeiten(nachricht("500000000000000099", "!pc off", BESITZER, "Autor", GUILD));
        gateway.verarbeiten("{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"d\":{\"channel_id\":\"" + KANAL
                + "\",\"content\":\"!pc off\",\"author\":{\"id\":\"" + FREMDER + "\",\"username\":\"Bot\",\"bot\":true}}}");
        gateway.verarbeiten(nachricht(KANAL, "kein Befehl", FREMDER, "X", GUILD));
        assertTrue(empfangen.isEmpty());
    }

    @Test
    void knopfBringtIdAusMemberUser() {
        gateway.verarbeiten("{\"op\":0,\"t\":\"INTERACTION_CREATE\",\"d\":{\"type\":3,\"channel_id\":\"" + KANAL
                + "\",\"guild_id\":\"" + GUILD + "\",\"member\":{\"user\":{\"id\":\"" + FREMDER
                + "\",\"username\":\"Clanmitglied\"}},\"data\":{\"custom_id\":\"!live\"}}}");
        assertEquals(1, empfangen.size());
        assertEquals("!live", empfangen.get(0).inhalt());
        assertEquals(FREMDER, empfangen.get(0).absender().id());
        assertEquals("Clanmitglied", empfangen.get(0).absender().name());
        assertEquals(GUILD, empfangen.get(0).absender().guild());
    }

    @Test
    void knopfOhneMemberNimmtUser() {
        gateway.verarbeiten("{\"op\":0,\"t\":\"INTERACTION_CREATE\",\"d\":{\"type\":3,\"channel_id\":\"" + KANAL
                + "\",\"user\":{\"id\":\"" + BESITZER + "\",\"username\":\"Autor\"},\"data\":{\"custom_id\":\"!status\"}}}");
        assertEquals(1, empfangen.size());
        assertEquals(BESITZER, empfangen.get(0).absender().id());
        assertEquals("", empfangen.get(0).absender().guild());
    }

    @Test
    void unsinnigeIdWirdLeerUndDarfNichts() {
        gateway.verarbeiten(nachricht(KANAL, "!pc off", "eigener-rechner", "Trick", GUILD));
        assertEquals(1, empfangen.size());
        assertEquals("", empfangen.get(0).absender().id());
        assertFalse(rechte.darf(GUILD, empfangen.get(0).absender().id()));
        assertTrue(empfangen.get(0).absender().vonDiscord());
    }

    @Test
    void einstellungsFormularNurFuerDenBesitzer() {
        List<String> geoeffnet = new ArrayList<>();
        gateway.setFixAnbindung(new DiscordGateway.FixAnbindung() {
            @Override public String setFormular(String autor, String bereich) { geoeffnet.add(bereich); return null; }
        });
        String knopf = "{\"op\":0,\"t\":\"INTERACTION_CREATE\",\"d\":{\"type\":3,\"channel_id\":\"" + KANAL
                + "\",\"guild_id\":\"" + GUILD + "\",\"member\":{\"user\":{\"id\":\"%s\",\"username\":\"x\"}},"
                + "\"data\":{\"custom_id\":\"setformular:krieg\"}}}";
        rechte.besitzerSetzen(GUILD, BESITZER);
        gateway.verarbeiten(String.format(knopf, FREMDER));
        assertTrue(geoeffnet.isEmpty(), "Fremder darf kein Einstellungs-Formular");
        gateway.verarbeiten(String.format(knopf, BESITZER));
        assertEquals(List.of("krieg"), geoeffnet);
    }

    @Test
    void startFormularLegtNutzerIdInDieFelder() {
        List<java.util.Map<String, String>> felder = new ArrayList<>();
        gateway.setFixAnbindung(new DiscordGateway.FixAnbindung() {
            @Override public void startEingereicht(String kanal, String autor, java.util.Map<String, String> f) { felder.add(f); }
        });
        gateway.verarbeiten("{\"op\":0,\"t\":\"INTERACTION_CREATE\",\"d\":{\"type\":5,\"channel_id\":\"" + KANAL
                + "\",\"guild_id\":\"" + GUILD + "\",\"member\":{\"user\":{\"id\":\"" + FREMDER + "\",\"username\":\"x\"}},"
                + "\"data\":{\"custom_id\":\"start:form\",\"components\":[{\"custom_id\":\"modus\",\"value\":\"dungeon\"}]}}}");
        assertEquals(1, felder.size());
        assertEquals("dungeon", felder.get(0).get("modus"));
        assertEquals(FREMDER, felder.get(0).get("_nutzerId"));
        assertEquals(GUILD, felder.get(0).get("_guild"));
    }

    @Test
    void besitzerAusDerAntwortVonDiscord() {
        assertEquals(BESITZER, DiscordGateway.besitzerAus("{\"id\":\"" + GUILD + "\",\"name\":\"Clan\",\"owner_id\":\"" + BESITZER + "\"}"));
        assertEquals("", DiscordGateway.besitzerAus("{\"id\":\"" + GUILD + "\"}"));
        assertEquals("", DiscordGateway.besitzerAus("{\"owner_id\":\"nicht-zahl\"}"));
        assertEquals("", DiscordGateway.besitzerAus("kein json"));
    }
}
