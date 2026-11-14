package de.hustlebot.bot;

import de.hustlebot.core.Sprache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Autor 30.10.: "Wieso steht im Seitenfenster so wenig ueber Flimsy? Keine Kampfpower, keine Punktzahl, nicht wie viele er gewonnen und verloren
 * hat, wie lange ein durchschnittlicher Kampf dauert, wie viele Punkte er bringt." Der Text fuer den Zettel im Seitenfenster, je Zeile ein Posten.
 * Autors Auswahl: Bilanz, Kampfdauer, Punkte, Kraft + Team-Punkte. Was unbekannt ist, steht nicht da.
 */
final class ArenaSteckbrief {

    private ArenaSteckbrief() { }

    /** So viele der letzten Kaempfe stehen einzeln da. */
    static final int LETZTE = 5;
    static final int PUNKTE_UNBEKANNT = Integer.MIN_VALUE;

    /**
     * @param verlauf       S/N der Kaempfe, der aelteste vorn; "" = keiner
     * @param siege         Siege ueber alle Schreibweisen
     * @param kaempfe       Kaempfe ueber alle Schreibweisen
     * @param chance        Siegchance in Prozent, -1 = nicht gerechnet
     * @param dauern        Kampfdauern in Sekunden, die aelteste vorn
     * @param zuletzt       wann der letzte Kampf war ("2026-10-30 23:31"), "" = unbekannt
     * @param punkte        Turnierpunkte der letzten Siege, die aeltesten vorn
     * @param schnittPunkte Punkte je Kampf im Schnitt, -1 = unbekannt
     * @param belohnung     was er jetzt bringt (150, 225), 0 = unbekannt
     * @param arenaPunkte   seine Turnierpunkte jetzt, -1 = nicht gelesen
     * @param kraft         seine Kraft, 0 oder weniger = unbekannt
     * @param meineKraft    deine Kraft, 0 oder weniger = unbekannt
     * @param gesamtPunkte  die Punkte des Bots fuer ihn, PUNKTE_UNBEKANNT = keine
     * @param teamTeile     woraus sie bestehen (teamTeile()), "" = Team nie angesehen
     */
    record Werte(String verlauf, int siege, int kaempfe, int chance, List<Integer> dauern, String zuletzt, List<Long> punkte,
                 int schnittPunkte, long belohnung, long arenaPunkte, long kraft, long meineKraft, int gesamtPunkte, String teamTeile) { }

