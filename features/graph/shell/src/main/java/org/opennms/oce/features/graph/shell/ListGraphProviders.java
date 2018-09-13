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

package org.opennms.oce.features.graph.shell;


import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.oce.features.graph.api.GraphProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

@Command(scope = "graph", name = "list", description = "Lists the available graph providers.")
@Service
public class ListGraphProviders  implements Action {

    @Reference
    private BundleContext bundleContext;

    @Override
    public Object execute() throws Exception {
        boolean didFindGraphProvider = false;
        final ServiceReference<?>[] graphProviderRefs = bundleContext.getAllServiceReferences(GraphProvider.class.getCanonicalName(), null);
        if (graphProviderRefs != null) {
            for (ServiceReference<?> graphProviderRef : graphProviderRefs) {
                final String name = (String)graphProviderRef.getProperty("name");
                if (name != null) {
                    GraphProvider graphProvider = bundleContext.getService((ServiceReference<GraphProvider>)graphProviderRef);
                    final AtomicInteger numVertices = new AtomicInteger();
                    final AtomicInteger numEdges = new AtomicInteger();
                    graphProvider.withReadOnlyGraph(g -> {
                        numVertices.set(g.getVertexCount());
                        numEdges.set(g.getEdgeCount());
                    });
                    System.out.printf("%s: %d vertices and %d edges.\n", name, numVertices.get(), numEdges.get());
                    bundleContext.ungetService(graphProviderRef);
                    didFindGraphProvider = true;
                }
            }
        }

        if (!didFindGraphProvider) {
            System.out.println("(No graph providers found)");
        }
        return null;
    }

}
