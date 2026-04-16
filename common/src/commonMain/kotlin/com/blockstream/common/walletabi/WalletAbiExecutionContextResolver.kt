package com.blockstream.common.walletabi

import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.managers.SessionManager
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import lwk.Network as LwkNetwork

 enum class WalletAbiSignerKind {
    SOFTWARE,
}

 data class WalletAbiExecutionContext(
    val session: GdkSession,
    val requestNetwork: WalletAbiNetwork,
    val accounts: List<Account>,
    val primaryAccount: Account,
    val lwkNetwork: LwkNetwork,
    val signerKind: WalletAbiSignerKind,
)

 data class WalletAbiAccountProfile(
    val id: String,
    val network: WalletAbiNetwork,
)

 data class WalletAbiSessionProfile(
    val id: String,
    val connected: Boolean,
    val signerKind: WalletAbiSignerKind?,
    val accountProfiles: List<WalletAbiAccountProfile>,
    val environmentNetworks: Set<WalletAbiNetwork>,
    val summary: String,
)

 interface WalletAbiExecutionContextResolving {
    suspend fun resolveDirect(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String? = null,
    ): WalletAbiExecutionContext

    suspend fun resolveSessionRequest(
        incoming: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String? = null,
    ): WalletAbiExecutionContext
}

 class WalletAbiExecutionContextResolver(
    private val sessionManager: SessionManager,
) : WalletAbiExecutionContextResolving {
    override suspend fun resolveDirect(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        return resolveProcessingContext(
            session = session,
            requestNetwork = requestNetwork,
            preferredAccountId = preferredAccountId,
        )
    }

    override suspend fun resolveSessionRequest(
        incoming: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        val connectedCandidates = sessionManager.getConnectedSessions().filter { it !== incoming }
        val incomingProfile = incoming.toWalletAbiSessionProfile()
        val candidateProfiles = connectedCandidates.map { it.toWalletAbiSessionProfile() }
        val selectedProfile = selectSessionRequestProfile(
            incoming = incomingProfile,
            connectedCandidates = candidateProfiles,
            requestNetwork = requestNetwork,
        )

        val selectedSession = when (selectedProfile?.id) {
            incomingProfile.id, null -> incoming
            else -> connectedCandidates.firstOrNull { it.walletAbiSessionIdentifier() == selectedProfile.id } ?: incoming
        }

        return resolveProcessingContext(
            session = selectedSession,
            requestNetwork = requestNetwork,
            preferredAccountId = preferredAccountId,
        )
    }

    private suspend fun resolveProcessingContext(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        requireSoftwareSigner(session)

        var accounts = selectLiquidAccounts(
            session = session,
            requestNetwork = requestNetwork,
        )
        if (accounts.isEmpty()) {
            session.updateAccountsAndBalances(refresh = true).join()
            accounts = selectLiquidAccounts(
                session = session,
                requestNetwork = requestNetwork,
            )
        }
        if (accounts.isEmpty()) {
            throw WalletAbiExecutionContextException(
                "Wallet ABI found no Liquid account for '${requestNetwork.serialValue()}'",
            )
        }

        val primaryAccount = preferredAccountId?.let { requestedId ->
            accounts.firstOrNull { it.id == requestedId }
        } ?: session.activeAccount.value?.takeIf { active ->
            accounts.any { it.id == active.id }
        } ?: accounts.first()

        return WalletAbiExecutionContext(
            session = session,
            requestNetwork = requestNetwork,
            accounts = accounts,
            primaryAccount = primaryAccount,
            lwkNetwork = requestNetwork.toLwkNetwork(),
            signerKind = WalletAbiSignerKind.SOFTWARE,
        )
    }
}

 fun selectSessionRequestProfile(
    incoming: WalletAbiSessionProfile,
    connectedCandidates: List<WalletAbiSessionProfile>,
    requestNetwork: WalletAbiNetwork,
): WalletAbiSessionProfile? {
    val signerCapableCandidates = (listOf(incoming) + connectedCandidates)
        .filter { it.connected && it.signerKind != null }

    return signerCapableCandidates.firstOrNull { profile ->
        profile.accountProfiles.any { it.network == requestNetwork }
    } ?: signerCapableCandidates.firstOrNull { profile ->
        requestNetwork in profile.environmentNetworks
    } ?: signerCapableCandidates.firstOrNull()
}

 fun WalletAbiNetwork.serialValue(): String {
    return when (this) {
        WalletAbiNetwork.LIQUID -> "liquid"
        WalletAbiNetwork.TESTNET_LIQUID -> "testnet-liquid"
        WalletAbiNetwork.LOCALTEST_LIQUID -> "localtest-liquid"
    }
}

 fun String.toWalletAbiNetwork(): WalletAbiNetwork {
    return when (trim().lowercase()) {
        "liquid" -> WalletAbiNetwork.LIQUID
        "testnet-liquid" -> WalletAbiNetwork.TESTNET_LIQUID
        "localtest-liquid" -> WalletAbiNetwork.LOCALTEST_LIQUID
        else -> throw IllegalArgumentException("Unsupported Wallet ABI network: '$this'")
    }
}

 fun WalletAbiNetwork.toLwkNetwork(): LwkNetwork {
    return when (this) {
        WalletAbiNetwork.LIQUID -> LwkNetwork.mainnet()
        WalletAbiNetwork.TESTNET_LIQUID -> LwkNetwork.testnet()
        WalletAbiNetwork.LOCALTEST_LIQUID -> LwkNetwork.regtestDefault()
    }
}

 fun com.blockstream.common.gdk.data.Network.toWalletAbiNetworkOrNull(): WalletAbiNetwork? {
    if (!isLiquid) {
        return null
    }

    return when {
        isDevelopment -> WalletAbiNetwork.LOCALTEST_LIQUID
        isMainnet -> WalletAbiNetwork.LIQUID
        else -> WalletAbiNetwork.TESTNET_LIQUID
    }
}

