# Integrating Custom GDK Fork with options_test Function

This document describes the complete process of integrating a forked GDK repository with a custom `options_test` function into the Green Android app.

## Overview

The goal was to:
1. Use a forked GDK repository (https://github.com/KyrylR/gdk) with a custom `options_test` Rust function
2. Expose this function to Java/Kotlin via the existing SWIG interface
3. Call it from the Android app and verify it works via Logcat

## Prerequisites

- Docker Desktop installed and running
- At least 10GB free disk space for Docker
- Android SDK with NDK installed
- JDK 11+

## Step-by-Step Guide

### Step 1: Update prepare_gdk_clang.sh to Use Your Fork

Modify `gdk/prepare_gdk_clang.sh` to clone your forked repository instead of the official Blockstream one:

```bash
# Original:
git clone https://github.com/Blockstream/gdk.git

# Changed to:
if [ -d gdk ]; then
    cd gdk
    git fetch origin
    git checkout master
    git pull origin master
else
    git clone https://github.com/KyrylR/gdk.git
    cd gdk
    git checkout master
fi
```

**File:** `gdk/prepare_gdk_clang.sh`

### Step 2: Add C++ Function Declaration

Add the `GA_options_test` function declaration to the GDK public header:

**File:** `gdk/gdk/include/gdk.h`

Add before the closing `#ifdef __cplusplus`:

```c
/**
 * Test function to verify custom GDK build
 *
 * :param input: Input JSON string
 * :param output: Destination for the result JSON string.
 *|     Returned string should be freed using `GA_destroy_string`.
 */
GDK_API int GA_options_test(const char* input, char** output);
```

### Step 3: Add C++ Function Implementation

Add the implementation that calls the Rust `GDKRUST_call` function:

**File:** `gdk/gdk/src/utils.cpp`

Add after `GA_generate_mnemonic_12`:

```cpp
extern "C" int GA_options_test(const char* input, char** output)
{
    if (!output) {
        return GA_ERROR;
    }
    *output = nullptr;
    try {
        char* rust_output = nullptr;
        int ret = GDKRUST_call("options_test", input ? input : "{}", &rust_output);
        if (ret == GA_OK && rust_output) {
            *output = strdup(rust_output);
            GDKRUST_destroy_string(rust_output);
        }
        return ret;
    } catch (const std::exception& e) {
        return GA_ERROR;
    }
}
```

### Step 4: Add SWIG Interface Wrapper

Add the SWIG wrapper to expose the function to Java:

**File:** `gdk/gdk/src/swig_java/swig_green_gdk.i`

Add after `%returns_string(GA_generate_mnemonic_12)`:

```swig
%returns_string(GA_options_test)
```

### Step 5: Build GDK Using Docker

The Docker-based build is the most reliable method, especially on macOS:

```bash
# Navigate to the GDK directory
cd gdk/gdk

# Build the Docker image
DOCKER_BUILDKIT=1 docker build --platform linux/amd64 . -t gdk-android-builder -f docker/android/Dockerfile

# Run the build inside Docker
docker run --platform linux/amd64 -v "$PWD:/root/gdk" gdk-android-builder \
    ./tools/build.sh --install /root/gdk/gdk-android-jni --ndk arm64-v8a \
    --external-deps-dir /prebuild/android-arm64-v8a
```

### Step 6: Copy Built Artifacts

Copy the generated files to the correct locations:

```bash
cd /path/to/green_android/gdk

# Create directories
mkdir -p src/main/jniLibs/arm64-v8a
mkdir -p src/main/java/com/blockstream/green_gdk
mkdir -p src/main/java/com/blockstream/libwally

# Copy native library
cp gdk/gdk-android-jni/lib/arm64-v8a/libgreen_gdk_java.so src/main/jniLibs/arm64-v8a/

# Copy Java wrappers
cp gdk/gdk-android-jni/share/java/com/blockstream/green_gdk/GDK.java src/main/java/com/blockstream/green_gdk/
cp gdk/gdk-android-jni/share/java/com/blockstream/libwally/Wally.java src/main/java/com/blockstream/libwally/
```

### Step 7: Add GDK Dependency to Android App

Add direct dependency on the `gdk` module in `androidApp/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":base-gms"))
    implementation(project(":compose"))
    implementation(project(":gdk")) // For direct GDK access (options_test)
    // ... rest of dependencies
}
```

### Step 8: Add Test Call in GreenActivity

Add a test call to verify the function works:

**File:** `androidApp/src/main/java/com/blockstream/green/GreenActivity.kt`

Add import:
```kotlin
import com.blockstream.green_gdk.GDK
```

Add in `onCreate()` after `enableEdgeToEdge()`:
```kotlin
// Test custom GDK fork - call options_test and log result
try {
    val optionsTestResult = GDK.options_test("{}")
    Log.d("GDK_OPTIONS_TEST", "===========================================")
    Log.d("GDK_OPTIONS_TEST", "options_test result: $optionsTestResult")
    Log.d("GDK_OPTIONS_TEST", "===========================================")
} catch (e: Exception) {
    Log.e("GDK_OPTIONS_TEST", "options_test failed", e)
}
```

### Step 9: Build and Run the App

```bash
# Build the app
./gradlew :androidApp:assembleDevelopmentDebug

# Or install directly (requires connected device/emulator)
./gradlew :androidApp:installDevelopmentDebug
```

### Step 10: Verify in Logcat

After launching the app, check Logcat with filter `GDK_OPTIONS_TEST`:

```
===========================================
options_test result: {"status":"ok","message":"options_test executed successfully"}
===========================================
```

## Files Changed Summary

### In green_android project:

| File | Change |
|------|--------|
| `gdk/prepare_gdk_clang.sh` | Updated to clone forked GDK repo |
| `androidApp/build.gradle.kts` | Added `implementation(project(":gdk"))` |
| `androidApp/src/main/java/.../GreenActivity.kt` | Added test call to `GDK.options_test()` |

### In forked GDK (gdk/gdk/):

| File | Change |
|------|--------|
| `include/gdk.h` | Added `GA_options_test` declaration |
| `src/utils.cpp` | Added `GA_options_test` implementation |
| `src/swig_java/swig_green_gdk.i` | Added `%returns_string(GA_options_test)` |

### Generated files (copied after build):

| File | Location |
|------|----------|
| `libgreen_gdk_java.so` | `gdk/src/main/jniLibs/arm64-v8a/` |
| `GDK.java` | `gdk/src/main/java/com/blockstream/green_gdk/` |
| `Wally.java` | `gdk/src/main/java/com/blockstream/libwally/` |

## How It Works

1. **Rust Layer**: The `options_test` function is implemented in `gdk_rust/gdk_rust/src/lib.rs` in the `handle_call` function. It's a session-less method that returns a JSON response.

2. **C++ Layer**: `GA_options_test` in `utils.cpp` calls `GDKRUST_call("options_test", ...)` which routes to the Rust implementation.

3. **SWIG Layer**: The `%returns_string(GA_options_test)` macro in the SWIG interface file generates the JNI bindings.

4. **Java Layer**: `GDK.java` is auto-generated by SWIG and includes `public final static native String options_test(String jarg1)`.

5. **Kotlin Layer**: The Android app directly calls `GDK.options_test("{}")` and logs the result.

## Troubleshooting

### Docker Build Issues

- **No space left on device**: Run `docker system prune -a -f` and restart Docker
- **Platform mismatch on Apple Silicon**: Always use `--platform linux/amd64`

### Gradle Build Issues

- **Unresolved reference 'green_gdk'**: Ensure `implementation(project(":gdk"))` is added to androidApp's dependencies
- **Cache issues**: Run with `--rerun-tasks` flag or clean with `./gradlew clean`

### Native Library Issues

- **UnsatisfiedLinkError**: Verify `libgreen_gdk_java.so` is in the correct jniLibs folder
- **Function not found**: Ensure all three layers (C++, SWIG, generated Java) are properly updated and rebuilt

## Notes

- The Docker build uses pre-built dependencies from `/prebuild/android-arm64-v8a` inside the container
- Only `arm64-v8a` architecture was built in this guide; for other architectures, run the build command with different `--ndk` values
- The `gdk/gdk` folder contains the cloned fork and should not be committed to the main project repository

