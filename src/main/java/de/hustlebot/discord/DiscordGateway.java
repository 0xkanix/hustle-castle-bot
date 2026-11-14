package de.hustlebot.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.hustlebot.core.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import de.hustlebot.core.Sprache;

/**
 * Haelt eine Verbindung zu Discord offen und lauscht auf Befehle im Kanal.
 *
 * Discord schickt Nachrichten ueber eine dauerhafte Verbindung, das sogenannte
 * Gateway. Die Abwicklung ist hier von Hand gebaut, damit keine zusaetzliche
 * Bibliothek noetig ist: anmelden, regelmaessig ein Lebenszeichen senden, auf
 * eingehende Nachrichten reagieren und bei Abbruch neu verbinden.
 */
public final class DiscordGateway {

    private static final String GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";

    /** Erlaubnisse: Nachrichten im Server, deren Inhalt, und Direktnachrichten. */
    private static final int INTENTS = (1 << 9) | (1 << 15) | (1 << 12);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "discord-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final String token;
    /** Alle Kanaele, auf die gehoert wird. Leere Eintraege sind aussortiert. */
    private final java.util.Set<String> channelIds = new java.util.LinkedHashSet<>();
    /**
     * Wer einen Befehl geschickt hat: Anzeigename, Nutzer-ID und der Server (Guild).
     *
     * Die ID ist das, was zaehlt (Sicherheit 16.10.): Namen kann sich jeder
     * geben, die ID vergibt Discord. Mit ihr prueft {@link Rechte}, ob der
     * Absender die gefaehrlichen Befehle darf. guild ist leer bei
     * Direktnachrichten.
     */
    public record Absender(String name, String id, String guild) {
        public Absender {
            name = name == null || name.isBlank() ? "jemand" : name;
            // Nur echte Discord-IDs (reine Zahlen) oder der eigene Rechner -- sonst leer, und leer darf nichts Gefaehrliches.
            id = id != null && (id.matches("\\d{5,25}") || Rechte.EIGENER_RECHNER.equals(id)) ? id : "";
            guild = guild == null ? "" : guild.trim();
        }

        /** Ein Befehl vom eigenen Rechner (Steuerprogramm): darf alles, wird nie gebremst. */
        public static Absender eigenerRechner() {
            return new Absender("Rechner", Rechte.EIGENER_RECHNER, "");
        }

        /** Kam der Befehl ueber Discord (und nicht vom eigenen Rechner)? */
        public boolean vonDiscord() {
            return !Rechte.EIGENER_RECHNER.equals(id);
        }
    }

    /**
     * Bekommt Kanal-ID, Befehlstext und den Absender.
     *
     * Der Absender kam frueher nicht mit -- es reichte zu wissen, WO ein
     * Befehl getippt wurde. Spaeter
     * zaehlte auch, WER. Seit 16.10. kommt die Nutzer-ID
     * mit ({@link Absender}), damit die gefaehrlichen Befehle nur der
     * Besitzer des Servers ausfuehren kann.
     */
    @FunctionalInterface
    public interface Empfaenger {
        void empfangen(String kanal, String inhalt, Absender absender);
    }

    private final Empfaenger onCommand;
    /** Wer darf die gefaehrlichen Befehle (Besitzer des Servers + erlaubte IDs). */
    private final Rechte rechte;
    /** Foto-/Berichts-Befehle hoechstens alle 5 s je Nutzer. */
    private final Flutbremse bremse;
    /** Kurze Antwort in einen Kanal (Kanal-ID, Text) -- gesetzt von der Bruecke, sonst nur Protokoll. */
    private volatile java.util.function.BiConsumer<String, String> antwort = (k, t) -> { };
    /** Die Server aus READY, fuer das Nachladen des Besitzers. */
    private volatile java.util.List<String> guilds = java.util.List.of();
    private volatile long besitzerVersuchUm = 0;
    private volatile boolean besitzerHinweisGegeben = false;

    private volatile WebSocket socket;
    private volatile boolean running = false;
    private volatile Integer lastSequence = null;
    private volatile boolean acknowledged = true;
    private volatile int reconnectAttempts = 0;
    /** True, sobald Discord die Anmeldung bestaetigt hat (READY). */
    private volatile boolean identified = false;
    /** Der laufende Lebenszeichen-Takt, damit er beim Neuverbinden abgeloest wird. */
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;

    /**
     * Laufende Nummer der Verbindung (14.10., Punkt 7). Rueckrufe einer ALTEN Verbindung (Schliessen,
     * Fehler, Nachrichten) werden ignoriert. Vorher: 14.10. 20:14 loesten "Verbindung geschlossen (1006)"
     * und eine Sekunde spaeter "Kein Lebenszeichen zurueck" ZWEI Neuverbindungen aus. Die zweite nahm der
     * ersten den Lebenszeichen-Takt weg, Discord schloss die erste nach ~50 s (1000), das loeste die
     * naechste Neuverbindung aus -- 20 Mal in 45 Minuten. In jeder Luecke gingen Discord-Befehle
     * (!stop, !pause) verloren, weil ohne RESUME nichts nachgeholt wird.
     */
    private volatile int verbindungNr = 0;
    /** Steht schon ein Neuverbinden an? Zwei Ausloeser ergeben dann EIN Neuverbinden. */
    private volatile boolean neuverbindenAngesetzt = false;
    /** Sitzung fuer RESUME: Discord holt damit verpasste Nachrichten nach (ein !stop waehrend der Luecke). */
    private volatile String sessionId = null;
    private volatile String resumeUrl = null;

    public DiscordGateway(String token, java.util.List<String> channels,
                          Empfaenger onCommand) {
        this(token, channels, onCommand, new Rechte(java.util.Set.of()), new Flutbremse());
    }

