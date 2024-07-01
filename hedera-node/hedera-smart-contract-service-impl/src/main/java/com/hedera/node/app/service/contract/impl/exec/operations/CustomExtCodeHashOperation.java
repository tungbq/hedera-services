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

package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractRequired;
import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isDeficientGas;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.ExtCodeHashOperation;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * Customization of {@link ExtCodeHashOperation} that treats every long-zero address for an account
 * below {@code 0.0.1001} as having a zero code hash; and otherwise requires the account to be
 * present or halts the frame with {@link CustomExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS}.
 */
public class CustomExtCodeHashOperation extends ExtCodeHashOperation {
    private static final Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    private final AddressChecks addressChecks;
    private final FeatureFlags featureFlags;

    public CustomExtCodeHashOperation(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks,
            @NonNull final FeatureFlags featureFlags) {
        super(Objects.requireNonNull(gasCalculator));
        this.addressChecks = Objects.requireNonNull(addressChecks);
        this.featureFlags = featureFlags;
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        try {
            final long cost = cost(false);
            if (isDeficientGas(frame, cost)) {
                return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
            }
            final var address = Words.toAddress(frame.getStackItem(0));
            // Special behavior for long-zero addresses below 0.0.1001
            if (addressChecks.isNonUserAccount(address)) {
                frame.popStackItem();
                frame.pushStackItem(UInt256.ZERO);
                return new OperationResult(cost, null);
            }
            // Otherwise the address must be present
            if (contractRequired(frame, address, featureFlags) && !addressChecks.isPresent(address, frame)) {
                return new OperationResult(cost, CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS);
            }
            return super.execute(frame, evm);
        } catch (UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }
}