private suspend fun requireSoftwareSigner(session: GdkSession) {
    if (!session.isConnected) {
        throw WalletAbiExecutionContextException("Wallet ABI requires a connected session")
    }
    if (session.isHardwareWallet) {
        throw WalletAbiExecutionContextException("Wallet ABI v1 supports only mnemonic-backed wallets")
    }

    val mnemonic = try {
        session.getCredentials().mnemonic
    } catch (error: Exception) {
        throw WalletAbiExecutionContextException(
            message = "Wallet ABI failed to read wallet credentials: ${error.message}",
            cause = error,
        )
    }

    if (mnemonic.isNullOrBlank()) {
        throw WalletAbiExecutionContextException("Wallet ABI requires mnemonic credentials")
    }
}

private suspend fun GdkSession.walletAbiSignerKindOrNull(): WalletAbiSignerKind? {
    if (!isConnected || isHardwareWallet) {
        return null
    }

    val mnemonic = runCatching { getCredentials().mnemonic }.getOrNull()
    return WalletAbiSignerKind.SOFTWARE.takeIf { !mnemonic.isNullOrBlank() }
}

private suspend fun GdkSession.toWalletAbiSessionProfile(): WalletAbiSessionProfile {
    val accountProfiles = (accounts.value + allAccounts.value)
        .distinctBy { it.id }
        .filter { it.isLiquid && !it.isLightning }
        .mapNotNull { account ->
            account.network.toWalletAbiNetworkOrNull()?.let { network ->
                WalletAbiAccountProfile(
                    id = account.id,
                    network = network,
                )
            }
        }

    val environmentNetworks = buildSet {
        if (!isTestnet) {
            add(WalletAbiNetwork.LIQUID)
        } else {
            add(WalletAbiNetwork.TESTNET_LIQUID)
            add(WalletAbiNetwork.LOCALTEST_LIQUID)
        }
    }

    val identifier = walletAbiSessionIdentifier()
    return WalletAbiSessionProfile(
        id = identifier,
        connected = isConnected,
        signerKind = walletAbiSignerKindOrNull(),
        accountProfiles = accountProfiles,
        environmentNetworks = environmentNetworks,
        summary = identifier,
    )
}

private fun GdkSession.walletAbiSessionIdentifier(): String {
    return ephemeralWallet?.id ?: buildString {
        append(hashCode())
        append(':')
        append(device?.name ?: "session")
        append(':')
        append(activeAccount.value?.id ?: "none")
    }
}

private fun selectLiquidAccounts(
    session: GdkSession,
    requestNetwork: WalletAbiNetwork,
): List<Account> {
    return (session.accounts.value + session.allAccounts.value)
        .distinctBy { it.id }
        .filter { account ->
            account.isLiquid &&
                !account.isLightning &&
                account.matchesWalletAbiNetwork(requestNetwork)
        }
}

private fun Account.matchesWalletAbiNetwork(requestNetwork: WalletAbiNetwork): Boolean {
    return when (requestNetwork) {
        WalletAbiNetwork.LIQUID -> network.isLiquid && network.isMainnet
        WalletAbiNetwork.TESTNET_LIQUID -> network.isLiquid && network.isTestnet && !network.isDevelopment
        WalletAbiNetwork.LOCALTEST_LIQUID -> network.isLiquid && network.isDevelopment
    }
}

 class WalletAbiExecutionContextException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
