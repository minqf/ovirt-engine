package org.ovirt.engine.core.bll.utils;

import static org.junit.Assert.assertEquals;
import static org.ovirt.engine.core.utils.MockConfigRule.mockConfig;

import java.util.Arrays;
import java.util.List;

import org.junit.ClassRule;
import org.junit.Test;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.VmBase;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.compat.Version;
import org.ovirt.engine.core.utils.MockConfigRule;

public class EmulatedMachineUtilsTest {

    @ClassRule
    public static MockConfigRule mcr = new MockConfigRule(
            mockConfig(
                    ConfigValues.ClusterEmulatedMachines,
                    Version.v4_0,
                    Arrays.asList("pc-i440fx-rhel7.2.0", "pc-i440fx-2.1", "pseries-rhel7.2.0"))
    );

    @Test
    public void testEffectiveEmulatedMachineWithCustomSet() {
        final VmBase vmBase = new VmBase();
        final Cluster cluster = new Cluster();
        cluster.setEmulatedMachine("cluster-pc-i440fx-rhel7.3.0");
        vmBase.setCustomEmulatedMachine("testpc-i440fx-rhel7.3.0");
        assertEquals("testpc-i440fx-rhel7.3.0", EmulatedMachineUtils.getEffective(vmBase, () -> cluster));
    }

    @Test
    public void testEffectiveEmulatedMachineWithoutCustomSet() {
        final VmBase vmBase = new VmBase();
        final Cluster cluster = new Cluster();
        cluster.setEmulatedMachine("cluster-pc-i440fx-rhel7.3.0");
        assertEquals("cluster-pc-i440fx-rhel7.3.0", EmulatedMachineUtils.getEffective(vmBase, () -> cluster));
    }

    @Test
    public void testEffectiveEmulatedMachineCCV() {
        final VmBase vmBase = new VmBase();
        final Cluster cluster = new Cluster();
        cluster.setEmulatedMachine("pc-i440fx-rhel7.3.0");
        vmBase.setCustomCompatibilityVersion(Version.v4_0);
        assertEquals("pc-i440fx-rhel7.2.0", EmulatedMachineUtils.getEffective(vmBase, () -> cluster));
    }

    @Test
    public void testFindBestMatchForEmulateMachine() {
        String original = "pc-i440fx-rhel7.3.0";
        String bestMatch = "pc-i440fx-rhel7.2.0";
        List<String> candidates = Arrays.asList("pc-i440fx-2.1", bestMatch, "pseries-rhel7.2.0");
        assertEquals(bestMatch, EmulatedMachineUtils.findBestMatchForEmulatedMachine(original, candidates));
    }

    @Test
    public void testFindBestMatchForEmulateMachineKeepsCurrent() {
        String original = "pc-i440fx-rhel7.3.0";
        List<String> candidates = Arrays.asList("pc-i440fx-2.1", original, "pseries-rhel7.2.0");
        assertEquals(original, EmulatedMachineUtils.findBestMatchForEmulatedMachine(original, candidates));
    }

}