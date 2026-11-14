package de.hustlebot.steuer;

import de.hustlebot.discord.DiscordClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sammelt alles, was der Bot melden will -- ob Discord nun da ist oder nicht.
 *
 * Haengt sich als Mithoerer an den DiscordClient: jede Karte, jeder Text und
 * jede Rueckfrage mit Knoepfen landet hier, BEVOR entschieden wird, ob sie
 * ueberhaupt nach Discord geht. Ohne Token sieht Autor sie so trotzdem, im
 * Steuerprogramm und als Windows-Meldung.
 *
 * Wer zuhoeren will (das Meldungen-Fenster, die Status-Fenster, die Windows-
 * Meldung), traegt sich mit {@link #zuhoeren} ein. Aufgerufen wird im Faden des
 * Senders -- Fenster muessen selbst auf den Swing-Faden wechseln.
 */
public final class Meldungen implements DiscordClient.Mithoerer {

    /** So viele Meldungen bleiben im Speicher. */
    private static final int HOECHSTENS = 400;

    private final Deque<Meldung> liste = new ArrayDeque<>();
    private final List<Consumer<Meldung>> zuhoerer = new ArrayList<>();

    @Override
    public void meldung(String kanal, String titel, String text, int farbe, List<String[]> knoepfe) {
        aufnehmen(Meldung.jetzt(kanal, titel, text, farbe, knoepfe));
    }

    /** Eine Meldung des Steuerprogramms selbst (etwa "Sprachsteuerung an"). */
    public void eigene(String titel, String text) {
        aufnehmen(Meldung.jetzt("", titel, text, DiscordClient.FARBE_INFO, List.of()));
    }

    public void eigene(String titel, String text, int farbe) {
        aufnehmen(Meldung.jetzt("", titel, text, farbe, List.of()));
    }

    private void aufnehmen(Meldung m) {
        List<Consumer<Meldung>> kopie;
        synchronized (liste) {
            liste.addLast(m);
            while (liste.size() > HOECHSTENS) liste.removeFirst();
            kopie = new ArrayList<>(zuhoerer);
        }
        for (Consumer<Meldung> z : kopie) {
            try {
                z.accept(m);
            } catch (Exception e) {
                // Ein Fenster, das klemmt, darf den Bot nie aufhalten.
            }
        }
    }

    /** Traegt einen Zuhoerer ein. */
    public void zuhoeren(Consumer<Meldung> z) {
        synchronized (liste) {
            zuhoerer.add(z);
        }
    }

    /** Traegt einen Zuhoerer wieder aus (ein Fenster, das neu gebaut wird). */
    public void nichtMehrZuhoeren(Consumer<Meldung> z) {
        synchronized (liste) {
            zuhoerer.remove(z);
        }
    }

    /** Alle gesammelten Meldungen, aelteste zuerst. */
    public List<Meldung> alle() {
        synchronized (liste) {
            return new ArrayList<>(liste);
        }
    }

    /** Die neueste Meldung, deren Titel eines der Woerter enthaelt -- oder null. */
    public Meldung letzteMit(String... titelWoerter) {
        synchronized (liste) {
            var it = liste.descendingIterator();
            while (it.hasNext()) {
                Meldung m = it.next();
                if (m.titelEnthaelt(titelWoerter)) return m;
            }
        }
        return null;
    }

    public int anzahl() {
        synchronized (liste) {
            return liste.size();
        }
    }

    public void leeren() {
        synchronized (liste) {
            liste.clear();
        }
    }
}
