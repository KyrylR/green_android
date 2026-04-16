package com.blockstream.domain

import com.blockstream.common.managers.SessionManager
import com.blockstream.common.walletabi.WalletAbiExecutionContextResolving
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class DomainModuleWalletAbiTest {

    @Test
    fun `domain module resolves WalletAbiExecutionContextResolving`() {
        val koinApplication = org.koin.core.context.startKoin {
            modules(
                module {
                    single<SessionManager> { mockk() }
                },
                domainModule,
            )
        }

        try {
            assertNotNull(
                koinApplication.koin.get<WalletAbiExecutionContextResolving>(),
            )
        } finally {
            stopKoin()
        }
    }
}
