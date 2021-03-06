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

package org.opennms.alec.processor.redundant;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.opennms.integration.api.v1.coordination.DomainManager;
import org.opennms.integration.api.v1.coordination.DomainManagerFactory;
import org.opennms.integration.api.v1.coordination.Role;
import org.opennms.integration.api.v1.coordination.RoleChangeHandler;
import org.opennms.alec.datasource.api.Alarm;
import org.opennms.alec.datasource.api.Situation;
import org.opennms.alec.datasource.api.SituationDatasource;
import org.opennms.alec.processor.api.SituationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A situation processor that uses active-standby redundancy. The active member immediately forwards situations while
 * standby members queue situations and listen to make sure the situation was processed by the active. In the event of
 * a switchover the former standby will catch up by forwarding remaining queued situations that were not forwarded by
 * the former active.
 */
public class ActiveStandbySituationProcessor implements SituationProcessor, RoleChangeHandler {
    /**
     * The logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ActiveStandbySituationProcessor.class);

    /**
     * The redundancy domain to register with.
     */
    static final String ALEC_DOMAIN = "alec";

    /**
     * The service Id to register with the redundancy domain.
     */
    private static final String ALEC_SERVICE_ID = "alec.driver";

    /**
     * The domain manager.
     */
    private final DomainManager domainManager;

    /**
     * The situation data source.
     */
    private final SituationDatasource situationDatasource;

    /**
     * The current role.
     */
    private Role currentRole = Role.UNKNOWN;

    /**
     * The map of unconfirmed situations. The key for this map is the set of reduction keys for the alarms correlated to
     * this situation.
     * <p>
     * Entries in this map will age out after one minute. Therefore if a situation takes greater than this time to
     * complete a round trip then it can potentially be dropped in the case of a switchover.
     */
    private final Map<Set<String>, Situation> unconfirmedSituations = SynchronizedExpiringLinkedHashMap.newInstance(1,
            TimeUnit.MINUTES);

    /**
     * Private Constructor.
     * <p>
     * Factory methods must perform the registration with the {@link #domainManager} or this instance will never become
     * active.
     *
     * @param situationDatasource   the situation data source
     * @param domainManagerFactory the domain manager factory
     */
    private ActiveStandbySituationProcessor(SituationDatasource situationDatasource,
                                            DomainManagerFactory domainManagerFactory) {
        this.situationDatasource = Objects.requireNonNull(situationDatasource);
        domainManager = Objects.requireNonNull(domainManagerFactory).getManagerForDomain(ALEC_DOMAIN);
    }

    /**
     * Default factory method.
     *
     * @param situationDatasource   the situation data source
     * @param domainManagerFactory the domain manager factory
     * @return a new {@link ActiveStandbySituationProcessor} instance
     */
    static ActiveStandbySituationProcessor newInstance(SituationDatasource situationDatasource,
                                                       DomainManagerFactory domainManagerFactory) {
        ActiveStandbySituationProcessor instance = new ActiveStandbySituationProcessor(situationDatasource,
                domainManagerFactory);
        LOG.debug("Registering service {} for domain {}", ALEC_SERVICE_ID, ALEC_DOMAIN);
        // The domain registration has to happen after the instance has been created to prevent leaking a 'this'
        // reference that may be used by the domain manager before the processor has been fully constructed
        instance.domainManager.register(ALEC_SERVICE_ID, instance);

        return instance;
    }

    /**
     * Checks if we are currently active within our redundancy domain.
     *
     * @return true if active, false otherwise
     */
    private synchronized boolean isActive() {
        return currentRole == Role.ACTIVE;
    }

    /**
     * Store a situation for later processing if we become active.
     *
     * @param situation the situation to store
     */
    private void storeSituation(Situation situation) {
        LOG.debug("Storing situation {}", situation);
        Set<String> reductionKeys = situation.getAlarms().stream()
                .map(Alarm::getId)
                .collect(Collectors.toSet());
        unconfirmedSituations.put(Collections.unmodifiableSet(reductionKeys), situation);
    }

    /**
     * Forwards all situations that were queued while standby.
     */
    private void catchupSituations() {
        synchronized (unconfirmedSituations) {
            LOG.debug("Catching up stored situations");

            for (Iterator<Map.Entry<Set<String>, Situation>> iter = unconfirmedSituations.entrySet().iterator();
                 iter.hasNext() && isActive(); ) {
                Situation situation = iter.next().getValue();
                LOG.debug("Catching up situation {}", situation);
                forwardSituation(situation);
                iter.remove();
            }
        }
    }

    /**
     * Forward the situation.
     *
     * @param situation the situation to forward
     */
    @SuppressWarnings("Duplicates")
    private void forwardSituation(Situation situation) {
        try {
            LOG.debug("Forwarding situation: {}", situation);
            situationDatasource.forwardSituation(situation);
            LOG.debug("Successfully forwarded situation.");
        } catch (Exception e) {
            LOG.error("An error occurred while forwarding situation: {}. The situation will be lost.", situation, e);
        }
    }

    /**
     * Gets the current role.
     *
     * @return the current role
     */
    Role getCurrentRole() {
        return currentRole;
    }

    /**
     * Deregister with the domain manager.
     */
    void destroy() {
        LOG.debug("Deregistering service {}", ALEC_SERVICE_ID);
        domainManager.deregister(ALEC_SERVICE_ID);
    }

    /**
     * Get a copy of the current queue of unconfirmed situations.
     *
     * @return a copy of the current unconfirmed situations map
     */
    Map<Set<String>, Situation> getUnconfirmedSituations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unconfirmedSituations));
    }

    @Override
    public void accept(Situation situation) {
        Objects.requireNonNull(situation);

        if (isActive()) {
            forwardSituation(situation);
        } else {
            storeSituation(situation);
        }
    }

    @Override
    public void confirm(Set<String> reductionKeysInAlarm) {
        LOG.debug("Confirming alarm with key {}", reductionKeysInAlarm);
        unconfirmedSituations.remove(reductionKeysInAlarm);
    }

    @Override
    public synchronized void handleRoleChange(Role role, String domain) {
        LOG.debug("Became {} for domain {}", role, domain);
        currentRole = role;

        if (isActive()) {
            CompletableFuture.runAsync(this::catchupSituations);
        }
    }
}
