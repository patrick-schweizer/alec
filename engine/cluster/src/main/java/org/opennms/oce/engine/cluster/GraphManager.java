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

package org.opennms.oce.engine.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.opennms.oce.datasource.api.Alarm;
import org.opennms.oce.datasource.api.InventoryObject;
import org.opennms.oce.datasource.api.InventoryObjectPeerRef;
import org.opennms.oce.datasource.api.InventoryObjectRelativeRef;
import org.opennms.oce.datasource.api.ResourceKey;
import org.opennms.oce.features.graph.api.Edge;
import org.opennms.oce.features.graph.api.GraphProvider;
import org.opennms.oce.features.graph.api.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.uci.ics.jung.graph.Graph;
import edu.uci.ics.jung.graph.SparseMultigraph;
import edu.uci.ics.jung.graph.util.Graphs;

public class GraphManager implements GraphProvider {

    private static final Logger LOG = LoggerFactory.getLogger(GraphManager.class);

    private final AtomicLong vertexIdGenerator = new AtomicLong();
    private final AtomicLong edgeIdGenerator = new AtomicLong();

    private final Map<Long, CEVertex> idtoVertexMap = new HashMap<>();
    private final Map<ResourceKey, CEVertex> resourceKeyVertexMap = new HashMap<>();

    private final Graph<CEVertex, CEEdge> g = new SparseMultigraph<>();

    private final AtomicBoolean didGraphChange = new AtomicBoolean();

    private final Set<Long> disconnectedVertices = new HashSet<>();

    private Set<InventoryObject> deferredIos = new HashSet<>();

    public synchronized void addInventory(Collection<InventoryObject> inventory) {
        addInventory(inventory, false);
    }

    private synchronized void addInventory(Collection<InventoryObject> inventory, boolean isDeferred) {
        // Start off by adding vertices to the graph for any new object
        for (InventoryObject io : inventory) {
            final ResourceKey resourceKey = getResourceKeyFor(io);
            resourceKeyVertexMap.computeIfAbsent(resourceKey, (key) -> {
                LOG.debug("Adding vertex with resource key: {} for inventory object: {}", resourceKey, io);
                final CEVertex vertex = createVertexFor(io);
                g.addVertex(vertex);
                didGraphChange.set(true);
                idtoVertexMap.put(vertex.getNumericId(), vertex);
                return vertex;
            });
        }

        // Now handle the relationships
        boolean didMatchAllRelations = true;
        for (InventoryObject io : inventory) {
            final ResourceKey resourceKey = getResourceKeyFor(io);
            final CEVertex vertex = resourceKeyVertexMap.get(resourceKey);

            // Parent relationships
            final ResourceKey parentResourceKey = getResourceKeyForParent(io);
            if (parentResourceKey != null) {
                final CEVertex parentVertex = resourceKeyVertexMap.get(parentResourceKey);
                if (parentVertex == null) {
                    LOG.info("No existing vertex found for parent with resource key '{}'. Deferring edge association.", parentResourceKey);
                    didMatchAllRelations = false;
                    continue;
                }
                if (!g.isNeighbor(parentVertex, vertex)) {
                    LOG.debug("Adding edge between child: {} and parent: {}", vertex, parentVertex);
                    final CEEdge edge = new CEEdge(edgeIdGenerator.getAndIncrement());
                    g.addEdge(edge, parentVertex, vertex);
                    didGraphChange.set(true);
                }
            }

            // Peer relationships
            for (InventoryObjectPeerRef peerRef : io.getPeers()) {
                final ResourceKey peerResourceKey = getResourceKeyForPeer(peerRef);
                final CEVertex peerVertex = resourceKeyVertexMap.get(peerResourceKey);
                if (peerVertex == null) {
                    LOG.info("No existing vertex found for peer with resource key '{}'. Deferring edge association.", peerResourceKey);
                    didMatchAllRelations = false;
                    continue;
                }
                if (!g.isNeighbor(peerVertex, vertex)) {
                    LOG.debug("Adding edge between peers A: {} and Z: {}", peerVertex, vertex);
                    final CEEdge edge = new CEEdge(edgeIdGenerator.getAndIncrement(), peerRef);
                    g.addEdge(edge, peerVertex, vertex);
                    didGraphChange.set(true);
                }
            }

            // Relative relationships
            for (InventoryObjectRelativeRef relativeRef : io.getRelatives()) {
                final ResourceKey relativeResourceKey = getResourceKeyForPeer(relativeRef);
                final CEVertex relativeVertex = resourceKeyVertexMap.get(relativeResourceKey);
                if (relativeVertex == null) {
                    LOG.info("No existing vertex found for relative with resource key '{}'. Deferring edge association.", relativeResourceKey);
                    didMatchAllRelations = false;
                    continue;
                }
                if (!g.isNeighbor(relativeVertex, vertex)) {
                    LOG.debug("Adding edge between relatives A: {} and Z: {}", relativeVertex, vertex);
                    final CEEdge edge = new CEEdge(edgeIdGenerator.getAndIncrement(), relativeRef);
                    g.addEdge(edge, relativeVertex, vertex);
                    didGraphChange.set(true);
                }
            }

            if (!didMatchAllRelations && !isDeferred) {
                deferredIos.add(io);
            } else if (isDeferred && didMatchAllRelations) {
                deferredIos.remove(io);
            }
        }

        if (!isDeferred) {
            handleDeferredIos();
        }

        disconnectedVertices.clear();
        disconnectedVertices.addAll(g.getVertices().stream()
                .filter(v -> g.getNeighborCount(v) == 0)
                .map(CEVertex::getNumericId)
                .collect(Collectors.toList()));
    }

