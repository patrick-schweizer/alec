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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.DBSCANClusterer;
import org.opennms.oce.datasource.api.Alarm;
import org.opennms.oce.datasource.api.Incident;
import org.opennms.oce.datasource.api.InventoryObject;
import org.opennms.oce.datasource.common.IncidentBean;
import org.opennms.oce.engine.api.Engine;
import org.opennms.oce.engine.api.IncidentHandler;
import org.opennms.oce.features.graph.api.Edge;
import org.opennms.oce.features.graph.api.GraphProvider;
import org.opennms.oce.features.graph.api.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath;
import edu.uci.ics.jung.graph.Graph;

/**
 * Clustering based correlation
 * Hypothesis: We can group alarms into incidents by an existing clustering algorithm (i.e. DBSCAN)
 * in conjunction with a distance metric that takes in account both space and time (i.e. spatio-temporal clustering).
 *
 * For measuring distance in time, we can use a metric which grows exponentially as time passes,
 * giving distances which are order of magnitudes smaller for events that are close in time.
 *
 * For measuring distances in space between alarms, we can map the alarms onto a network topology graph and
 * use a standard graph metric which measures the number of hops in the shortest path between the two vertices.
 *
 * Assume a_i and a_k are some alarms we can define:
 *
 *   d(a_i,a_k) = A(e^|a_i_t - a_k_t| - 1) + B(dg(a_i,a_k) ^2)
 *
 * where a_i_t is the time at which a_i was last observed
 * where dg(a_i,a_k) is the number of hops in the shortest path of the network graph
 * where A and B are some constants (need to be tweaked based on how important we want to make space vs time)
 */
