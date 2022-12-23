/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
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
package com.hedera.services.bdd.junit.utils;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.stream.proto.RecordStreamItem;
import java.util.HashSet;
import java.util.Set;

public class AccountClassifier {
    private final Set<Long> contractAccounts = new HashSet<>();

    public void incorporate(final RecordStreamItem item) {
        try {
            final var txn = CommonUtils.extractTransactionBody(item.getTransaction());
            if (txn.hasContractCreateInstance()) {
                final var createdContract = item.getRecord().getReceipt().getContractID();
                contractAccounts.add(createdContract.getContractNum());
            }
        } catch (final InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isContract(final long num) {
        return contractAccounts.contains(num);
    }
}
