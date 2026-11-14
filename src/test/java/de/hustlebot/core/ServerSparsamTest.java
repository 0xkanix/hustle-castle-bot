package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerSparsamTest {

    @Test
    void bilderAndererModiWerdenNichtGeladen() {
        assertTrue(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\gelernt\\titanen\\x.png"));
        assertTrue(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\gelernt\\portal\\unter\\x.png"));
        assertTrue(ServerSparsam.nichtNoetig("gelernt\\stigma\\sorten\\x.png"), "auch im Ordner gelernt neben dem Bot");
    }

    @Test
    void wasDerClankriegBrauchtBleibt() {
        assertFalse(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\clanwar\\cw_icon.png"));
        assertFalse(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\dungeon\\confirm.png"));
        assertFalse(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\gelernt\\clanwar\\anzeigen\\x.png"));
        assertFalse(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\gelernt\\heldenset\\x.png"));
        assertFalse(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\gelernt\\team\\ts_burg_knopf.png"), "der Clankrieg-Weg nennt Bilder aus team");
        assertFalse(ServerSparsam.nichtNoetig("C:\\HustleBot\\templates\\gelernt\\portalx\\x.png"), "nur der ganze Ordnername zaehlt");
    }

    @Test
    void ohneSchalterWirdAllesGeladen() {
        // In der Testumgebung liegt keine server-sparsam.txt: alles wie bisher.
        assertTrue(ServerSparsam.bildLaden(java.nio.file.Path.of("gelernt", "titanen", "x.png")));
    }
}
