/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.common.policies.data;

import com.google.common.base.Objects;
import org.apache.bookkeeper.common.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

public class EnsemblePlacementPolicyConfig {
    public static final String ENSEMBLE_PLACEMENT_POLICY_CONFIG = "EnsemblePlacementPolicyConfig";
    private final Class policyClass;
    private final Map<String, Object> properties;

    // Add a default constructor for decode data from bytes to construct this.
    private EnsemblePlacementPolicyConfig() {
        this.policyClass = null;
        this.properties = Collections.emptyMap();
    }

    public EnsemblePlacementPolicyConfig(Class policyClass, Map<String, Object> properties) {
        super();
        this.policyClass = policyClass;
        this.properties = properties;
    }

    public Class getPolicyClass() {
        return policyClass;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(policyClass != null ? policyClass.getName() : "", properties);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof EnsemblePlacementPolicyConfig) {
            EnsemblePlacementPolicyConfig other = (EnsemblePlacementPolicyConfig) obj;
            return Objects.equal(this.policyClass == null ? null : this.policyClass.getName(),
                other.policyClass == null ? null : other.policyClass.getName())
                && Objects.equal(this.properties, other.properties);
        }
        return false;
    }

    public byte[] encode() throws JsonUtil.ParseJsonException {
        return JsonUtil.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static EnsemblePlacementPolicyConfig decode(byte[] data) throws JsonUtil.ParseJsonException {
        return JsonUtil.fromJson(new String(data, StandardCharsets.UTF_8), EnsemblePlacementPolicyConfig.class);
    }
}
