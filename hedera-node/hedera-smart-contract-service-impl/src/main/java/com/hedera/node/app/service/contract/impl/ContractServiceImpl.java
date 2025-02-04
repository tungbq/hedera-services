/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.contract.impl.schemas.V0500ContractSchema;
import com.swirlds.state.spi.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;

/**
 * Implementation of the {@link ContractService}.
 */
public class ContractServiceImpl implements ContractService {
    public static final long INTRINSIC_GAS_LOWER_BOUND = 21_000L;
    public static final String LAZY_MEMO = "lazy-created account";

    private final ContractServiceComponent component;

    public ContractServiceImpl(@NonNull final InstantSource instantSource) {
        requireNonNull(instantSource);
        this.component = DaggerContractServiceComponent.factory().create(instantSource);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490ContractSchema());
        registry.register(new V0500ContractSchema());
    }

    public ContractHandlers handlers() {
        return component.handlers();
    }
}
