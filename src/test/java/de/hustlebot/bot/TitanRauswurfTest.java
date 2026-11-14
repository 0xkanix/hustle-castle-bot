package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Autor 26.10.: das Team mit der wenigsten Kampfkraft heraus -- nie Schutzliste, nie ueber der Grenze, nie man selbst. */
class TitanRauswurfTest {

    private static TitanRauswurf.Team t(String n, long k) {
        return new TitanRauswurf.Team(n, k);
    }

    // Autors Fotos 26.10. (Temple of the Rampaging Elements, 6/6)
    private static final List<TitanRauswurf.Team> TEMPLE = List.of(t("Hagen", 1_142_003), t("Goblin Queen", 1_014_868),
            t("Paulaskelett", 797_135), t("Carl", 2_563_964), t("Bruno", 1_235_160), t("Bernd", 1_711_897));

    @Test
    void schwaechstesTeamHeraus() {
        assertEquals("Paulaskelett", TitanRauswurf.waehlen(TEMPLE, 2_000_000, "Autor").name());
    }

    @Test
    void schutzlisteUndGrenze() {
        // ohne Grenze wirft der Bot niemanden heraus (Artefakt: "Ohne Grenze wirft er niemanden raus")
        assertNull(TitanRauswurf.waehlen(TEMPLE, 0, "Autor"));
    }

    // Autor 26.10.: Notfall -- ist JEDES Team geschuetzt ODER ueber der Grenze, trotzdem das schwaechste heraus,
    // sonst kaeme mein Titanen-Trupp nie in den vollen Turm.
    @Test
    void notfallAllesGeschuetztOderUeberGrenze() {
        // 5 Plaetze, alle 5 auf der Niemals-Liste -> das schwaechste von ihnen (Anna) trotzdem raus
        assertEquals("Anna", TitanRauswurf.waehlen(List.of(t("Bernd", 1_700_000), t("Carl", 2_563_964),
                t("Anna", 500_000), t("Bruno", 1_235_160)), 2_000_000, "Autor").name());
        // Gemischt: 2 geschuetzt, 1 ueber der Grenze -> nichts normal erlaubt -> schwaechstes (Anna) raus
        assertEquals("Anna", TitanRauswurf.waehlen(List.of(t("Carl", 900_000), t("Anna", 500_000),
                t("Riese", 2_100_000)), 2_000_000, "Autor").name());
    }

    @Test
    void unlesbareKraftUndManSelbstNie() {
        assertEquals("Goblin Queen", TitanRauswurf.waehlen(List.of(t("Paulaskelett", 0), t("Goblin Queen", 1_014_868),
                t("Autor", 600_000)), 2_000_000, "Autor").name());
        // nur ein Team mit unlesbarem Namen -> auch im Notfall niemand (koennte ein Geschuetzter sein)
        assertNull(TitanRauswurf.waehlen(List.of(t("", 100_000)), 2_000_000, "Autor"));
    }
}