    static String text(Werte w) {
        List<String> z = new ArrayList<>();
        if (w.kaempfe() > 0) {
            String v = w.verlauf() == null ? "" : w.verlauf();
            String letzte = v.substring(Math.max(0, v.length() - LETZTE));
            z.add(Sprache.t("Won ", "Gewonnen ") + w.siege() + Sprache.t(" · lost ", " · verloren ") + (w.kaempfe() - w.siege())
                    + (letzte.isEmpty() ? "" : Sprache.t(" · last: ", " · zuletzt: ") + String.join(" ", letzte.split(""))));
        } else {
            z.add(Sprache.t("No fights yet", "Noch kein Kampf"));
        }
        if (w.chance() >= 0) {
            z.add(Sprache.t("Win chance ", "Siegchance ") + w.chance() + Sprache.t(" % · lose chance ", " % · Verlustchance ") + (100 - w.chance()) + " %");
        }
        if (!w.dauern().isEmpty()) {
            int summe = 0, min = Integer.MAX_VALUE, max = 0;
            for (int d : w.dauern()) {
                summe += d;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            z.add(Sprache.t("Fight: average ", "Kampf: Schnitt ") + Math.round(summe / (double) w.dauern().size())
                    + Sprache.t(" s · shortest ", " s · kuerzester ") + min + Sprache.t(" s · longest ", " s · laengster ") + max + " s");
            List<Integer> letzte = w.dauern().subList(Math.max(0, w.dauern().size() - LETZTE), w.dauern().size());
            String wann = zeit(w.zuletzt());
            z.add(Sprache.t("Last fights: ", "Letzte Kaempfe: ") + letzte.stream().map(d -> d + " s").collect(Collectors.joining(", "))
                    + (wann.isEmpty() ? "" : Sprache.t(" · last fight ", " · letzter Kampf ") + wann));
        }
        if (w.schnittPunkte() >= 0 || !w.punkte().isEmpty()) {
            List<Long> letzte = w.punkte().subList(Math.max(0, w.punkte().size() - LETZTE), w.punkte().size());
            z.add((w.schnittPunkte() >= 0 ? Sprache.t("Average points per fight: ", "Punkte je Kampf im Schnitt: ") + w.schnittPunkte() : Sprache.t("Points", "Punkte"))
                    + (letzte.isEmpty() ? "" : Sprache.t(" · last: ", " · zuletzt: ") + letzte.stream().map(String::valueOf).collect(Collectors.joining(", "))));
        }
        if (w.belohnung() > 0 || w.arenaPunkte() >= 0) {
            String b = w.belohnung() > 0 ? Sprache.t("Reward now: +", "Belohnung jetzt: +") + w.belohnung() : "";
            String a = w.arenaPunkte() >= 0 ? Sprache.t("Arena points now: ", "Arena-Punkte jetzt: ") + w.arenaPunkte() : "";
            z.add(b.isEmpty() || a.isEmpty() ? b + a : b + " · " + a);
        }
        if (w.kraft() > 0) {
            z.add(Sprache.t("Power ", "Kraft ") + Sprache.zahl(w.kraft()) + kraftAbstand(w.kraft(), w.meineKraft()));
        }
        if (w.gesamtPunkte() != PUNKTE_UNBEKANNT || (w.teamTeile() != null && !w.teamTeile().isBlank())) {
            String t = w.teamTeile() == null || w.teamTeile().isBlank() ? "" : " (" + w.teamTeile() + ")";
            z.add((w.gesamtPunkte() != PUNKTE_UNBEKANNT ? Sprache.t("Points ", "Punkte ") + w.gesamtPunkte() : Sprache.t("Team", "Team")) + t);
        }
        return String.join("\n", z);
    }

    private static String kraftAbstand(long seine, long meine) {
        if (meine <= 0) return "";
        double mio = Math.abs(seine - meine) / 1_000_000.0;
        String zahl = String.format(Sprache.istEnglisch() ? Locale.ENGLISH : Locale.GERMANY, "%.1f", mio);
        // Autor 01.11.: weit ueber der eigenen Kraft soll die Anzeige das sagen -- dieselbe Grenze wie die Chance (1,5-fach, siegchanceRoh).
        String weit = seine > meine * 1.5 ? Sprache.t(" -- much stronger than you", " -- deutlich staerker als du") : "";
        return " · " + zahl + (seine > meine ? Sprache.t(" M above you", " Mio ueber dir") : Sprache.t(" M below you", " Mio unter dir")) + weit;
    }

    /** "2026-10-30 23:31" wird "30.10. 23:31"; alles andere bleibt leer. */
    static String zeit(String t) {
        if (t == null || t.length() < 16 || t.charAt(4) != '-' || t.charAt(7) != '-') return "";
        return t.substring(8, 10) + "." + t.substring(5, 7) + ". " + t.substring(11, 16);
    }

    /** Woraus die Team-Punkte bestehen; was 0 ist, steht nicht da. */
    static String teamTeile(int sets, int artefakte, int bauarbeiter, int fehlende, int steine, int schirm, int pals) {
        List<String> t = new ArrayList<>();
        if (sets > 0) t.add(Sprache.t("sets ", "Sets ") + sets);
        if (artefakte > 0) t.add(Sprache.t("artifacts ", "Artefakte ") + artefakte);
        if (bauarbeiter > 0) t.add(Sprache.t("builders ", "Bauarbeiter ") + bauarbeiter);
        if (fehlende > 0) t.add(Sprache.t("missing units ", "fehlende Einheiten ") + fehlende);
        if (steine > 0) t.add(Sprache.t("stones ", "Steine ") + steine);
        if (schirm > 0) t.add(Sprache.t("patronages ", "Schirm ") + schirm);
        if (pals > 0) t.add("pals " + pals);
        return String.join(", ", t);
    }
}
