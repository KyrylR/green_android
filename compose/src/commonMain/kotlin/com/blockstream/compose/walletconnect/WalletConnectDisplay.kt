package com.blockstream.compose.walletconnect

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun walletConnectApprovalTitle(intent: String?, method: String?, fallback: String): String {
    val cleanIntent = intent?.takeIf { it.isNotBlank() }?.let(::walletConnectIntentLabel)
    return cleanIntent ?: walletConnectMethodLabel(method) ?: fallback
}

fun walletConnectIntentLabel(value: String): String =
    when (value) {
        "Sign PSBT" -> "Review Bitcoin transaction"
        "Sign and broadcast PSBT" -> "Review and send Bitcoin transaction"
        else -> value
    }

fun walletConnectMethodLabel(value: String?): String? =
    when (value) {
        "getAccountAddresses" -> "Share Bitcoin addresses"
        "sendTransfer" -> "Send Bitcoin"
        "signMessage" -> "Sign Bitcoin message"
        "signPsbt" -> "Sign Bitcoin transaction"
        "wc_sessionAuthenticate" -> "Sign in with Bitcoin"
        else -> value?.takeIf { it.isNotBlank() }
    }

fun walletConnectStatusLabel(value: String): String =
    when (value) {
        "Connected" -> "Ready for connections"
        "Pairing" -> "Pairing with app"
        "Paired" -> "Connected app active"
        "Connecting" -> "Starting WalletConnect"
        "Disconnected" -> "Offline"
        "Disabled" -> "Unavailable"
        "Failed" -> "Needs attention"
        else -> value
    }

fun walletConnectRiskLabel(value: String?): String? =
    when (value?.lowercase()) {
        null, "" -> null
        "unknown" -> "Not verified"
        "low" -> "Verified"
        "medium" -> "Check carefully"
        "high" -> "High risk"
        else -> value
    }

fun walletConnectReviewLabel(label: String): String =
    when (label) {
        "Connected account" -> "Wallet account"
        "PSBT size" -> "Request size"
        "PSBT inputs" -> "Transaction inputs"
        "PSBT outputs" -> "Transaction outputs"
        "OP_RETURN outputs" -> "Data outputs"
        "Input selection" -> "Inputs to sign"
        "Requested signers" -> "Requested signing addresses"
        "Broadcast after signing" -> "Broadcast transaction"
        "Verify risk" -> "Verification"
        "Required namespaces" -> "Required permissions"
        "Optional namespaces" -> "Optional permissions"
        else -> label
    }

fun walletConnectReviewValue(label: String, value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return trimmed

    return when (label) {
        "PSBT size" -> formatRequestSize(trimmed)
        "PSBT inputs", "PSBT outputs", "OP_RETURN outputs" -> formatCount(trimmed)
        "Known input amounts" -> formatKnownInputAmounts(trimmed)
        "Outputs" -> formatOutputs(trimmed)
        "Chain", "Chains", "Chains kept active", "Chains losing access", "Network" -> formatChainList(trimmed)
        "Account", "Accounts", "Accounts kept active", "Accounts losing access", "Connected account", "Wallet account" ->
            formatAccountList(trimmed)
        "Methods", "Methods kept active", "Methods losing access" -> formatMethodList(trimmed)
        "Events" -> formatEventList(trimmed)
        "Expiry", "Current expiry" -> formatExpiry(trimmed)
        "Connection state" -> trimmed.replaceFirstChar { it.titlecase() }
        "Acknowledged" -> if (trimmed.equals("yes", ignoreCase = true)) "Confirmed" else trimmed
        "Session topic" -> compactIdentifier(trimmed)
        "Verify risk", "Verification" -> walletConnectRiskLabel(trimmed) ?: trimmed
        "Required namespaces", "Optional namespaces" -> formatNamespaceSummary(trimmed)
        else -> when {
            isChainList(trimmed) -> formatChainList(trimmed)
            isAccountList(trimmed) -> formatAccountList(trimmed)
            else -> trimmed
        }
    }
}

fun walletConnectReviewText(value: String): String =
    when (value) {
        "A signed PSBT may later be finalized and broadcast outside this wallet." ->
            "A signed transaction request may later be finalized and broadcast outside this wallet."
        "The app did not name specific inputs. The wallet will only sign PSBT inputs that match this wallet's spendable UTXOs." ->
            "The app did not name specific inputs. The wallet will only sign transaction inputs that match spendable funds in this wallet."
        "The PSBT does not include enough input amount data to derive the fee; compare the displayed outputs with your intent before approving." ->
            "The transaction request does not include enough input amount data to estimate the fee. Compare the displayed outputs with your intent before approving."
        else -> value
            .replace("PSBT", "transaction request")
            .replace("UTXOs", "spendable funds")
    }

