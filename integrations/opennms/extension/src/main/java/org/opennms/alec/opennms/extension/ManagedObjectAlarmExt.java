/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.alec.opennms.extension;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.opennms.alec.opennms.model.BgpPeerInstance;
import org.opennms.alec.opennms.model.ManagedObjectType;
import org.opennms.alec.opennms.model.SnmpInterfaceLinkInstance;
import org.opennms.alec.opennms.model.VpnTunnelInstance;
import org.opennms.integration.api.v1.alarms.AlarmPersisterExtension;
import org.opennms.integration.api.v1.dao.NodeDao;
import org.opennms.integration.api.v1.dao.SnmpInterfaceDao;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.model.DatabaseEvent;
import org.opennms.integration.api.v1.model.EventParameter;
import org.opennms.integration.api.v1.model.InMemoryEvent;
import org.opennms.integration.api.v1.model.Node;
import org.opennms.integration.api.v1.model.SnmpInterface;
import org.opennms.integration.api.v1.model.immutables.ImmutableAlarm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.smi.OID;

import com.google.gson.Gson;

public class ManagedObjectAlarmExt implements AlarmPersisterExtension {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedObjectAlarmExt.class);

    private static final Gson gson = new Gson();

    protected static final String ENT_PHYSICAL_INDEX_PARM_NAME = "entPhysicalIndex";
    protected static final String IFINDEX_PARM_NAME = "ifIndex";
    protected static final String IFDESCR_PARM_NAME = "ifDescr";
    protected static final String SNMPIFINDEX_PARM_NAME = "snmpifindex";

    protected static final String SNMP_POLLER_SOURCE = "snmppoller";
    protected static final String THRESHOLD_SOURCE = "threshd";

    protected static final String A_IFDESCR_PARM_NAME = "aIfDescr";
    protected static final String Z_IFDESCR_PARM_NAME = "zIfDescr";
    protected static final String Z_HOSTNAME_PARM_NAME = "zHostname";

    protected static final String BGP_PEER_PARM_NAME = "bgpPeer";
    protected static final String BGP_VRF_PARM_NAME = "bgpVrf";

    protected static final String VPN_PEER_LOCAL_ADDR_PARM_NAME = "peerLocalAddr";
    protected static final String VPN_PEER_REMOTE_ADDR_PARM_NAME = "peerRemoteAddr";
    protected static final String VPN_TUNNEL_ID_PARM_NAME = "tunnelId";

    protected static final String VRF_NAME_PARM_NAME = "vrfName";
    protected static final String VRF_NAME_OID_PARM_NAME = "vrfNameOid";

    protected static final String OSPF_ROUTER_ID_PARM_NAME = "ospfRouterId";

    protected static final String MPLS_TUNNEL_ID_PARM_NAME = "mplsTunnelId";
    protected static final String MPLS_LDP_ENTITY_ID_PARM_NAME = "mplsLdpEntityID";

    private final NodeDao nodeDao;
    private final SnmpInterfaceDao snmpInterfaceDao;

    public ManagedObjectAlarmExt(NodeDao nodeDao, SnmpInterfaceDao snmpInterfaceDao) {
        this.nodeDao = Objects.requireNonNull(nodeDao);
        this.snmpInterfaceDao = Objects.requireNonNull(snmpInterfaceDao);
    }

    @Override
    public Alarm afterAlarmCreated(Alarm alarm, InMemoryEvent inMemoryEvent, DatabaseEvent databaseEvent) {
        ManagedObjectType managedObjectType = null;
        // handle MO types specified in the alarm-data of the events
        if (alarm.getManagedObjectType() != null) {
            try {
                managedObjectType = ManagedObjectType.fromName(alarm.getManagedObjectType());
            } catch (NoSuchElementException nse) {
                LOG.warn("'{}' does not map to a known type for alarm with reduction key: {}", alarm.getManagedObjectType(), alarm.getReductionKey());
            }
        }

        // handle alarms generated by threshd
        if (managedObjectType == null && inMemoryEvent.getSource().toLowerCase().contains(THRESHOLD_SOURCE) &&
                !inMemoryEvent.getParametersByName(IFINDEX_PARM_NAME).isEmpty()) {
            // If this is an SNMP interface threshold alarm then we can tag it to the appropriate ifIndex
            managedObjectType = ManagedObjectType.SnmpInterface;
        }

        // handle alarms generated by snmppollerd
        if (managedObjectType == null && inMemoryEvent.getSource().toLowerCase().contains(SNMP_POLLER_SOURCE) &&
                !inMemoryEvent.getParametersByName(SNMPIFINDEX_PARM_NAME).isEmpty()) {
            // If this is an SNMP poller alarm then we can tag it to the appropriate ifIndex
            managedObjectType = ManagedObjectType.SnmpInterface;
        }

        // resolve the managed object based on the given type
        ManagedObject managedObject = null;
        if (managedObjectType != null) {
            managedObject = getManagedObjectFor(managedObjectType, alarm, inMemoryEvent);
        }

        if (managedObject == null && !alarm.getRelatedAlarms().isEmpty()) {
            managedObject = inheritManagedObjectFromRelatedAlarms(alarm.getRelatedAlarms());
        }

        if (managedObject == null) {
            // we we're not able to resolve the managed object - if the alarm is associated with a node,
            // then default to the node MO
            if (alarm.getNode() != null) {
                managedObject = new ManagedObject(ManagedObjectType.Node, toNodeCriteria(alarm.getNode()));
            }
        }

        if (managedObject != null) {
            LOG.info("Tagged alarm with reduction key: {} and MO type: {} and MO instance: {}",
                    alarm.getReductionKey(), managedObject.getType(), managedObject.getInstance());
        } else {
            LOG.warn("No tagging was performed on alarm with reduction key: {}", alarm.getReductionKey());
            return null;
        }

        return ImmutableAlarm.newBuilderFrom(alarm)
                .setManagedObjectInstance(managedObject.getInstance())
                .setManagedObjectType(managedObject.getType().getName())
                .build();
    }

    @Override
    public Alarm afterAlarmUpdated(Alarm alarm, InMemoryEvent inMemoryEvent, DatabaseEvent databaseEvent) {
        // noop
        return null;
    }

    private static ManagedObject inheritManagedObjectFromRelatedAlarms(List<Alarm> relatedAlarms) {
        return relatedAlarms.stream().sorted(Comparator.comparing(Alarm::getId))
                .filter(a -> {
                    // Ensure we have some MO set
                    if (a.getManagedObjectType() == null || a.getManagedObjectInstance() == null) {
                        return false;
                    }

                    // Validate that the MO maps to a known type
                    try {
                        ManagedObjectType.fromName(a.getManagedObjectType());
                        return true;
                    } catch (NoSuchElementException nse) {
                        LOG.warn("'{}' does not map to a known type for alarm with reduction key: {}", a.getManagedObjectType(), a.getReductionKey());
                    }
                    return false;
                })
                .map(a -> new ManagedObject(ManagedObjectType.fromName(a.getManagedObjectType()), a.getManagedObjectInstance()))
                .findFirst()
                .orElse(null);
    }

    protected ManagedObject getManagedObjectFor(ManagedObjectType managedObjectType, Alarm alarm, InMemoryEvent event) {
        switch(managedObjectType) {
            case Node:
                return new ManagedObject(managedObjectType, toNodeCriteria(alarm.getNode()));
            case EntPhysicalEntity:
                final Integer entPhysicalIndex = getIntValueForParamNamed(ENT_PHYSICAL_INDEX_PARM_NAME, event);
                if (entPhysicalIndex != null) {
                    return new ManagedObject(managedObjectType, Integer.toString(entPhysicalIndex));
                } else {
                    LOG.warn("Could not determine entPhysicalIndex for {} on {}.", managedObjectType, alarm);
                }
                break;
            case SnmpInterface:
                Integer ifIndex = getIntValueForParamNamed(IFINDEX_PARM_NAME, event);
                if (ifIndex == null) {
                    ifIndex = getIntValueForParamNamed(SNMPIFINDEX_PARM_NAME, event);
                }
                if (ifIndex == null) {
                    final String ifDescr = getStringValueForParamNamed(IFDESCR_PARM_NAME, event);
                    ifIndex = getIfIndexFromIfDescr(alarm.getNode(), ifDescr);
                }
                if (ifIndex != null) {
                    return new ManagedObject(managedObjectType, Integer.toString(ifIndex));
                } else {
                    LOG.warn("Could not determine ifIndex for {} on {}.", managedObjectType, alarm);
                }
                break;
            case SnmpInterfaceLink:
                return handleSnmpInterfaceLink(alarm, event);
            case BgpPeer:
                return handleBgpPeer(alarm, event);
            case VpnTunnel:
                return handleVpnTunnel(alarm, event);
            case MplsL3Vrf:
                return handleMplsL3Vrf(alarm, event);
            case OspfRouter:
                final String ospfRouterId = getStringValueForParamNamed(OSPF_ROUTER_ID_PARM_NAME, event);
                if (ospfRouterId != null) {
                    return new ManagedObject(managedObjectType, ospfRouterId);
                } else {
                    LOG.warn("Could not determine ospfRouterId for {} on {}.", managedObjectType, alarm);
                }
                break;
            case MplsLdpSession:
                final String mplsLdpEntityId = getStringValueForParamNamed(MPLS_LDP_ENTITY_ID_PARM_NAME, event);
                if (mplsLdpEntityId != null) {
                    return new ManagedObject(managedObjectType, mplsLdpEntityId);
                } else {
                    LOG.warn("Could not determine mplsLdpEntityId for {} on {}.", managedObjectType, alarm);
                }
                break;
            case MplsTunnel:
                final String mplsTunnelId = getStringValueForParamNamed(MPLS_TUNNEL_ID_PARM_NAME, event);
                if (mplsTunnelId != null) {
                    return new ManagedObject(managedObjectType, mplsTunnelId);
                } else {
                    LOG.warn("Could not determine mplsTunnelId for {} on {}.", managedObjectType, alarm);
                }
                break;
            default:
                LOG.warn("Unsupported object type: {}", managedObjectType);
        }
        return null;
    }

    private ManagedObject handleMplsL3Vrf(Alarm alarm, InMemoryEvent event) {
        String vrfName = null;
        final String vrfNameOid = getStringValueForParamNamed(VRF_NAME_OID_PARM_NAME, event);
        if (vrfNameOid != null) {
            vrfName = oidToDisplayString(vrfNameOid);
            LOG.debug("Converted {} to '{}'.", vrfNameOid, vrfName);
        }

        if (vrfName ==  null) {
            vrfName = getStringValueForParamNamed(VRF_NAME_PARM_NAME, event);
        }

        if (vrfName ==  null) {
            LOG.info("No VRF name found for event: {}", event);
            return null;
        }

        return new ManagedObject(ManagedObjectType.MplsL3Vrf, vrfName);
    }

    private ManagedObject handleVpnTunnel(Alarm alarm, InMemoryEvent event) {
        final String peerLocalAddr = getStringValueForParamNamed(VPN_PEER_LOCAL_ADDR_PARM_NAME, event);
        if (peerLocalAddr == null) {
            LOG.info("No peer local address found for event: {}", event);
        }
        final String peerRemoteAddr = getStringValueForParamNamed(VPN_PEER_REMOTE_ADDR_PARM_NAME, event);
        if (peerRemoteAddr == null) {
            LOG.info("No peer remote address found for event: {}", event);
        }
        final String tunnelId = getStringValueForParamNamed(VPN_TUNNEL_ID_PARM_NAME, event);
        if (peerLocalAddr == null) {
            LOG.info("No tunnel id found for event: {}", event);
        }

        final VpnTunnelInstance vpnTunnelInstance = new VpnTunnelInstance(peerLocalAddr, peerRemoteAddr, tunnelId);
        return new ManagedObject(ManagedObjectType.VpnTunnel, gson.toJson(vpnTunnelInstance));
    }

    private ManagedObject handleBgpPeer(Alarm alarm, InMemoryEvent event) {
        final String bgpPeer = getStringValueForParamNamed(BGP_PEER_PARM_NAME, event);
        if (bgpPeer == null) {
            LOG.info("No BGP peer found for event: {}", event);
            return null;
        }
        final String bgpVrf = getStringValueForParamNamed(BGP_VRF_PARM_NAME, event);

        final BgpPeerInstance bgpPeerInstance = new BgpPeerInstance(bgpPeer, bgpVrf);
        return new ManagedObject(ManagedObjectType.BgpPeer, gson.toJson(bgpPeerInstance));
    }

    private ManagedObject handleSnmpInterfaceLink(Alarm alarm, InMemoryEvent event) {
        final Node aNode = alarm.getNode();
        final String aIfDescr = getStringValueForParamNamed(A_IFDESCR_PARM_NAME, event);
        final Integer aIfIndex = getIfIndexFromIfDescr(aNode, aIfDescr);
        if (aIfIndex == null) {
            LOG.info("No ifIndex found for interface with description '{}' on node {}.", aIfDescr, aNode);
            return null;
        }

        final String zHostname = getStringValueForParamNamed(Z_HOSTNAME_PARM_NAME, event);
        final Node zNode = getNodeFromHostname(zHostname);
        if (zNode == null) {
            LOG.info("No node found with hostname '{}' for Z side of link. Associating alarm with interface on A side of link.", zHostname);
            return new ManagedObject(ManagedObjectType.SnmpInterface, Integer.toString(aIfIndex));
        }
        final String zIfDescr = getStringValueForParamNamed(Z_IFDESCR_PARM_NAME, event);
        final Integer zIfIndex = getIfIndexFromIfDescr(zNode, zIfDescr);
        if (zIfIndex == null) {
            LOG.info("No ifIndex found for interface with description '{}' on node {} for Z side of link. Associating alarm with interface on A side of link.", zIfDescr, zNode);
            return new ManagedObject(ManagedObjectType.SnmpInterface, Integer.toString(aIfIndex));
        }

        final String aNodeCriteria = toNodeCriteria(aNode);
        final String zNodeCriteria = toNodeCriteria(zNode);
        final SnmpInterfaceLinkInstance snmpInterfaceLinkInstance = new SnmpInterfaceLinkInstance(aNodeCriteria, aIfIndex, zNodeCriteria, zIfIndex);
        return new ManagedObject(ManagedObjectType.SnmpInterfaceLink, gson.toJson(snmpInterfaceLinkInstance));
    }

    private Integer getIfIndexFromIfDescr(Node node, String ifDescr) {
        if (node == null || ifDescr == null) {
            return null;
        }

        final SnmpInterface snmpInterface = snmpInterfaceDao.findByNodeIdAndDescrOrName(node.getId(), ifDescr);
        if (snmpInterface == null) {
            return null;
        }

        return snmpInterface.getIfIndex();
    }

    private Node getNodeFromHostname(String hostname) {
        return nodeDao.getNodeByLabel(hostname);
    }

    private static String toNodeCriteria(Node node) {
        if (node == null) {
            return null;
        }
        if (node.getForeignSource() != null && node.getForeignId() != null) {
            return node.getForeignSource() + ":" + node.getForeignId();
        }
        return node.getId() != null ? node.getId().toString() : null;
    }

    private static String getStringValueForParamNamed(String paramName, InMemoryEvent e) {
        final EventParameter parm = e.getParametersByName(paramName).stream()
                .findFirst()
                .orElse(null);
        if (parm == null) {
            return null;
        }
        return parm.getValue();
    }

    private static Integer getIntValueForParamNamed(String paramName, InMemoryEvent e) {
        final String stringValue = getStringValueForParamNamed(paramName, e);
        if (stringValue == null) {
            return null;
        }
        try {
            return Integer.parseInt(stringValue);
        } catch (NumberFormatException nfe) {
            LOG.warn("Unable to convert parameter value to an integer: {}", stringValue);
            return null;
        }
    }

    private static String oidToDisplayString(String oidStr) {
        final StringBuilder sb = new StringBuilder();
        final OID oid = new OID(oidStr);
        for (int el : oid.toIntArray()) {
            sb.append((char)el);
        }
        return sb.toString();
    }

    protected static final class ManagedObject {
        private final ManagedObjectType type;
        private final String instance;

        public ManagedObject(ManagedObjectType type, String instance) {
            this.type = Objects.requireNonNull(type);
            this.instance = Objects.requireNonNull(instance);
        }

        public ManagedObjectType getType() {
            return type;
        }

        public String getInstance() {
            return instance;
        }

    }

}