    public DiscordGateway(String token, java.util.List<String> channels,
                          Empfaenger onCommand, Rechte rechte, Flutbremse bremse) {
        this.token = token == null ? "" : token.trim();
        if (channels != null) {
            for (String id : channels) {
                if (id != null && !id.trim().isEmpty()) channelIds.add(id.trim());
            }
        }
        this.onCommand = onCommand;
        this.rechte = rechte == null ? new Rechte(java.util.Set.of()) : rechte;
        this.bremse = bremse == null ? new Flutbremse() : bremse;
    }

    public Rechte rechte() {
        return rechte;
    }

    /** Wohin kurze Hinweise gehen (Kanal-ID, Text) -- z. B. "Besitzer unbekannt" oder "bitte warten". */
    public void setAntwort(java.util.function.BiConsumer<String, String> ziel) {
        if (ziel != null) antwort = ziel;
    }

    /** Eine rohe Gateway-Nachricht (JSON) verarbeiten -- fuer die Tests ohne Netz. */
    void verarbeiten(String rohesJson) {
        handle(verbindungNr, rohesJson);
    }

    // ------------------------------------------------------------------
    //  Besitzer des Servers (Sicherheit 16.10.)
    // ------------------------------------------------------------------

    /**
     * Beim READY: fuer jeden Server den Besitzer holen (GET /guilds/{id} ->
     * owner_id). Klappt es nicht, nach 5 s noch einmal; bleibt es leer, sind
     * die gefaehrlichen Befehle gesperrt (Sicherheitsmodus), und das steht
     * einmal im Kanal.
     */
    private void besitzerErmitteln(JsonNode ready) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode g : ready.path("guilds")) {
            String id = g.path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        guilds = java.util.List.copyOf(ids);
        if (ids.isEmpty()) Log.info("Discord", Sprache.t("READY without a server list -- looking for the server via the channels.",
                "READY ohne Server-Liste -- der Server wird ueber die Kanaele gesucht."));
        besitzerNachladen(true);
    }

    /** Ersatzweg, wenn READY keine Server nennt: GET /channels/{id} -> guild_id fuer jeden Bot-Kanal. */
    private java.util.List<String> guildsAusKanaelen() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String kanal : channelIds) {
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        URI.create("https://discord.com/api/v10/channels/" + kanal))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bot " + token)
                        .GET().build();
                var r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() < 200 || r.statusCode() >= 300) continue;
                String g = mapper.readTree(r.body()).path("guild_id").asText("");
                if (g.matches("\\d{5,25}") && !ids.contains(g)) ids.add(g);
            } catch (Exception e) {
                Log.debug("Discord", "GET /channels/" + kanal + " fehlgeschlagen: " + e.getMessage());
            }
        }
        return ids;
    }

    /**
     * Noch einmal versuchen, den Besitzer zu holen -- etwa, wenn ein Befehl
     * wegen "Besitzer unbekannt" abgelehnt wurde. Hoechstens alle 60 s.
     */
    public void besitzerNachladen() {
        besitzerNachladen(false);
    }

    private void besitzerNachladen(boolean sofort) {
        if (!guilds.isEmpty() && guilds.stream().allMatch(rechte::besitzerBekannt)) return;
        long jetzt = System.currentTimeMillis();
        if (!sofort && jetzt - besitzerVersuchUm < 60_000) return;
        besitzerVersuchUm = jetzt;
        Thread t = new Thread(() -> {
            java.util.List<String> alle = guilds;
            if (alle.isEmpty()) {
                alle = guildsAusKanaelen();
                guilds = alle;
            }
            if (alle.isEmpty()) {
                Log.warn("Discord", Sprache.t("No server found -- the owner stays unknown, dangerous commands are locked.",
                        "Kein Server gefunden -- der Besitzer bleibt unbekannt, gefaehrliche Befehle sind gesperrt."));
            }
            java.util.List<String> offen = new java.util.ArrayList<>();
            for (String g : alle) if (!rechte.besitzerBekannt(g)) offen.add(g);
            for (String g : offen) {
                String owner = besitzerHolen(g);
                if (owner.isEmpty()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    owner = besitzerHolen(g);
                }
                if (!owner.isEmpty()) {
                    rechte.besitzerSetzen(g, owner);
                    Log.info("Discord", Sprache.t("Owner of server " + g + " is user " + owner + " -- only they (and discord.erlaubteNutzer) may use !pc, !off, !settings, !clear, !screenshot, !live.",
                            "Besitzer des Servers " + g + " ist Nutzer " + owner
                            + " -- nur er (und discord.erlaubteNutzer) darf !pc, !off, !settings, !clear, !screenshot, !live."));
                } else {
                    Log.warn("Discord", Sprache.t("Owner of server " + g + " could not be determined -- " + "safety mode: !pc, !off, !settings, !clear, !screenshot, !live are locked.",
                            "Besitzer des Servers " + g + " konnte nicht ermittelt werden -- "
                            + "Sicherheitsmodus: !pc, !off, !settings, !clear, !screenshot, !live sind gesperrt."));
                    if (!besitzerHinweisGegeben && !channelIds.isEmpty()) {
                        besitzerHinweisGegeben = true;
                        hinweis(channelIds.iterator().next(), de.hustlebot.core.Sprache.t(
                                "⚠️ The bot could not find out who owns this Discord server. For safety, `!pc`, `!off`, "
                                + "`!settings`, `!clear`, `!screenshot` and `!live` are locked until that works "
                                + "(it tries again when one of them is used). Other commands work as usual.",
                                "⚠️ Der Bot konnte nicht feststellen, wem dieser Discord-Server gehoert. Aus Sicherheitsgruenden "
                                + "sind `!pc`, `!off`, `!settings`, `!clear`, `!screenshot` und `!live` gesperrt, bis das klappt "
                                + "(er versucht es wieder, sobald einer davon getippt wird). Alle anderen Befehle gehen wie gewohnt."));
                    }
                }
            }
        }, "discord-besitzer");
        t.setDaemon(true);
        t.start();
    }

    /** GET /guilds/{id} -> owner_id, oder "" bei jedem Fehler. */
    private String besitzerHolen(String guildId) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                    URI.create("https://discord.com/api/v10/guilds/" + guildId))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token)
                    .GET().build();
            var r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                Log.debug("Discord", "GET /guilds/" + guildId + ": " + r.statusCode());
                return "";
            }
            return besitzerAus(r.body());
        } catch (Exception e) {
            Log.debug("Discord", "GET /guilds/" + guildId + " fehlgeschlagen: " + e.getMessage());
            return "";
        }
    }

    /** Das Feld owner_id aus der Antwort von GET /guilds/{id}, oder "". */
    static String besitzerAus(String json) {
        try {
            String id = new ObjectMapper().readTree(json).path("owner_id").asText("");
            return id.matches("\\d{5,25}") ? id : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void hinweis(String kanal, String text) {
        try {
            antwort.accept(kanal, text);
        } catch (Exception e) {
            Log.debug("Discord", "Hinweis nicht geschickt: " + e.getMessage());
        }
    }

    /** Absender einer Nachricht (author) oder einer Interaktion (member.user / user). */
    private static Absender absenderAus(JsonNode nutzer, JsonNode traeger) {
        String id = nutzer.path("id").asText("");
        if (!id.matches("\\d{5,25}")) id = "";   // aus Discord kommen nur Zahlen; alles andere zaehlt als "unbekannt"
        return new Absender(nutzer.path("username").asText("jemand"), id, traeger.path("guild_id").asText(""));
    }

    /**
     * Nimmt einen weiteren Kanal in die Liste auf, auf den gehoert wird.
     *
     * Noetig, weil zwei Kanaele erst nach dem Start am Namen gesucht werden:
     * "wichtig" und "pc-specs". Die konnten dem Gateway beim Bauen noch nicht
     * mitgegeben werden -- und genau deshalb blieb ein !specs, das in
     * #pc-specs getippt wurde, unbeantwortet. Der Bot hat es nie gehoert,
     * denn Nachrichten aus unbekannten Kanaelen werden verworfen.
     */
    public void addChannel(String id) {
        if (id == null || id.trim().isEmpty()) return;
        if (channelIds.add(id.trim())) {
            Log.debug("Discord", "Hoert ab jetzt auch auf Kanal " + id.trim() + ".");
        }
    }

    /**
     * Kanaele, in denen Webhook-Nachrichten gelesen werden (nicht nur
     * Befehle). Gebraucht fuer die Lebenszeichen der Freunde-Bots: Die kommen
     * ueber einen Webhook in einen stummen Sammelkanal, und der Bot reicht
     * nur die gewuenschten nach #schluessel weiter (!lebenszeichen).
     */
    private final java.util.Set<String> webhookKanaele = new java.util.HashSet<>();
    private volatile java.util.function.BiConsumer<String, String> onWebhook = (k, t) -> { };

    // ------------------------------------------------------------------
    //  Formulare: !start und !settings (der fruehere !fix-Versand an den Besitzer ist entfernt)
    // ------------------------------------------------------------------

    /**
     * Anbindung an die Formulare. Ein Formular (Discord: "Modal") geht nur als
     * Antwort auf einen Knopf oder einen Schraegstrich-Befehl auf -- nicht auf
     * eine getippte Nachricht.
     */
    public interface FixAnbindung {
        /**
         * Das !start-Formular (Autor, 27.09.) -- oder null. Knopf "startformular:event"
         * (29.09.) oeffnet den zweiten Schritt: die Einstellungen des Event-Ablaufs.
         */
        default String startFormular(String autor, String knopf) { return null; }

        /** Das ausgefuellte !start-Formular; Mehrfachauswahlen stehen mit Komma getrennt. */
        default void startEingereicht(String kanal, String autor, java.util.Map<String, String> felder) { }

        /** Ein Einstellungs-Formular (Bereich z. B. "krieg") -- oder null. */
        default String setFormular(String autor, String bereich) { return null; }

        /** Ein ausgefuelltes Einstellungs-Formular. */
        default void setEingereicht(String kanal, String autor, String bereich, java.util.Map<String, String> felder) { }
    }

    private volatile FixAnbindung fix;
    private volatile boolean fixBefehlAngemeldet;

    public void setFixAnbindung(FixAnbindung anbindung) {
        this.fix = anbindung;
    }

    public void webhookKanal(String id, java.util.function.BiConsumer<String, String> empfaenger) {
        if (id == null || id.isBlank() || empfaenger == null) return;
        webhookKanaele.add(id.trim());
        onWebhook = empfaenger;
    }

    public boolean isConfigured() {
        return !token.isEmpty() && !channelIds.isEmpty();
    }

    // ------------------------------------------------------------------
    //  Verbindung
    // ------------------------------------------------------------------

    public void start() {
        if (!isConfigured()) {
            Log.info("Discord", Sprache.t("No access token stored, commands are switched off.",
                    "Kein Zugangsschluessel hinterlegt, Befehle sind ausgeschaltet."));
            return;
        }
        running = true;
        connect();
    }

    private void connect() {
        if (!running) return;
        neuverbindenAngesetzt = false;

        // Die alte Verbindung endgueltig schliessen -- ihre Rueckrufe zaehlen ab jetzt nicht mehr.
        WebSocket alt = socket;
        socket = null;
        if (alt != null) {
            try { alt.abort(); } catch (Exception ignored) { /* schon zu */ }
        }
        final int nr = ++verbindungNr;
        final boolean fortsetzen = sessionId != null && resumeUrl != null;
        if (!fortsetzen) lastSequence = null;
        String url = fortsetzen ? resumeUrl : GATEWAY;

        Log.info("Discord", Sprache.t(fortsetzen ? "Connecting to Discord (resuming the session) …" : "Connecting to Discord …",
                fortsetzen ? "Verbinde mit Discord (Sitzung wird fortgesetzt) …" : "Verbinde mit Discord …"));
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create(url), new Listener(nr))
            .whenComplete((ws, error) -> {
                if (nr != verbindungNr) return;   // inzwischen gibt es eine neuere Verbindung
                if (error != null) {
                    Log.warn("Discord", Sprache.t("Connection failed: " + error.getMessage(),
                            "Verbindung fehlgeschlagen: " + error.getMessage()));
                    if (fortsetzen) {   // die Fortsetz-Adresse taugt nicht: beim naechsten Mal neu anmelden
                        sessionId = null;
                        resumeUrl = null;
                    }
                    scheduleReconnect();
                } else {
                    // Fehlversuche erst bei READY/RESUMED auf 0 (Review 15.10.): sonst bleibt die Pause bei 2 s,
                    // auch wenn Discord nach dem Verbinden immer wieder ablehnt -- 1000 IDENTIFY/Tag setzen den Token zurueck.
                    socket = ws;
                }
            });
    }

    private void scheduleReconnect() {
        if (!running) return;
        // Schliessen + "kein Lebenszeichen" (oder zwei Fehler) ergeben EIN Neuverbinden, nicht zwei.
        if (neuverbindenAngesetzt) return;
        neuverbindenAngesetzt = true;
        java.util.concurrent.ScheduledFuture<?> takt = heartbeatTask;
        if (takt != null) takt.cancel(false);

        reconnectAttempts++;
        // Wartezeit waechst mit jedem Fehlversuch, hoechstens zwei Minuten.
        long seconds = Math.min(120, (long) Math.pow(2, Math.min(7, reconnectAttempts)));
        Log.info("Discord", Sprache.t("New connection attempt in " + seconds + " seconds.",
                "Neuer Verbindungsversuch in " + seconds + " Sekunden."));
        timer.schedule(this::connect, seconds, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        WebSocket current = socket;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "Programmende");
            } catch (Exception ignored) {
                // Beim Beenden nicht weiter wichtig.
            }
        }
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------
    //  Nachrichtenverkehr
    // ------------------------------------------------------------------

    /** Schickt etwas an Discord. Liefert false, wenn keine Verbindung offen ist. */
    private boolean send(String payload) {
        WebSocket current = socket;
        if (current == null) {
            Log.warn("Discord", Sprache.t("Nothing could be sent: no open connection.",
                    "Es konnte nichts gesendet werden: keine offene Verbindung."));
            return false;
        }
        current.sendText(payload, true);
        return true;
    }

    private void identify() {
        String payload = "{\"op\":2,\"d\":{"
                + "\"token\":" + DiscordClient.quote(token) + ","
                + "\"intents\":" + INTENTS + ","
                + "\"properties\":{\"os\":\"windows\",\"browser\":\"hustle-bot\","
                + "\"device\":\"hustle-bot\"}}}";
        if (!send(payload)) {
            Log.error("Discord", Sprache.t("The login could not be sent. " + "Reconnecting.",
                    "Die Anmeldung konnte nicht abgeschickt werden. "
                    + "Es wird neu verbunden."));
            scheduleReconnect();
            return;
        }
        Log.info("Discord", Sprache.t("Login sent, waiting for confirmation.", "Anmeldung gesendet, warte auf Bestaetigung."));
        anmeldeWache();
    }

    /**
     * Sitzung fortsetzen (RESUME, 14.10.): Discord schickt dann alle Nachrichten nach, die in der
     * Luecke kamen -- ein !stop oder !pause geht so nicht mehr verloren. Lehnt Discord ab (op 9),
     * wird neu angemeldet.
     */
    private void resume() {
        String payload = "{\"op\":6,\"d\":{"
                + "\"token\":" + DiscordClient.quote(token) + ","
                + "\"session_id\":" + DiscordClient.quote(sessionId) + ","
                + "\"seq\":" + (lastSequence == null ? "null" : lastSequence) + "}}";
        if (!send(payload)) {
            Log.error("Discord", Sprache.t("The session resume could not be sent. Reconnecting.",
                    "Das Fortsetzen der Sitzung konnte nicht abgeschickt werden. Es wird neu verbunden."));
            scheduleReconnect();
            return;
        }
        Log.info("Discord", Sprache.t("Session resume sent, waiting for confirmation.",
                "Fortsetzen der Sitzung gesendet, warte auf Bestaetigung."));
        anmeldeWache();
    }

    /**
     * Wachhund: bleibt die Bestaetigung (READY / RESUMED) aus, faellt das sonst nicht auf --
     * die Verbindung steht ja und die Lebenszeichen laufen weiter. Der Bot
     * waere dann dauerhaft offline, ohne dass irgendwo etwas danebengeht.
     */
    private void anmeldeWache() {
        identified = false;
        final int nr = verbindungNr;
        timer.schedule(() -> {
            if (running && !identified && nr == verbindungNr) {
                Log.error("Discord", Sprache.t("Discord did not confirm the login. " + "Otherwise the bot stays offline and accepts no commands. " + "Reconnecting.",
                        "Discord hat die Anmeldung nicht bestaetigt. "
                        + "Der Bot bleibt sonst offline und nimmt keine Befehle an. "
                        + "Es wird neu verbunden."));
                WebSocket current = socket;
                if (current != null) current.abort();
                scheduleReconnect();
            }
        }, 15, TimeUnit.SECONDS);
    }

    private void startHeartbeat(long intervalMillis) {
        acknowledged = true;

        // Den vorigen Takt beenden. Ohne das laeuft nach jedem Neuverbinden ein
        // weiterer mit, und die Lebenszeichen schaukeln sich auf.
        java.util.concurrent.ScheduledFuture<?> previous = heartbeatTask;
        if (previous != null) previous.cancel(false);

        final int nr = verbindungNr;
        heartbeatTask = timer.scheduleAtFixedRate(() -> {
            if (!running || nr != verbindungNr) return;   // Takt einer alten Verbindung

            if (!acknowledged) {
                Log.warn("Discord", Sprache.t("No heartbeat back. Renewing the connection.",
                        "Kein Lebenszeichen zurueck. Verbindung wird erneuert."));
                WebSocket current = socket;
                if (current != null) current.abort();
                scheduleReconnect();
                return;
            }
            acknowledged = false;
            send("{\"op\":1,\"d\":" + (lastSequence == null ? "null" : lastSequence) + "}");
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void handle(int nr, String raw) {
        if (nr != verbindungNr) return;   // Nachricht einer alten Verbindung
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null) return;

            JsonNode sequence = root.get("s");
            if (sequence != null && !sequence.isNull()) lastSequence = sequence.asInt();

            int op = root.path("op").asInt(-1);

            switch (op) {
                case 10 -> {   // Willkommen: Taktrate erfahren und anmelden
                    long interval = root.path("d").path("heartbeat_interval").asLong(45000);
                    Log.info("Discord", Sprache.t("Connected. Heartbeat every " + (interval / 1000) + " seconds.",
                            "Verbunden. Lebenszeichen alle "
                            + (interval / 1000) + " Sekunden."));
                    startHeartbeat(interval);
                    if (sessionId != null && resumeUrl != null) resume(); else identify();
                }
                case 11 -> acknowledged = true;
                case 1  -> send("{\"op\":1,\"d\":" + (lastSequence == null ? "null" : lastSequence) + "}");
                case 7 -> {   // Discord moechte, dass wir neu verbinden -- die Sitzung bleibt fortsetzbar
                    Log.info("Discord", Sprache.t("Discord asks for a new connection.",
                            "Discord bittet um eine neue Verbindung."));
                    scheduleReconnect();
                }
                case 9 -> {   // Sitzung ungueltig; d=true heisst: Fortsetzen geht trotzdem noch
                    boolean fortsetzbar = root.path("d").asBoolean(false);
                    if (!fortsetzbar) {
                        sessionId = null;
                        resumeUrl = null;
                        Log.info("Discord", Sprache.t("Discord no longer knows the session -- logging in again.",
                                "Discord kennt die Sitzung nicht mehr -- es wird neu angemeldet."));
                    } else {
                        Log.info("Discord", Sprache.t("Discord asks for a new connection.",
                                "Discord bittet um eine neue Verbindung."));
                    }
                    scheduleReconnect();
                }
                case 0 -> dispatch(root);
                default -> { /* andere Ereignisse sind hier ohne Belang */ }
            }
        } catch (Exception e) {
            Log.warn("Discord", Sprache.t("Message could not be read: " + e.getMessage(),
                    "Nachricht konnte nicht gelesen werden: " + e.getMessage()));
        }
    }

    private void dispatch(JsonNode root) {
        String type = root.path("t").asText("");

        if ("READY".equals(type)) {
            reconnectAttempts = 0;
            String name = root.path("d").path("user").path("username").asText("Bot");
            identified = true;
            // Fuer RESUME merken: Sitzung und die Adresse, unter der sie fortgesetzt wird.
            String sid = root.path("d").path("session_id").asText("");
            String url = root.path("d").path("resume_gateway_url").asText("");
            if (!sid.isEmpty() && !url.isEmpty()) {
                sessionId = sid;
                resumeUrl = (url.endsWith("/") ? url : url + "/") + "?v=10&encoding=json";
            } else {
                sessionId = null;
                resumeUrl = null;
            }
            Log.info("Discord", Sprache.t("Logged in as " + name + ". Commands are ready.",
                    "Angemeldet als " + name + ". Befehle sind bereit."));
            fixBefehlAnmelden(root.path("d"));
            besitzerErmitteln(root.path("d"));
            return;
        }
        if ("RESUMED".equals(type)) {
            identified = true;
            reconnectAttempts = 0;
            Log.info("Discord", Sprache.t("Session resumed -- messages from the gap were caught up. Commands are ready.",
                    "Sitzung fortgesetzt -- Nachrichten aus der Luecke wurden nachgeholt. Befehle sind bereit."));
            return;
        }
        if ("INTERACTION_CREATE".equals(type)) {
            JsonNode d = root.path("d");
            int art = d.path("type").asInt(0);
            String cid = d.path("data").path("custom_id").asText("");
            if (art == 2 || art == 5 || (art == 3
                    && (cid.startsWith("startformular")
                        || cid.startsWith("setformular")))) {
                fixInteraktion(d, art);
                return;
            }
            knopfGedrueckt(d);
            return;
        }
        if (!"MESSAGE_CREATE".equals(type)) return;

        JsonNode data = root.path("d");

        // Webhook-Nachricht in einem Sammelkanal (Lebenszeichen)?
        String kanalId = data.path("channel_id").asText("");
        if (!data.path("webhook_id").isMissingNode() && webhookKanaele.contains(kanalId)) {
            try {
                onWebhook.accept(kanalId, data.path("content").asText(""));
            } catch (Exception e) {
                Log.debug("Discord", "Webhook-Nachricht nicht verarbeitet: " + e.getMessage());
            }
            return;
        }

        // Eigene Nachrichten und die anderer Bots ignorieren.
        if (data.path("author").path("bot").asBoolean(false)) return;

        String channel = data.path("channel_id").asText("");
        if (!channelIds.contains(channel)) return;

        String content = data.path("content").asText("").trim();
        if (content.isEmpty() || !content.startsWith("!")) return;

        Absender absender = absenderAus(data.path("author"), data);
        String author = absender.name();
        Log.info("Discord", Sprache.t("Command from " + author + " (" + absender.id() + "): " + content,
                "Befehl von " + author + " (" + absender.id() + "): " + content));

        try {
            onCommand.empfangen(channel, content, absender);
        } catch (Exception e) {
            Log.error("Discord", Sprache.t("Command could not be executed", "Befehl konnte nicht ausgefuehrt werden"), e);
        }
    }

    /**
     * Ein Knopf unter einer Bot-Karte wurde gedrueckt.
     *
     * Jeder Knopf traegt als Kennung den Befehl, fuer den er steht
     * ("!shortcut wahl 3"). Der Druck wird deshalb genau so behandelt, als
     * haette Autor den Befehl getippt -- nur im selben Kanal, nur von einem
     * Menschen. Discord will binnen drei Sekunden eine Quittung, sonst zeigt
     * es "Interaktion fehlgeschlagen"; die geht deshalb zuerst raus.
     */
    private void knopfGedrueckt(JsonNode d) {
        if (d.path("type").asInt(0) != 3) return;          // 3 = Knopf/Auswahl
        String channel = d.path("channel_id").asText("");
        if (!channelIds.contains(channel)) return;

        JsonNode nutzer = d.path("member").path("user");
        if (nutzer.isMissingNode()) nutzer = d.path("user");
        if (nutzer.path("bot").asBoolean(false)) return;
        Absender absender = absenderAus(nutzer, d);
        String author = absender.name();

        String befehl = d.path("data").path("custom_id").asText("");
        JsonNode werte = d.path("data").path("values");
        if (werte.isArray() && werte.size() > 0) befehl = werte.get(0).asText("");

        quittieren(d.path("id").asText(""), d.path("token").asText(""));
        if (!befehl.startsWith("!")) return;

        Log.info("Discord", Sprache.t("Button from " + author + " (" + absender.id() + "): " + befehl,
                "Knopf von " + author + " (" + absender.id() + "): " + befehl));
        try {
            onCommand.empfangen(channel, befehl, absender);
        } catch (Exception e) {
            Log.error("Discord", Sprache.t("Button could not be executed", "Knopf konnte nicht ausgefuehrt werden"), e);
        }
    }

    /**
     * "/start" (Schraegstrich-Befehl, Art 2), die Formular-Knoepfe (Art 3)
     * und das abgeschickte Formular (Art 5).
     */
    private void fixInteraktion(JsonNode d, int art) {
        String id = d.path("id").asText(""), tokenI = d.path("token").asText("");
        FixAnbindung f = fix;
        JsonNode nutzer = d.path("member").path("user");
        if (nutzer.isMissingNode()) nutzer = d.path("user");
        Absender absender = absenderAus(nutzer, d);
        String author = absender.name();
        String channel = d.path("channel_id").asText("");

        // Nur in den Bot-Kanaelen -- wie die !-Befehle (Sicherheitspruefung 29.09.: /start
        // und die Formular-Knoepfe gingen in jedem Kanal des Servers).
        if (!channelIds.contains(channel)) {
            antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":\"" + de.hustlebot.core.Sprache.t("Please use this in the bot channel.", "Bitte im Bot-Kanal benutzen.") + "\",\"flags\":64}}");
            return;
        }

        if (f == null) {
            antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":\"" + Sprache.t("The learning mode is not ready.", "Der Lernmodus ist nicht bereit.") + "\",\"flags\":64}}");
            return;
        }
        String befehlName = d.path("data").path("name").asText("");
        String cid = d.path("data").path("custom_id").asText("");
        if (art == 2 && !"start".equals(befehlName)) return;

        // Einstellungs-Formulare sind !settings (16.10.): nur der Besitzer des Servers. Die Knoepfe
        // bleiben in alten Karten stehen -- deshalb hier pruefen, nicht nur beim Befehl.
        if ((art == 3 && cid.startsWith("setformular:")) || (art == 5 && cid.startsWith("set:"))) {
            String sperre = rechte.sperrtext(absender.guild(), absender.id(), "!settings");
            if (sperre != null) {
                Log.warn("Discord", Sprache.t("Settings form from " + author + " (" + absender.id() + ") refused.",
                        "Einstellungs-Formular von " + author + " (" + absender.id() + ") abgelehnt."));
                antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":" + DiscordClient.quote(sperre) + ",\"flags\":64}}");
                return;
            }
        }
        // Einstellungs-Formulare (!settings, 27.09.): Knopf "setformular:<bereich>", abgeschickt "set:<bereich>".
        if (art == 3 && cid.startsWith("setformular:")) {
            String formular = f.setFormular(author, cid.substring(12));
            if (formular == null) {
                antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":\"" + Sprache.t("This form does not exist.", "Dieses Formular gibt es nicht.") + "\",\"flags\":64}}");
                return;
            }
            int status = antworten(id, tokenI, "{\"type\":9,\"data\":" + formular + "}");
            Log.info("Discord", Sprache.t("Settings form " + cid.substring(12) + " for " + author + " (Discord: " + status + ").",
                    "Einstellungs-Formular " + cid.substring(12) + " fuer " + author + " (Discord: " + status + ")."));
            return;
        }
        if (art == 5 && cid.startsWith("set:")) {
            java.util.Map<String, String> felder = new java.util.HashMap<>();
            felderLesen(d.path("data").path("components"), d.path("data").path("resolved"), felder, new String[]{"", ""});
            antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":\"" + Sprache.t("💾 Received – being saved.", "💾 Angekommen – wird gespeichert.") + "\"}}");
            Log.info("Discord", Sprache.t("Settings form " + cid.substring(4) + " from " + author + ": " + felder,
                    "Einstellungs-Formular " + cid.substring(4) + " von " + author + ": " + felder));
            try {
                f.setEingereicht(channel, author, cid.substring(4), felder);
            } catch (Exception e) {
                Log.error("Discord", Sprache.t("Settings form could not be processed",
                        "Einstellungs-Formular konnte nicht verarbeitet werden"), e);
            }
            return;
        }

        // Das !start-Formular: oeffnen (/start, Knopf) und abgeschickt (start:...).
        boolean startForm = (art == 2 && "start".equals(befehlName)) || (art == 3 && cid.startsWith("startformular"));
        if (startForm) {
            String formular = f.startFormular(author, art == 3 ? cid : "");
            if (formular == null) {
                antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":\""
                        + de.hustlebot.core.Sprache.t("The form is not available right now.", "Das Formular ist gerade nicht verfuegbar.")
                        + "\",\"flags\":64}}");
                return;
            }
            int status = antworten(id, tokenI, "{\"type\":9,\"data\":" + formular + "}");
            Log.info("Discord", Sprache.t("Start form for " + author + " opened (Discord: " + status + ").",
                    "Start-Formular fuer " + author + " geoeffnet (Discord: " + status + ")."));
            return;
        }
        if (art == 5 && cid.startsWith("start:")) {
            java.util.Map<String, String> felder = new java.util.HashMap<>();
            felderLesen(d.path("data").path("components"), d.path("data").path("resolved"), felder, new String[]{"", ""});
            antworten(id, tokenI, "{\"type\":4,\"data\":{\"content\":\""
                    + de.hustlebot.core.Sprache.t("▶️ Received.", "▶️ Auswahl angekommen.") + "\"}}");
            Log.info("Discord", Sprache.t("Start form from " + author + ": " + felder,
                    "Start-Formular von " + author + ": " + felder));
            felder.put("_formular", cid);   // "start:form" (Schritt 1) oder "start:event" (Schritt 2)
            felder.put("_nutzerId", absender.id());   // fuer "!off HH:MM" aus dem Formular: nur der Besitzer (16.10.)
            felder.put("_guild", absender.guild());
            try {
                f.startEingereicht(channel, author, felder);
            } catch (Exception e) {
                Log.error("Discord", Sprache.t("Start form could not be processed",
                        "Start-Formular konnte nicht verarbeitet werden"), e);
            }
            return;
        }

    }

    /** Alle Felder des Formulars einsammeln, egal wie tief Discord sie verschachtelt. */
    private static void felderLesen(JsonNode knoten, JsonNode resolved,
                                    java.util.Map<String, String> felder, String[] bild) {
        if (knoten == null || knoten.isMissingNode()) return;
        if (knoten.isArray()) {
            for (JsonNode k : knoten) felderLesen(k, resolved, felder, bild);
            return;
        }
        String cid = knoten.path("custom_id").asText("");
        if (!cid.isEmpty()) {
            if (knoten.has("value")) felder.put(cid, knoten.path("value").asText(""));
            JsonNode werte = knoten.path("values");
            if (werte.isArray() && werte.size() > 0) {
                String w = werte.get(0).asText("");
                JsonNode anhang = resolved.path("attachments").path(w);
                if (!anhang.isMissingNode()) {
                    bild[0] = anhang.path("url").asText("");
                    bild[1] = anhang.path("filename").asText("bild.png");
                } else {
                    // Mehrfachauswahl (Start-Formular "Bezahlart & Extras"): alle, mit Komma.
                    StringBuilder alle = new StringBuilder();
                    for (JsonNode v : werte) alle.append(alle.length() == 0 ? "" : ",").append(v.asText(""));
                    felder.put(cid, alle.toString());
                }
            }
        }
        felderLesen(knoten.path("component"), resolved, felder, bild);
        felderLesen(knoten.path("components"), resolved, felder, bild);
    }

    /** Antwort auf eine Interaktion; liefert den Status von Discord (0 = keine Verbindung). */
    private int antworten(String id, String interaktionsToken, String json) {
        if (id.isEmpty() || interaktionsToken.isEmpty()) return 0;
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                    URI.create("https://discord.com/api/v10/interactions/" + id + "/"
                            + interaktionsToken + "/callback"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();
            var r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                Log.warn("Discord", Sprache.t("Reply to the interaction refused (" + r.statusCode() + "): " + (r.body().length() > 300 ? r.body().substring(0, 300) : r.body()),
                        "Antwort auf Interaktion abgelehnt (" + r.statusCode() + "): "
                        + (r.body().length() > 300 ? r.body().substring(0, 300) : r.body())));
            }
            return r.statusCode();
        } catch (Exception e) {
            Log.debug("Discord", "Antwort auf Interaktion fehlgeschlagen: " + e.getMessage());
            return 0;
        }
    }

    /**
     * "/start" als Schraegstrich-Befehl im eigenen Server eintragen (eigener Bot-Token) -- damit das
     * Formular mit einem Befehl aufgeht. Einmal je Start; misslingt es (dem Bot
     * fehlt das Recht "applications.commands"), bleibt "!start" mit dem Knopf.
     */
    private void fixBefehlAnmelden(JsonNode ready) {
        if (fix == null || fixBefehlAngemeldet) return;
        String app = ready.path("application").path("id").asText("");
        if (app.isEmpty()) app = ready.path("user").path("id").asText("");
        if (app.isEmpty()) return;
        fixBefehlAngemeldet = true;
        String startBody = "{\"name\":\"start\",\"type\":1,\"description\":"
                + "\"Start a mode with a form / Modus per Formular starten\"}";
        for (JsonNode g : ready.path("guilds")) {
            String guild = g.path("id").asText("");
            if (guild.isEmpty()) continue;
            for (String body : new String[]{startBody})
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        URI.create("https://discord.com/api/v10/applications/" + app + "/guilds/" + guild + "/commands"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bot " + token)
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
                http.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
                    if (r.statusCode() < 300) Log.info("Discord", de.hustlebot.core.Sprache.t("Command /", "Befehl /") + (body.contains("\"start\"") ? "start" : "fix") + de.hustlebot.core.Sprache.t(" is registered.", " ist eingetragen."));
                    else Log.warn("Discord", de.hustlebot.core.Sprache.t("/start could not be registered (", "/start liess sich nicht eintragen (") + r.statusCode()
                            + de.hustlebot.core.Sprache.t(") -- !start with the button still works.", ") -- es bleibt !start mit dem Knopf."));
                });
            } catch (Exception e) {
                Log.debug("Discord", "/fix nicht eingetragen: " + e.getMessage());
            }
        }
    }

    /** "Angekommen" an Discord -- ohne die Nachricht mit dem Knopf zu veraendern. */
    private void quittieren(String id, String interaktionsToken) {
        if (id.isEmpty() || interaktionsToken.isEmpty()) return;
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                    URI.create("https://discord.com/api/v10/interactions/" + id + "/"
                            + interaktionsToken + "/callback"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"type\":6}"))
                    .build();
            http.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Log.debug("Discord", "Knopf-Quittung fehlgeschlagen: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Empfaenger der Verbindung
    // ------------------------------------------------------------------

    private final class Listener implements WebSocket.Listener {

        /** Nummer der Verbindung, zu der dieser Empfaenger gehoert. */
        private final int nr;
        /** Sammelt zerstueckelte Nachrichten dieser Verbindung, bis der letzte Teil eintrifft. */
        private final StringBuilder teile = new StringBuilder();

        Listener(int nr) {
            this.nr = nr;
        }

        /** Gehoert dieser Rueckruf noch zur aktuellen Verbindung? */
        private boolean aktuell() {
            return nr == verbindungNr;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!aktuell()) {   // inzwischen wurde eine neuere Verbindung aufgebaut
                try { webSocket.abort(); } catch (Exception ignored) { /* egal */ }
                return;
            }
            // Die Verbindung SOFORT merken, nicht erst wenn buildAsync fertig
            // meldet. Discord schickt sein "Hallo" naemlich schon vorher, und
            // die Antwort darauf ist die Anmeldung. Wurde sie abgeschickt,
            // waehrend das Feld noch leer war, ging sie ins Leere: der Bot war
            // dann zwar verbunden, aber nie angemeldet -- in Discord offline
            // und taub fuer alle Befehle.
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence part, boolean last) {
            teile.append(part);
            if (last) {
                String complete = teile.toString();
                teile.setLength(0);
                handle(nr, complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            if (!aktuell()) {   // eine alte Verbindung geht zu -- das ist erwartet, kein neues Neuverbinden
                Log.debug("Discord", "Alte Verbindung " + nr + " geschlossen (" + status + "), aktuell ist " + verbindungNr + ".");
                return null;
            }
            Log.warn("Discord", Sprache.t("Connection closed (" + status + "). " + reason,
                    "Verbindung geschlossen (" + status + "). " + reason));
            if (status == 4007 || status == 4009) {   // Zaehler falsch / Sitzung abgelaufen: neu anmelden
                sessionId = null;
                resumeUrl = null;
            }
            if (status >= 4010 && status <= 4013) {   // laut Discord endgueltig: nicht endlos neu anmelden
                Log.error("Discord", Sprache.t("Discord refuses the connection for good (code " + status + "). Commands are off.",
                        "Discord lehnt die Verbindung endgueltig ab (Code " + status + "). Befehle sind aus."));
                running = false;
                return null;
            }
            if (status == 4004) {
                Log.error("Discord", Sprache.t("The access token was refused. " + "Please check the Discord bot token (config.properties / setup).",
                        "Der Zugangsschluessel wurde abgelehnt. "
                        + "Bitte den Discord-Bot-Schluessel pruefen (config.properties / Einrichtung)."));
                running = false;
                return null;
            }
            if (status == 4014) {
                Log.error("Discord", Sprache.t("The MESSAGE CONTENT INTENT permission is missing. " + "It must be switched on in the developer portal under Bot.",
                        "Die Berechtigung MESSAGE CONTENT INTENT fehlt. "
                        + "Sie muss im Entwicklerportal unter Bot eingeschaltet werden."));
                running = false;
                return null;
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!aktuell()) return;   // Fehler einer alten Verbindung
            Log.warn("Discord", "Verbindungsfehler: " + error.getMessage());
            scheduleReconnect();
        }
    }
}
