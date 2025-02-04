/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.junit.hedera.utils;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static java.util.Objects.requireNonNull;
import static java.util.stream.StreamSupport.stream;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility class for generating an address book configuration file.
 */
public class AddressBookUtils {
    public static final long CLASSIC_FIRST_NODE_ACCOUNT_NUM = 3;
    public static final String[] CLASSIC_NODE_NAMES = new String[] {
        "Alice", "Bob", "Carol", "Dave", "Eve", "Faythe", "Grace", "Heidi", "Ivy", "Judy", "Kathy", "Lana",
        "Mallory", "Nadia", "Olivia", "Peggy", "Quinn", "Rita", "Sue", "Tina", "Ursula", "Vera", "Wendy", "Xena",
    };

    private AddressBookUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns the contents of a <i>config.txt</i> file for the given network.
     *
     * @param networkName the name of the network
     * @param nodes the nodes in the network
     * @param nextGossipPort the next gossip port to use
     * @param nextGossipTlsPort the next gossip TLS port to use
     * @return the contents of the <i>config.txt</i> file
     */
    public static String configTxtForLocal(
            @NonNull final String networkName,
            @NonNull final List<HederaNode> nodes,
            final int nextGossipPort,
            final int nextGossipTlsPort) {
        final var sb = new StringBuilder();
        sb.append("swirld, ")
                .append(networkName)
                .append("\n")
                .append("\n# This next line is, hopefully, ignored.\n")
                .append("app, HederaNode.jar\n\n#The following nodes make up this network\n");
        var maxNodeId = 0L;
        for (final var node : nodes) {
            sb.append("address, ")
                    .append(node.getNodeId())
                    .append(", ")
                    // For now only use the node id as its nickname
                    .append(node.getNodeId())
                    .append(", ")
                    .append(node.getName())
                    .append(", 1, 127.0.0.1, ")
                    .append(nextGossipPort + (node.getNodeId() * 2))
                    .append(", 127.0.0.1, ")
                    .append(nextGossipTlsPort + (node.getNodeId() * 2))
                    .append(", ")
                    .append("0.0.")
                    .append(node.getAccountId().accountNumOrThrow())
                    .append("\n");
            maxNodeId = Math.max(node.getNodeId(), maxNodeId);
        }
        sb.append("\nnextNodeId, ").append(maxNodeId + 1).append("\n");
        return sb.toString();
    }

    /**
     * Returns the "classic" metadata for a node in the network, matching the names
     * used by {@link #configTxtForLocal(String, List, int, int)} to generate the
     * <i>config.txt</i> file.
     *
     * @param nodeId the ID of the node
     * @param networkName the name of the network
     * @param scope if non-null, an additional scope to use for the working directory
     * @param nextGrpcPort the next gRPC port to use
     * @param nextGossipPort the next gossip port to use
     * @param nextGossipTlsPort the next gossip TLS port to use
     * @param nextPrometheusPort the next Prometheus port to use
     * @return the metadata for the node
     */
    public static NodeMetadata classicMetadataFor(
            final int nodeId,
            @NonNull final String networkName,
            @NonNull final String host,
            @Nullable String scope,
            final int nextGrpcPort,
            final int nextGossipPort,
            final int nextGossipTlsPort,
            final int nextPrometheusPort) {
        requireNonNull(host);
        requireNonNull(networkName);
        return new NodeMetadata(
                nodeId,
                CLASSIC_NODE_NAMES[nodeId],
                AccountID.newBuilder()
                        .accountNum(CLASSIC_FIRST_NODE_ACCOUNT_NUM + nodeId)
                        .build(),
                host,
                nextGrpcPort + nodeId * 2,
                nextGossipPort + nodeId * 2,
                nextGossipTlsPort + nodeId * 2,
                nextPrometheusPort + nodeId,
                workingDirFor(nodeId, scope));
    }

    /**
     * Returns a stream of numeric node ids from the given address book.
     *
     * @param addressBook the address book
     * @return the stream of node ids
     */
    public static Stream<Long> nodeIdsFrom(AddressBook addressBook) {
        return stream(addressBook.spliterator(), false).map(Address::getNodeId).map(NodeId::id);
    }
}