fun walletConnectChainLabel(value: String?): String? =
    value?.takeIf { it.isNotBlank() }?.let(::formatChainList)

private fun formatRequestSize(value: String): String =
    Regex("""^(\d+) chars$""").matchEntire(value)?.let { match ->
        "${match.groupValues[1]} characters"
    } ?: value

private fun formatCount(value: String): String =
    Regex("""^(\d+) (input|output)\(s\)$""").matchEntire(value)?.let { match ->
        val count = match.groupValues[1].toIntOrNull()
        val noun = match.groupValues[2]
        val label = if (count == 1) noun else "${noun}s"
        "${match.groupValues[1]} $label"
    } ?: value

private fun formatKnownInputAmounts(value: String): String =
    Regex("""^(\d+) of (\d+) input\(s\)$""").matchEntire(value)?.let { match ->
        val total = match.groupValues[2].toIntOrNull()
        val label = if (total == 1) "input amount" else "input amounts"
        "${match.groupValues[1]} of ${match.groupValues[2]} $label known"
    } ?: value

private fun formatOutputs(value: String): String =
    value.lines().joinToString("\n") { line ->
        Regex("""^#(\d+): (.+) to (.+)$""").matchEntire(line.trim())?.let { match ->
            val outputNumber = match.groupValues[1].toIntOrNull()?.plus(1)?.toString()
                ?: match.groupValues[1]
            "Output $outputNumber\n${match.groupValues[2]}\nTo ${match.groupValues[3]}"
        } ?: line
    }

private fun formatNamespaceSummary(value: String): String {
    if (value == "None") return value

    val chains = value.substringAfter("chains ", "").substringBefore("; methods ")
    val methods = value.substringAfter("; methods ", "").substringBefore("; events ")
    val events = value.substringAfter("; events ", "")

    return listOfNotNull(
        chains.takeIf { it.isNotBlank() }?.let { "Networks: ${formatChainList(it)}" },
        methods.takeIf { it.isNotBlank() }?.let { "Actions: ${formatMethodList(it)}" },
        events.takeIf { it.isNotBlank() }?.let { "Updates: ${formatEventList(it)}" },
    ).joinToString("\n")
}

private fun formatChainList(value: String): String =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ") { chainLabel(it) }

private fun chainLabel(value: String): String =
    when (value) {
        "bip122:000000000019d6689c085ae165831e93" -> "Bitcoin"
        "bip122:000000000933ea01ad0ee984209779ba" -> "Bitcoin Testnet"
        "bip122:00000008819873e925422c1ff0f99f7c" -> "Bitcoin Signet"
        "bip122:0f9188f13cb7b2c71f2a335e3a4fc328" -> "Bitcoin Regtest"
        else -> value
    }

private fun formatAccountList(value: String): String =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n") { accountLabel(it) }

private fun accountLabel(value: String): String {
    val parts = value.split(':')
    if (parts.size >= 3 && parts[0] == "bip122") {
        val chain = chainLabel("${parts[0]}:${parts[1]}")
        val address = parts.drop(2).joinToString(":")
        return "$chain\n$address"
    }
    return value
}

private fun formatMethodList(value: String): String =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ") { walletConnectMethodLabel(it) ?: it }

private fun formatEventList(value: String): String =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ") { event ->
            when (event) {
                "bip122_addressesChanged", "accountsChanged" -> "Address updates"
                "chainChanged" -> "Network updates"
                "None" -> "None"
                else -> event
            }
        }

private fun formatExpiry(value: String): String {
    val epochSeconds = value.removePrefix("Unix ").toLongOrNull() ?: return value
    val local = Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return local.toString().replace('T', ' ')
}

private fun compactIdentifier(value: String): String =
    if (value.length <= 18) value else "${value.take(8)}...${value.takeLast(8)}"

private fun isChainList(value: String): Boolean =
    value.split(',').all { it.trim().startsWith("bip122:") && it.trim().count { char -> char == ':' } == 1 }

private fun isAccountList(value: String): Boolean =
    value.split(',').all { it.trim().startsWith("bip122:") && it.trim().count { char -> char == ':' } >= 2 }
