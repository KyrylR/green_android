package com.blockstream.common.walletabi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletAbiProposalValidationTest {
    @Test
    fun autoApprovedGettersIncludeReceiveAddressWhenRequested() {
        assertEquals(
            setOf(WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS),
            walletAbiAutoApprovedGettersForRequestedMethods(
                listOf(WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS),
            ),
        )
    }

    @Test
    fun autoApprovedGettersIncludeAllRequestedGetterMethods() {
        assertEquals(
            setOf(
                WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
                WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY,
            ),
            walletAbiAutoApprovedGettersForRequestedMethods(
                listOf(
                    WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                    WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY,
                    WALLET_ABI_METHOD_PROCESS_REQUEST,
                ),
            ),
        )
    }

    @Test
    fun mergeApprovedGettersBackfillsSessionApprovalsWithoutDroppingExistingEntries() {
        assertEquals(
            setOf(
                WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
                WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY,
            ),
            mergeWalletAbiApprovedGetters(
                approvedGetters = setOf(WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY),
                requestedMethods = listOf(
                    WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                    WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY,
                ),
            ),
        )
    }

    @Test
    fun normalizeWalletConnectPairingUriAcceptsDeepLinkQuery() {
        val normalized = normalizeWalletConnectPairingUri(
            "https://wallet.example/connect?uri=wc%3Aabc123%402%3Frelay-protocol%3Dirn%26symKey%3Ddeadbeef",
        )

        assertEquals(
            "wc:abc123@2?relay-protocol=irn&symKey=deadbeef",
            normalized,
        )
        assertEquals("abc123", walletConnectPairingTopic(normalized))
    }

    @Test
    fun validateWalletAbiProposalAcceptsSupportedNamespace() {
        val validation = validateWalletAbiProposal(
            WalletAbiSessionProposal(
                pairingTopic = "pairing-topic",
                proposerPublicKey = "pubkey",
                name = "dApp",
                description = null,
                url = "https://dapp.example",
                icons = emptyList(),
                redirect = null,
                relayProtocol = "irn",
                requiredNamespaces = mapOf(
                    WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespaceProposal(
                        chains = listOf("walabi:testnet-liquid"),
                        methods = listOf(
                            WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                            WALLET_ABI_METHOD_PROCESS_REQUEST,
                        ),
                        events = emptyList(),
                    ),
                ),
                optionalNamespaces = emptyMap(),
                properties = null,
                scopedProperties = null,
                verifyContext = null,
            ),
        )

        assertEquals("walabi:testnet-liquid", validation.chainId)
        assertEquals(
            listOf(
                WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                WALLET_ABI_METHOD_PROCESS_REQUEST,
            ),
            validation.requestedMethods,
        )
    }

    @Test
    fun validateWalletAbiProposalRejectsEvents() {
        assertFailsWith<IllegalArgumentException> {
            validateWalletAbiProposal(
                WalletAbiSessionProposal(
                    pairingTopic = "pairing-topic",
                    proposerPublicKey = "pubkey",
                    name = "dApp",
                    description = null,
                    url = "https://dapp.example",
                    icons = emptyList(),
                    redirect = null,
                    relayProtocol = "irn",
                    requiredNamespaces = mapOf(
                        WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespaceProposal(
                            chains = listOf("walabi:liquid"),
                            methods = listOf(WALLET_ABI_METHOD_PROCESS_REQUEST),
                            events = listOf("accountsChanged"),
                        ),
                    ),
                    optionalNamespaces = emptyMap(),
                    properties = null,
                    scopedProperties = null,
                    verifyContext = null,
                ),
            )
        }
    }
}
