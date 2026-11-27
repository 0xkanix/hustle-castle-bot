package de.hustlebot.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.hustlebot.core.Log;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import de.hustlebot.core.Sprache;

/**
 * Schickt Meldungen und Bildschirmfotos nach Discord.
 *
 * Es gibt zwei Wege: einen Webhook, der ohne Bot-Zugang auskommt, oder die
 * Schnittstelle des eigenen Bots, wenn ein Zugangsschluessel hinterlegt ist.
 * Ist beides vorhanden, wird der Bot bevorzugt, weil seine Nachrichten im
 * Kanal einheitlicher aussehen.
 */
public final class DiscordClient {

    /** Farben der Randleiste, damit Meldungen auf einen Blick zu unterscheiden sind. */
    public static final int FARBE_INFO    = 0x4EC8E8;
    public static final int FARBE_ERFOLG  = 0x7BBD4A;
    public static final int FARBE_WARNUNG = 0xE8C04A;
    public static final int FARBE_FEHLER  = 0xD2382F;
    public static final int FARBE_START   = 0xFF7A1A;

    private static final String API = "https://discord.com/api/v10";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    private final Random random = new Random();
    private final ObjectMapper mapper = new ObjectMapper();

    private String webhookUrl = "";
    private String botToken = "";
    private String channelId = "";
    private boolean enabled = false;

    /** Zaehlt Fehlversuche, um bei dauerhaften Problemen Ruhe zu geben. */
    private int consecutiveFailures = 0;
    private long mutedUntil = 0;

    /** Merkt sich die letzten Meldungen, um Wiederholungen zu unterdruecken. */
    private final Deque<String> recent = new ArrayDeque<>();

    // ------------------------------------------------------------------
    //  Mithoerer -- das Steuerprogramm (3.2)
    // ------------------------------------------------------------------
    //
    // Das Steuerprogramm traegt sich hier ein und bekommt JEDE Meldung --
    // Karte, Text, Rueckfrage mit Knoepfen -- bevor entschieden wird, ob sie
    // nach Discord geht. So kommen die Antworten auch ohne Discord an.

    /** Bekommt jede Meldung, die der Bot ausgibt. Knoepfe sind Paare {Aufschrift, Befehl}, sonst leer. */
    public interface Mithoerer {
        void meldung(String kanal, String titel, String text, int farbe, List<String[]> knoepfe);
    }

    private volatile Mithoerer mithoerer;

    public void setMithoerer(Mithoerer m) {
        this.mithoerer = m;
    }

    private void mithoeren(String kanal, String titel, String text, int farbe, List<String[]> knoepfe) {
        Mithoerer m = mithoerer;
        if (m == null) return;
        try {
            m.meldung(kanal, titel, text, farbe, knoepfe == null ? List.of() : knoepfe);
        } catch (Exception e) {
            // Das Fenster darf den Bot nie aufhalten.
        }
    }

    public void configure(boolean enabled, String webhookUrl, String botToken, String channelId) {
        this.enabled = enabled;
        this.webhookUrl = trim(webhookUrl);
        this.botToken = trim(botToken);
        this.channelId = trim(channelId);
    }

    public boolean isEnabled() {
        return enabled && (canUseBot() || !webhookUrl.isEmpty());
    }

    private boolean canUseBot() {
        return !botToken.isEmpty() && !channelId.isEmpty();
    }

    // ------------------------------------------------------------------
    //  Nachrichten
    // ------------------------------------------------------------------

    /** Einfache Textnachricht. */
    public void send(String text) {
        mithoeren(null, "", text, FARBE_INFO, null);
        if (!ready()) return;
        post(json("{\"content\":" + quote(cut(text, 1900)) + "}"));
    }

    /** Meldung mit Titel und farbiger Randleiste. */
    public void sendCard(String title, String body, int color) {
        sendCardTo(null, title, body, color);
    }

