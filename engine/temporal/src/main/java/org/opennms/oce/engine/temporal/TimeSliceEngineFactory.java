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

package org.opennms.oce.engine.temporal;

import org.opennms.oce.engine.common.SimpleEngineFactory;

public class TimeSliceEngineFactory extends SimpleEngineFactory<TimeSliceProcessor> {
    private Integer sliceMillis;

    public TimeSliceEngineFactory() {
        super(TimeSliceProcessor.class);
    }

    @Override
    public String getName() {
        return "temporal";
    }

    @Override
    public TimeSliceProcessor createEngine() {
        final TimeSliceProcessor engine = super.createEngine();
        if (sliceMillis != null) {
            engine.setSliceMillis(sliceMillis);
        }
        return engine;
    }

    public void setSliceMillis(int sliceMillis) {
        this.sliceMillis = sliceMillis;
    }
}