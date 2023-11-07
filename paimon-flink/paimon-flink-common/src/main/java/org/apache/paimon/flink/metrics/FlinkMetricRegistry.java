/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.metrics;

import org.apache.paimon.metrics.MetricGroup;
import org.apache.paimon.metrics.MetricRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** {@link MetricRegistry} to create {@link FlinkMetricGroup}. */
public class FlinkMetricRegistry extends MetricRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkMetricRegistry.class);

    public org.apache.flink.metrics.MetricGroup getGroup() {
        return group;
    }

    private final org.apache.flink.metrics.MetricGroup group;

    public FlinkMetricRegistry(org.apache.flink.metrics.MetricGroup group) {
        this.group = group;
    }

    @Override
    protected MetricGroup createMetricGroup(String groupName, Map<String, String> variables) {
        LOG.info(
                "gjli: createMetricGroup by FlinkMetricRegistry, flink group info: {}, groupName: {}, variables: {}",
                group.getAllVariables(),
                groupName,
                variables);
        FlinkMetricGroup flinkMetricGroup = new FlinkMetricGroup(group, groupName, variables);
        return flinkMetricGroup;
    }
}
