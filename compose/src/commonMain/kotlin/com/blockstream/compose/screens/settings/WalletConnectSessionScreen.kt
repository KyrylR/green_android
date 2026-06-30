package com.blockstream.compose.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Info
import com.blockstream.compose.components.GreenAlert
import com.blockstream.compose.components.GreenButton
import com.blockstream.compose.components.GreenButtonColor
import com.blockstream.compose.components.GreenButtonSize
import com.blockstream.compose.components.GreenButtonType
import com.blockstream.compose.components.GreenCard
import com.blockstream.compose.components.GreenColumn
import com.blockstream.compose.components.ListHeader
import com.blockstream.compose.models.settings.WalletConnectSessionViewModelAbstract
import com.blockstream.compose.navigation.LocalInnerPadding
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.bodySmall
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.SetupScreen
import com.blockstream.compose.utils.bottom
import com.blockstream.compose.utils.plus
import com.blockstream.compose.walletconnect.walletConnectIntentLabel
import com.blockstream.compose.walletconnect.walletConnectReviewLabel
import com.blockstream.compose.walletconnect.walletConnectReviewText
import com.blockstream.compose.walletconnect.walletConnectReviewValue
import com.blockstream.compose.walletconnect.walletConnectStatusLabel
import com.blockstream.data.walletconnect.WalletConnectReviewField
import com.blockstream.data.walletconnect.WalletConnectSessionActionReview
import com.blockstream.data.walletconnect.WalletConnectSessionReview

@Composable
fun WalletConnectSessionScreen(
    viewModel: WalletConnectSessionViewModelAbstract
) {
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val innerPadding = LocalInnerPadding.current

    SetupScreen(
        viewModel = viewModel,
        withPadding = false,
        withBottomInsets = false
    ) {
        LazyColumn(
            contentPadding = innerPadding
                .bottom()
                .plus(PaddingValues(horizontal = 16.dp))
                .plus(PaddingValues(bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GreenAlert(
                    title = "WalletConnect",
                    message = walletConnectStatusLabel(status.name),
                    icon = PhosphorIcons.Regular.Info,
                    isBlue = true
                )
            }

            lastError?.takeIf { it.isNotBlank() }?.also { error ->
                item {
                    GreenAlert(
                        title = "Last WalletConnect error",
                        message = error,
                    )
                }
            }

            if (activeSession == null) {
                item {
                    GreenCard {
                        GreenColumn(padding = 0, space = 8) {
                            Text(
                                text = "No active session",
                                style = titleSmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "No connected app currently has WalletConnect grants for this wallet.",
                                style = bodyMedium,
                                color = whiteMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                activeSession?.also { session ->
                    item {
                        SessionSummary(session)
                    }

                    item {
                        ReviewWarnings(session.warnings)
                    }

                    item {
                        ReviewFieldsCard(
                            title = "Details",
                            fields = session.details
                        )
                    }

                    item {
                        ReviewTextCard(
                            title = "Why this matters",
                            values = session.info
                        )
                    }

                    item {
                        ListHeader(title = "Actions")
                    }

                    item {
                        ActionReviewCard(
                            title = "Disconnect",
                            review = session.disconnect,
                            buttonText = "Disconnect",
                            buttonColor = GreenButtonColor.RED,
                            buttonType = GreenButtonType.COLOR,
                            onClick = viewModel::disconnect
                        )
                    }

                    item {
                        ActionReviewCard(
                            title = "Extend",
                            review = session.extend,
                            buttonText = "Extend session",
                            buttonColor = GreenButtonColor.GREENER,
                            buttonType = GreenButtonType.OUTLINE,
                            onClick = viewModel::extend
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummary(session: WalletConnectSessionReview) {
    GreenCard {
        GreenColumn(padding = 0, space = 8) {
            Text(
                text = walletConnectIntentLabel(session.title),
                style = titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            session.subtitle?.takeIf { it.isNotBlank() }?.also {
                Text(
                    text = it,
                    style = bodyMedium,
                    color = whiteMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            session.intent?.takeIf { it.isNotBlank() && it != session.title }?.also {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = walletConnectIntentLabel(it),
                    style = bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ReviewWarnings(warnings: List<String>) {
    if (warnings.isEmpty()) return

    GreenAlert(
        title = "Important",
        message = warnings.joinToString("\n") { walletConnectReviewText(it) },
        icon = PhosphorIcons.Regular.Info,
        isBlue = true
    )
}

@Composable
private fun ReviewFieldsCard(
    title: String,
    fields: List<WalletConnectReviewField>
) {
    if (fields.isEmpty()) return

    GreenCard {
        GreenColumn(padding = 0, space = 10) {
            Text(
                text = title,
                style = titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            fields.forEach { field ->
                GreenColumn(padding = 0, space = 2) {
                    Text(
                        text = walletConnectReviewLabel(field.label),
                        style = bodySmall,
                        color = whiteMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = walletConnectReviewValue(field.label, field.value),
                        style = bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewTextCard(
    title: String,
    values: List<String>
) {
    if (values.isEmpty()) return

    GreenCard {
        ReviewTextList(title = title, values = values)
    }
}

@Composable
private fun ActionReviewCard(
    title: String,
    review: WalletConnectSessionActionReview?,
    buttonText: String,
    buttonColor: GreenButtonColor,
    buttonType: GreenButtonType,
    onClick: () -> Unit
) {
    GreenCard {
        GreenColumn(padding = 0, space = 12) {
            Text(
                text = review?.intent?.let(::walletConnectIntentLabel) ?: title,
                style = titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            review?.warnings?.takeIf { it.isNotEmpty() }?.also {
                ReviewTextList(title = "Important", values = it)
            }
            review?.details?.takeIf { it.isNotEmpty() }?.also {
                ReviewFieldList(title = "Details", fields = it)
            }
            review?.info?.takeIf { it.isNotEmpty() }?.also {
                ReviewTextList(title = "Why this matters", values = it)
            }
            GreenButton(
                text = buttonText,
                modifier = Modifier.fillMaxWidth(),
                type = buttonType,
                color = buttonColor,
                size = GreenButtonSize.LARGE,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun ReviewTextList(title: String, values: List<String>) {
    GreenColumn(padding = 0, space = 6) {
        Text(
            text = title,
            style = titleSmall,
            modifier = Modifier.fillMaxWidth()
        )
        values.forEach { value ->
            Text(
                text = walletConnectReviewText(value),
                style = bodyMedium,
                color = whiteMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ReviewFieldList(title: String, fields: List<WalletConnectReviewField>) {
    GreenColumn(padding = 0, space = 8) {
        Text(
            text = title,
            style = titleSmall,
            modifier = Modifier.fillMaxWidth()
        )
        fields.forEach { field ->
            GreenColumn(padding = 0, space = 2) {
                Text(
                    text = walletConnectReviewLabel(field.label),
                    style = bodySmall,
                    color = whiteMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = walletConnectReviewValue(field.label, field.value),
                    style = bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