    /**
     * Karte mit einer Zeile davor (Erwaehnung wie @everyone) -- fuer den Tipp des Tages.
     * Eine Erwaehnung pingt nur im Text der Nachricht, nicht in der Karte.
     *
     * Wartet auf Discords Antwort (einmal am Tag, hoechstens 20 s) und sagt, ob die
     * Nachricht angenommen wurde -- nur dann gilt der Tipp als gepostet (30.09.).
     */
    public boolean sendCardMitPing(String kopf, String title, String body, int color) {
        mithoeren(null, title, body, color, null);
        if (!ready()) return false;
        String erlaubt = "\"allowed_mentions\":{\"parse\":[\"everyone\",\"users\"]}";
        String davor = kopf == null ? "" : kopf.strip();
        String payload;
        if (kartenGehenNicht) {
            payload = "{\"content\":" + quote(cut((davor.isEmpty() ? "" : davor + "\n") + alsText(title, body), 1900))
                    + "," + erlaubt + "}";
        } else {
            payload = "{" + (davor.isEmpty() ? "" : "\"content\":" + quote(cut(davor, 300)) + ",") + erlaubt
                    + ",\"embeds\":[{"
                    + "\"title\":" + quote(cut(title, 250)) + ","
                    + "\"description\":" + quote(cut(body, 3800)) + ","
                    + "\"color\":" + color + ","
                    + "\"footer\":{\"text\":\"Hustle Castle Bot\"},"
                    + "\"timestamp\":\"" + java.time.Instant.now() + "\""
                    + "}]}";
        }
        try {
            HttpResponse<String> antwort = http.send(json(payload), HttpResponse.BodyHandlers.ofString());
            int status = antwort.statusCode();
            if (status >= 200 && status < 300) de.hustlebot.core.Weltuhr.datumKopf(antwort.headers());   // Konrad 19.11.
            if (status < 200 || status >= 300) {
                failure(Sprache.t("Discord answered with ", "Discord antwortete mit ") + status + ": " + cut(antwort.body(), 200));
                return false;
            }
            consecutiveFailures = 0;
            if (!kartenGehenNicht) {
                JsonNode m = mapper.readTree(antwort.body());
                if (m.path("embeds").isArray() && m.path("embeds").isEmpty()) {
                    // Kein Recht "Links einbetten": die Karte kam leer an -- den Text nachreichen.
                    kartenGehenNicht = true;
                    post(json("{\"content\":" + quote(alsText(title, body)) + "}"));
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            failure(Sprache.t("Connection to Discord failed: ", "Verbindung zu Discord fehlgeschlagen: ") + e.getMessage());
            return false;
        }
    }

    /**
     * Wie sendCard, aber in einen bestimmten Kanal. Wird fuer den zweiten Kanal
     * gebraucht, in dem nur die Statistik landet. Ohne Kanal-ID geht die
     * Meldung in den Hauptkanal.
     */
    public void sendCardTo(String channel, String title, String body, int color) {
        mithoeren(channel, title, body, color, null);
        karteNachDiscord(channel, title, body, color);
    }

    /** Die Karte nach Discord -- ohne den Mithoerer (der hat sie schon). */
    private void karteNachDiscord(String channel, String title, String body, int color) {
        if (!ready()) return;

        String payload = "{\"embeds\":[{"
                + "\"title\":" + quote(cut(title, 250)) + ","
                + "\"description\":" + quote(cut(body, 3800)) + ","
                + "\"color\":" + color + ","
                + "\"footer\":{\"text\":\"Hustle Castle Bot\"},"
                + "\"timestamp\":\"" + java.time.Instant.now() + "\""
                + "}]}";
        if (kartenGehenNicht) {
            post(json("{\"content\":" + quote(alsText(title, body)) + "}", channel));
            return;
        }
        postKarte(json(payload, channel), channel, title, body);
    }

    /**
     * Karte mit Knoepfen darunter. Jeder Knopf ist ein Paar {Aufschrift,
     * Befehl}; ein Druck darauf wirkt wie der getippte Befehl (siehe
     * DiscordGateway, INTERACTION_CREATE). Hoechstens 25 Knoepfe (5 Reihen
     * zu je 5), Aufschrift hoechstens 80 Zeichen, Befehl hoechstens 100.
     */
    public void sendCardMitKnoepfen(String channel, String title, String body, int color,
                                    java.util.List<String[]> knoepfe) {
        mithoeren(channel, title, body, color, knoepfe);
        if (!ready()) return;
        if (!canUseBot() || knoepfe == null || knoepfe.isEmpty() || kartenGehenNicht) {
            karteNachDiscord(channel, title, body, color);
            return;
        }
        StringBuilder reihen = new StringBuilder("[");
        int anzahl = Math.min(25, knoepfe.size());
        for (int i = 0; i < anzahl; i++) {
            if (i % 5 == 0) {
                if (i > 0) reihen.append("]},");
                reihen.append("{\"type\":1,\"components\":[");
            } else {
                reihen.append(',');
            }
            String[] k = knoepfe.get(i);
            reihen.append("{\"type\":2,\"style\":").append(i == 0 ? 1 : 2)
                  .append(",\"label\":").append(quote(cut(k[0], 80)))
                  .append(",\"custom_id\":").append(quote(cut(k[1], 100))).append('}');
        }
        reihen.append("]}]");

        String payload = "{\"embeds\":[{"
                + "\"title\":" + quote(cut(title, 250)) + ","
                + "\"description\":" + quote(cut(body, 3800)) + ","
                + "\"color\":" + color + ","
                + "\"footer\":{\"text\":\"Hustle Castle Bot\"}"
                + "}],\"components\":" + reihen + "}";
        postKarte(json(payload, channel), channel, title, body);
    }

    // ------------------------------------------------------------------
    //  Karten, die leer ankommen
    // ------------------------------------------------------------------
    //
    // Ein Freund bekam auf !help eine LEERE Nachricht (23.09., Bild 30.1).
    // So sieht es aus, wenn dem Bot im Kanal das Recht "Links einbetten"
    // fehlt: Discord nimmt die Nachricht an, wirft die Karte aber still weg.
    // Der Bot merkt das an der Antwort (keine Karte darin) und schickt
    // dieselbe Meldung dann als normalen Text -- ab da immer.

    // Dauerhaft als Text (30.09., ein Freund sah !help und !settings leer, !log und
    // !screenshot aber nicht -- die Karten kamen bei Discord an, wurden bei ihm aber
    // nicht angezeigt; die Antwort des Servers gab keinen Hinweis). Mit "!text an"
    // schickt der Bot alles als normalen Text, gespeichert in nur-text.txt.
    private static final de.hustlebot.core.Schalterdatei NUR_TEXT =
            new de.hustlebot.core.Schalterdatei(de.hustlebot.core.Ordner.bei("nur-text.txt"), "Nur Text", false);
    private volatile boolean kartenGehenNicht = NUR_TEXT.lesen();

    /**
     * Rechte des Bots im Hauptkanal nachsehen (30.09.): Fehlt "Links einbetten", kommen alle
     * Karten (!help, !settings ...) LEER an, waehrend !log und !screenshot (Dateien) gehen.
     * Dann schaltet der Bot fuer diese Sitzung auf Text um und sagt es im Kanal -- als Text,
     * denn das kommt an. Rechnet wie Discord: Rollen des Bots, dann die Ausnahmen des Kanals
     * (erst @everyone, dann die Rollen, dann der Bot selbst). Administrator darf alles.
     *
     * @return der Hinweis fuer Discord, oder null wenn alles passt oder nicht pruefbar
     */
    public String rechtePruefen() {
        if (!canUseBot()) return null;
        try {
            JsonNode kanal = getJson(API + "/channels/" + channelId);
            if (kanal == null) {
                // Genau hier braucht der Freund einen Hinweis: Kanal-ID falsch oder "Kanal ansehen" fehlt.
                Log.warn("Discord", Sprache.t("Channel " + channelId + " cannot be fetched by the bot -- check the channel ID " + "(right-click the channel -> Copy ID) and whether the bot may see the channel.",
                        "Der Kanal " + channelId + " ist fuer den Bot nicht abrufbar -- Kanal-ID pruefen "
                        + "(Rechtsklick auf den Kanal -> ID kopieren) und ob der Bot den Kanal sehen darf."));
                return null;
            }
            String guild = kanal.path("guild_id").asText("");
            if (guild.isEmpty()) return null;
            JsonNode ich = getJson(API + "/users/@me");
            if (ich == null) return null;
            String botId = ich.path("id").asText("");
            // Fuer Bots geht nur /guilds/{id}/members/{bot-id} (das @me-Gegenstueck gibt 403).
            JsonNode mitglied = getJson(API + "/guilds/" + guild + "/members/" + botId);
            JsonNode rollen = getJson(API + "/guilds/" + guild + "/roles");
            if (mitglied == null || rollen == null) return null;
            java.util.Set<String> meine = new java.util.HashSet<>();
            for (JsonNode r : mitglied.path("roles")) meine.add(r.asText(""));
            long rechte = 0;
            for (JsonNode r : rollen) {
                String id = r.path("id").asText("");
                if (id.equals(guild) || meine.contains(id)) rechte |= Long.parseLong(r.path("permissions").asText("0"));
            }
            final long ADMIN = 1L << 3, KANAL_SEHEN = 1L << 10, SCHREIBEN = 1L << 11, EINBETTEN = 1L << 14, DATEIEN = 1L << 15;
            if ((rechte & ADMIN) == 0) {
                long erlaubtRollen = 0, verbotenRollen = 0, erlaubtBot = 0, verbotenBot = 0;
                for (JsonNode o : kanal.path("permission_overwrites")) {
                    String id = o.path("id").asText("");
                    long erlaubt = Long.parseLong(o.path("allow").asText("0"));
                    long verboten = Long.parseLong(o.path("deny").asText("0"));
                    if (id.equals(guild)) { rechte &= ~verboten; rechte |= erlaubt; }
                    else if (meine.contains(id)) { erlaubtRollen |= erlaubt; verbotenRollen |= verboten; }
                    else if (id.equals(botId)) { erlaubtBot |= erlaubt; verbotenBot |= verboten; }
                }
                rechte &= ~verbotenRollen; rechte |= erlaubtRollen;
                rechte &= ~verbotenBot; rechte |= erlaubtBot;
            }
            java.util.List<String> fehlt = new java.util.ArrayList<>();
            if ((rechte & ADMIN) == 0) {
                if ((rechte & KANAL_SEHEN) == 0) fehlt.add("Kanal ansehen / View Channel");
                if ((rechte & SCHREIBEN) == 0) fehlt.add("Nachrichten senden / Send Messages");
                if ((rechte & EINBETTEN) == 0) fehlt.add("Links einbetten / Embed Links");
                if ((rechte & DATEIEN) == 0) fehlt.add("Dateien anhaengen / Attach Files");
            }
            if (fehlt.isEmpty()) {
                Log.info("Discord", Sprache.t("Permissions in the channel checked: all there (view channel, send, embed links, files).",
                        "Rechte im Kanal geprueft: alles da (Kanal ansehen, senden, Links einbetten, Dateien)."));
                return null;
            }
            String text = "Dem Bot fehlen im Kanal Rechte: " + String.join(", ", fehlt)
                    + ". In Discord: Servereinstellungen -> Rollen -> Rolle des Bots (oder Kanal -> Berechtigungen)."
                    + " / The bot lacks permissions in this channel: " + String.join(", ", fehlt) + ".";
            Log.warn("Discord", text);
            if ((rechte & ADMIN) == 0 && (rechte & EINBETTEN) == 0) {
                kartenGehenNicht = true;   // nur fuer diese Sitzung -- nach dem Recht gehen die Karten wieder
                text = Sprache.t("Without \"Embed Links\" the bot's answers arrive empty -- until then it answers as text. ", "Ohne \"Links einbetten\" kommen die Antworten des Bots leer an -- bis dahin antwortet er als Text. ") + text;
            }
            return text;
        } catch (Exception e) {
            Log.debug("Discord", "Rechte nicht pruefbar: " + e.getMessage());
            return null;
        }
    }

    /** Normalen Text schicken (kommt auch ohne "Links einbetten" an). */
    public void sendText(String text) {
        mithoeren(null, "", text, FARBE_INFO, null);
        if (!ready()) return;
        post(json("{\"content\":" + quote(cut(text, 1900)) + "}"));
    }

    /** "!text an": ab jetzt (und nach jedem Start) nur normaler Text statt Karten. */
    public void setNurText(boolean an) {
        kartenGehenNicht = an;
        NUR_TEXT.setzen(an, "Antworten als normaler Text statt Karten (!text an / aus)");
    }

    public boolean nurText() {
        return kartenGehenNicht;
    }

    private static String alsText(String title, String body) {
        return cut("**" + title + "**\n" + body, 1900);
    }

    private void postKarte(HttpRequest request, String channel, String title, String body) {
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    consecutiveFailures = 0;
                    try {
                        JsonNode m = mapper.readTree(response.body());
                        if (m.path("embeds").isArray() && m.path("embeds").isEmpty()) {
                            if (!kartenGehenNicht) {
                                kartenGehenNicht = true;
                                Log.warn("Discord", Sprache.t("The bot's cards arrive empty -- it " + "lacks the \"Embed Links\" permission in the channel. From now on " + "it sends plain text. Better: give the bot " + "the \"Embed Links\" permission in Discord.",
                                        "Die Karten des Bots kommen leer an -- ihm "
                                        + "fehlt im Kanal das Recht \"Links einbetten\". Ab jetzt "
                                        + "schickt er normalen Text. Besser: in Discord dem Bot "
                                        + "das Recht \"Links einbetten\" geben."));
                            }
                            post(json("{\"content\":" + quote(alsText(title, body)) + "}", channel));
                        }
                    } catch (Exception ignored) {
                        // Antwort nicht lesbar -- die Karte ist trotzdem raus
                    }
                } else if (status == 429) {
                    Log.warn("Discord", Sprache.t("Too many messages, short break.", "Zu viele Nachrichten, kurze Pause."));
                    mutedUntil = System.currentTimeMillis() + 20_000;
                } else {
                    failure(Sprache.t("Discord answered with ", "Discord antwortete mit ") + status + ": " + cut(response.body(), 200));
                }
            })
            .exceptionally(error -> {
                failure(Sprache.t("Connection to Discord failed: ", "Verbindung zu Discord fehlgeschlagen: ") + error.getMessage());
                return null;
            });
    }

    /**
     * Meldung, die nicht mehrfach hintereinander erscheinen soll. Praktisch fuer
     * wiederkehrende Warnungen, die sonst den Kanal fluten wuerden.
     */
    public void sendOnce(String key, String title, String body, int color) {
        synchronized (recent) {
            if (recent.contains(key)) return;
            recent.addLast(key);
            while (recent.size() > 12) recent.removeFirst();
        }
        sendCard(title, body, color);
    }

    public void clearRecent() {
        synchronized (recent) {
            recent.clear();
        }
    }

    // ------------------------------------------------------------------
    //  Dateien und Bilder
    // ------------------------------------------------------------------

    /** Schickt ein Bild oder eine Datei mit einem kurzen Begleittext. */
    public void sendFile(String comment, String fileName, byte[] content) {
        if (content != null) mithoeren(null, comment, "(File: " + fileName + ", " + (content.length / 1024) + " KB)", FARBE_INFO, null);
        if (!ready() || content == null || content.length == 0) return;

        String boundary = "----hustle" + Math.abs(random.nextLong());
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            String payload = mitStumm("{\"content\":" + quote(cut(comment, 1900)) + "}");
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n"
                    + payload + "\r\n").getBytes(StandardCharsets.UTF_8));

            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files[0]\"; filename=\""
                    + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(target()))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));

            if (canUseBot()) builder.header("Authorization", "Bot " + botToken);

            fire(builder.build());
        } catch (Exception e) {
            failure(Sprache.t("File could not be sent: ", "Datei konnte nicht gesendet werden: ") + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Ein Bild, das sich selbst erneuert (!live)
    // ------------------------------------------------------------------

    /**
     * Schickt ein Bild und wartet auf die Antwort -- noetig, weil !live die
     * Nummer der Nachricht braucht, um sie danach immer wieder zu ersetzen.
     *
     * @return die Nachrichtennummer, oder "" wenn es nicht geklappt hat
     */
    public String sendFileAndGetId(String channel, String comment, String fileName, byte[] content) {
        if (!ready() || !canUseBot() || content == null) return "";
        try {
            HttpResponse<String> r = http.send(multipart("POST", target(channel), comment,
                    fileName, content, false), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) return "";
            return mapper.readTree(r.body()).path("id").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Ersetzt Text und Bild einer schon geschickten Nachricht.
     *
     * @return Wartezeit in Millisekunden, die Discord verlangt (0 = alles gut,
     *         -1 = Nachricht weg oder anderer Fehler)
     */
    public long replaceFile(String channel, String messageId, String comment,
                            String fileName, byte[] content) {
        if (!canUseBot() || messageId == null || messageId.isBlank() || content == null) return -1;
        try {
            HttpResponse<String> r = http.send(multipart("PATCH",
                    target(channel) + "/" + messageId, comment, fileName, content, true),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) {
                double sek = mapper.readTree(r.body()).path("retry_after").asDouble(2.0);
                return Math.max(500, (long) (sek * 1000));
            }
            return (r.statusCode() >= 200 && r.statusCode() < 300) ? 0 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Baut eine Nachricht mit Datei. Beim Ersetzen fliegen die alten Anhaenge raus. */
    private HttpRequest multipart(String methode, String adresse, String comment,
                                  String fileName, byte[] content, boolean alteAnhaengeWeg)
            throws java.io.IOException {
        String boundary = "----hustle" + Math.abs(random.nextLong());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String payload = "{\"content\":" + quote(cut(comment, 1900))
                + (alteAnhaengeWeg ? ",\"attachments\":[]" : "") + "}";
        if (!alteAnhaengeWeg) payload = mitStumm(payload);   // Aendern klingelt ohnehin nicht
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + payload + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\""
                + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return HttpRequest.newBuilder()
                .uri(URI.create(adresse))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bot " + botToken)
                .method(methode, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    // ------------------------------------------------------------------
    //  Innereien
    // ------------------------------------------------------------------

    private boolean ready() {
        if (!isEnabled()) return false;
        if (System.currentTimeMillis() < mutedUntil) return false;
        return true;
    }

    private String target() {
        return target(null);
    }

    /**
     * Wohin die Nachricht geht. Mit Bot-Zugang laesst sich jeder Kanal
     * ansteuern; ueber einen Webhook geht nur der eine fest eingetragene.
     */
    private String target(String channel) {
        if (!canUseBot()) return webhookUrl;
        String id = (channel == null || channel.isBlank()) ? channelId : channel.trim();
        return API + "/channels/" + id + "/messages";
    }

    /**
     * Sucht einen Textkanal im selben Server am NAMEN und liefert seine Kennung.
     *
     * Damit muss fuer einen zusaetzlichen Kanal keine Kennung von Hand
     * abgeschrieben werden: Kanal in Discord anlegen, passend benennen, fertig.
     * Der Bot findet ihn beim naechsten Start von selbst.
     *
     * Gebraucht werden dafuer keine Sonderrechte -- der Bot muss den Kanal nur
     * sehen duerfen. Gross- und Kleinschreibung sind egal, und es werden
     * mehrere Schreibweisen durchprobiert.
     *
     * Liefert eine leere Zeichenkette, wenn nichts passt oder etwas schiefgeht.
     * Der Bot laeuft dann ohne den Kanal weiter.
     */
    public String findChannelByName(String... names) {
        if (!canUseBot() || channelId.isBlank()) return "";

        try {
            // Ueber den bekannten Kanal kommt man an den Server.
            JsonNode channel = getJson(API + "/channels/" + channelId);
            if (channel == null || channel.get("guild_id") == null) return "";
            String guildId = channel.get("guild_id").asText();

            JsonNode all = getJson(API + "/guilds/" + guildId + "/channels");
            if (all == null || !all.isArray()) return "";

            for (String wanted : names) {
                if (wanted == null || wanted.isBlank()) continue;
                String needle = wanted.trim().toLowerCase();

                for (JsonNode node : all) {
                    // 0 ist ein normaler Textkanal. Sprachkanaele und
                    // Kategorien scheiden aus.
                    if (node.path("type").asInt(-1) != 0) continue;

                    String name = node.path("name").asText("").toLowerCase();
                    if (name.equals(needle)) {
                        return node.path("id").asText("");
                    }
                }
            }
        } catch (Exception e) {
            Log.debug("Discord", "Kanalsuche nicht moeglich: " + e.getMessage());
        }
        return "";
    }

    /**
     * Loescht alte Nachrichten des Bots in einem Kanal.
     *
     * Gedacht gegen zulaufende Kanaele: In pc-specs kommt alle fuenf Minuten
     * eine Karte, das sind ueber zweihundert am Tag. Wer nach einer Woche
     * etwas sucht, scrollt sich tot.
     *
     * WICHTIG -- es werden ausschliesslich Nachrichten geloescht, die der Bot
     * SELBST geschrieben hat. Was Autor getippt hat, bleibt stehen. Zum einen,
     * weil seine Befehle die Geschichte lesbar halten; zum anderen, weil das
     * Loeschen fremder Nachrichten ein ganz anderes Recht braucht und schnell
     * mehr trifft, als gemeint war.
     *
     * Geloescht wird einzeln und langsam. Discord laesst nur ein paar
     * Loeschungen je Sekunde zu, und ein Bot, der dagegen anrennt, wird
     * voruebergehend ausgesperrt -- dann kaemen auch keine Alarme mehr durch.
     * Lieber ein paar Durchgaenge mehr.
     *
     * @param channel        Kanalnummer
     * @param aelterAlsStd   Alter in Stunden, ab dem geloescht wird
     * @param hoechstens     wie viele in diesem Durchgang hoechstens
     * @return wie viele geloescht wurden
     */
    public int deleteOldMessages(String channel, int aelterAlsStd, int hoechstens) {
        if (!canUseBot() || channel == null || channel.isBlank()) return 0;

        try {
            JsonNode messages = getJson(API + "/channels/" + channel + "/messages?limit=100");
            if (messages == null || !messages.isArray()) return 0;

            long grenze = System.currentTimeMillis() - aelterAlsStd * 3600_000L;
            int geloescht = 0;

            for (JsonNode m : messages) {
                if (geloescht >= hoechstens) break;

                // Eigene Nachrichten -- und seit dem 23.09. auch die Befehle,
                // die Autor getippt hat (Punkt 9: "meine Nachrichten bleiben").
                // Andere Bots und Webhooks (Lebenszeichen) bleiben stehen.
                // Fremde Nachrichten loeschen braucht das Recht "Nachrichten
                // verwalten"; fehlt es, wird es einmal gemeldet und dann
                // nicht mehr versucht.
                boolean istBot = m.path("author").path("bot").asBoolean(false);
                boolean eigene = istBot && m.path("author").path("id").asText("").equals(ownUserId());
                // Von Menschen nur BEFEHLE ("!..."), kein Gespraech -- sonst
                // verschwaende nach 24 Stunden auch, was sich Leute im Kanal
                // geschrieben haben.
                boolean mensch = !istBot && m.path("webhook_id").isMissingNode()
                        && m.path("content").asText("").strip().startsWith("!");
                if (!eigene && !(mensch && !fremdeVerboten)) continue;

                String stamp = m.path("timestamp").asText("");
                if (stamp.isEmpty()) continue;

                long wann;
                try {
                    wann = java.time.OffsetDateTime.parse(stamp).toInstant().toEpochMilli();
                } catch (Exception e) {
                    continue;
                }
                if (wann > grenze) continue;   // noch zu jung

                int status = deleteStatus(channel, m.path("id").asText(""));
                if (status == 403 && mensch) {
                    if (!fremdeVerboten) {
                        fremdeVerboten = true;
                        Log.info("Discord", Sprache.t("The bot cannot delete your own commands -- " + "it lacks the \"Manage Messages\" permission. It keeps " + "cleaning up its own messages.",
                                "Deine eigenen Befehle kann der Bot nicht loeschen -- "
                                + "ihm fehlt das Recht \"Nachrichten verwalten\". Seine eigenen "
                                + "Nachrichten raeumt er weiter auf."));
                    }
                    continue;
                }
                if (status >= 200 && status < 300) {
                    geloescht++;
                    try {
                        Thread.sleep(1200);   // Discord nicht ueberrennen
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return geloescht;

        } catch (Exception e) {
            Log.debug("Discord", "Aufraeumen fehlgeschlagen: " + e.getMessage());
            return 0;
        }
    }

    /** Fehlt das Recht, fremde Nachrichten zu loeschen? Dann nicht jedes Mal neu versuchen. */
    private volatile boolean fremdeVerboten = false;

    /** Loescht eine einzelne Nachricht. True, wenn es geklappt hat. */
    private boolean deleteMessage(String channel, String messageId) {
        int s = deleteStatus(channel, messageId);
        return s >= 200 && s < 300;
    }

    /** Loescht eine Nachricht und liefert die Antwort von Discord (0 bei Netzfehler). */
    private int deleteStatus(String channel, String messageId) {
        if (messageId.isEmpty()) return 0;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API + "/channels/" + channel + "/messages/" + messageId))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bot " + botToken)
                    .DELETE()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();

        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * "!clear" -- leert einen Kanal ganz (Autor, Punkt 9: "einen Chat in
     * einem Channel komplett clearen ... ohne alles einzeln zu loeschen").
     *
     * Nachrichten bis 14 Tage alt gehen in Hunderter-Paketen auf einmal weg
     * (Sammelloeschung). Aeltere laesst Discord nur einzeln loeschen -- davon
     * hoechstens 100 je Aufruf, damit der Bot nicht gesperrt wird.
     *
     * @return wie viele geloescht wurden; -1 wenn das Recht "Nachrichten
     *         verwalten" fehlt
     */
    public int kanalLeeren(String channel) {
        if (!canUseBot() || channel == null || channel.isBlank()) return 0;
        long grenze = System.currentTimeMillis() - 13L * 24 * 3600_000L;   // Sicherheitsabstand
        int geloescht = 0;
        int einzeln = 0;
        try {
            for (int runde = 0; runde < 20; runde++) {
                JsonNode messages = getJson(API + "/channels/" + channel + "/messages?limit=100");
                if (messages == null || !messages.isArray() || messages.isEmpty()) break;

                List<String> jung = new ArrayList<>();
                List<String> alt = new ArrayList<>();
                for (JsonNode m : messages) {
                    long wann;
                    try {
                        wann = java.time.OffsetDateTime.parse(m.path("timestamp").asText(""))
                                .toInstant().toEpochMilli();
                    } catch (Exception e) {
                        continue;
                    }
                    (wann > grenze ? jung : alt).add(m.path("id").asText(""));
                }

                if (jung.size() >= 2) {
                    String koerper = "{\"messages\":[\"" + String.join("\",\"", jung) + "\"]}";
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(API + "/channels/" + channel + "/messages/bulk-delete"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bot " + botToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(koerper))
                            .build();
                    int s = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
                    if (s == 403) return -1;
                    if (s >= 200 && s < 300) geloescht += jung.size();
                    Thread.sleep(1500);
                    continue;
                }
                List<String> einzelne = new ArrayList<>(jung);
                einzelne.addAll(alt);
                if (einzelne.isEmpty()) break;
                boolean etwasGeloescht = false;
                for (String id : einzelne) {
                    if (einzeln >= 100) return geloescht;
                    int s = deleteStatus(channel, id);
                    if (s == 403) return geloescht == 0 ? -1 : geloescht;
                    if (s >= 200 && s < 300) {
                        geloescht++;
                        etwasGeloescht = true;
                    }
                    einzeln++;
                    Thread.sleep(1200);
                }
                if (!etwasGeloescht) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.debug("Discord", "Kanal leeren fehlgeschlagen: " + e.getMessage());
        }
        return geloescht;
    }

    /** Die eigene Nutzernummer, einmal geholt und dann gemerkt. */
    private volatile String ownId = null;

    private String ownUserId() {
        if (ownId != null) return ownId;
        JsonNode me = getJson(API + "/users/@me");
        ownId = me == null ? "" : me.path("id").asText("");
        return ownId;
    }
    /** Holt eine Adresse der Discord-Schnittstelle und liest die Antwort als JSON. */
    private JsonNode getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bot " + botToken)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            de.hustlebot.core.Weltuhr.datumKopf(response.headers());   // Konrad 19.11.: Discord sagt die Uhrzeit

            return mapper.readTree(response.body());
        } catch (Exception e) {
            return null;
        }
    }

    private HttpRequest json(String payload) {
        return json(payload, null);
    }

    // ------------------------------------------------------------------
    //  !mute -- Nachrichten ohne Benachrichtigung
    // ------------------------------------------------------------------
    //
    // Autor, 23.09.: Die Nachrichten sollen weiter ankommen, aber ohne
    // Benachrichtigung -- "das gleiche als wuerde ich selbststaendig meinen
    // Channel muten". Ein Bot kann einen Kanal nicht fuer Autor stummschalten
    // (das ist eine Einstellung in Autors Discord). Er kann aber jede eigene
    // Nachricht als "stumm" kennzeichnen (Discord-Kennzeichen 4096, dasselbe
    // wie "@silent"). Dann klingelt nichts, die Nachricht steht trotzdem da.
    // Autors Wahl: WIRKLICH alles stumm, auch Fehler.

    private volatile boolean stumm = false;

    public void setStumm(boolean an) { this.stumm = an; }
    public boolean istStumm() { return stumm; }

    private String mitStumm(String payload) {
        if (!stumm || payload == null || !payload.startsWith("{")) return payload;
        return "{\"flags\":4096," + payload.substring(1);
    }

    private HttpRequest json(String payload, String channel) {
        payload = mitStumm(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target(channel)))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

        if (canUseBot()) builder.header("Authorization", "Bot " + botToken);
        return builder.build();
    }

    /** Senden laeuft im Hintergrund, damit der Bot nie darauf wartet. */
    private void post(HttpRequest request) {
        fire(request);
    }

    private void fire(HttpRequest request) {
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    consecutiveFailures = 0;
                } else if (status == 429) {
                    Log.warn("Discord", Sprache.t("Too many messages, short break.", "Zu viele Nachrichten, kurze Pause."));
                    mutedUntil = System.currentTimeMillis() + 20_000;
                } else {
                    failure(Sprache.t("Discord answered with ", "Discord antwortete mit ") + status + ": " + cut(response.body(), 200));
                }
            })
            .exceptionally(error -> {
                failure(Sprache.t("Connection to Discord failed: ", "Verbindung zu Discord fehlgeschlagen: ") + error.getMessage());
                return null;
            });
    }

    private void failure(String message) {
        consecutiveFailures++;
        Log.warn("Discord", message);

        if (consecutiveFailures >= 5) {
            mutedUntil = System.currentTimeMillis() + 300_000;
            consecutiveFailures = 0;
            Log.error("Discord", Sprache.t("Five failed attempts in a row. " + "Discord is skipped for five minutes. " + "Please check the webhook address or the access token.",
                    "Fuenf Fehlversuche hintereinander. "
                    + "Discord wird fuer fuenf Minuten uebersprungen. "
                    + "Bitte Webhook-Adresse beziehungsweise Zugangsschluessel pruefen."));
        }
    }

    // ------------------------------------------------------------------
    //  Werkzeug
    // ------------------------------------------------------------------

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cut(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    /** Baut eine JSON-Zeichenkette mit allen noetigen Maskierungen. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