    private void handleDeferredIos() {
        if (deferredIos.size() < 1) {
            return;
        }
        addInventory(new LinkedList<>(deferredIos), true);
    }

    public synchronized void removeInventory(Collection<InventoryObject> inventory) {
        for (InventoryObject io : inventory) {
            final ResourceKey resourceKey = getResourceKeyFor(io);
            final CEVertex vertex = resourceKeyVertexMap.remove(resourceKey);
            if (vertex != null) {
                g.removeVertex(vertex);
                didGraphChange.set(true);
            }
            deferredIos.remove(io);
        }
    }

    public synchronized void addOrUpdateAlarms(List<Alarm> alarms) {
        for (Alarm alarm : alarms) {
            addOrUpdateAlarm(alarm);
        }
    }

    public synchronized void addOrUpdateAlarm(Alarm alarm) {
        if (alarm.getInventoryObjectType() == null || alarm.getInventoryObjectId() == null) {
            LOG.info("Alarm with id: {} is not associated with any resource. It will not be added to the graph.", alarm.getId());
            return;
        }
        final ResourceKey resourceKey = getResourceKeyFor(alarm);
        final CEVertex vertex = resourceKeyVertexMap.computeIfAbsent(resourceKey, (key) -> {
            LOG.info("No existing vertex was found with resource key: {} for alarm with id: {}. Creating a new vertex.", resourceKey, alarm.getId());
            final CEVertex v = new CEVertex(vertexIdGenerator.getAndIncrement(), resourceKey);
            g.addVertex(v);
            didGraphChange.set(true);
            idtoVertexMap.put(v.getNumericId(), v);
            handleDeferredIos();
            return v;
        });
        vertex.addOrUpdateAlarm(alarm);
    }

    @Override
    public <V> V withReadOnlyGraph(Function<Graph<? extends Vertex, ? extends Edge>, V> consumer) {
        final Graph<CEVertex, CEEdge> readOnlyGraph = Graphs.unmodifiableGraph(g);
        return consumer.apply(readOnlyGraph);
    }

    @Override
    public void withReadOnlyGraph(Consumer<Graph<? extends Vertex, ? extends Edge>> consumer) {
        final Graph<CEVertex, CEEdge> readOnlyGraph = Graphs.unmodifiableGraph(g);
        consumer.accept(readOnlyGraph);
    }

    public synchronized <V> V withGraph(Function<Graph<CEVertex, CEEdge>, V> consumer) {
        return consumer.apply(g);
    }

    public synchronized void withGraph(Consumer<Graph<CEVertex, CEEdge>> consumer) {
        consumer.accept(g);
    }

    public synchronized void withVertex(String type, String id, BiConsumer<Graph<CEVertex, CEEdge>, CEVertex> consumer) {
        consumer.accept(g, resourceKeyVertexMap.get(ResourceKey.key(type, id)));
    }

    protected Graph<CEVertex, CEEdge> getGraph() {
        return g;
    }

    protected CEVertex getVertexWithId(Long id) {
        return idtoVertexMap.get(id);
    }

    private CEVertex createVertexFor(InventoryObject io) {
        final ResourceKey resourceKey = getResourceKeyFor(io);
        return new CEVertex(vertexIdGenerator.getAndIncrement(), resourceKey, io);
    }

    private static ResourceKey getResourceKeyFor(InventoryObject io) {
        return ResourceKey.key(io.getType(), io.getId());
    }

    private static ResourceKey getResourceKeyFor(Alarm alarm) {
        return ResourceKey.key(alarm.getInventoryObjectType(), alarm.getInventoryObjectId());
    }

    private static ResourceKey getResourceKeyForParent(InventoryObject child) {
        if (child.getParentType() != null && child.getParentId() != null) {
            return ResourceKey.key(child.getParentType(), child.getParentId());
        }
        return null;
    }

    private static ResourceKey getResourceKeyForPeer(InventoryObjectPeerRef peerRef) {
        return ResourceKey.key(peerRef.getType(), peerRef.getId());
    }

    private static ResourceKey getResourceKeyForPeer(InventoryObjectRelativeRef relativeRef) {
        return ResourceKey.key(relativeRef.getType(), relativeRef.getId());
    }

    public boolean getDidGraphChangeAndReset() {
        return didGraphChange.getAndSet(false);
    }

    public Set<Long> getDisconnectedVertices() {
        return disconnectedVertices;
    }

    public int getNumDeferredObjects() {
        return deferredIos.size();
    }

}
