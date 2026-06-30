import java.io.File
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.app.cash.sqldelight)
    alias(libs.plugins.nativeCocoapods)
}

val walletConnectNativeChecksums = mapOf(
    "arm64-v8a" to "6a78e1a1aaaffe4867e1266a9e3db8d673663094708c75972371f0aac73ffa16",
    "armeabi-v7a" to "1deabf7386428338ae7bd1ea203bb2455739ffb4175604015096b0120cb0304d",
    "x86" to "cc5625e21051d02b252b7db71f0a4f522cb6699e9b51f6693a6058c75c6c255a",
    "x86_64" to "0e6923a89d4725eebfe8f7f4ed350dcdbc3869704510985c799486729b7186cb",
)

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

val verifyWalletConnectNativeBinaries by tasks.registering {
    group = "verification"
    description = "Verifies pinned WalletConnect native library provenance and checksums."

    inputs.file(layout.projectDirectory.file("src/androidMain/jniLibs/wc_uniffi.provenance.json"))
    walletConnectNativeChecksums.keys.forEach { abi ->
        inputs.file(layout.projectDirectory.file("src/androidMain/jniLibs/$abi/libwc_uniffi.so"))
    }

    doLast {
        val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs").asFile
        val expectedFiles = walletConnectNativeChecksums.mapKeys { (abi, _) ->
            jniLibsDir.resolve("$abi/libwc_uniffi.so").canonicalFile
        }

        expectedFiles.forEach { (library, expectedSha256) ->
            check(library.isFile) { "Missing WalletConnect native library: ${library.path}" }

            val actualSha256 = sha256Hex(library)
            check(actualSha256 == expectedSha256) {
                "WalletConnect native library checksum mismatch for ${library.path}: " +
                    "expected $expectedSha256, got $actualSha256"
            }
        }

        val unexpectedLibraries = jniLibsDir.walkTopDown()
            .filter { it.isFile && it.name == "libwc_uniffi.so" }
            .map { it.canonicalFile }
            .filterNot { it in expectedFiles.keys }
            .toList()

        check(unexpectedLibraries.isEmpty()) {
            "Unpinned WalletConnect native libraries found: " +
                unexpectedLibraries.joinToString { it.path }
        }
    }
}

tasks.configureEach {
    if (
        name == "check" ||
        name == "preBuild" ||
        (name.startsWith("preAndroid") && name.endsWith("Build"))
    ) {
        dependsOn(verifyWalletConnectNativeBinaries)
    }
}

sqldelight {
    databases {
        create("WalletDB") {
            packageName.set("com.blockstream.data.database.wallet")
            srcDirs.setFrom("src/commonMain/database_wallet")
        }
        create("LocalDB") {
            packageName.set("com.blockstream.data.database.local")
            srcDirs.setFrom("src/commonMain/database_local")
        }
    }
    linkSqlite.set(true)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-Xexplicit-backing-fields")
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }

    jvmToolchain(libs.versions.jvm.get().toInt())

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "com.blockstream.green.data"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "/META-INF/LICENSE.md"
                excludes += "/META-INF/LICENSE-notice.md"
            }
        }
    }

    jvm()

    val xcf = XCFramework()
    listOf(
        iosArm64(), iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "common"
            xcf.add(this)
            isStatic = true
        }

        val platform = when (it.targetName) {
            "iosX64" -> "ios_simulator_x86"
            "iosSimulatorArm64" -> "ios_simulator_arm64"
            "iosArm64" -> "ios_arm64"
            else -> error("Unsupported target $name")
        }

        it.compilations["main"].cinterops {
            create("gdkCInterop") {
                defFile(project.file("src/nativeInterop/cinterop/gdk.def"))
                includeDirs(project.file("src/include"), project.file("src/libs/$platform"))
            }
        }
    }

    cocoapods {
        version = "2.0"
        ios.deploymentTarget = "15.3"

        pod("Countly") {
            source = git("https://github.com/angelix/countly-sdk-ios") {
                commit = "1892410d13fceccd7cf91f803f06f110efc215b3"
            }

            // Support for Objective-C headers with @import directives
            // https://kotlinlang.org/docs/native-cocoapods-libraries.html#support-for-objective-c-headers-with-import-directives
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
    }

    sourceSets {

        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlin.io.encoding.ExperimentalEncodingApi")
                optIn("kotlin.experimental.ExperimentalObjCName")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }

        commonMain {
            dependencies {
                /**  --- Modules  --- */
                api(project(":utils"))
                api(project(":network"))
                api(project(":jade"))

                /**  --- Compose -------------------------------------------------------------------- */
                api(libs.lifecycle.viewmodel.compose)
                /** --------------------------------------------------------------------------------- */

                /**  --- Kotlin  --- */
                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.serialization.cbor)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.atomicfu)

                /**  --- Blockstream  --- */
                api(libs.blockstream.lwk)
                api(libs.blockstream.glsdk)

                /**  --- Utils  --- */
                api(libs.sqldelight.coroutines.extensions)
                api(libs.stately.concurrent.collections)
                api(libs.kotlin.retry)
                api(libs.ksoup.entites) // html entities
                api(libs.filekit.core)
                api(libs.filekit.dialogs)
                api(libs.okio) // Filesystem
                api(libs.kable.core)
                api(libs.kotlincrypto.hash.md)
                api(libs.kotlincrypto.hash.sha2)
                api(libs.multiplatform.settings)
                api(libs.multiplatform.settings.no.arg)
                api(libs.multiplatform.settings.make.observable)
                api(libs.multiplatform.settings.coroutines)
                api(libs.tuulbox.coroutines)
                api(libs.uri.kmp)
                api(libs.state.keeper)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }


        jvmMain.dependencies {
            api(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)
        }

        androidMain.dependencies {
            implementation(project(":gdk"))
            implementation(libs.sqldelight.android.driver)
            api(libs.koin.android)
            api(libs.androidx.biometric)

            api(libs.androidx.preference.ktx)

            /**  --- Breez FDroid ----------------------------------------------------------------------- */
            // Temp fix for FDroid breez dependencies
            // api(libs.breez.sdk.android.get().toString()) { exclude(group = "net.java.dev.jna", module = "jna") }
            implementation("${libs.jna.get()}@aar")
            /** ----------------------------------------------------------------------------------------- */
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.runner)
                implementation(libs.androidx.core)
                implementation(libs.androidx.junit)
                implementation(libs.mockk)
            }
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.mockk)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

task("fetchIosBinaries") {
    doFirst {
        val exists = project.file("src/include").exists() && project.file("src/libs").exists()
        if (!exists) {
            providers.exec {
                workingDir = project.projectDir
                commandLine("./fetch_ios_binaries.sh")
            }.result.get()
        } else {
            print("-- Skipped --")
        }
    }
    outputs.upToDateWhen { false }
}

tasks.configureEach {
    if (name.contains("cinterop")) {
        dependsOn("fetchIosBinaries")
    }
}

tasks.getByName("clean").doFirst {
    delete(project.file("src/include"))
    delete(project.file("src/libs"))
}
