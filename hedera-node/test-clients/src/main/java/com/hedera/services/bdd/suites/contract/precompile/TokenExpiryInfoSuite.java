/*
 * Copyright (C) 2021-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_CONTRACT_CALL_RESULTS;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_FUNCTION_PARAMETERS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class TokenExpiryInfoSuite {
    private static final String TOKEN_EXPIRY_CONTRACT = "TokenExpiryContract";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String UPDATED_AUTO_RENEW_ACCOUNT = "updatedAutoRenewAccount";
    private static final String INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
    private static final long DEFAULT_MAX_LIFETIME =
            Long.parseLong(HapiSpecSetup.getDefaultNodeProps().get("entities.maxLifetime"));
    private static final KeyShape TRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final String ADMIN_KEY = TokenKeyType.ADMIN_KEY.name();
    private static final String CONTRACT_KEY = "contractKey";
    public static final String GET_EXPIRY_INFO_FOR_TOKEN = "getExpiryInfoForToken";
    public static final String UPDATE_EXPIRY_INFO_FOR_TOKEN = "updateExpiryInfoForToken";
    public static final String UPDATE_EXPIRY_INFO_FOR_TOKEN_AND_READ_LATEST_INFO =
            "updateExpiryInfoForTokenAndReadLatestInfo";
    public static final long MONTH_IN_SECONDS = 7_000_000L;
    public static final int GAS_TO_OFFER = 1_000_000;

    @HapiTest
    @SuppressWarnings({"java:S5960", "java:S1192"
    }) // using `assertThat` in production code - except this isn't production code
    final Stream<DynamicTest> updateExpiryInfoForToken() {

        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> updatedAutoRenewAccountID = new AtomicReference<>();

        return defaultHapiSpec("updateExpiryInfoForToken")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        cryptoCreate(UPDATED_AUTO_RENEW_ACCOUNT)
                                .balance(0L)
                                .keyShape(SigControl.ED25519_ON)
                                .exposingCreatedIdTo(updatedAutoRenewAccountID::set),
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(TOKEN_EXPIRY_CONTRACT),
                        contractCreate(TOKEN_EXPIRY_CONTRACT).gas(1_000_000L),
                        tokenCreate(VANILLA_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .treasury(TOKEN_TREASURY)
                                .expiry(100)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500L)
                                .adminKey(ADMIN_KEY)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN,
                                        asHeadlongAddress(INVALID_ADDRESS),
                                        DEFAULT_MAX_LIFETIME - 12_345L,
                                        HapiParserUtil.asHeadlongAddress(asAddress(updatedAutoRenewAccountID.get())),
                                        MONTH_IN_SECONDS)
                                .alsoSigningWithFullPrefix(ADMIN_KEY, UPDATED_AUTO_RENEW_ACCOUNT)
                                .via("invalidTokenTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        DEFAULT_MAX_LIFETIME - 12_345L,
                                        HapiParserUtil.asHeadlongAddress(asAddress(updatedAutoRenewAccountID.get())),
                                        MONTH_IN_SECONDS)
                                .via("invalidSignatureTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(CONTRACT_KEY).shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, TOKEN_EXPIRY_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).adminKey(CONTRACT_KEY).signedByPayerAnd(ADMIN_KEY, CONTRACT_KEY),
                        cryptoUpdate(UPDATED_AUTO_RENEW_ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        100L,
                                        HapiParserUtil.asHeadlongAddress(asAddress(updatedAutoRenewAccountID.get())),
                                        MONTH_IN_SECONDS)
                                .alsoSigningWithFullPrefix(ADMIN_KEY, UPDATED_AUTO_RENEW_ACCOUNT)
                                .via("invalidExpiryTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        DEFAULT_MAX_LIFETIME - 12_345L,
                                        asHeadlongAddress(INVALID_ADDRESS),
                                        MONTH_IN_SECONDS)
                                .alsoSigningWithFullPrefix(ADMIN_KEY, UPDATED_AUTO_RENEW_ACCOUNT)
                                .via("invalidAutoRenewAccountTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        DEFAULT_MAX_LIFETIME - 12_345L,
                                        HapiParserUtil.asHeadlongAddress(asAddress(updatedAutoRenewAccountID.get())),
                                        1L)
                                .alsoSigningWithFullPrefix(ADMIN_KEY, UPDATED_AUTO_RENEW_ACCOUNT)
                                .via("invalidAutoRenewPeriodTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        DEFAULT_MAX_LIFETIME - 12_345L,
                                        HapiParserUtil.asHeadlongAddress(asAddress(updatedAutoRenewAccountID.get())),
                                        MONTH_IN_SECONDS)
                                .alsoSigningWithFullPrefix(ADMIN_KEY, UPDATED_AUTO_RENEW_ACCOUNT)
                                .via("updateExpiryTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS))))
                .then(
                        childRecordsCheck(
                                "invalidTokenTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_TOKEN_ID)),
                        childRecordsCheck(
                                "invalidSignatureTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_SIGNATURE)),
                        childRecordsCheck(
                                "invalidExpiryTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_EXPIRATION_TIME)),
                        childRecordsCheck(
                                "invalidAutoRenewAccountTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_AUTORENEW_ACCOUNT)),
                        childRecordsCheck(
                                "invalidAutoRenewPeriodTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_RENEWAL_PERIOD)),
                        withOpContext((spec, opLog) -> {
                            final var getTokenInfoQuery = getTokenInfo(VANILLA_TOKEN);
                            allRunFor(spec, getTokenInfoQuery);
                            final var expirySecond = getTokenInfoQuery
                                    .getResponse()
                                    .getTokenGetInfo()
                                    .getTokenInfo()
                                    .getExpiry()
                                    .getSeconds();
                            final var autoRenewAccount = getTokenInfoQuery
                                    .getResponse()
                                    .getTokenGetInfo()
                                    .getTokenInfo()
                                    .getAutoRenewAccount();
                            final var autoRenewPeriod = getTokenInfoQuery
                                    .getResponse()
                                    .getTokenGetInfo()
                                    .getTokenInfo()
                                    .getAutoRenewPeriod()
                                    .getSeconds();
                            assertEquals(expirySecond, DEFAULT_MAX_LIFETIME - 12_345L);
                            assertEquals(autoRenewAccount, spec.registry().getAccountID(UPDATED_AUTO_RENEW_ACCOUNT));
                            assertEquals(autoRenewPeriod, MONTH_IN_SECONDS);
                        }));
    }

    @HapiTest
    @SuppressWarnings("java:S1192") // "use already defined const instead of copying its value here" - not this time
    final Stream<DynamicTest> updateExpiryInfoForTokenAndReadLatestInfo() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> updatedAutoRenewAccountID = new AtomicReference<>();
        return defaultHapiSpec("updateExpiryInfoForTokenAndReadLatestInfo")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        cryptoCreate(UPDATED_AUTO_RENEW_ACCOUNT)
                                .keyShape(SigControl.ED25519_ON)
                                .balance(0L)
                                .exposingCreatedIdTo(updatedAutoRenewAccountID::set),
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(TOKEN_EXPIRY_CONTRACT),
                        contractCreate(TOKEN_EXPIRY_CONTRACT).gas(1_000_000L),
                        tokenCreate(VANILLA_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .treasury(TOKEN_TREASURY)
                                .expiry(100)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500L)
                                .adminKey(ADMIN_KEY)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY).shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, TOKEN_EXPIRY_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).adminKey(CONTRACT_KEY).signedByPayerAnd(ADMIN_KEY, CONTRACT_KEY),
                        cryptoUpdate(UPDATED_AUTO_RENEW_ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        UPDATE_EXPIRY_INFO_FOR_TOKEN_AND_READ_LATEST_INFO,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                        DEFAULT_MAX_LIFETIME - 12_345L,
                                        HapiParserUtil.asHeadlongAddress(asAddress(updatedAutoRenewAccountID.get())),
                                        MONTH_IN_SECONDS)
                                .alsoSigningWithFullPrefix(ADMIN_KEY, UPDATED_AUTO_RENEW_ACCOUNT)
                                .via("updateExpiryAndReadLatestInfoTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS))))
                .then(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                "updateExpiryAndReadLatestInfoTxn",
                                SUCCESS,
                                recordWith().status(SUCCESS),
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.HAPI_GET_TOKEN_EXPIRY_INFO)
                                                        .withStatus(SUCCESS)
                                                        .withExpiry(
                                                                DEFAULT_MAX_LIFETIME - 12_345L,
                                                                updatedAutoRenewAccountID.get(),
                                                                MONTH_IN_SECONDS)))))));
    }

    @HapiTest
    final Stream<DynamicTest> getExpiryInfoForToken() {

        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return defaultHapiSpec(
                        "GetExpiryInfoForToken",
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(TOKEN_EXPIRY_CONTRACT),
                        contractCreate(TOKEN_EXPIRY_CONTRACT).gas(1_000_000L),
                        tokenCreate(VANILLA_TOKEN)
                                .supplyType(TokenSupplyType.FINITE)
                                .treasury(TOKEN_TREASURY)
                                .expiry(100)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500L)
                                .adminKey(ADMIN_KEY)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        GET_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(TokenID.newBuilder().build())))
                                .via("expiryForInvalidTokenIDTxn")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS),
                        contractCall(
                                        TOKEN_EXPIRY_CONTRACT,
                                        GET_EXPIRY_INFO_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .via("expiryTxn")
                                .gas(GAS_TO_OFFER)
                                .payingWith(GENESIS),
                        contractCallLocal(
                                TOKEN_EXPIRY_CONTRACT,
                                GET_EXPIRY_INFO_FOR_TOKEN,
                                HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get()))))))
                .then(
                        childRecordsCheck(
                                "expiryForInvalidTokenIDTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_TOKEN_ID)
                                //                                        .contractCallResult(resultWith()
                                //
                                // .contractCallResult(htsPrecompileResult()
                                //
                                // .forFunction(FunctionType.HAPI_GET_TOKEN_EXPIRY_INFO)
                                //                                                        .withStatus(INVALID_TOKEN_ID)
                                //                                                        .withExpiry(0,
                                // AccountID.getDefaultInstance(), 0)))
                                ),
                        withOpContext((spec, opLog) -> {
                            final var getTokenInfoQuery = getTokenInfo(VANILLA_TOKEN);
                            allRunFor(spec, getTokenInfoQuery);
                            final var expirySecond = getTokenInfoQuery
                                    .getResponse()
                                    .getTokenGetInfo()
                                    .getTokenInfo()
                                    .getExpiry()
                                    .getSeconds();
                            allRunFor(
                                    spec,
                                    childRecordsCheck(
                                            "expiryTxn",
                                            SUCCESS,
                                            recordWith()
                                                    .status(SUCCESS)
                                                    .contractCallResult(resultWith()
                                                            .contractCallResult(htsPrecompileResult()
                                                                    .forFunction(
                                                                            FunctionType.HAPI_GET_TOKEN_EXPIRY_INFO)
                                                                    .withStatus(SUCCESS)
                                                                    .withExpiry(
                                                                            expirySecond,
                                                                            spec.registry()
                                                                                    .getAccountID(AUTO_RENEW_ACCOUNT),
                                                                            THREE_MONTHS_IN_SECONDS)))));
                        }));
    }
}
