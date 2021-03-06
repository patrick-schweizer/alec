/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
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

package org.opennms.alec.datasource.opennms

import com.google.common.base.Strings
import groovy.util.logging.Slf4j
import org.opennms.alec.datasource.common.inventory.Edge
import org.opennms.alec.datasource.common.inventory.ManagedObjectType
import org.opennms.alec.datasource.common.inventory.Port
import org.opennms.alec.datasource.common.inventory.Segment
import org.opennms.alec.datasource.common.inventory.TypeToInventory
import org.opennms.alec.datasource.opennms.EnrichedAlarm
import org.opennms.alec.datasource.opennms.InventoryFromAlarm
import org.opennms.alec.datasource.opennms.OpennmsMapper
import org.opennms.alec.datasource.opennms.proto.InventoryModelProtos
import org.opennms.alec.datasource.opennms.proto.InventoryModelProtos.InventoryObject
import org.opennms.alec.datasource.opennms.proto.InventoryModelProtos.InventoryObjects
import org.opennms.alec.datasource.opennms.proto.OpennmsModelProtos
import org.opennms.alec.datasource.opennms.proto.OpennmsModelProtos.Node
import org.opennms.alec.datasource.opennms.proto.OpennmsModelProtos.TopologyEdge

@Slf4j
class InventoryFactory {
    private static final long PORT_LINK_WEIGHT = 100;
    // Use half the regular link weight when there is a segment since there will be twice as many hops
    private static final long SEGMENT_LINK_WEIGHT = 50;

