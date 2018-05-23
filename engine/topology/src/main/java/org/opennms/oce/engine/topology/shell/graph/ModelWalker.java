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

package org.opennms.oce.engine.topology.shell.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.opennms.oce.engine.topology.model.ModelImpl;
import org.opennms.oce.engine.topology.model.ModelObjectImpl;

public class ModelWalker {

    public static void visit(ModelImpl model, ModelVisitor visitor) {
        // Walk the entire model
        walkChildren(model.getRoot(), model.getRoot().getChildren(), visitor);
    }

    public static void visitNeighbors(ModelObjectImpl object, int szl, ModelVisitor visitor) {
        // Used to collect nodes during partial traversal
        final Set<ModelObjectImpl> collected = new HashSet<>();
        visitor.visitNode(object);
        walkNeighbors(Collections.singleton(object), szl, visitor, collected);
    }

    private static void walkChildren(ModelObjectImpl parent, Set<ModelObjectImpl> children, ModelVisitor visitor) {
        for (ModelObjectImpl child : children) {
            visitor.visitNode(child);
            visitor.visitEdge(child, parent, EdgeType.PARENT);

            for (ModelObjectImpl peer : child.getPeers()) {
                // Bi-directional peers
                visitor.visitEdge(child, peer, EdgeType.PEER);
                visitor.visitEdge(peer, child, EdgeType.PEER);
            }
            for (ModelObjectImpl uncle : child.getUncles()) {
                visitor.visitEdge(child, uncle, EdgeType.UNCLE);
            }

            // recurse through each child.
            walkChildren(child, child.getChildren(), visitor);
        }
    }

    /**
     * Walk model objects that have not yet been collected and reduce scope each iteration
     */
    private static void walkNeighbors(Set<ModelObjectImpl> last, Integer szl, ModelVisitor visitor, Set<ModelObjectImpl> collected) {
        if (szl <= 0) {
            // we've reached the maximium depth for this graph
            return;
        }
        for (ModelObjectImpl object : last) {
            Set<ModelObjectImpl> neighbors = new LinkedHashSet<>();
            ModelObjectImpl parent = object.getParent();
            if (!isRoot(parent)) {
                visitor.visitEdge(object, parent, EdgeType.PARENT);
                neighbors.add(parent);
            }
            neighbors.addAll(object.getPeers().stream().filter(o -> !collected.contains(o)).collect(Collectors.toSet()));
            for (ModelObjectImpl peer : object.getPeers()) {
                visitor.visitEdge(object, peer, EdgeType.PEER);
                visitor.visitEdge(peer, object, EdgeType.PEER);
            }
            neighbors.addAll(object.getUncles().stream().filter(o -> !collected.contains(o)).collect(Collectors.toSet()));
            for (ModelObjectImpl dependent : object.getUncles()) {
                visitor.visitEdge(dependent, object, EdgeType.UNCLE);
            }
            neighbors.addAll(object.getChildren().stream().filter(o -> !collected.contains(o)).collect(Collectors.toSet()));
            for (ModelObjectImpl child : object.getChildren()) {
                visitor.visitEdge(child, object, EdgeType.PARENT);
            }
            collected.addAll(neighbors);
            walkNeighbors(neighbors, szl - 1, visitor, collected);
        }
    }

    private static boolean isRoot(ModelObjectImpl mo) {
        return mo.getParent() == null || mo.getParent().equals(mo);
    }

}
