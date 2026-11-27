package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** VM-Erkennung fuer die DXGI-Voreinstellung (Autor 21.11.). */
class VirtuelleMaschineTest {

    @Test
    void uebliche_vms_werden_erkannt() {
        assertTrue(VirtuelleMaschine.istVm("innotek GmbH", "VirtualBox"));
        assertTrue(VirtuelleMaschine.istVm("VMware, Inc.", "VMware Virtual Platform"));
        assertTrue(VirtuelleMaschine.istVm("Microsoft Corporation", "Virtual Machine"));
        assertTrue(VirtuelleMaschine.istVm("QEMU", "Standard PC (Q35 + ICH9, 2009)"));
        assertTrue(VirtuelleMaschine.istVm("Xen", "HVM domU"));
    }

    @Test
    void echte_pcs_nicht() {
        assertFalse(VirtuelleMaschine.istVm("Micro-Star International Co., Ltd.", "MS-7E26"));
        assertFalse(VirtuelleMaschine.istVm("LENOVO", "21CB0021GE"));
        assertFalse(VirtuelleMaschine.istVm(null, null));
    }
}
