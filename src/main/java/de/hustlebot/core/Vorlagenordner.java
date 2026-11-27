package de.hustlebot.core;

import java.util.List;

/**
 * In welchen Unterordner von templates eine Vorlage gehoert (Autor, 02.10.: "sortier sie ein
 * bisschen, dass es uebersichtlicher ist").
 *
 * Fuer den Bot ist der Ordner egal -- er liest alle Unterordner und schaut nur auf den
 * Dateinamen. Die Regel gilt fuer drei Stellen gleich: das Aufnahme-Werkzeug (Autors Bilder),
 * die vom Bot selbst gelernten Bilder (templates\gelernt\...) und das einmalige Umsortieren.
 * Die ERSTE passende Regel gilt; ein Name ohne Regel kommt in den Ordner seines Spielteils.
 */
public final class Vorlagenordner {

    private Vorlagenordner() { }

    /** Anfang des Namens -> Unterordner. Reihenfolge zaehlt: das Genauere zuerst. */
    private static final List<String[]> REGELN = List.of(
            // ---- Arena: die Gegner-Listen, je Liste ein Ordner ----
            r("ar_block_",      "arena/gegner/niemals"),
            r("ar_ungern_",     "arena/gegner/meiden"),
            r("ar_sicher_",     "arena/gegner/sicher"),
            r("ar_machbar_",    "arena/gegner/machbar"),
            r("ar_fastsicher_", "arena/gegner/fastsicher"),
            r("ar_pause_",      "arena/gegner/pause"),
            r("ar_clan_",       "arena/gegner/clans"),
            r("ar_name_",       "arena/gegner/namensbilder"),
            r("ar_schirm_",     "arena/schirmherrschaften"),
            r("ar_stigma_",     "arena/schirmherrschaften"),
            r("ar_pal_",        "arena/pals"),
            r("ar_art_",        "arena/artefakte"),
            r("ar_artschlecht_", "arena/artefakte-schlecht"),   // Autor 22.10.: schlechte Artefakte
            r("ar_totem_",      "arena/totems"),                 // Autor 22.10.: gute Totems
            r("ar_totemschlecht_", "arena/totems-schlecht"),     // Autor 22.10.: schlechte Totems
            r("ar_emblem_",     "arena/embleme"),          // gute Embleme (+3), eigene Aufnahmen (11.10.)
            r("ar_allianz_",    "arena/allianz"),          // Allianz-Talente (mitgeliefert in gelernt/arena/allianz)
            r("ar_log_",        "arena/kampfprotokoll"),
            r("ar_stelle_",     "arena/stellen"),
            r("ar_dweller",     "arena/einheiten-pruefung"),
            r("ar_item_",       "arena/einheiten-pruefung"),
            r("ar_leben_",      "arena/einheiten-pruefung"),
            r("ar_apfel_knopf", "arena/knoepfe"),
            r("ar_cancel",      "arena/knoepfe"),
            r("ar_claim",       "arena/knoepfe"),
            r("ar_close_x",     "arena/knoepfe"),
            r("ar_confirm",     "arena/knoepfe"),
            r("ar_deploy_best", "arena/knoepfe"),
            r("ar_for_",        "arena/knoepfe"),
            r("ar_home",        "arena/knoepfe"),
            r("ar_next_arrow",  "arena/knoepfe"),
            r("ar_prev_arrow",  "arena/knoepfe"),
            r("ar_participate", "arena/knoepfe"),
            r("ar_previous_squad", "arena/knoepfe"),
            r("ar_quick_attack", "arena/knoepfe"),
            r("ar_to_battle",   "arena/knoepfe"),
            r("ar_return",      "arena/knoepfe"),
            r("ar_",            "arena/anzeigen"),

            // ---- Dunkle Kathedrale (auch die alten dc_/key_-Aufnahmen aus dungeon) ----
            r("key_",           "cathedral/ziffern"),
            r("dc_ally_screen", "cathedral/anzeigen"),
            r("dc_text_",       "cathedral/karte"),
            r("dc_ally",        "cathedral/karte"),
            r("dc_altar",       "cathedral/karte"),
            r("dc_arena",       "cathedral/karte"),
            r("dc_camp",        "cathedral/karte"),
            r("dc_cube",        "cathedral/karte"),
            r("dc_danger",      "cathedral/karte"),
            r("dc_guardian",    "cathedral/karte"),
            r("dc_key_icon",    "cathedral/karte"),
            r("dc_light_icon",  "cathedral/karte"),
            r("dc_minion",      "cathedral/karte"),
            r("dc_node",        "cathedral/karte"),
            r("dc_question",    "cathedral/karte"),
            r("dc_skirmish",    "cathedral/karte"),
            r("dc_visited",     "cathedral/karte"),
            r("dc_waypoint",    "cathedral/karte"),
            r("dc_apfel_knopf", "cathedral/knoepfe"),
            r("dc_attack",      "cathedral/knoepfe"),
            r("dc_blessing_ok", "cathedral/knoepfe"),
            r("dc_claim",       "cathedral/knoepfe"),
            r("dc_close",       "cathedral/knoepfe"),
            r("dc_confirm",     "cathedral/knoepfe"),
            r("dc_deploy_best", "cathedral/knoepfe"),
            r("dc_end_journey", "cathedral/knoepfe"),
            r("dc_finish",      "cathedral/knoepfe"),
            r("dc_for_",        "cathedral/knoepfe"),
            r("dc_home",        "cathedral/knoepfe"),
            r("dc_join",        "cathedral/knoepfe"),
            r("dc_move_on",     "cathedral/knoepfe"),
            r("dc_next_floor",  "cathedral/knoepfe"),
            r("dc_participate", "cathedral/knoepfe"),
            r("dc_previous_squad", "cathedral/knoepfe"),
            r("dc_quick_attack", "cathedral/knoepfe"),
            r("dc_resurrect",   "cathedral/knoepfe"),
            r("dc_to_battle",   "cathedral/knoepfe"),
            r("dc_yes",         "cathedral/knoepfe"),
            r("dc_",            "cathedral/anzeigen"),

            // ---- Clankrieg ----
            r("cw_digit_",      "clanwar/ziffern"),
            r("cw_slash",       "clanwar/ziffern"),
            r("cw_nahrung_",    "clanwar/nahrung"),
            r("cw_outpost_",    "clanwar/aussenposten"),
            r("cw_back",        "clanwar/knoepfe"),
            r("cw_buy_",        "clanwar/knoepfe"),
            r("cw_close_x",     "clanwar/knoepfe"),
            r("cw_confirm",     "clanwar/knoepfe"),
            r("cw_create_squad", "clanwar/knoepfe"),
            r("cw_deploy_",     "clanwar/knoepfe"),
            r("cw_previous_squad", "clanwar/knoepfe"),
            r("cw_skip_",       "clanwar/knoepfe"),
            r("cw_video_knopf", "clanwar/knoepfe"),
            r("cw_yes",         "clanwar/knoepfe"),
            r("cw_",            "clanwar/anzeigen"),

            // ---- Titan-Event "Titanic Excavation" (04.10.) -- MUSS vor allen "ev_"-Regeln stehen ----
            r("ev_titan_",      "titan-event"),

            // ---- Hammer-Event ----
            r("ev_kiste_",      "event-belohnungen"),
            r("ev_stab",        "event-belohnungen"),
            r("ev_gems",        "event-belohnungen"),
            r("ev_fass_",       "event/spielfeld"),
            r("ev_vase_",       "event/spielfeld"),
            r("ev_genie",       "event/spielfeld"),
            r("ev_lampe",       "event/spielfeld"),
            r("ev_quest_",      "event/quests"),
            r("ev_objectives_", "event/quests"),
            r("ev_angebot_x",   "event/knoepfe"),
            r("ev_claim",       "event/knoepfe"),
            r("ev_form_x",      "event/knoepfe"),
            r("ev_ok",          "event/knoepfe"),
            r("ev_open",        "event/knoepfe"),
            r("ev_refresh_",    "event/knoepfe"),
            r("ev_superhammer_use", "event/knoepfe"),
            r("ev_view",        "event/knoepfe"),
            r("ev_game_reiter", "event/knoepfe"),
            r("ev_reiter_",     "event/knoepfe"),
            r("ev_",            "event/anzeigen"),

            // ---- Taegliche Spiel-Quests (Autor 18.11.): Buch, lila Reiter, Watch, Claim ----
            r("sq_",            "spielquests"),

            // ---- kleine Spielteile: ein Ordner reicht ----
            r("lh_",            "lighthouse"),
            r("ko_",            "colosseum"),
            // Clan-Verlies und Gebietskarte (gelernt 18.-20.11.) -- vor der allgemeinen "rs_"-Regel (Burg-Stand)
            r("rs_verlies_",    "dungeon/clan"),
            r("rs_chat_",       "dungeon/clan"),
            r("rs_reisechat_",  "dungeon/clan"),
            r("rs_gebietskarte_", "dungeon/karte"),
            r("rs_",            "burgstand"),
            r("cl_",            "clanladen"),
            r("tt_",            "titantower"),
            r("gb_",            "gembay"),
            r("fr_",            "freunde"),   // Freunden senden (05.10.), Ausschnitte in templates\gelernt\freunde

            // ---- Verlies (und alles Allgemeine) ----
            r("friend_",        "dungeon/freunde"),
            r("chat_",          "dungeon/chat"),
            r("unit_",          "dungeon/einheiten"),
            r("name_",          "dungeon/einheiten"),
            r("nameplate_",     "dungeon/einheiten"),
            r("digit_",         "dungeon/ziffern"),
            r("effect_",        "dungeon/effekte"),
            r("bag_",           "dungeon/tasche"),
            r("ad_",            "dungeon/werbung"),
            r("waypoint_",      "dungeon/karte"),
            r("dungeon_node",   "dungeon/karte"),
            r("go_here",        "dungeon/karte"),
            r("moving_here",    "dungeon/karte"),
            r("map_button",     "dungeon/karte"),
            r("worldmap_home",  "dungeon/karte"),
            r("go_to_map",      "dungeon/karte"),
            r("lock_icon",      "dungeon/karte"),
            r("ether_icon",     "dungeon/karte"),
            r("duel_",          "dungeon/karte"),
            r("progress_",      "dungeon/karte"),
            r("du_fackel_icon", "dungeon/karte"),
            r("tab_torches",    "dungeon/karte"),
            r("invite_",        "dungeon/mitspieler"),
            r("partner_",       "dungeon/mitspieler"),
            r("teammate_",      "dungeon/mitspieler"),
            r("ally_left",      "dungeon/mitspieler"),
            r("online_dot",     "dungeon/mitspieler"),
            r("other_device",   "dungeon/mitspieler"),
            r("cancel_search",  "dungeon/mitspieler"),
            r("participate",    "dungeon/mitspieler"),
            r("ready",          "dungeon/mitspieler"),
            r("quit_warning",   "dungeon/anzeigen"),
            r("defeat_banner",  "dungeon/anzeigen"),
            r("victory_banner", "dungeon/anzeigen"),
            r("deploy_screen",  "dungeon/anzeigen"),
            r("dg_screen_title", "dungeon/anzeigen"),
            r("journey_complete", "dungeon/anzeigen"),
            r("multiplier",     "dungeon/anzeigen"),
            r("sacrifice_screen", "dungeon/anzeigen"),
            r("daily_reward",   "dungeon/anzeigen"),
            r("event_reward",   "dungeon/anzeigen"),
            r("",               "dungeon/knoepfe"));

    private static String[] r(String anfang, String ordner) {
        return new String[]{anfang, ordner};
    }

    /** Unterordner (mit "/" getrennt, relativ zu templates) fuer eine Vorlage, z. B. "arena/gegner/sicher". */
    public static String fuer(String name) {
        String n = name == null ? "" : name;
        if (n.toLowerCase().endsWith(".png")) n = n.substring(0, n.length() - 4);
        for (String[] regel : REGELN) {
            if (n.startsWith(regel[0])) return regel[1];
        }
        return "dungeon/knoepfe";
    }
}
