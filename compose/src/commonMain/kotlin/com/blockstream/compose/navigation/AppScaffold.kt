package com.blockstream.compose.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import blockstream_green.common.generated.resources.Res
import blockstream_green.common.generated.resources.id_home
import blockstream_green.common.generated.resources.id_security
import blockstream_green.common.generated.resources.id_settings
import blockstream_green.common.generated.resources.id_transact
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsDownUp
import com.adamglin.phosphoricons.regular.CurrencyBtc
import com.adamglin.phosphoricons.regular.Gear
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.ShieldCheck
import com.blockstream.compose.components.GreenTopAppBar
import com.blockstream.compose.models.MainViewModel
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.HandleSideEffect
import com.blockstream.compose.walletconnect.walletConnectApprovalTitle
import com.blockstream.compose.walletconnect.walletConnectChainLabel
import com.blockstream.compose.walletconnect.walletConnectMethodLabel
import com.blockstream.compose.walletconnect.walletConnectReviewLabel
import com.blockstream.compose.walletconnect.walletConnectReviewText
import com.blockstream.compose.walletconnect.walletConnectReviewValue
import com.blockstream.compose.walletconnect.walletConnectRiskLabel
import com.blockstream.data.walletconnect.WalletConnectApproval
import com.blockstream.data.walletconnect.WalletConnectReviewField
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

sealed class TopLevelRoute(val name: StringResource, val icon: ImageVector, val klass: KClass<*>) {
    data object Home : TopLevelRoute(
        name = Res.string.id_home,
        icon = PhosphorIcons.Regular.House,
        klass = NavigateDestinations.WalletOverview::class
    )

    data object Transact :
        TopLevelRoute(
            name = Res.string.id_transact,
            icon = PhosphorIcons.Regular.ArrowsDownUp,
            klass = NavigateDestinations.Transact::class
        )

    data object Security :
        TopLevelRoute(
            name = Res.string.id_security,
            icon = PhosphorIcons.Regular.ShieldCheck,
            klass = NavigateDestinations.Security::class
        )

    data object Settings : TopLevelRoute(
        name = Res.string.id_settings,
        icon = PhosphorIcons.Regular.Gear,
        klass = NavigateDestinations.WalletSettings::class
    )
}

val TopLevelRoutes = listOf(
    TopLevelRoute.Home,
    TopLevelRoute.Transact,
    TopLevelRoute.Security,
    TopLevelRoute.Settings
)

