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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R). If not, see:
 * http://www.gnu.org/licenses/
 *
 * For more information contact:
 * OpenNMS(R) Licensing <license@opennms.org>
 * http://www.opennms.org/
 * http://www.opennms.com/
 *******************************************************************************/
package org.opennms.oce.engine.topology.model;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.opennms.oce.datasource.api.Alarm;
import org.opennms.oce.datasource.api.Severity;

import com.google.common.collect.Sets;

public class ModelObjectImpl {
    private final String type;
    private String subType;
    private final String id;
    private String friendlyName;
    private ModelObjectImpl parent;
    private Map<String, GroupImpl> children = new HashMap<>(0);
    private Map<String, GroupImpl> peers = new HashMap<>(0);
    private Map<String, GroupImpl> nephews = new HashMap<>(0);
    private Map<String, GroupImpl> uncles = new HashMap<>(0);
    private OperationalState operationalState = OperationalState.NORMAL;
    private ServiceState serviceState = ServiceState.IN;
    private final Map<String, Alarm> outstandingAlarmsById = new LinkedHashMap<>();

    public ModelObjectImpl(String type, String id) {
        this.type = Objects.requireNonNull(type);
        this.id = Objects.requireNonNull(id);
    }

    /**
     * non-null
     */
    public String getType() {
        return type;
    }

    /**
     * nullable
     */
    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }

    /**
     * non-null
     *
     * A globally unique id
     * 
     * @return uuid
     */
    
    public String getId() {
        return id;
    }

    /**
     * nullable
     */
    
    public String getFriendlyName() {
        return friendlyName;
    }

    public void setFriendlyName(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    
    public ModelObjectImpl getParent() {
        return parent;
    }

    public void setParent(ModelObjectImpl parent) {
        this.parent = parent;
        if (type != "model") {
            // Parent must be null for the Root of the Model
            ((ModelObjectImpl) parent).addChild(this);
        }
    }

    
    public Set<ModelObjectImpl> getChildren() {
        return children.values().stream().map(g -> g.getMembers()).flatMap(Set::stream).collect(Collectors.toSet());
    }

    
    public Set<ModelObjectImpl> getPeers() {
        return peers.values().stream().map(g -> g.getMembers()).flatMap(Set::stream).collect(Collectors.toSet());
    }

    
    public Set<ModelObjectImpl> getUncles() {
        return uncles.values().stream().map(g -> g.getMembers()).flatMap(Set::stream).collect(Collectors.toSet());
    }

    
    public Set<ModelObjectImpl> getNephews() {
        return nephews.values().stream().map(g -> g.getMembers()).flatMap(Set::stream).collect(Collectors.toSet());
    }

    public void addChild(ModelObjectImpl child) {
        addMember(child, children);
    }

    public void addPeer(ModelObjectImpl child) {
        addMember(child, peers);
    }

    public void addNephew(ModelObjectImpl child) {
        addMember(child, nephews);
    }

    public void addUncle(ModelObjectImpl child) {
        addMember(child, uncles);
    }

    private void addMember(ModelObjectImpl member, Map<String, GroupImpl> map) {
        ((GroupImpl) getGroup(map, member.getType())).addMember(member);
    }

    
    public GroupImpl getChildGroup(String objectType) {
        return children.get(objectType);
    }

    
    public GroupImpl getPeerGroup(String objectType) {
        return peers.get(objectType);
    }

    
    public GroupImpl getNephewGroup(String objectType) {
        return nephews.get(objectType);
    }

    
    public GroupImpl getUncleGroup(String objectType) {
        return uncles.get(objectType);
    }

    private GroupImpl getGroup(Map<String, GroupImpl> map, String type) {
        GroupImpl g = map.get(type);
        if (g == null) {
            g = new GroupImpl(this);
            map.put(type, g);
        }
        return g;
    }

    
    public String toString() {
        return "MO[" + type + "," + id + "]";
    }

    
    public OperationalState getOperationalState() {
        return operationalState;
    }

    
    public void setOperationalState(OperationalState state) {
        if (state == operationalState) {
            return; // Nothing to do
        }

        // Place the previous state into a local variable so we can pass it along with the current ModelObjectImpl
        OperationalState previous = operationalState;
        operationalState = state;
        propagateOperationalStateChange(previous);
    }

    
    public ServiceState getServiceState() {
        return serviceState;
    }

    
    public void setServiceState(ServiceState state) {
        ServiceState previous = serviceState;
        serviceState = state;
        propagateServiceStateChange(previous);
    }

    
    public void onAlarm(Alarm alarm) {
        if (alarm.isClear()) {
            outstandingAlarmsById.remove(alarm.getId());
        } else {
            outstandingAlarmsById.put(alarm.getId(), alarm);
        }

        final Optional<Severity> highestSeverity = outstandingAlarmsById.values().stream()
                .map(Alarm::getSeverity)
                .max(Comparator.comparing(Severity::getValue));
        final OperationalState effectiveOperationalState;
        if (!highestSeverity.isPresent() || highestSeverity.get().getValue() <= Severity.NORMAL.getValue()) {
            effectiveOperationalState = OperationalState.NORMAL;
        } else if (highestSeverity.get().getValue() >= Severity.MAJOR.getValue()) {
            effectiveOperationalState = OperationalState.SA;
        } else {
            effectiveOperationalState = OperationalState.NSA;
        }
        setOperationalState(effectiveOperationalState);
    }

    
    public Set<Alarm> getAlarms() {
        return Sets.newHashSet(outstandingAlarmsById.values());
    }

    private void propagateOperationalStateChange(OperationalState previous) {
        getAlarmGroups().stream().forEach(g -> updateOperationalState(g, previous));
    }

    private void propagateServiceStateChange(ServiceState previous) {
        getAlarmGroups().stream().forEach(g -> updateServiceState(g, previous));
    }

    // Update the Group OpStatus if this ModelObjectImpl has a group of that type
    private void updateOperationalState(GroupImpl group, OperationalState previous) {
        if (group == null) {
            return;
        }
        group.updateOperationalState(this, previous);
    }

    // Update the Group SvcStatus if this ModelObjectImpl has a group of that type
    private void updateServiceState(GroupImpl group, ServiceState previous) {
        if (group == null) {
            return;
        }
        group.updateServiceState(this, previous);
    }

    
    public Set<GroupImpl> getAlarmGroups() {
        Set<GroupImpl> groups = new HashSet<>();
        // Add the Parent's Child group
        groups.add(parent.getChildGroup(type));
        // Add the Peers' Peer groups
        groups.addAll(getContainingPeerGroups());
        // Add the Uncles' nephew groups
        groups.addAll(getContainingUncleGroups());
        return groups;
    }

    private Set<GroupImpl> getContainingUncleGroups() {
        return getUncles().stream().map(u -> u.getNephewGroup(type)).collect(Collectors.toSet());
    }

    private Set<GroupImpl> getContainingPeerGroups() {
        return getPeers().stream().map(u -> u.getPeerGroup(type)).collect(Collectors.toSet());
    }

}
