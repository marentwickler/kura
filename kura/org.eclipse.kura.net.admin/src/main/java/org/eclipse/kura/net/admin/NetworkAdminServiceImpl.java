/*******************************************************************************
 * Copyright (c) 2011, 2017 Eurotech and/or its affiliates
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *******************************************************************************/
package org.eclipse.kura.net.admin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.configuration.ComponentConfiguration;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.configuration.SelfConfiguringComponent;
import org.eclipse.kura.core.net.AbstractNetInterface;
import org.eclipse.kura.core.net.NetInterfaceAddressConfigImpl;
import org.eclipse.kura.core.net.NetworkConfiguration;
import org.eclipse.kura.core.net.WifiInterfaceAddressConfigImpl;
import org.eclipse.kura.core.net.modem.ModemInterfaceAddressConfigImpl;
import org.eclipse.kura.core.net.modem.ModemInterfaceConfigImpl;
import org.eclipse.kura.linux.net.dhcp.DhcpClientManager;
import org.eclipse.kura.linux.net.dhcp.DhcpServerManager;
import org.eclipse.kura.linux.net.dns.LinuxNamed;
import org.eclipse.kura.linux.net.iptables.LinuxFirewall;
import org.eclipse.kura.linux.net.iptables.NATRule;
import org.eclipse.kura.linux.net.util.IScanTool;
import org.eclipse.kura.linux.net.util.KuraConstants;
import org.eclipse.kura.linux.net.util.LinuxNetworkUtil;
import org.eclipse.kura.linux.net.util.ScanTool;
import org.eclipse.kura.linux.net.wifi.HostapdManager;
import org.eclipse.kura.linux.net.wifi.WpaSupplicantManager;
import org.eclipse.kura.linux.net.wifi.WpaSupplicantStatus;
import org.eclipse.kura.net.IPAddress;
import org.eclipse.kura.net.NetConfig;
import org.eclipse.kura.net.NetConfig6;
import org.eclipse.kura.net.NetConfigIP4;
import org.eclipse.kura.net.NetConfigIP6;
import org.eclipse.kura.net.NetInterfaceAddressConfig;
import org.eclipse.kura.net.NetInterfaceConfig;
import org.eclipse.kura.net.NetInterfaceStatus;
import org.eclipse.kura.net.NetInterfaceType;
import org.eclipse.kura.net.NetworkAdminService;
import org.eclipse.kura.net.admin.event.FirewallConfigurationChangeEvent;
import org.eclipse.kura.net.admin.event.NetworkConfigurationChangeEvent;
import org.eclipse.kura.net.admin.monitor.WifiInterfaceState;
import org.eclipse.kura.net.admin.visitor.linux.WpaSupplicantConfigWriter;
import org.eclipse.kura.net.admin.visitor.linux.util.KuranetConfig;
import org.eclipse.kura.net.dhcp.DhcpServerConfigIP4;
import org.eclipse.kura.net.firewall.FirewallAutoNatConfig;
import org.eclipse.kura.net.firewall.FirewallNatConfig;
import org.eclipse.kura.net.firewall.FirewallOpenPortConfigIP;
import org.eclipse.kura.net.firewall.FirewallPortForwardConfigIP;
import org.eclipse.kura.net.modem.ModemConfig;
import org.eclipse.kura.net.wifi.WifiAccessPoint;
import org.eclipse.kura.net.wifi.WifiConfig;
import org.eclipse.kura.net.wifi.WifiHotspotInfo;
import org.eclipse.kura.net.wifi.WifiInterfaceAddressConfig;
import org.eclipse.kura.net.wifi.WifiMode;
import org.eclipse.kura.net.wifi.WifiSecurity;
import org.eclipse.kura.system.SystemService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkAdminServiceImpl implements NetworkAdminService, EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(NetworkAdminServiceImpl.class);

    private static final String OS_VERSION = System.getProperty("kura.os.version");

    private ConfigurationService configurationService;
    private NetworkConfigurationService networkConfigurationService;
    private FirewallConfigurationService firewallConfigurationService;
    private SystemService systemService;

    private boolean pendingNetworkConfigurationChange = false;
    private boolean pendingFirewallConfigurationChange = false;

    private static final String[] EVENT_TOPICS = new String[] {
            NetworkConfigurationChangeEvent.NETWORK_EVENT_CONFIG_CHANGE_TOPIC, };

    private class NetworkRollbackItem {

        String src;
        String dst;

        NetworkRollbackItem(String src, String dst) {
            this.src = src;
            this.dst = dst;
        }
    }

    // ----------------------------------------------------------------
    //
    // Dependencies
    //
    // ----------------------------------------------------------------
    public void setConfigurationService(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    public void unsetConfigurationService(ConfigurationService configurationService) {
        this.configurationService = null;
    }

    public void setNetworkConfigurationService(NetworkConfigurationService networkConfigurationService) {
        this.networkConfigurationService = networkConfigurationService;
    }

    public void unsetNetworkConfigurationService(NetworkConfigurationService networkConfigurationService) {
        this.networkConfigurationService = null;
    }

    public void setFirewallConfigurationService(FirewallConfigurationService firewallConfigurationService) {
        this.firewallConfigurationService = firewallConfigurationService;
    }

    public void unsetFirewallConfigurationService(FirewallConfigurationService firewallConfigurationService) {
        this.firewallConfigurationService = null;
    }

    public void setSystemService(SystemService systemService) {
        this.systemService = systemService;
    }

    public void unsetSystemService(SystemService systemService) {
        this.systemService = null;
    }

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    protected void activate(ComponentContext componentContext) {

        logger.debug("Activating NetworkAdmin Service...");

        // save the bundle context
        ComponentContext ctx = componentContext;

        // since we are just starting up, start named if needed
        LinuxNamed linuxNamed;
        try {
            linuxNamed = LinuxNamed.getInstance();
            if (linuxNamed.isConfigured()) {
                linuxNamed.disable();
                linuxNamed.enable();
            }
        } catch (KuraException e) {
            logger.warn("Exception while activating NetworkAdmin Service!", e);
        }

        Dictionary<String, String[]> d = new Hashtable<>();
        d.put(EventConstants.EVENT_TOPIC, EVENT_TOPICS);
        ctx.getBundleContext().registerService(EventHandler.class.getName(), this, d);

        logger.debug("Done Activating NetworkAdmin Service...");
    }

    protected void deactivate(ComponentContext componentContext) {
    }

    @Override
    // FIME: This api should be deprecated in favor of the following signature:
    // List<? extends NetInterfaceConfig<? extends NetInterfaceAddressConfig>> getNetworkInterfaceConfigs()
    public List<? extends NetInterfaceConfig<? extends NetInterfaceAddressConfig>> getNetworkInterfaceConfigs()
            throws KuraException {

        try {
            logger.debug("Getting all networkInterfaceConfigs");
            return this.networkConfigurationService.getNetworkConfiguration().getNetInterfaceConfigs();
        } catch (Exception e) {
            throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public List<NetConfig> getNetworkInterfaceConfigs(String interfaceName) throws KuraException {

        ArrayList<NetConfig> netConfigs = new ArrayList<>();
        NetworkConfiguration networkConfig = this.networkConfigurationService.getNetworkConfiguration();
        if (interfaceName != null && networkConfig != null) {
            try {
                logger.debug("Getting networkInterfaceConfigs for {}", interfaceName);
                if (networkConfig.getNetInterfaceConfigs() != null
                        && !networkConfig.getNetInterfaceConfigs().isEmpty()) {
                    for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : networkConfig
                            .getNetInterfaceConfigs()) {
                        if (interfaceName.equals(netInterfaceConfig.getName())) {
                            List<? extends NetInterfaceAddressConfig> netInterfaceAddressConfigs = netInterfaceConfig
                                    .getNetInterfaceAddresses();
                            if (netInterfaceAddressConfigs != null && !netInterfaceAddressConfigs.isEmpty()) {
                                for (NetInterfaceAddressConfig netInterfaceAddressConfig : netInterfaceAddressConfigs) {
                                    netConfigs.addAll(netInterfaceAddressConfig.getConfigs());
                                }
                            }

                            break;
                        }
                    }
                }
            } catch (Exception e) {
                throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
            }
        }

        return netConfigs;
    }

    @Override
    public void updateEthernetInterfaceConfig(String interfaceName, boolean autoConnect, int mtu,
            List<NetConfig> netConfigs) throws KuraException {

        NetConfigIP4 netConfig4 = null;
        NetConfigIP6 netConfig6 = null;
        DhcpServerConfigIP4 dhcpServerConfigIP4 = null;
        FirewallAutoNatConfig natConfig = null;
        boolean hadNetConfig4 = false;
        boolean hadNetConfig6 = false;
        boolean hadDhcpServerConfigIP4 = false;
        boolean hadNatConfig = false;

        if (netConfigs != null && !netConfigs.isEmpty()) {
            for (NetConfig netConfig : netConfigs) {
                if (!netConfig.isValid()) {
                    throw new KuraException(KuraErrorCode.CONFIGURATION_ERROR,
                            "NetConfig Configuration is invalid: " + netConfig.toString());
                }
                if (netConfig instanceof NetConfigIP4) {
                    netConfig4 = (NetConfigIP4) netConfig;
                } else if (netConfig instanceof NetConfigIP6) {
                    netConfig6 = (NetConfigIP6) netConfig;
                } else if (netConfig instanceof DhcpServerConfigIP4) {
                    dhcpServerConfigIP4 = (DhcpServerConfigIP4) netConfig;
                } else if (netConfig instanceof FirewallAutoNatConfig) {
                    natConfig = (FirewallAutoNatConfig) netConfig;
                }
            }
        }

        // validation
        if (netConfig4 == null && netConfig6 == null) {
            throw new KuraException(KuraErrorCode.CONFIGURATION_REQUIRED_ATTRIBUTE_MISSING,
                    "Either IPv4 or IPv6 configuration must be defined");
        }

        List<String> modifiedInterfaceNames = new ArrayList<>();
        boolean configurationChanged = false;

        ComponentConfiguration originalNetworkComponentConfiguration = ((SelfConfiguringComponent) this.networkConfigurationService)
                .getConfiguration();
        if (originalNetworkComponentConfiguration == null) {
            logger.debug("Returning for some unknown reason - no existing config???");
            return;
        }
        try {
            NetworkConfiguration newNetworkConfiguration = new NetworkConfiguration(
                    originalNetworkComponentConfiguration.getConfigurationProperties());
            List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = newNetworkConfiguration
                    .getNetInterfaceConfigs();
            for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
                if (netInterfaceConfig.getName().equals(interfaceName)) {
                    // handle MTU
                    if (mtu != netInterfaceConfig.getMTU()) {
                        AbstractNetInterface<?> absNetInterfaceConfig = (AbstractNetInterface<?>) netInterfaceConfig;
                        logger.debug("updating MTU for {}", interfaceName);
                        absNetInterfaceConfig.setMTU(mtu);
                        configurationChanged = true;
                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                            modifiedInterfaceNames.add(interfaceName);
                        }
                    }

                    // handle autoconnect
                    if (autoConnect != netInterfaceConfig.isAutoConnect()) {
                        AbstractNetInterface<?> absNetInterfaceConfig = (AbstractNetInterface<?>) netInterfaceConfig;
                        logger.debug("updating autoConnect for {} to be {}", interfaceName, autoConnect);
                        absNetInterfaceConfig.setAutoConnect(autoConnect);
                        configurationChanged = true;
                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                            modifiedInterfaceNames.add(interfaceName);
                        }
                    }

                    // replace existing configs
                    List<? extends NetInterfaceAddressConfig> netInterfaceAddressConfigs = netInterfaceConfig
                            .getNetInterfaceAddresses();
                    if (netInterfaceAddressConfigs != null && !netInterfaceAddressConfigs.isEmpty()) {
                        for (NetInterfaceAddressConfig netInterfaceAddressConfig : netInterfaceAddressConfigs) {
                            List<NetConfig> existingNetConfigs = netInterfaceAddressConfig.getConfigs();
                            List<NetConfig> newNetConfigs = new ArrayList<>();
                            for (NetConfig netConfig : existingNetConfigs) {
                                logger.debug("looking at existing NetConfig for {} with value: {}", interfaceName,
                                        netConfig);
                                if (netConfig instanceof NetConfigIP4) {
                                    if (netConfig4 == null) {
                                        logger.debug("removing NetConfig4 for {}", interfaceName);
                                    } else {
                                        hadNetConfig4 = true;
                                        newNetConfigs.add(netConfig4);
                                        if (!netConfig.equals(netConfig4)) {
                                            logger.debug("updating NetConfig4 for {}", interfaceName);
                                            logger.debug("Is new State DHCP? {}", netConfig4.isDhcp());
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating NetConfig4 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof NetConfig6) {
                                    if (netConfig6 == null) {
                                        logger.debug("removing NetConfig6 for {}", interfaceName);
                                    } else {
                                        hadNetConfig6 = true;
                                        newNetConfigs.add(netConfig6);
                                        if (!netConfig.equals(netConfig6)) {
                                            logger.debug("updating NetConfig6 for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating NetConfig6 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof DhcpServerConfigIP4) {
                                    if (dhcpServerConfigIP4 == null) {
                                        logger.debug("removing DhcpServerConfigIP4 for {}", interfaceName);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    } else {
                                        hadDhcpServerConfigIP4 = true;
                                        newNetConfigs.add(dhcpServerConfigIP4);
                                        if (!netConfig.equals(dhcpServerConfigIP4)) {
                                            logger.debug("updating DhcpServerConfigIP4 for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug(
                                                    "not updating DhcpServerConfigIP4 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof FirewallAutoNatConfig) {
                                    if (natConfig == null) {
                                        logger.debug("removing FirewallAutoNatConfig for {}", interfaceName);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    } else {
                                        hadNatConfig = true;
                                        newNetConfigs.add(natConfig);
                                        if (!netConfig.equals(natConfig)) {
                                            logger.debug("updating FirewallAutoNatConfig for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug(
                                                    "not updating FirewallAutoNatConfig for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else {
                                    logger.debug("Found unsupported configuration: {}", netConfig.toString());
                                }
                            }

                            // add configs that did not match any in the current configuration
                            if (netConfigs != null && !netConfigs.isEmpty()) {
                                for (NetConfig netConfig : netConfigs) {
                                    if (netConfig instanceof NetConfigIP4 && !hadNetConfig4) {
                                        logger.debug("adding new NetConfig4 to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof NetConfigIP6 && !hadNetConfig6) {
                                        logger.debug("adding new NetConfig6 to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof DhcpServerConfigIP4 && !hadDhcpServerConfigIP4) {
                                        logger.debug("adding new DhcpServerConfigIP4 to existing config for {}",
                                                interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof FirewallAutoNatConfig && !hadNatConfig) {
                                        logger.debug("adding new FirewallAutoNatConfig to existing config for {}",
                                                interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                }
                            }

                            for (NetConfig netConfig : newNetConfigs) {
                                logger.debug("New NetConfig: {} :: {}", netConfig.getClass().toString(),
                                        netConfig.toString());
                            }

                            // replace with new list
                            ((NetInterfaceAddressConfigImpl) netInterfaceAddressConfig).setNetConfigs(newNetConfigs);
                        }
                    }
                }
            }

            if (configurationChanged) {
                submitNetworkConfiguration(modifiedInterfaceNames, newNetworkConfiguration);
            }
        } catch (UnknownHostException e) {
            logger.warn("Exception while updating EthernetInterfaceConfig", e);
        }
    }

    @Override
    public void updateWifiInterfaceConfig(String interfaceName, boolean autoConnect, WifiAccessPoint accessPoint,
            List<NetConfig> netConfigs) throws KuraException {

        NetConfigIP4 netConfig4 = null;
        NetConfigIP6 netConfig6 = null;
        WifiConfig wifiConfig = null;
        DhcpServerConfigIP4 dhcpServerConfigIP4 = null;
        FirewallAutoNatConfig natConfig = null;
        boolean hadNetConfig4 = false;
        boolean hadNetConfig6 = false;
        boolean hadWifiConfig = false;
        boolean hadDhcpServerConfigIP4 = false;
        boolean hadNatConfig = false;

        if (netConfigs != null && !netConfigs.isEmpty()) {
            for (NetConfig netConfig : netConfigs) {
                if (!netConfig.isValid()) {
                    throw new KuraException(KuraErrorCode.CONFIGURATION_ERROR,
                            "NetConfig Configuration is invalid: " + netConfig.toString());
                }

                if (netConfig instanceof NetConfigIP4) {
                    logger.debug("got new NetConfigIP4");
                    netConfig4 = (NetConfigIP4) netConfig;
                } else if (netConfig instanceof NetConfigIP6) {
                    logger.debug("got new NetConfigIP6");
                    netConfig6 = (NetConfigIP6) netConfig;
                } else if (netConfig instanceof WifiConfig) {
                    logger.debug("got new WifiConfig");
                    wifiConfig = (WifiConfig) netConfig;
                } else if (netConfig instanceof DhcpServerConfigIP4) {
                    logger.debug("got new DhcpServerConfigIP4");
                    dhcpServerConfigIP4 = (DhcpServerConfigIP4) netConfig;
                } else if (netConfig instanceof FirewallAutoNatConfig) {
                    logger.debug("got new NatConfig");
                    natConfig = (FirewallAutoNatConfig) netConfig;
                }
            }
        }

        // validation
        if (netConfig4 == null && netConfig6 == null) {
            throw new KuraException(KuraErrorCode.CONFIGURATION_REQUIRED_ATTRIBUTE_MISSING,
                    "Either IPv4 or IPv6 configuration must be defined");
        }
        if (wifiConfig == null) {
            throw new KuraException(KuraErrorCode.CONFIGURATION_REQUIRED_ATTRIBUTE_MISSING,
                    "WiFi configuration must be defined");
        }

        List<String> modifiedInterfaceNames = new ArrayList<>();
        boolean configurationChanged = false;

        ComponentConfiguration originalNetworkComponentConfiguration = ((SelfConfiguringComponent) this.networkConfigurationService)
                .getConfiguration();
        if (originalNetworkComponentConfiguration == null) {
            return;
        }
        try {
            NetworkConfiguration newNetworkConfiguration = new NetworkConfiguration(
                    originalNetworkComponentConfiguration.getConfigurationProperties());
            List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = newNetworkConfiguration
                    .getNetInterfaceConfigs();
            for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
                if (netInterfaceConfig.getName().equals(interfaceName)) {

                    // replace existing configs
                    List<? extends NetInterfaceAddressConfig> netInterfaceAddressConfigs = netInterfaceConfig
                            .getNetInterfaceAddresses();
                    if (netInterfaceAddressConfigs != null && !netInterfaceAddressConfigs.isEmpty()) {
                        for (NetInterfaceAddressConfig netInterfaceAddressConfig : netInterfaceAddressConfigs) {
                            List<NetConfig> existingNetConfigs = netInterfaceAddressConfig.getConfigs();
                            List<NetConfig> newNetConfigs = new ArrayList<>();
                            WifiMode newWifiMode = wifiConfig != null ? wifiConfig.getMode() : null;
                            for (NetConfig netConfig : existingNetConfigs) {
                                logger.debug("looking at existing NetConfig for {} with value: {}", interfaceName,
                                        netConfig);
                                if (netConfig instanceof NetConfigIP4) {
                                    if (netConfig4 == null) {
                                        logger.debug("removing NetConfig4 for {}", interfaceName);
                                    } else {
                                        hadNetConfig4 = true;
                                        newNetConfigs.add(netConfig4);
                                        if (!netConfig.equals(netConfig4)) {
                                            logger.debug("updating NetConfig4 for {}", interfaceName);
                                            logger.debug("Is new State DHCP? {}", netConfig4.isDhcp());
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating NetConfig4 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof NetConfig6) {
                                    if (netConfig6 == null) {
                                        logger.debug("removing NetConfig6 for {}", interfaceName);
                                    } else {
                                        hadNetConfig6 = true;
                                        newNetConfigs.add(netConfig6);
                                        if (!netConfig.equals(netConfig6)) {
                                            logger.debug("updating NetConfig6 for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating NetConfig6 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof WifiConfig) {
                                    if (wifiConfig == null) {
                                        logger.debug("removing wifiConfig for {}", interfaceName);
                                    } else {
                                        // There should be one new WifiConfig, which indicates the selected mode
                                        // but there may be multiple current wifi configs, one for each mode (infra,
                                        // master, adhoc)
                                        // Check the one corresponding to the newly selected mode, and automatically the
                                        // others
                                        if (newWifiMode.equals(((WifiConfig) netConfig).getMode())) {
                                            hadWifiConfig = true;
                                            newNetConfigs.add(wifiConfig);
                                            logger.debug("checking WifiConfig for {} mode", wifiConfig.getMode());
                                            if (!netConfig.equals(wifiConfig)) {
                                                logger.debug("updating WifiConfig for {}", interfaceName);
                                                configurationChanged = true;
                                                if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                    modifiedInterfaceNames.add(interfaceName);
                                                }
                                            } else {
                                                logger.debug("not updating WifiConfig for {} because it is unchanged",
                                                        interfaceName);
                                            }
                                        } else {
                                            // Keep the old WifiConfig for the non-selected wifi modes
                                            logger.debug("adding other WifiConfig: {}", netConfig);
                                            newNetConfigs.add(netConfig);
                                        }
                                    }
                                } else if (netConfig instanceof DhcpServerConfigIP4) {
                                    if (dhcpServerConfigIP4 == null) {
                                        logger.debug("removing DhcpServerConfigIP4 for {}", interfaceName);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    } else {
                                        hadDhcpServerConfigIP4 = true;
                                        newNetConfigs.add(dhcpServerConfigIP4);
                                        if (!netConfig.equals(dhcpServerConfigIP4)) {
                                            logger.debug("updating DhcpServerConfigIP4 for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug(
                                                    "not updating DhcpServerConfigIP4 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof FirewallAutoNatConfig) {
                                    if (natConfig == null) {
                                        logger.debug("removing FirewallAutoNatConfig for {}", interfaceName);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    } else {
                                        hadNatConfig = true;
                                        newNetConfigs.add(natConfig);
                                        if (!netConfig.equals(natConfig)) {
                                            logger.debug("updating FirewallAutoNatConfig for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug(
                                                    "not updating FirewallNatConfig for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else {
                                    logger.debug("Found unsupported configuration: {}", netConfig.toString());
                                }
                            }

                            // add configs that did not match any in the current configuration
                            if (netConfigs != null && !netConfigs.isEmpty()) {
                                for (NetConfig netConfig : netConfigs) {
                                    if (netConfig instanceof NetConfigIP4 && !hadNetConfig4) {
                                        logger.debug("adding new NetConfig4 to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof NetConfigIP6 && !hadNetConfig6) {
                                        logger.debug("adding new NetConfig6 to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof WifiConfig && !hadWifiConfig) {
                                        logger.debug("adding new WifiConfig to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof DhcpServerConfigIP4 && !hadDhcpServerConfigIP4) {
                                        logger.debug("adding new DhcpServerConfigIP4 to existing config for {}",
                                                interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof FirewallAutoNatConfig && !hadNatConfig) {
                                        logger.debug("adding new FirewallAutoNatConfig to existing config for {}",
                                                interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                }
                            }

                            // Update the wifi mode
                            if (newWifiMode != null) {
                                logger.debug("setting address config wifiMode to: {}", newWifiMode);
                                ((WifiInterfaceAddressConfigImpl) netInterfaceAddressConfig).setMode(newWifiMode);
                            }

                            // replace with new list
                            for (NetConfig netConfig : newNetConfigs) {
                                logger.debug("Current NetConfig: {} :: {}", netConfig.getClass(), netConfig);
                            }
                            ((WifiInterfaceAddressConfigImpl) netInterfaceAddressConfig).setNetConfigs(newNetConfigs);
                        }
                    }
                }
            }

            if (configurationChanged) {
                submitNetworkConfiguration(modifiedInterfaceNames, newNetworkConfiguration);
            }
        } catch (UnknownHostException e) {
            logger.warn("Exception while updating WifiInterfaceConfig", e);
        }
    }

    @Override
    public void updateModemInterfaceConfig(String interfaceName, String serialNum, String modemId, int pppNumber,
            boolean autoConnect, int mtu, List<NetConfig> netConfigs) throws KuraException {

        NetConfigIP4 netConfig4 = null;
        NetConfigIP6 netConfig6 = null;
        ModemConfig modemConfig = null;
        boolean hadNetConfig4 = false;
        boolean hadNetConfig6 = false;
        boolean hadModemConfig = false;

        if (netConfigs != null && !netConfigs.isEmpty()) {
            for (NetConfig netConfig : netConfigs) {
                if (!netConfig.isValid()) {
                    throw new KuraException(KuraErrorCode.CONFIGURATION_ERROR,
                            "NetConfig Configuration is invalid: " + netConfig.toString());
                }
                if (netConfig instanceof NetConfigIP4) {
                    netConfig4 = (NetConfigIP4) netConfig;
                } else if (netConfig instanceof NetConfigIP6) {
                    netConfig6 = (NetConfigIP6) netConfig;
                } else if (netConfig instanceof ModemConfig) {
                    modemConfig = (ModemConfig) netConfig;
                }
            }
        }

        // validation
        if (netConfig4 == null && netConfig6 == null) {
            throw new KuraException(KuraErrorCode.CONFIGURATION_REQUIRED_ATTRIBUTE_MISSING,
                    "Either IPv4 or IPv6 configuration must be defined");
        }
        if (modemConfig == null) {
            throw new KuraException(KuraErrorCode.CONFIGURATION_REQUIRED_ATTRIBUTE_MISSING,
                    "Modem configuration must be defined");
        }

        List<String> modifiedInterfaceNames = new ArrayList<>();
        boolean configurationChanged = false;

        ComponentConfiguration originalNetworkComponentConfiguration = ((SelfConfiguringComponent) this.networkConfigurationService)
                .getConfiguration();
        if (originalNetworkComponentConfiguration == null) {
            return;
        }
        try {
            NetworkConfiguration newNetworkConfiguration = new NetworkConfiguration(
                    originalNetworkComponentConfiguration.getConfigurationProperties());
            List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = newNetworkConfiguration
                    .getNetInterfaceConfigs();
            for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
                if (netInterfaceConfig.getName().equals(interfaceName)) {
                    // handle MTU
                    if (mtu != netInterfaceConfig.getMTU()) {
                        AbstractNetInterface<?> absNetInterfaceConfig = (AbstractNetInterface<?>) netInterfaceConfig;
                        logger.debug("updating MTU for {}", interfaceName);
                        absNetInterfaceConfig.setMTU(mtu);
                        configurationChanged = true;
                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                            modifiedInterfaceNames.add(interfaceName);
                        }
                    }

                    if (netInterfaceConfig instanceof ModemInterfaceConfigImpl) {
                        ModemInterfaceConfigImpl modemInterfaceConfig = (ModemInterfaceConfigImpl) netInterfaceConfig;
                        if (modemId == null) {
                            modemId = "";
                        }

                        // handle modem id
                        if (!modemId.equals(modemInterfaceConfig.getModemIdentifier())) {
                            logger.debug("updating Modem identifier: {}", modemId);
                            modemInterfaceConfig.setModemIdentifier(modemId);
                            configurationChanged = true;
                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                modifiedInterfaceNames.add(interfaceName);
                            }
                        }

                        // handle ppp num
                        if (pppNumber != modemInterfaceConfig.getPppNum()) {
                            logger.debug("updating PPP number: {}", pppNumber);
                            modemInterfaceConfig.setPppNum(pppNumber);
                            configurationChanged = true;
                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                modifiedInterfaceNames.add(interfaceName);
                            }
                        }
                    }

                    // replace existing configs
                    List<? extends NetInterfaceAddressConfig> netInterfaceAddressConfigs = netInterfaceConfig
                            .getNetInterfaceAddresses();
                    if (netInterfaceAddressConfigs != null && !netInterfaceAddressConfigs.isEmpty()) {
                        for (NetInterfaceAddressConfig netInterfaceAddressConfig : netInterfaceAddressConfigs) {
                            List<NetConfig> existingNetConfigs = netInterfaceAddressConfig.getConfigs();
                            List<NetConfig> newNetConfigs = new ArrayList<>();
                            for (NetConfig netConfig : existingNetConfigs) {
                                logger.debug("looking at existing NetConfig for {} with value: {}", interfaceName,
                                        netConfig);
                                if (netConfig instanceof NetConfigIP4) {
                                    if (netConfig4 == null) {
                                        logger.debug("removing NetConfig4 for {}", interfaceName);
                                    } else {
                                        hadNetConfig4 = true;
                                        newNetConfigs.add(netConfig4);
                                        if (!netConfig.equals(netConfig4)) {
                                            logger.debug("updating NetConfig4 for {}", interfaceName);
                                            logger.debug("Is new State DHCP? {}", netConfig4.isDhcp());
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating NetConfig4 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof NetConfig6) {
                                    if (netConfig6 == null) {
                                        logger.debug("removing NetConfig6 for {}", interfaceName);
                                    } else {
                                        hadNetConfig6 = true;
                                        newNetConfigs.add(netConfig6);
                                        if (!netConfig.equals(netConfig6)) {
                                            logger.debug("updating NetConfig6 for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating NetConfig6 for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else if (netConfig instanceof ModemConfig) {
                                    if (modemConfig == null) {
                                        logger.debug("removing ModemConfig for {}", interfaceName);
                                    } else {
                                        hadModemConfig = true;
                                        newNetConfigs.add(modemConfig);
                                        if (!netConfig.equals(modemConfig)) {
                                            logger.debug("updating ModemConfig for {}", interfaceName);
                                            configurationChanged = true;
                                            if (!modifiedInterfaceNames.contains(interfaceName)) {
                                                modifiedInterfaceNames.add(interfaceName);
                                            }
                                        } else {
                                            logger.debug("not updating ModemConfig for {} because it is unchanged",
                                                    interfaceName);
                                        }
                                    }
                                } else {
                                    logger.debug("Found unsupported configuration: {}", netConfig.toString());
                                }
                            }

                            // add configs that did not match any in the current configuration
                            if (netConfigs != null && !netConfigs.isEmpty()) {
                                for (NetConfig netConfig : netConfigs) {
                                    if (netConfig instanceof NetConfigIP4 && !hadNetConfig4) {
                                        logger.debug("adding new NetConfig4 to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof NetConfigIP6 && !hadNetConfig6) {
                                        logger.debug("adding new NetConfig6 to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                    if (netConfig instanceof ModemConfig && !hadModemConfig) {
                                        logger.debug("adding new ModemConfig to existing config for {}", interfaceName);
                                        newNetConfigs.add(netConfig);
                                        configurationChanged = true;
                                        if (!modifiedInterfaceNames.contains(interfaceName)) {
                                            modifiedInterfaceNames.add(interfaceName);
                                        }
                                    }
                                }
                            }

                            for (NetConfig netConfig : newNetConfigs) {
                                logger.debug("Current NetConfig: {} :: {}", netConfig.getClass(), netConfig);
                            }

                            // replace with new list
                            ((ModemInterfaceAddressConfigImpl) netInterfaceAddressConfig).setNetConfigs(newNetConfigs);
                        }
                    }
                }

                newNetworkConfiguration.addNetInterfaceConfig(netInterfaceConfig);
            }

            if (configurationChanged) {
                submitNetworkConfiguration(modifiedInterfaceNames, newNetworkConfiguration);
            }
        } catch (UnknownHostException e) {
            logger.warn("Exception while updating ModemInterfaceConfig", e);
        }
    }

    @Override
    public void enableInterface(String interfaceName, boolean dhcp) throws KuraException {

        try {
            NetInterfaceType type = LinuxNetworkUtil.getType(interfaceName);

            NetInterfaceStatus status = NetInterfaceStatus.netIPv4StatusUnknown;
            WifiMode wifiMode = WifiMode.UNKNOWN;
            WifiConfig wifiConfig = null;
            WifiInterfaceState wifiInterfaceState = null;
            if (type == NetInterfaceType.WIFI) {
                List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> wifiNetInterfaceConfigs = getWifiInterfaceConfigs();

                List<? extends NetInterfaceAddressConfig> wifiNetInterfaceAddressConfigs = getWifiNetInterfaceAddressConfigs(
                        interfaceName, wifiNetInterfaceConfigs);

                WifiInterfaceAddressConfig wifiInterfaceAddressConfig = getWifiAddressConfig(
                        wifiNetInterfaceAddressConfigs);

                wifiMode = wifiInterfaceAddressConfig.getMode();
                wifiInterfaceState = new WifiInterfaceState(interfaceName, wifiMode);

                for (NetConfig netConfig : wifiInterfaceAddressConfig.getConfigs()) {
                    if (netConfig instanceof NetConfigIP4) {
                        status = ((NetConfigIP4) netConfig).getStatus();
                        logger.debug("Interface status is set to {}", status);
                    } else if (netConfig instanceof WifiConfig && ((WifiConfig) netConfig).getMode() == wifiMode) {
                        wifiConfig = (WifiConfig) netConfig;
                    }
                }
            }

            if (!LinuxNetworkUtil.hasAddress(interfaceName)
                    || type == NetInterfaceType.WIFI && wifiInterfaceState != null && !wifiInterfaceState.isLinkUp()) {

                logger.info("bringing interface {} up", interfaceName);

                if (type == NetInterfaceType.WIFI) {
                    enableWifiInterface(interfaceName, status, wifiMode, wifiConfig);
                }
                if (dhcp) {
                    renewDhcpLease(interfaceName);
                } else {
                    LinuxNetworkUtil.enableInterface(interfaceName);
                }

                // if it isn't up - at least make sure the Ethernet controller is powered on
                if (!LinuxNetworkUtil.hasAddress(interfaceName)) {
                    LinuxNetworkUtil.bringUpDeletingAddress(interfaceName);
                }
            } else {
                logger.info("not bringing interface {} up because it is already up", interfaceName);
                if (dhcp) {
                    renewDhcpLease(interfaceName);
                }
            }
        } catch (Exception e) {
            throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
        }

    }

    private WifiInterfaceAddressConfig getWifiAddressConfig(
            List<? extends NetInterfaceAddressConfig> wifiNetInterfaceAddressConfigs) {
        for (NetInterfaceAddressConfig wifiNetInterfaceAddressConfig : wifiNetInterfaceAddressConfigs) {
            if (wifiNetInterfaceAddressConfig instanceof WifiInterfaceAddressConfig) {
                return (WifiInterfaceAddressConfig) wifiNetInterfaceAddressConfig;
            }
        }
        return null;
    }

    private List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> getWifiInterfaceConfigs()
            throws KuraException {
        List<? extends NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = getNetworkInterfaceConfigs();

        List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> wifiNetInterfaceConfigs = new ArrayList<>();
        for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
            if (netInterfaceConfig.getType() == NetInterfaceType.WIFI) {
                wifiNetInterfaceConfigs.add(netInterfaceConfig);
            }
        }

        return wifiNetInterfaceConfigs;
    }

    private List<? extends NetInterfaceAddressConfig> getWifiNetInterfaceAddressConfigs(String interfaceName,
            List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> wifiNetInterfaceConfigs) {
        List<? extends NetInterfaceAddressConfig> wifiNetInterfaceAddresses = null;

        for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> wifiNetInterfaceConfig : wifiNetInterfaceConfigs) {
            if (wifiNetInterfaceConfig.getName().equals(interfaceName)) {
                wifiNetInterfaceAddresses = wifiNetInterfaceConfig.getNetInterfaceAddresses();
                break;
            }
        }

        return wifiNetInterfaceAddresses;
    }

    @Override
    public void disableInterface(String interfaceName) throws KuraException {

        if (!"lo".equals(interfaceName)) {
            try {
                if (LinuxNetworkUtil.hasAddress(interfaceName)) {
                    logger.info("bringing interface {} down", interfaceName);
                    manageDhcpClient(interfaceName, false);
                    manageDhcpServer(interfaceName, false);

                    // FIXME: can we avoid getting the interface type again and ask for the caller to pass it in?
                    NetInterfaceType type = LinuxNetworkUtil.getType(interfaceName);
                    if (type == NetInterfaceType.WIFI) {
                        disableWifiInterface(interfaceName);
                    }

                    LinuxNetworkUtil.disableInterface(interfaceName);

                } else {
                    logger.info("not bringing interface {} down because it is already down", interfaceName);
                    manageDhcpClient(interfaceName, false);
                    manageDhcpServer(interfaceName, false);
                }
            } catch (Exception e) {
                throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
            }
        }
    }

    @Override
    public void manageDhcpClient(String interfaceName, boolean enable) throws KuraException {
        try {
            DhcpClientManager.disable(interfaceName);
            if (enable) {
                renewDhcpLease(interfaceName);
            }
        } catch (Exception e) {
            throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public void manageDhcpServer(String interfaceName, boolean enable) throws KuraException {
        DhcpServerManager.disable(interfaceName);
        if (enable) {
            DhcpServerManager.enable(interfaceName);
        }
    }

    @Override
    public void renewDhcpLease(String interfaceName) throws KuraException {
        DhcpClientManager.releaseCurrentLease(interfaceName);
        DhcpClientManager.enable(interfaceName);
    }

    @Override
    public void manageFirewall(String gatewayIface) throws KuraException {
        // get desired NAT rules interfaces
        LinkedHashSet<NATRule> desiredNatRules = null;
        ComponentConfiguration networkComponentConfiguration = ((SelfConfiguringComponent) this.networkConfigurationService)
                .getConfiguration();
        if (gatewayIface != null && networkComponentConfiguration != null) {
            try {
                NetworkConfiguration netConfiguration = new NetworkConfiguration(
                        networkComponentConfiguration.getConfigurationProperties());
                List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = netConfiguration
                        .getNetInterfaceConfigs();
                for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
                    String ifaceName = netInterfaceConfig.getName();
                    List<? extends NetInterfaceAddressConfig> netInterfaceAddressConfigs = netInterfaceConfig
                            .getNetInterfaceAddresses();
                    if (netInterfaceAddressConfigs != null && !netInterfaceAddressConfigs.isEmpty()) {
                        for (NetInterfaceAddressConfig netInterfaceAddressConfig : netInterfaceAddressConfigs) {
                            List<NetConfig> existingNetConfigs = netInterfaceAddressConfig.getConfigs();
                            if (existingNetConfigs != null && !existingNetConfigs.isEmpty()) {
                                for (NetConfig netConfig : existingNetConfigs) {
                                    if (netConfig instanceof FirewallAutoNatConfig) {
                                        if (desiredNatRules == null) {
                                            desiredNatRules = new LinkedHashSet<>();
                                        }
                                        desiredNatRules.add(new NATRule(ifaceName, gatewayIface, true));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (UnknownHostException e) {
                logger.warn("Exception while updating firewall configuration", e);
            }
        }

        LinuxFirewall firewall = LinuxFirewall.getInstance();
        if (desiredNatRules != null) {
            firewall.replaceAllNatRules(desiredNatRules);
        } else {
            firewall.deleteAllAutoNatRules();
        }

        firewall.enable();
    }

    @Override
    public List<NetConfig> getFirewallConfiguration() throws KuraException {
        logger.debug("getting the firewall configuration");
        return this.firewallConfigurationService.getFirewallConfiguration().getConfigs();
    }

    @Override
    public void setFirewallOpenPortConfiguration(
            List<FirewallOpenPortConfigIP<? extends IPAddress>> firewallConfiguration) throws KuraException {
        this.firewallConfigurationService.setFirewallOpenPortConfiguration(firewallConfiguration);
        submitFirewallConfiguration();
    }

    @Override
    public void setFirewallPortForwardingConfiguration(
            List<FirewallPortForwardConfigIP<? extends IPAddress>> firewallConfiguration) throws KuraException {
        this.firewallConfigurationService.setFirewallPortForwardingConfiguration(firewallConfiguration);
        submitFirewallConfiguration();
    }

    @Override
    public void setFirewallNatConfiguration(List<FirewallNatConfig> natConfigs) throws KuraException {
        this.firewallConfigurationService.setFirewallNatConfiguration(natConfigs);
        submitFirewallConfiguration();
    }

    @Override
    public Map<String, WifiHotspotInfo> getWifiHotspots(String ifaceName) throws KuraException {
        List<WifiHotspotInfo> wifiHotspotInfoList = getWifiHotspotList(ifaceName);
        return wifiHotspotInfoList.stream().collect(Collectors.toMap(WifiHotspotInfo::getSsid, item -> item));
    }

    @Override
    public List<WifiHotspotInfo> getWifiHotspotList(String ifaceName) throws KuraException {
        List<WifiHotspotInfo> wifiHotspotInfoList = new ArrayList<>();
        WifiMode wifiMode = getWifiMode(ifaceName);
        try {
            if (wifiMode == WifiMode.MASTER) {
                startTemporaryWpaSupplicant(ifaceName);
            }

            logger.info("getWifiHotspots() :: scanning for available access points ...");
            IScanTool scanTool = ScanTool.get(ifaceName);
            if (scanTool != null) {
                List<WifiAccessPoint> wifiAccessPoints = scanTool.scan();
                for (WifiAccessPoint wap : wifiAccessPoints) {

                    int frequency = (int) wap.getFrequency();
                    int channel = frequencyMhz2Channel(frequency);

                    if (wap.getSSID() == null || wap.getSSID().length() == 0
                            || isHotspotInList(channel, wap.getSSID(), wifiHotspotInfoList)) {
                        logger.debug("Skipping hidden SSID");
                        continue;
                    }

                    logger.trace("getWifiHotspots() :: SSID={}", wap.getSSID());
                    logger.trace("getWifiHotspots() :: Signal={}", wap.getStrength());
                    logger.trace("getWifiHotspots() :: Frequency={}", wap.getFrequency());

                    StringBuilder sbMacAddress = getMacAddress(wap.getHardwareAddress());
                    WifiSecurity wifiSecurity = getWifiSecurity(wap);
                    WifiHotspotInfo wifiHotspotInfo = new WifiHotspotInfo(wap.getSSID(), sbMacAddress.toString(),
                            0 - wap.getStrength(), channel, frequency, wifiSecurity);
                    setCiphers(wifiHotspotInfo, wap, wifiSecurity);
                    wifiHotspotInfoList.add(wifiHotspotInfo);
                }
            }

            if (wifiMode == WifiMode.MASTER) {
                stopTemporaryWpaSupplicant(ifaceName);
            }
        } catch (Throwable t) {
            throw new KuraException(KuraErrorCode.INTERNAL_ERROR, t, "scan operation has failed");
        }

        return wifiHotspotInfoList;
    }

    @Override
    public boolean verifyWifiCredentials(String ifaceName, WifiConfig wifiConfig, int tout) {

        boolean ret = false;
        boolean restartSupplicant = false;
        WpaSupplicantConfigWriter wpaSupplicantConfigWriter = WpaSupplicantConfigWriter.getInstance();
        try {
            wpaSupplicantConfigWriter.generateTempWpaSupplicantConf(wifiConfig, ifaceName);

            if (WpaSupplicantManager.isRunning(ifaceName)) {
                logger.debug("verifyWifiCredentials() :: stoping wpa_supplicant");
                WpaSupplicantManager.stop(ifaceName);
                restartSupplicant = true;
            }
            logger.debug("verifyWifiCredentials() :: Restarting temporary instance of wpa_supplicant");
            WpaSupplicantManager.startTemp(ifaceName, WifiMode.INFRA, wifiConfig.getDriver());
            wifiModeWait(ifaceName, WifiMode.INFRA, 10);
            ret = isWifiConnectionCompleted(ifaceName, tout);

            if (WpaSupplicantManager.isTempRunning()) {
                logger.debug("verifyWifiCredentials() :: stopping temporary instance of wpa_supplicant");
                WpaSupplicantManager.stop(ifaceName);
            }
        } catch (KuraException e) {
            logger.warn("Exception while managing the temporary instance of the Wpa supplicant.", e);
        }

        if (restartSupplicant) {
            try {
                logger.debug("verifyWifiCredentials() :: Restarting wpa_supplicant");
                WpaSupplicantManager.start(ifaceName, WifiMode.INFRA, wifiConfig.getDriver());
                if (isWifiConnectionCompleted(ifaceName, tout)) {
                    renewDhcpLease(ifaceName);
                }
            } catch (KuraException e) {
                logger.warn("Exception while trying to restart the Wpa supplicant.", e);
            }
        }

        return ret;
    }

    @Override
    @Deprecated
    public boolean rollbackDefaultConfiguration() throws KuraException {
        logger.debug("rollbackDefaultConfiguration() :: Recovering default configuration ...");

        ArrayList<NetworkRollbackItem> rollbackItems = new ArrayList<>();

        if (this.systemService == null) {
            return false;
        }

        String dstDataDirectory = this.systemService.getKuraDataDirectory();
        if (dstDataDirectory == null) {
            return false;
        }

        int ind = dstDataDirectory.lastIndexOf('/');
        String srcDataDirectory = null;
        if (ind >= 0) {
            srcDataDirectory = "".concat(dstDataDirectory.substring(0, ind + 1).concat(".data"));
        }

        if (srcDataDirectory == null) {
            return false;
        }

        rollbackItems
                .add(new NetworkRollbackItem(srcDataDirectory + "/kuranet.conf", dstDataDirectory + "/kuranet.conf"));

        if (OS_VERSION.equals(KuraConstants.Intel_Edison.getImageName() + "_"
                + KuraConstants.Intel_Edison.getImageVersion() + "_" + KuraConstants.Intel_Edison.getTargetName())) {
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/hostapd.conf", "/etc/hostapd/hostapd.conf"));
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/dhcpd-eth0.conf", "/etc/udhcpd-usb0.conf"));
            rollbackItems
                    .add(new NetworkRollbackItem(srcDataDirectory + "/dhcpd-wlan0.conf", "/etc/udhcpd-wlan0.conf"));
        } else {
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/hostapd.conf", "/etc/hostapd.conf"));
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/dhcpd-eth0.conf", "/etc/dhcpd-eth0.conf"));
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/dhcpd-wlan0.conf", "/etc/dhcpd-wlan0.conf"));
        }

        if (OS_VERSION
                .equals(KuraConstants.Mini_Gateway.getImageName() + "_" + KuraConstants.Mini_Gateway.getImageVersion())
                || OS_VERSION.equals(KuraConstants.Raspberry_Pi.getImageName())
                || OS_VERSION.equals(KuraConstants.BeagleBone.getImageName())
                || OS_VERSION.equals(
                        KuraConstants.Intel_Edison.getImageName() + "_" + KuraConstants.Intel_Edison.getImageVersion()
                                + "_" + KuraConstants.Intel_Edison.getTargetName())) {
            // restore Debian interface configuration
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/interfaces", "/etc/network/interfaces"));
        } else {
            // restore RedHat interface configuration
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/ifcfg-eth0",
                    "/etc/sysconfig/network-scripts/ifcfg-eth0"));
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/ifcfg-eth1",
                    "/etc/sysconfig/network-scripts/ifcfg-eth1"));
            rollbackItems.add(new NetworkRollbackItem(srcDataDirectory + "/ifcfg-wlan0",
                    "/etc/sysconfig/network-scripts/ifcfg-wlan0"));
        }

        for (NetworkRollbackItem rollbackItem : rollbackItems) {
            rollbackItem(rollbackItem);
        }

        logger.debug("rollbackDefaultConfiguration() :: setting network configuration ...");
        ComponentConfiguration networkComponentConfiguration = ((SelfConfiguringComponent) this.networkConfigurationService)
                .getConfiguration();
        if (networkComponentConfiguration != null) {
            try {
                NetworkConfiguration netConfiguration = new NetworkConfiguration(
                        networkComponentConfiguration.getConfigurationProperties());
                this.networkConfigurationService.setNetworkConfiguration(netConfiguration);
            } catch (UnknownHostException e) {
                logger.error("rollback to snapshot_0 has failed ", e);
            }
        }
        return true;
    }

    @Override
    @Deprecated
    public boolean rollbackDefaultFirewallConfiguration() throws KuraException {
        logger.debug("rollbackDefaultFirewallConfiguration() :: initializing firewall ...");
        if (this.systemService == null) {
            return false;
        }

        String dstDataDirectory = this.systemService.getKuraDataDirectory();
        if (dstDataDirectory == null) {
            return false;
        }

        int ind = dstDataDirectory.lastIndexOf('/');
        String srcDataDirectory = null;
        if (ind >= 0) {
            srcDataDirectory = "".concat(dstDataDirectory.substring(0, ind + 1).concat(".data"));
        }

        if (srcDataDirectory == null) {
            return false;
        }

        NetworkRollbackItem firewallRollbackItem = new NetworkRollbackItem(srcDataDirectory + "/iptables",
                "/etc/sysconfig/iptables");
        rollbackItem(firewallRollbackItem);
        LinuxFirewall.getInstance().initialize();
        LinuxFirewall.getInstance().enable();
        return true;
    }

    private void rollbackItem(NetworkRollbackItem rollbackItem) {
        File srcFile = new File(rollbackItem.src);
        File dstFile = new File(rollbackItem.dst);
        if (srcFile.exists()) {
            try {
                logger.debug("rollbackItem() :: copying {} to {} ...", srcFile, dstFile);
                copyFile(srcFile, dstFile);
            } catch (IOException e) {
                logger.error("rollbackItem() :: Failed to recover {} file ", dstFile, e);
            }
        }
    }

    private void copyFile(File sourceFile, File destFile) throws IOException {
        if (!destFile.exists()) {
            destFile.createNewFile();
        }

        FileInputStream sourceStream = null;
        FileOutputStream destinationStream = null;
        FileChannel source = null;
        FileChannel destination = null;

        try {
            sourceStream = new FileInputStream(sourceFile);
            source = sourceStream.getChannel();
            destinationStream = new FileOutputStream(destFile);
            destination = destinationStream.getChannel();
            destination.transferFrom(source, 0, source.size());
        } finally {
            if (source != null) {
                source.close();
            }
            if (destination != null) {
                destination.close();
            }
            if (sourceStream != null) {
                sourceStream.close();
            }
            if (destinationStream != null) {
                destinationStream.close();
            }
        }
    }

    @Override
    public void handleEvent(Event event) {
        logger.debug("handleEvent - topic: {}", event.getTopic());
        String topic = event.getTopic();
        if (topic.equals(NetworkConfigurationChangeEvent.NETWORK_EVENT_CONFIG_CHANGE_TOPIC)) {
            this.pendingNetworkConfigurationChange = false;
        } else if (topic.equals(FirewallConfigurationChangeEvent.FIREWALL_EVENT_CONFIG_CHANGE_TOPIC)) {
            this.pendingFirewallConfigurationChange = false;
        }
    }

    private boolean isWifiConnectionCompleted(String ifaceName, int tout) throws KuraException {

        boolean ret = false;
        long start = System.currentTimeMillis();
        do {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
            WpaSupplicantStatus wpaSupplicantStatus = new WpaSupplicantStatus(ifaceName);
            String wpaState = wpaSupplicantStatus.getWpaState();
            if (wpaState != null && "COMPLETED".equals(wpaState)) {
                ret = true;
                break;
            }
        } while (System.currentTimeMillis() - start < tout * 1000);

        return ret;
    }

    private void wifiModeWait(String ifaceName, WifiMode mode, int tout) {
        long startTimer = System.currentTimeMillis();
        do {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            try {
                if (LinuxNetworkUtil.getWifiMode(ifaceName) == mode) {
                    break;
                }
            } catch (KuraException e) {
                logger.error("wifiModeWait() :: Failed to obtain WiFi mode ", e);
            }
        } while (System.currentTimeMillis() - startTimer < 1000L * tout);
    }

    // ----------------------------------------------------------------
    //
    // Private Methods
    //
    // ----------------------------------------------------------------

    // TODO: simplify method signature. Probably we could take the mode from the wifiConfig.
    private void enableWifiInterface(String ifaceName, NetInterfaceStatus status, WifiMode wifiMode,
            WifiConfig wifiConfig) throws KuraException {

        // ignore mon.* interface
        // ignore redpine vlan interface
        if (ifaceName.startsWith("mon.") || ifaceName.startsWith("rpine")) {
            return;
        }

        logger.debug("Configuring {} for {} mode", ifaceName, wifiMode);

        logger.debug("Stopping hostapd and wpa_supplicant");
        HostapdManager.stop(ifaceName);
        WpaSupplicantManager.stop(ifaceName);

        if (status == NetInterfaceStatus.netIPv4StatusEnabledLAN && wifiMode.equals(WifiMode.MASTER)) {

            logger.debug("Starting hostapd");
            HostapdManager.start(ifaceName);

        } else if ((status == NetInterfaceStatus.netIPv4StatusEnabledLAN
                || status == NetInterfaceStatus.netIPv4StatusEnabledWAN)
                && (wifiMode.equals(WifiMode.INFRA) || wifiMode.equals(WifiMode.ADHOC))) {

            if (wifiConfig != null) {
                logger.debug("Starting wpa_supplicant");
                logger.warn("enableWifiInterface() :: Starting wpa_supplicant ... driver={}", wifiConfig.getDriver());
                WpaSupplicantManager.start(ifaceName, wifiMode, wifiConfig.getDriver());
                if (isWifiConnectionCompleted(ifaceName, 60)) {
                    logger.debug("WiFi Connection Completed on {} !", ifaceName);
                } else {
                    logger.warn("Failed to complete WiFi Connection on {}", ifaceName);
                }
            } else {
                logger.warn("No WifiConfig configured for mode " + wifiMode);
            }
        } else {
            logger.debug("Invalid wifi configuration - NetInterfaceStatus: {}, WifiMode:{}", status, wifiMode);
        }
    }

    private void disableWifiInterface(String ifaceName) throws KuraException {
        logger.debug("Stopping hostapd and wpa_supplicant");
        HostapdManager.stop(ifaceName);
        WpaSupplicantManager.stop(ifaceName);
    }

    // Submit new configuration, waiting for network configuration change event before returning
    private void submitNetworkConfiguration(List<String> modifiedInterfaceNames,
            NetworkConfiguration networkConfiguration) throws KuraException {
        short timeout = 30000;		// in milliseconds
        final short sleep = 500;

        this.pendingNetworkConfigurationChange = true;
        if (modifiedInterfaceNames != null && !modifiedInterfaceNames.isEmpty()) {
            networkConfiguration.setModifiedInterfaceNames(modifiedInterfaceNames);
            logger.debug("Set modified interface names: {}", modifiedInterfaceNames.toString());
        }
        this.networkConfigurationService.setNetworkConfiguration(networkConfiguration);
        this.configurationService.snapshot();

        while (this.pendingNetworkConfigurationChange && timeout > 0) {
            timeout -= sleep;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        if (this.pendingNetworkConfigurationChange) {
            logger.warn("Did not receive a network configuration change event");
            this.pendingNetworkConfigurationChange = false;
        }
    }

    private void submitFirewallConfiguration() throws KuraException {
        short timeout = 30000;		// in milliseconds
        final short sleep = 500;

        this.pendingFirewallConfigurationChange = true; // WTF: why this is set to true? the while and the if will be
        // always executed!

        this.configurationService.snapshot();

        while (this.pendingFirewallConfigurationChange && timeout > 0) {
            timeout -= sleep;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        if (this.pendingFirewallConfigurationChange) {
            logger.warn("Did not receive a firewall configuration change event");
            this.pendingFirewallConfigurationChange = false;
        }
    }

    private int frequencyMhz2Channel(int frequency) {
        return (frequency - 2407) / 5;
    }

    private void stopTemporaryWpaSupplicant(String ifaceName) throws KuraException {
        if (WpaSupplicantManager.isTempRunning()) {
            logger.debug("getWifiHotspots() :: stoping temporary instance of wpa_supplicant");
            WpaSupplicantManager.stop(ifaceName);
        }
        reloadKernelModule(ifaceName, WifiMode.MASTER);
    }

    private void startTemporaryWpaSupplicant(String ifaceName) throws KuraException {
        reloadKernelModule(ifaceName, WifiMode.INFRA);
        WpaSupplicantConfigWriter wpaSupplicantConfigWriter = WpaSupplicantConfigWriter.getInstance();
        wpaSupplicantConfigWriter.generateTempWpaSupplicantConf();

        logger.debug("getWifiHotspots() :: Starting temporary instance of wpa_supplicant");
        StringBuilder key = new StringBuilder("net.interface.").append(ifaceName).append(".config.wifi.infra.driver");
        String driver = KuranetConfig.getProperty(key.toString());
        WpaSupplicantManager.startTemp(ifaceName, WifiMode.INFRA, driver);
        wifiModeWait(ifaceName, WifiMode.INFRA, 10);
    }

    private StringBuilder getMacAddress(byte[] baMacAddress) {
        StringBuilder sbMacAddress = new StringBuilder();
        for (int i = 0; i < baMacAddress.length; i++) {
            sbMacAddress.append(String.format("%02x", baMacAddress[i] & 0x0ff).toUpperCase());
            if (i < baMacAddress.length - 1) {
                sbMacAddress.append(':');
            }
        }
        return sbMacAddress;
    }

    private void reloadKernelModule(String interfaceName, WifiMode wifiMode) throws KuraException {
        logger.info("monitor() :: reload {} using kernel module for WiFi mode {}", interfaceName, wifiMode);
        LinuxNetworkUtil.unloadKernelModule(interfaceName);
        LinuxNetworkUtil.loadKernelModule(interfaceName, wifiMode);
    }

    private boolean isHotspotInList(int channel, String ssid, List<WifiHotspotInfo> wifiHotspotInfoList) {
        boolean found = false;
        for (WifiHotspotInfo whi : wifiHotspotInfoList) {
            if (ssid.equals(whi.getSsid()) && channel == whi.getChannel()) {
                found = true;
                break;
            }
        }
        return found;
    }

    private WifiMode getWifiMode(String ifaceName) throws KuraException {
        WifiMode wifiMode = WifiMode.UNKNOWN;
        List<? extends NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = getNetworkInterfaceConfigs();
        for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
            if (netInterfaceConfig.getName().equals(ifaceName)) {
                List<? extends NetInterfaceAddressConfig> netInterfaceAddresses = netInterfaceConfig
                        .getNetInterfaceAddresses();
                if (netInterfaceAddresses != null) {
                    wifiMode = getWifiMode(netInterfaceAddresses);
                }
                break;
            }
        }
        return wifiMode;
    }

    private WifiMode getWifiMode(List<? extends NetInterfaceAddressConfig> netInterfaceAddresses) {
        WifiMode wifiMode = WifiMode.UNKNOWN;
        for (NetInterfaceAddressConfig netInterfaceAddress : netInterfaceAddresses) {
            if (netInterfaceAddress instanceof WifiInterfaceAddressConfig) {
                wifiMode = ((WifiInterfaceAddressConfig) netInterfaceAddress).getMode();
                break;
            }
        }
        return wifiMode;
    }

    private WifiSecurity getWifiSecurity(WifiAccessPoint wap) {
        WifiSecurity wifiSecurity = WifiSecurity.NONE;

        EnumSet<WifiSecurity> esWpaSecurity = wap.getWpaSecurity();
        if (esWpaSecurity != null && !esWpaSecurity.isEmpty()) {
            wifiSecurity = WifiSecurity.SECURITY_WPA;

            Iterator<WifiSecurity> itWpaSecurity = esWpaSecurity.iterator();
            while (itWpaSecurity.hasNext()) {
                logger.trace("getWifiHotspots() :: WPA Security={}", itWpaSecurity.next());
            }
        }

        EnumSet<WifiSecurity> esRsnSecurity = wap.getRsnSecurity();
        if (esRsnSecurity != null && !esRsnSecurity.isEmpty()) {
            if (wifiSecurity == WifiSecurity.SECURITY_WPA) {
                wifiSecurity = WifiSecurity.SECURITY_WPA_WPA2;
            } else {
                wifiSecurity = WifiSecurity.SECURITY_WPA2;
            }
            Iterator<WifiSecurity> itRsnSecurity = esRsnSecurity.iterator();
            while (itRsnSecurity.hasNext()) {
                logger.trace("getWifiHotspots() :: RSN Security={}", itRsnSecurity.next());
            }
        }

        if (wifiSecurity == WifiSecurity.NONE) {
            List<String> capabilities = wap.getCapabilities();
            if (capabilities != null && !capabilities.isEmpty() && capabilities.contains("Privacy")) {
                wifiSecurity = WifiSecurity.SECURITY_WEP;
            }
        }

        return wifiSecurity;

    }

    private void setCiphers(WifiHotspotInfo wifiHotspotInfo, WifiAccessPoint wap, WifiSecurity wifiSecurity) {
        EnumSet<WifiSecurity> esSecurity = null;
        EnumSet<WifiSecurity> pairCiphers = EnumSet.noneOf(WifiSecurity.class);
        EnumSet<WifiSecurity> groupCiphers = EnumSet.noneOf(WifiSecurity.class);
        if (wifiSecurity == WifiSecurity.SECURITY_WPA_WPA2) {
            esSecurity = wap.getWpaSecurity();
            esSecurity.addAll(wap.getRsnSecurity());
        } else if (wifiSecurity == WifiSecurity.SECURITY_WPA) {
            esSecurity = wap.getWpaSecurity();
        } else if (wifiSecurity == WifiSecurity.SECURITY_WPA2) {
            esSecurity = wap.getRsnSecurity();
        }
        if (esSecurity != null) {
            getCiphers(esSecurity, pairCiphers, groupCiphers);
        }
        wifiHotspotInfo.setGroupCiphers(groupCiphers);
        wifiHotspotInfo.setPairCiphers(pairCiphers);
    }

    private void getCiphers(EnumSet<WifiSecurity> esSecurity, EnumSet<WifiSecurity> pairCiphers,
            EnumSet<WifiSecurity> groupCiphers) {
        Iterator<WifiSecurity> itRsnSecurity = esSecurity.iterator();
        while (itRsnSecurity.hasNext()) {
            WifiSecurity securityEntry = itRsnSecurity.next();
            if (securityEntry == WifiSecurity.PAIR_CCMP
                    || securityEntry == WifiSecurity.PAIR_TKIP && !pairCiphers.contains(securityEntry)) {
                pairCiphers.add(securityEntry);
            } else if (securityEntry == WifiSecurity.GROUP_CCMP
                    || securityEntry == WifiSecurity.GROUP_TKIP && !groupCiphers.contains(securityEntry)) {
                groupCiphers.add(securityEntry);
            }
        }
    }
}