    static InventoryObjects edgeToInventory(TopologyEdge edge) {
        log.trace("EdgeToInventory - edge: {}", edge);
        final InventoryObjects.Builder iosBuilder = InventoryObjects.newBuilder();
        final InventoryObject.Builder edgeIoBuilder = InventoryObject.newBuilder();
        
        // Set the type of the link
        long weightForLink = PORT_LINK_WEIGHT;
        if(edge.hasSourceNode() && edge.hasTargetNode()) {
            edgeIoBuilder.setType(ManagedObjectType.NodeLink.getName());
        } else if (edge.hasSourceSegment() || edge.hasTargetSegment()) {
            weightForLink = SEGMENT_LINK_WEIGHT;
            edgeIoBuilder.setType(ManagedObjectType.BridgeLink.getName());
        } else {
            edgeIoBuilder.setType(ManagedObjectType.SnmpInterfaceLink.getName());
        }

        // Derive segments if applicable
        if (edge.hasSourceSegment()) {
            iosBuilder.addInventoryObject(InventoryObject.newBuilder()
                    .setType(ManagedObjectType.BridgeSegment.getName())
                    .setId(Segment.generateId(edge.getSourceSegment().getRef().getId(),
                    edge.getSourceSegment().getRef().getProtocol().name()))
                    .build());
        }
        if (edge.hasTargetSegment()) {
            iosBuilder.addInventoryObject(InventoryObject.newBuilder()
                    .setType(ManagedObjectType.BridgeSegment.getName())
                    .setId(Segment.generateId(edge.getTargetSegment().getRef().getId(),
                    edge.getTargetSegment().getRef().getProtocol().name()))
                    .build());
        }

        InventoryModelProtos.InventoryObjectPeerRef peerA = null;
        InventoryModelProtos.InventoryObjectPeerRef peerZ = null;
        
        // Add the peers
        if (edge.hasSourcePort()) {
            peerA = InventoryModelProtos.InventoryObjectPeerRef.newBuilder()
                    .setEndpoint(InventoryModelProtos.InventoryObjectPeerEndpoint.A)
                    .setWeight(weightForLink)
                    .setId(Port.generateId(edge.getSourcePort().getIfIndex(),
                    OpennmsMapper.toNodeCriteria(edge.getSourcePort().getNodeCriteria())))
                    .setType(ManagedObjectType.SnmpInterface.getName())
                    .build();
        } else if (edge.hasSourceNode()) {
            peerA = InventoryModelProtos.InventoryObjectPeerRef.newBuilder()
                    .setEndpoint(InventoryModelProtos.InventoryObjectPeerEndpoint.A)
                    .setWeight(weightForLink)
                    .setId(OpennmsMapper.toNodeCriteria(edge.getSourceNode()))
                    .setType(ManagedObjectType.Node.getName())
                    .build();
        } else if (edge.hasSourceSegment()) {
            peerA = InventoryModelProtos.InventoryObjectPeerRef.newBuilder()
                    .setEndpoint(InventoryModelProtos.InventoryObjectPeerEndpoint.A)
                    .setWeight(weightForLink)
                    .setId(Segment.generateId(edge.getSourceSegment()
                    .getRef()
                    .getId(),
                    edge.getSourceSegment()
                            .getRef()
                            .getProtocol()
                            .name()))
                    .setType(ManagedObjectType.BridgeSegment.getName())
                    .build();
        }

        if (edge.hasTargetPort()) {
            peerZ = InventoryModelProtos.InventoryObjectPeerRef.newBuilder()
                    .setEndpoint(InventoryModelProtos.InventoryObjectPeerEndpoint.Z)
                    .setWeight(weightForLink)
                    .setId(Port.generateId(edge.getTargetPort().getIfIndex(),
                    OpennmsMapper.toNodeCriteria(edge.getTargetPort().getNodeCriteria())))
                    .setType(ManagedObjectType.SnmpInterface.getName())
                    .build();
        } else if (edge.hasTargetNode()) {
            peerZ = InventoryModelProtos.InventoryObjectPeerRef.newBuilder()
                    .setEndpoint(InventoryModelProtos.InventoryObjectPeerEndpoint.Z)
                    .setWeight(weightForLink)
                    .setId(OpennmsMapper.toNodeCriteria(edge.getTargetNode()))
                    .setType(ManagedObjectType.Node.getName())
                    .build();
        } else if (edge.hasTargetSegment()) {
            peerZ = InventoryModelProtos.InventoryObjectPeerRef.newBuilder()
                    .setEndpoint(InventoryModelProtos.InventoryObjectPeerEndpoint.Z)
                    .setWeight(weightForLink)
                    .setId(Segment.generateId(edge.getTargetSegment()
                    .getRef()
                    .getId(),
                    edge.getTargetSegment()
                            .getRef()
                            .getProtocol()
                            .name()))
                    .setType(ManagedObjectType.BridgeSegment.getName())
                    .build();
        }
        
        edgeIoBuilder.addPeer(Objects.requireNonNull(peerA));
        edgeIoBuilder.addPeer(Objects.requireNonNull(peerZ));
        
        // Set Id and friendly name
        edgeIoBuilder.setId(Edge.generateId(edge.getRef().getProtocol().name(), peerA.getId(), peerZ.getId()));
        edgeIoBuilder.setFriendlyName(Edge.generateFriendlyName(edge.getRef().getProtocol().name(), peerA.getId(),
                peerZ.getId()));

        iosBuilder.addInventoryObject(edgeIoBuilder.build());

        return iosBuilder.build();
    }

    static EnrichedAlarm enrichAlarm(OpennmsModelProtos.Alarm alarm) {
        if (alarm == null) {
            return null;
        }

        String managedObjectInstance = null;
        String managedObjectType = null;

        final InventoryObjects.Builder iosBuilder = InventoryObjects.newBuilder();
        final InventoryObjects ios;
        if (!Strings.isNullOrEmpty(alarm.getManagedObjectType()) &&
                !Strings.isNullOrEmpty(alarm.getManagedObjectInstance())) {
            final InventoryFromAlarm inventoryFromAlarm = getInventoryFromAlarm(alarm);
            for (InventoryObject io : inventoryFromAlarm.getInventory()) {
                iosBuilder.addInventoryObject(io);
            }
            ios = iosBuilder.build();
            if (inventoryFromAlarm.getManagedObjectInstance() != null && inventoryFromAlarm.getManagedObjectType() != null) {
                managedObjectInstance = inventoryFromAlarm.getManagedObjectInstance();
                managedObjectType = inventoryFromAlarm.getManagedObjectType();
            } else if (ios.getInventoryObjectCount() > 0) {
                final InventoryObject io = ios.getInventoryObject(0);
                managedObjectInstance = io.getId();
                managedObjectType = io.getType();
            }
        } else {
            ios = iosBuilder.build();
        }

        if ((managedObjectInstance == null || managedObjectType == null) && alarm.hasNodeCriteria()) {
            final String nodeCriteria = OpennmsMapper.toNodeCriteria(alarm.getNodeCriteria());
            managedObjectType = ManagedObjectType.Node.getName();
            managedObjectInstance = nodeCriteria;
        }

        return new EnrichedAlarm(alarm, ios, managedObjectType, managedObjectInstance);
    }

