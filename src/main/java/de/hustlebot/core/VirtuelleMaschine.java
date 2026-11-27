package de.hustlebot.core;

import java.util.Locale;

/**
 * Laeuft der Bot in einer virtuellen Maschine (VirtualBox, VMware, Hyper-V, QEMU/KVM, Xen, Parallels)? Autor 21.11.: in
 * VMs soll die schnelle DXGI-Fotoart von selbst an sein (in der HustleVM 1,3 ms statt 28 ms je Foto) -- Konrad spielt in
 * einer VM. Gelesen wird einmal der BIOS-Eintrag in der Registry; jeder Fehler gilt als "keine VM" (Verhalten wie bisher).
 */
public final class VirtuelleMaschine {

    private VirtuelleMaschine() {
    }

    private static volatile Boolean erkannt;

    /** Einmal je Programmstart aus der Registry (HKLM\HARDWARE\DESCRIPTION\System\BIOS). */
    public static boolean erkannt() {
        Boolean e = erkannt;
        if (e != null) return e;
        boolean vm = false;
        try {
            String pfad = "HARDWARE\\DESCRIPTION\\System\\BIOS";
            com.sun.jna.platform.win32.WinReg.HKEY hklm = com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE;
            String hersteller = com.sun.jna.platform.win32.Advapi32Util.registryValueExists(hklm, pfad, "SystemManufacturer")
                    ? com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(hklm, pfad, "SystemManufacturer") : "";
            String produkt = com.sun.jna.platform.win32.Advapi32Util.registryValueExists(hklm, pfad, "SystemProductName")
                    ? com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(hklm, pfad, "SystemProductName") : "";
            vm = istVm(hersteller, produkt);
            if (vm) Log.info("Start", Sprache.t("Running in a virtual machine (" + produkt + ") -- fast DXGI photos are on by default.",
                    "Laeuft in einer virtuellen Maschine (" + produkt + ") -- schnelle DXGI-Fotos sind von selbst an."));
        } catch (Throwable t) {
            vm = false;
        }
        erkannt = vm;
        return vm;
    }

    /** Rein pruefbar: erkennt die ueblichen VM-Hersteller/-Produktnamen. */
    static boolean istVm(String hersteller, String produkt) {
        String h = ((hersteller == null ? "" : hersteller) + " " + (produkt == null ? "" : produkt)).toLowerCase(Locale.ROOT);
        return h.contains("virtualbox") || h.contains("innotek") || h.contains("vmware") || h.contains("qemu")
                || h.contains("kvm") || h.contains("xen") || h.contains("parallels") || h.contains("bochs")
                || h.contains("virtual machine");
    }
}