public class ClusterEngine implements Engine, GraphProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterEngine.class);

    public static final double DEFAULT_EPSILON = 100d;
    public static final double DEFAULT_ALPHA = 781.78317629d;
    public static final double DEFAULT_BETA = 0.53244801d;

    private static final int NUM_VERTEX_THRESHOLD_FOR_HOP_DIAG = 10;

    /**
     * A special incident id that can be used to key alarms in a map that do not belong to an incident.
     * This value must be non-null, and must never collide with a valid incident id generated by this class.
     */
    private static final String EMPTY_INCIDENT_ID = "";

    private final AlarmInSpaceTimeDistanceMeasure distanceMeasure;

    private final Map<String, IncidentBean> alarmIdToIncidentMap = new HashMap<>();
    private final Map<String, IncidentBean> incidentsById = new HashMap<>();

    private long tickResolutionMs = TimeUnit.SECONDS.toMillis(30);

    private IncidentHandler incidentHandler;

    private long lastRun = 0;

    private final double epsilon;

    private long problemTimeoutMs = TimeUnit.HOURS.toMillis(2);
    private long clearTimeoutMs = TimeUnit.MINUTES.toMillis(5);

    private boolean alarmsChangedSinceLastTick = false;
    private DijkstraShortestPath<CEVertex, CEEdge> shortestPath;
    private Set<Long> disconnectedVertices = new HashSet<>();

    private final GraphManager graphManager = new GraphManager();

    public ClusterEngine() {
        this(DEFAULT_EPSILON, DEFAULT_ALPHA, DEFAULT_BETA);
    }

    public ClusterEngine(double epsilon, double alpha, double beta) {
        this.epsilon = epsilon;
        distanceMeasure = new AlarmInSpaceTimeDistanceMeasure(this, alpha, beta);
    }

    @Override
    public void registerIncidentHandler(IncidentHandler handler) {
        this.incidentHandler = handler;
    }


    @Override
    public long getTickResolutionMs() {
        return tickResolutionMs;
    }


    public void setTickResolutionMs(long tickResolutionMs) {
        this.tickResolutionMs = tickResolutionMs;
    }


    @Override
    public void tick(long timestampInMillis) {
        LOG.debug("Starting tick for {}", timestampInMillis);
        if (timestampInMillis - lastRun >= tickResolutionMs - 1) {
            onTick(timestampInMillis);
            lastRun = timestampInMillis;
        } else {
            LOG.debug("Less than {} milliseconds elapsed since last tick. Ignoring.", tickResolutionMs);
        }
        LOG.debug("Done tick for {}", timestampInMillis);
    }

    @Override
    public void init(List<Alarm> alarms, List<Incident> incidents, List<InventoryObject> inventory) {
        LOG.debug("Initialized with {} alarms, {} incidents and {} inventory objects.", alarms.size(), incidents.size(), inventory.size());
        LOG.debug("Alarms on init: {}", alarms);
        LOG.debug("Incidents on init: {}", incidents);
        LOG.debug("Inventory objects on init: {}", inventory);
        graphManager.addInventory(inventory);
        graphManager.addOrUpdateAlarms(alarms);

        // Index the given incidents and the alarms they contain, so that we can cluster alarms in existing
        // incidents when applicable
        for (Incident incident : incidents) {
            final IncidentBean incidentBean = new IncidentBean(incident);
            incidentsById.put(incidentBean.getId(), incidentBean);
            for (Alarm alarmInIncident : incidentBean.getAlarms()) {
                alarmIdToIncidentMap.put(alarmInIncident.getId(), incidentBean);
            }
        }

        if (alarms.size()>0) {
            alarmsChangedSinceLastTick = true;
        }
    }

    @Override
    public void destroy() {
        // no-op
    }

    public void onTick(long timestampInMillis) {
        if (!alarmsChangedSinceLastTick) {
            LOG.debug("{}: No alarm changes since last tick. Nothing to do.", timestampInMillis);
            return;
        }
        // Reset
        alarmsChangedSinceLastTick = false;

        // Perform the clustering with the graph locked
        final Set<IncidentBean> incidents = new HashSet<>();
        graphManager.withGraph(g -> {
            if (graphManager.getDidGraphChangeAndReset()) {
                // If the graph has changed, then reset the cache
                LOG.debug("{}: Graph has changed. Resetting hop cache.", timestampInMillis);
                hops.invalidateAll();
                shortestPath = null;
                disconnectedVertices = graphManager.getDisconnectedVertices();
            }

            // GC alarms from vertices
            int numGarbageCollectedAlarms = 0;
            for (CEVertex v : g.getVertices()) {
                numGarbageCollectedAlarms += v.garbageCollectAlarms(timestampInMillis, problemTimeoutMs, clearTimeoutMs);
            }
            LOG.debug("{}: Garbage collected {} alarms.", timestampInMillis, numGarbageCollectedAlarms);

            // Ensure the points are sorted in order to make sure that the output of the clusterer is deterministic
            // OPTIMIZATION: Can we avoid doing this every tick?
            final List<AlarmInSpaceTime> alarms = g.getVertices().stream()
                    .map(v -> v.getAlarms().stream()
                            .map(a -> new AlarmInSpaceTime(v,a))
                            .collect(Collectors.toList()))
                    .flatMap(Collection::stream)
                    .sorted(Comparator.comparing(AlarmInSpaceTime::getAlarmTime).thenComparing(AlarmInSpaceTime::getAlarmId))
                    .collect(Collectors.toList());
            if (alarms.size() < 1) {
                LOG.debug("{}: The graph contains no alarms. No clustering will be performed.", timestampInMillis);
                return;
            }

            LOG.debug("{}: Clustering {} alarms.", timestampInMillis, alarms.size());
            final DBSCANClusterer<AlarmInSpaceTime> clusterer = new DBSCANClusterer<>(epsilon, 1, distanceMeasure);
            final List<Cluster<AlarmInSpaceTime>> clustersOfAlarms = clusterer.cluster(alarms);
            LOG.debug("{}: Found {} clusters of alarms.", timestampInMillis, clustersOfAlarms.size());
            for (Cluster<AlarmInSpaceTime> clusterOfAlarms : clustersOfAlarms) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}: Processing cluster containing {} alarms.", timestampInMillis, clusterOfAlarms.getPoints().size());
                }
                incidents.addAll(mapClusterToIncidents(clusterOfAlarms, alarmIdToIncidentMap, incidentsById));
            }
        });

        // Index and notify the incident handler
        LOG.debug("{}: Creating/updating {} incidents.", timestampInMillis, incidents.size());
        for (IncidentBean incident : incidents) {
            for (Alarm alarm : incident.getAlarms()) {
                alarmIdToIncidentMap.put(alarm.getId(), incident);
            }
            incidentsById.put(incident.getId(), incident);
            incidentHandler.onIncident(incident);
        }
    }

    /**
     * Maps the clusters to incidents and returns a set of updated incident
     * which should be forwarded to the incident handler.
     *
     * @param clusterOfAlarms clusters to group into incidents
     * @param alarmIdToIncidentMap map of existing alarm ids to incident ids
     * @param incidentsById map of existing incidents ids to incidents
     * @return set of updated incidents
     */
    @VisibleForTesting
    protected Set<IncidentBean> mapClusterToIncidents(Cluster<AlarmInSpaceTime> clusterOfAlarms,
                                                    Map<String, IncidentBean> alarmIdToIncidentMap,
                                                    Map<String, IncidentBean> incidentsById) {
        // Map the alarms by existing incident id, using the empty incident id id if they are not associated with an incident
        final Map<String, List<Alarm>> alarmsByIncidentId = clusterOfAlarms.getPoints().stream()
                .map(AlarmInSpaceTime::getAlarm)
                .collect(Collectors.groupingBy(a -> {
                    final Incident incident = alarmIdToIncidentMap.get(a.getId());
                    if (incident != null) {
                        return incident.getId();
                    }
                    return EMPTY_INCIDENT_ID;
                }));

        final Set<IncidentBean> incidents = new LinkedHashSet<>();
        final boolean existsAlarmWithoutIncident = alarmsByIncidentId.containsKey(EMPTY_INCIDENT_ID);
        if (!existsAlarmWithoutIncident) {
            // All of the alarms are already in incidents, nothing to do here
            return incidents;
        }

        if (alarmsByIncidentId.size() == 1) {
            // All of the alarms in the cluster are not associated with an incident yet
            // Create a new incident with all of the alarms
            final IncidentBean incident = new IncidentBean();
            incident.setId(UUID.randomUUID().toString());
            for (AlarmInSpaceTime alarm : clusterOfAlarms.getPoints()) {
                incident.addAlarm(alarm.getAlarm());

            }
            incidents.add(incident);
        } else if (alarmsByIncidentId.size() == 2) {
            // Some of the alarms in the cluster already belong to an incident whereas other don't
            // Add them all to the same incident
            final String incidentId = alarmsByIncidentId.keySet().stream().filter(k -> !EMPTY_INCIDENT_ID.equals(k))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Should not happen."));
            final IncidentBean incident = incidentsById.get(incidentId);
            if (incident == null) {
                throw new IllegalStateException("Should not happen.");
            }

            alarmsByIncidentId.get(EMPTY_INCIDENT_ID).forEach(incident::addAlarm);
            incidents.add(incident);
        } else {
            // The alarms in this cluster already belong to different incidents
            // Let's locate the ones that aren't part of any incident
            final List<Alarm> alarmsWithoutIncidents = alarmsByIncidentId.get(EMPTY_INCIDENT_ID);
            if (LOG.isTraceEnabled()) {
                LOG.trace("Found {} unclassified alarms in cluster where alarms are associated with {} incidents.",
                        alarmsWithoutIncidents.size(), alarmsByIncidentId.size());
            }

            final List<Alarm> candidateAlarms = alarmsByIncidentId.entrySet().stream()
                    .filter(e -> !EMPTY_INCIDENT_ID.equals(e.getKey()))
                    .flatMap(e -> e.getValue().stream())
                    .collect(Collectors.toList());

            // For each of these we want to associate the alarm with the other alarm that is the "closest"
            for (Alarm alarm : alarmsWithoutIncidents) {
                final Alarm closestNeighbor = getClosestNeighborInIncident(alarm, candidateAlarms);
                final IncidentBean incident = alarmIdToIncidentMap.get(closestNeighbor.getId());
                if (incident == null) {
                    throw new IllegalStateException("Should not happen.");
                }
                incident.addAlarm(alarm);
                incidents.add(incident);
            }
        }

        LOG.debug("Generating diagnostic texts for {} incidents...", incidents.size());
        for (IncidentBean incident : incidents) {
            incident.setDiagnosticText(getDiagnosticTextForIncident(incident));
        }
        LOG.debug("Done generating diagnostic texts.");

        return incidents;
    }

    private String getDiagnosticTextForIncident(IncidentBean incident) {
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        Long maxNumHops = null;

        final Set<Long> vertexIds = new HashSet<>();
        for (Alarm alarm : incident.getAlarms()) {
            minTime = Math.min(minTime, alarm.getTime());
            maxTime = Math.max(maxTime, alarm.getTime());
            // The alarm may no longer be in this graph
            getOptionalVertexIdForAlarm(alarm).ifPresent(vertexIds::add);
        }

        if (vertexIds.size() < NUM_VERTEX_THRESHOLD_FOR_HOP_DIAG) {
            maxNumHops = 0L;
            for (Long vertexIdA : vertexIds) {
                for (Long vertexIdB : vertexIds) {
                    if (!vertexIdA.equals(vertexIdB)) {
                        maxNumHops = Math.max(maxNumHops, getNumHopsBetween(vertexIdA, vertexIdB));
                    }
                }
            }
        }

        String diagText = String.format("The alarms happened within %.2f seconds across %d vertices",
                Math.abs(maxTime - minTime) / 1000d, vertexIds.size());
        if (maxNumHops != null && maxNumHops > 0) {
            diagText += String.format(" separated by %d hops", maxNumHops);
        }
        diagText += ".";
        return diagText;
    }

    @Override
    public void onAlarmCreatedOrUpdated(Alarm alarm) {
        graphManager.addOrUpdateAlarm(alarm);
        alarmsChangedSinceLastTick = true;
    }

    @Override
    public void onAlarmCleared(Alarm alarm) {
        graphManager.addOrUpdateAlarm(alarm);
        alarmsChangedSinceLastTick = true;
    }

    @Override
    public void onInventoryAdded(Collection<InventoryObject> inventory) {
        graphManager.addInventory(inventory);
    }

    @Override
    public void onInventoryRemoved(Collection<InventoryObject> inventory) {
        graphManager.removeInventory(inventory);
    }

    @Override
    public <V> V withReadOnlyGraph(Function<Graph<? extends Vertex, ? extends Edge>, V> consumer) {
        return graphManager.withReadOnlyGraph(consumer);
    }

    @Override
    public void withReadOnlyGraph(Consumer<Graph<? extends Vertex, ? extends Edge>> consumer) {
        graphManager.withReadOnlyGraph(consumer);
    }

    private static class CandidateAlarmWithDistance {

        private final Alarm alarm;
        private final double distance;

        private CandidateAlarmWithDistance(Alarm alarm, double distance) {
            this.alarm = alarm;
            this.distance = distance;
        }

        public Alarm getAlarm() {
            return alarm;
        }

        public double getDistance() {
            return distance;
        }
    }

    private Optional<Long> getOptionalVertexIdForAlarm(Alarm alarm) {
        return graphManager.withGraph(g -> {
            for (CEVertex v : graphManager.getGraph().getVertices()) {
                final Optional<Alarm> match = v.getAlarms().stream()
                        .filter(a -> a.equals(alarm))
                        .findFirst();
                if (match.isPresent()) {
                    return Optional.of(v.getNumericId());
                }
            }
            return Optional.empty();
        });
    }

    private long getVertexIdForAlarm(Alarm alarm) {
        final Optional<Long> vertexId = getOptionalVertexIdForAlarm(alarm);
        if (vertexId.isPresent()) {
            return vertexId.get();
        }
        throw new IllegalStateException("No vertex found for alarm: " + alarm);
    }

    private Alarm getClosestNeighborInIncident(Alarm alarm, List<Alarm> candidates) {
        final double timeA = alarm.getTime();
        final long vertexIdA = getVertexIdForAlarm(alarm);

        return candidates.stream()
                .map(candidate -> {
                    final double timeB = candidate.getTime();
                    final long vertexIdB = getVertexIdForAlarm(candidate);
                    final int numHops = vertexIdA == vertexIdB ? 0 : getNumHopsBetween(vertexIdA, vertexIdB);
                    final double distance = distanceMeasure.compute(timeA, timeB, numHops);
                    return new CandidateAlarmWithDistance(candidate, distance);
                })
                .min(Comparator.comparingDouble(CandidateAlarmWithDistance::getDistance)
                        .thenComparing(c -> c.getAlarm().getId()))
                .orElseThrow(() -> new IllegalStateException("Should not happen!")).alarm;
    }

    protected int getNumHopsBetween(long vertexIdA, long vertexIdB) {
        final EdgeKey key = new EdgeKey(vertexIdA, vertexIdB);
        try {
            return hops.get(key);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private final LoadingCache<EdgeKey, Integer> hops = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .build(new CacheLoader<EdgeKey, Integer>() {
                        public Integer load(EdgeKey key) {
                            if (disconnectedVertices.contains(key.vertexIdA) || disconnectedVertices.contains(key.vertexIdB)) {
                                return 0;
                            }
                            final CEVertex vertexA = graphManager.getVertexWithId(key.vertexIdA);
                            if (vertexA == null) {
                                throw new IllegalStateException("Could not find vertex with id: " + key.vertexIdA);
                            }
                            final CEVertex vertexB = graphManager.getVertexWithId(key.vertexIdB);
                            if (vertexB == null) {
                                throw new IllegalStateException("Could not find vertex with id: " + key.vertexIdB);
                            }

                            if (shortestPath == null) {
                                shortestPath = new DijkstraShortestPath<>(graphManager.getGraph(), true);
                            }
                            return shortestPath.getPath(vertexA, vertexB).size();
                        }
                    });

    private static class EdgeKey {
        private long vertexIdA;
        private long vertexIdB;

        private EdgeKey(long vertexIdA, long vertexIdB) {
            if (vertexIdA <= vertexIdB) {
                this.vertexIdA = vertexIdA;
                this.vertexIdB = vertexIdB;
            } else {
                this.vertexIdA = vertexIdB;
                this.vertexIdB = vertexIdA;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EdgeKey edgeKey = (EdgeKey) o;
            return vertexIdA == edgeKey.vertexIdA &&
                    vertexIdB == edgeKey.vertexIdB;
        }

        @Override
        public int hashCode() {
            return Objects.hash(vertexIdA, vertexIdB);
        }
    }

    @VisibleForTesting
    Graph<CEVertex, CEEdge> getGraph() {
        return graphManager.getGraph();
    }
}