    static InventoryFromAlarm getInventoryFromAlarm(OpennmsModelProtos.Alarm alarm) {
        final List<InventoryObject> ios = new ArrayList<>();
        final ManagedObjectType type;
        try {
            type = ManagedObjectType.fromName(alarm.getManagedObjectType());
        } catch (NoSuchElementException nse) {
            log.warn("Found unsupported type: {} with id: {}. Skipping.", alarm.getManagedObjectType(), alarm.getManagedObjectInstance());
            return new InventoryFromAlarm(ios);
        }

        final String nodeCriteria = OpennmsMapper.toNodeCriteria(alarm.getNodeCriteria());
        String managedObjectInstance = null;
        String managedObjectType = null;
        switch (type) {
            case Node:
                // Nothing to do here
                break;
            case ManagedObjectType.SnmpInterfaceLink:
                ios.add(OpennmsMapper.fromInventory(TypeToInventory.getSnmpInterfaceLink(alarm.getManagedObjectInstance())));
                break;
            case ManagedObjectType.EntPhysicalEntity:
                ios.add(OpennmsMapper.fromInventory(TypeToInventory.getEntPhysicalEntity(alarm.getManagedObjectInstance(), nodeCriteria)));
                break;
            case ManagedObjectType.BgpPeer:
                ios.add(OpennmsMapper.fromInventory(TypeToInventory.getBgpPeer(alarm.getManagedObjectInstance(), nodeCriteria)));
                break;
            case ManagedObjectType.VpnTunnel:
                ios.add(OpennmsMapper.fromInventory(TypeToInventory.getVpnTunnel(alarm.getManagedObjectInstance(), nodeCriteria)));
                break;
            default:
                managedObjectType = type.getName();
                // Scope the object id by node
                managedObjectInstance = String.format("%s:%s", nodeCriteria, alarm.getManagedObjectInstance());
        }
        return new InventoryFromAlarm(ios, managedObjectInstance, managedObjectType);
    }

    static InventoryObject toInventoryObject(OpennmsModelProtos.SnmpInterface snmpInterface, InventoryObject parent) {
        log.trace("toInventoryObject: {} : {}", snmpInterface, parent);
        return InventoryObject.newBuilder()
                .setType(ManagedObjectType.SnmpInterface.getName())
                .setId(Port.generateId((long) snmpInterface.getIfIndex(), parent.getId()))
                .setFriendlyName(snmpInterface.getIfDescr())
                .setParentType(parent.getType())
                .setParentId(parent.getId())
                .build();
    }

    static List<InventoryObject> toInventoryObjects(Node node) {
        log.trace("Node toInventoryObject: {}", node);
        final List<InventoryObject> inventory = new ArrayList<>();

        InventoryObject nodeObj = InventoryObject.newBuilder()
                .setType(ManagedObjectType.Node.getName())
                .setId(OpennmsMapper.toNodeCriteria(node))
                .setFriendlyName(node.getLabel())
                .build();
        inventory.add(nodeObj);

        // Attach the SNMP interfaces directly to the node
        node.getSnmpInterfaceList().stream()
                .map { iff -> toInventoryObject(iff, nodeObj) }
                .forEach { i -> inventory.add(i) };

        return inventory;
    }

}

def InventoryObjects edgeToInventory(TopologyEdge edge) {
    InventoryFactory.edgeToInventory(edge);
}

def EnrichedAlarm enrichAlarm(OpennmsModelProtos.Alarm alarm) {
    InventoryFactory.enrichAlarm(alarm);
}

def List<InventoryObject> toInventoryObjects(Node node) {
    InventoryFactory.toInventoryObjects(node);
}


