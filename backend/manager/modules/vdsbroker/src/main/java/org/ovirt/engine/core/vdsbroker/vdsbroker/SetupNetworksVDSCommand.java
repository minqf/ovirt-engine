package org.ovirt.engine.core.vdsbroker.vdsbroker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.common.businessentities.VdsNetworkInterface;
import org.ovirt.engine.core.common.businessentities.network;
import org.ovirt.engine.core.common.vdscommands.SetupNetworksVdsCommandParameters;
import org.ovirt.engine.core.compat.StringHelper;
import org.ovirt.engine.core.utils.NetworkUtils;
import org.ovirt.engine.core.vdsbroker.xmlrpc.XmlRpcStruct;

public class SetupNetworksVDSCommand<T extends SetupNetworksVdsCommandParameters> extends FutureVDSCommand<T> {

    protected static final String DHCP_BOOT_PROTOCOL = VdsProperties.dhcp;
    protected static final String BOOT_PROTOCOL = VdsProperties.bootproto;
    protected static final String BONDING_OPTIONS = "options";
    protected static final String SLAVES = "nics";
    private static final Map<String, String> REMOVE_OBJ = Collections.singletonMap("remove", Boolean.TRUE.toString());

    public SetupNetworksVDSCommand(T parameters) {
        super(parameters);
    }

    @Override
    protected void ExecuteVdsBrokerCommand() {
        httpTask = getBroker().setupNetworks(generateNetworks(), generateBonds(), generateOptions());
    }

    private XmlRpcStruct generateNetworks() {
        XmlRpcStruct networks = new XmlRpcStruct();

        for (network net : getParameters().getNetworks()) {
            Map<String, String> opts = new HashMap<String, String>();
            VdsNetworkInterface i = findNetworkInterface(net.getname(), getParameters().getInterfaces(),
                    getParameters().getBonds());
            String type = (i.getBonded() != null && i.getBonded()) ? "bonding" : "nic";

            opts.put(type, NetworkUtils.StripVlan(i.getName()));
            if (net.getvlan_id() != null) {
                opts.put("vlan", net.getvlan_id().toString());
            }
            // TODO: add bootproto to network object
            switch (i.getBootProtocol()) {
            case Dhcp:
                opts.put(BOOT_PROTOCOL, DHCP_BOOT_PROTOCOL);
                break;
            case StaticIp:
                putIfNotEmpty(opts, "ipaddr", i.getAddress());
                putIfNotEmpty(opts, "netmask", i.getSubnet());
                putIfNotEmpty(opts, "gateway", i.getGateway());
                break;
            }

            if (net.getMtu() != 0) {
                opts.put("mtu", String.valueOf(net.getMtu()));
            }

            opts.put("bridged", Boolean.toString(net.isVmNetwork()));
            networks.add(net.getname(), opts);
        }

        for (String net : getParameters().getRemovedNetworks()) {
            networks.add(net, REMOVE_OBJ);
        }

        return networks;
    }

    private XmlRpcStruct generateBonds() {
        XmlRpcStruct bonds = new XmlRpcStruct();

        for (VdsNetworkInterface bond : getParameters().getBonds()) {
            XmlRpcStruct opts = new XmlRpcStruct();
            opts.add(SLAVES, getBondNics(bond, getParameters().getInterfaces()));

            if (!StringHelper.isNullOrEmpty(bond.getBondOptions())) {
                opts.add(BONDING_OPTIONS, bond.getBondOptions());
            }
            bonds.add(bond.getName(), opts);
        }

        for (VdsNetworkInterface bond : getParameters().getRemovedBonds()) {
            bonds.add(bond.getName(), REMOVE_OBJ);
        }

        return bonds;
    }

    private XmlRpcStruct generateOptions() {
        XmlRpcStruct options = new XmlRpcStruct();

        options.add(VdsProperties.connectivityCheck, Boolean.toString(getParameters().isCheckConnectivity()));

        if (getParameters().getConectivityTimeout() >= 0) {
            options.add(VdsProperties.connectivityTimeout, getParameters().getConectivityTimeout());
        }
        return options;
    }

    private static void putIfNotEmpty(Map<String, String> map, String key, String value) {
        if (!StringUtils.isEmpty(value)) {
            map.put(key, value);
        }
    }

    private List<String> getBondNics(VdsNetworkInterface bond, List<VdsNetworkInterface> interfaces) {
        List<String> nics = new ArrayList<String>();

        for (VdsNetworkInterface i : interfaces) {
            if (bond.getName().equals(i.getBondName())) {
                nics.add(i.getName());
            }
        }
        return nics;
    }

    private VdsNetworkInterface findNetworkInterface(String network,
            List<VdsNetworkInterface> interfaces,
            List<VdsNetworkInterface> bonds) {
        for (VdsNetworkInterface i : interfaces) {
            if (network.equals(i.getNetworkName())) {
                return i;
            }
        }

        for (VdsNetworkInterface i : bonds) {
            if (network.equals(i.getNetworkName())) {
                return i;
            }
        }

        return null;
    }

}
