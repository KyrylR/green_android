package com.blockstream.domain

import com.blockstream.common.walletabi.NoopWalletAbiImpactPreviewer
import com.blockstream.common.walletabi.WalletAbiEsploraHttpClient
import com.blockstream.common.walletabi.WalletAbiExecutionContextResolving
import com.blockstream.common.walletabi.WalletAbiExecutionContextResolver
import com.blockstream.common.walletabi.WalletAbiImpactPreviewing
import com.blockstream.common.walletabi.WalletAbiProcessor
import com.blockstream.common.walletabi.WalletAbiProviderRunner
import com.blockstream.common.walletabi.WalletAbiResultPresenter
import com.blockstream.common.walletabi.WalletAbiSessionCoordinator
import com.blockstream.common.walletabi.WalletAbiTransactionStore
import com.blockstream.data.json.DefaultJson
import com.blockstream.domain.account.accountModule
import com.blockstream.domain.banner.GetBannerUseCase
import com.blockstream.domain.bitcoinpricehistory.ObserveBitcoinPriceHistory
import com.blockstream.domain.hardware.VerifyAddressUseCase
import com.blockstream.domain.lightning.LightningNodeIdUseCase
import com.blockstream.domain.meld.CreateCryptoQuoteUseCase
import com.blockstream.domain.meld.CreateCryptoWidgetUseCase
import com.blockstream.domain.meld.DefaultValuesUseCase
import com.blockstream.domain.meld.GetLastSuccessfulPurchaseExchange
import com.blockstream.domain.meld.MeldUseCase
import com.blockstream.domain.meld.meldDomainModule
import com.blockstream.domain.notifications.notificationsDomainModule
import com.blockstream.domain.promo.GetPromoUseCase
import com.blockstream.domain.receive.receiveModule
import com.blockstream.domain.send.sendModule
import com.blockstream.domain.swap.swapModule
import com.blockstream.domain.wallet.walletModule
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module

val domainModule = module {
    includes(notificationsDomainModule)
    includes(meldDomainModule)
    includes(swapModule)
    includes(sendModule)
    includes(receiveModule)
    includes(walletModule)
    includes(accountModule)
    single {
        LightningNodeIdUseCase(get())
    }
    single {
        VerifyAddressUseCase(get())
    }
    single {
        CreateCryptoQuoteUseCase(get())
    }
    single {
        CreateCryptoWidgetUseCase(get())
    }
    single {
        DefaultValuesUseCase(get())
    }
    single {
        MeldUseCase(get(), get(), get())
    }
    factory {
        ObserveBitcoinPriceHistory(get())
    }
    factory {
        GetLastSuccessfulPurchaseExchange(get())
    }
    single {
        GetBannerUseCase()
    }
    single {
        GetPromoUseCase(get(), get(), get())
    }
    single(named("walletAbiJson")) {
        Json(DefaultJson) {
            explicitNulls = false
        }
    }
    single<WalletAbiExecutionContextResolving> {
        WalletAbiExecutionContextResolver(get())
    }
    single {
        WalletAbiEsploraHttpClient(get())
    }
    single {
        WalletAbiProviderRunner(
            json = get(named("walletAbiJson")),
            esploraHttpClient = get(),
        )
    }
    single {
        WalletAbiProcessor(
            json = get(named("walletAbiJson")),
            executionContextResolver = get(),
            providerRunner = get(),
        )
    }
    single<WalletAbiImpactPreviewing> {
        NoopWalletAbiImpactPreviewer()
    }
    single {
        WalletAbiResultPresenter()
    }
    single {
        WalletAbiTransactionStore(
            database = get(),
            json = get(named("walletAbiJson")),
        )
    }
    single {
        WalletAbiSessionCoordinator(
            json = get(named("walletAbiJson")),
            executionContextResolver = get(),
            walletAbiImpactPreviewer = get(),
            walletAbiProcessor = get(),
            walletAbiResultPresenter = get(),
            walletAbiProviderRunner = get(),
            walletSettingsManager = get(),
            walletConnectBridge = get(),
            walletAbiTransactionStore = get(),
        )
    }
}