@Composable
fun AppScaffold(
    navData: NavData = NavData(),
    snackbarHostState: SnackbarHostState? = null,
    mainViewModel: MainViewModel,
    navigate: (destination: NavigateDestination) -> Unit = {},
    goBack: () -> Unit = { },
    content: @Composable (PaddingValues) -> Unit
) {
    val navigator = LocalNavigator.current
    val navBackStackEntry by navigator.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentBackStack by navigator.currentBackStack.collectAsStateWithLifecycle()
    val walletConnectApprovals by mainViewModel.walletConnectApprovals.collectAsStateWithLifecycle()
    val walletConnectLastError by mainViewModel.walletConnectLastError.collectAsStateWithLifecycle()
    var showNavigationBar by mutableStateOf(true)

    navigator.addOnDestinationChangedListener { _, destination, _ ->
        showNavigationBar = destination.let {
            TopLevelRoutes.any { topLevelRoute ->
                it.hasRoute(topLevelRoute.klass)
            }
        }
    }

    val greenWallet by remember {
        derivedStateOf {
            currentBackStack.find {
                it.destination.hasRoute<NavigateDestinations.WalletOverview>()
            }?.toRoute<NavigateDestinations.WalletOverview>()?.greenWallet
        }
    }

    // Clear tre backstack when navigating to a new wallet
    LaunchedEffect(greenWallet) {
        // Be sure there is a backstack
        // Fix: Exception java.lang.IllegalStateException: You must call setGraph() before calling getGraph()
        if (navigator.currentBackStackEntry != null) {
            navigator.clearBackStack<NavigateDestinations.WalletOverview>()
            navigator.clearBackStack<NavigateDestinations.Transact>()
            navigator.clearBackStack<NavigateDestinations.Security>()
            navigator.clearBackStack<NavigateDestinations.WalletSettings>()
        }
    }

    // Handle side effects from MainViewModel like navigating from handled intent
    HandleSideEffect(mainViewModel)

    // val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollBehavior = null

    Scaffold(
        // modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GreenTopAppBar(
                hasBackStack = currentBackStack.size > 2,
                scrollBehavior = scrollBehavior,
                navData = navData,
                goBack = goBack
            )
        }, snackbarHost = {
            snackbarHostState?.also {
                SnackbarHost(
                    // provide ime padding to show the snackbar above the keyboard
                    modifier = Modifier.imePadding(), hostState = it
                )
            }
        }) { innerPadding ->

        Box(modifier = Modifier.fillMaxSize()) {

            // Content
            content(innerPadding)

            // Bottom NavigationBar
            greenWallet?.also { greenWallet ->
                AnimatedVisibility(
                    visible = showNavigationBar && navData.showBottomNavigation && navData.isVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
                ) {
                    NavigationBar {
                        TopLevelRoutes.forEach { topLevelRoute ->
                            NavigationBarItem(
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (topLevelRoute is TopLevelRoute.Security && navData.showBadge) {
                                                Badge()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = topLevelRoute.icon,
                                            contentDescription = topLevelRoute.name.key
                                        )
                                    }
                                },
                                label = {
                                    Text(stringResource(topLevelRoute.name), style = bodyMedium)
                                },
                                selected = currentDestination?.hierarchy?.any {
                                    it.hasRoute(
                                        when (topLevelRoute) {
                                            TopLevelRoute.Home -> NavigateDestinations.WalletOverview::class
                                            TopLevelRoute.Transact -> NavigateDestinations.Transact::class
                                            TopLevelRoute.Security -> NavigateDestinations.Security::class
                                            TopLevelRoute.Settings -> NavigateDestinations.WalletSettings::class
                                        }
                                    )
                                } == true,
                                onClick = {
                                    val destination = when (topLevelRoute) {
                                        TopLevelRoute.Home -> NavigateDestinations.WalletOverview(
                                            greenWallet = greenWallet,
                                            isBottomNav = true
                                        )

                                        TopLevelRoute.Transact -> NavigateDestinations.Transact(
                                            greenWallet = greenWallet
                                        )

                                        TopLevelRoute.Security -> NavigateDestinations.Security(
                                            greenWallet = greenWallet
                                        )

                                        TopLevelRoute.Settings -> NavigateDestinations.WalletSettings(
                                            greenWallet = greenWallet
                                        )
                                    }

                                    navigate(destination)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    walletConnectApprovals.firstOrNull()?.also { approval ->
        WalletConnectApprovalDialog(
            approval = approval,
            lastError = walletConnectLastError,
            onApprove = { mainViewModel.approveWalletConnect(approval.id) },
            onReject = { mainViewModel.rejectWalletConnect(approval.id) }
        )
    }
}

@Composable
private fun WalletConnectApprovalDialog(
    approval: WalletConnectApproval,
    lastError: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        icon = {
            Icon(
                imageVector = PhosphorIcons.Regular.CurrencyBtc,
                contentDescription = null
            )
        },
        title = {
            Text(
                walletConnectApprovalTitle(
                    intent = approval.review.intent,
                    method = approval.review.method,
                    fallback = approval.review.title
                )
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                approval.review.requesterName?.also {
                    WalletConnectDialogField(label = "App", value = it)
                }
                walletConnectChainLabel(approval.review.chainId)?.also {
                    WalletConnectDialogField(label = "Network", value = it)
                }
                walletConnectMethodLabel(approval.review.method)?.also {
                    WalletConnectDialogField(label = "Action", value = it)
                }
                walletConnectRiskLabel(approval.review.verifyRisk)?.also {
                    WalletConnectDialogField(label = "Verification", value = it)
                }

                WalletConnectTextSection(title = "Check before approving", values = approval.review.warnings)
                WalletConnectFieldSection(title = "Request details", fields = approval.review.details)
                WalletConnectTextSection(title = "What this means", values = approval.review.info)

                if (!approval.review.canApprove) {
                    WalletConnectTextSection(
                        title = "Cannot approve",
                        values = listOfNotNull(approval.review.approveUnavailableReason)
                    )
                }
                lastError?.takeIf { it.isNotBlank() }?.also {
                    WalletConnectTextSection(title = "Latest error", values = listOf(it))
                }
            }
        },
        confirmButton = {
            if (approval.review.canApprove) {
                TextButton(onClick = onApprove) {
                    Text("Approve")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Reject")
            }
        }
    )
}

@Composable
private fun WalletConnectDialogField(label: String, value: String) {
    Column {
        Text(label, style = bodyMedium, color = whiteMedium)
        Text(value, style = bodyMedium)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WalletConnectTextSection(title: String, values: List<String>) {
    if (values.isEmpty()) return

    Spacer(Modifier.height(12.dp))
    Text(title, style = bodyMedium)
    Spacer(Modifier.height(4.dp))
    values.forEach { value ->
        Text(walletConnectReviewText(value), style = bodyMedium, color = whiteMedium)
    }
}

@Composable
private fun WalletConnectFieldSection(title: String, fields: List<WalletConnectReviewField>) {
    if (fields.isEmpty()) return

    Spacer(Modifier.height(12.dp))
    Text(title, style = bodyMedium)
    Spacer(Modifier.height(4.dp))
    fields.forEach { field ->
        WalletConnectDialogField(
            label = walletConnectReviewLabel(field.label),
            value = walletConnectReviewValue(field.label, field.value)
        )
    }
}
