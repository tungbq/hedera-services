/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.spec;

import com.hedera.services.bdd.spec.utilops.records.SnapshotModeOp;

/**
 * Enumerates the different types of network that can be targeted by a test suite. There are some
 * operations (currently just {@link SnapshotModeOp}) that only make sense when running against
 * a certain type of network.
 */
public enum TargetNetworkType {
    /**
     * A network whose nodes are running in child subprocesses of the test process.
     */
    SUBPROCESS_NETWORK,
    /**
     * A long-lived remote network.
     */
    REMOTE_NETWORK,
    /**
     * An embedded "network" with a single Hedera instance whose workflows invoked directly, without gRPC.
     */
    EMBEDDED_NETWORK,
}
