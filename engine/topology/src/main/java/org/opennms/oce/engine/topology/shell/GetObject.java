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

package org.opennms.oce.engine.topology.shell;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.oce.engine.topology.model.ModelBuilderImpl;
import org.opennms.oce.engine.topology.model.ModelImpl;
import org.opennms.oce.engine.topology.model.ModelObjectImpl;

@Command(scope = "oce", name = "modelObjectById", description="Model Object Listing by Id")
@Service
public class GetObject implements Action {
    @Reference
    private ModelBuilderImpl builder;

    @Argument(index = 0, name = "type", description = "This is TYPE for the modelObject", required = true, multiValued = false)
    private String type;

    @Argument(index = 1, name = "id", description = "This is ID for the modelObject", required = true, multiValued = false)
    private String id;

    @Override
    public Object execute() throws Exception {
        ModelImpl model = builder.buildModel();
        final ModelObjectImpl modelObject = model.getObjectById(type, id);
        if (modelObject == null) {
            System.out.println("(No modelObject for id: " + id + ")");
        } else {
            System.out.println("ModelObject:" + modelObject);
        }
        return modelObject;
    }
}