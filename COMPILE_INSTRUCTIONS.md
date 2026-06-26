# PebbleNotificationCenter- Compilation Instructions

This document explains how to compile both the **PebbleNotificationCenter2 watch app** and the **PebbleNotificationCenter2 companion app**

---

## Project Overview

The PebbleNotificationCenter2 project contains two separate applications:

| Application | Location | Language | Purpose |
| ------------- | ---------- | ---------- | -------- |
| **Watch App** | `watch/` | C | Pebble smartwatch application |
| **Companion App** | `mobile/` | Kotlin/Java | Android smartphone app (this document) |

---

## Compiling the Companion App

### Prerequisites

#### 1. Development Environment

- **Java Development Kit (JDK)** version 21 or newer

  ```bash
  # Verify installation
  java -version
  ```

- **Git** with Git LFS support

  ```bash
  # Verify Git LFS is installed
  git lfs version
  ```

- **Android SDK Command Line Tools**
  - Download from: <https://developer.android.com/studio#command-tools>
  - Set `ANDROID_HOME` environment variable to the **root SDK directory** (the folder that contains `ndk-bundle`, `platform-tools`, `cmdline-tools`, `platforms`, etc.)
  - Set `PATH` to include `ANDROID_HOME/cmdline-tools/latest/bin`

  **Example on Debian/Ubuntu** (if installed via `apt`):
  
  ```bash
  ANDROID_HOME=/usr/lib/android-sdk
  ```

- **Gradle** (9.5.1) — managed via the Gradle Wrapper (no manual installation needed)

#### 2. Android SDK Components

Install the required SDK platforms and build tools:

```bash
# Set up environment variables
export ANDROID_HOME=/path/to/your/android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# Install required platforms
sdkmanager "platforms;android-33" "platforms;android-34"

# Install build tools
sdkmanager "build-tools;34.0.0"

# (Optional) Install additional useful tools
sdkmanager "platform-tools"  # For ADB
```

#### 3. Git Submodules & LFS

The project contains Git submodules and Git LFS files that must be initialized:

```bash
# Clone the repository with submodules
git clone --recursive https://github.com/matejdro/PebbleNotificationCenter2.git

# Navigate to the project root
cd PebbleNotificationCenter2

# Initialize Git LFS
sudo apt install git-lfs  # Ubuntu/Debian
brew install git-lfs     # macOS
# Then initialize LFS
sudo git lfs install
git lfs fetch

# Initialize and update submodules
git submodule update --init --recursive
```

---

### Linting and error detection

Any indentation error MUST BE ignored.

```bash
cd PebbleNotificationCenter2/mobile

# Run detekt
./gradlew runDebugDetekt
```

### Building from Command Line

#### Debug Build

```bash
cd PebbleNotificationCenter2/mobile

# Clean and assemble debug build
./gradlew clean assembleDebug

# View the output APK
./app/build/outputs/apk/debug/app-debug.apk
```

#### Release Build

```bash
cd PebbleNotificationCenter2/mobile

# Assemble release build
./gradlew assembleRelease

# View the output APK
./app/build/outputs/apk/release/app-release.apk
```

#### Individual Tasks

```bash
# Sync Gradle and fetch dependencies
./gradlew --refresh-dependencies

# Check the project structure
./gradlew tasks

# Run lint checks
./gradlew lintRelease

# Run code quality checks (Detekt)
./gradlew runDebugDetekt

# Generate project reports
./gradlew reports
```

---

### Building from Android Studio

#### Opening the Project

1. Launch Android Studio
2. Select **Open** → Navigate to `/home/leo/PEBBLE/PebbleNotificationCenter2/mobile/`
3. Click **OK**

#### First Build

1. **Sync Gradle** — Android Studio will download all dependencies (this may take several minutes)
2. **Build → Make Project** (or press `Ctrl+F9` / `Cmd+9`)
3. If any errors appear, try:
   - **File → Sync Project with Gradle Files**
   - **File → Invalidate Caches → Invalidate and Restart**

#### Debugging

1. Connect an Android device via USB or start an emulator
2. Enable **USB Debugging** on the device
3. Verify the device is listed in **Device Manager** (View → Device Manager)
4. Run **Build → Run** (or press `Ctrl+Shift+F10` / `Cmd+R`)
5. The app will install and launch automatically

---

### Project Structure

```bash
mobile/
├── app/                              # Main application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/matejdro/pebblenotificationcenter2/  # Application code
│   │   │   ├── resources/                                   # Android resources
│   │   └── androidTest/                                     # Instrumentation tests
│   └── build.gradle.kts                                     # Module-level build configuration
├── app-screenshot-tests/               # Paparazzi screenshot tests
├── common/                            # Pure Kotlin shared code
├── common-android/                    # Android-specific shared code
├── common-compose/                    # Jetpack Compose shared UI
├── common-navigation/                 # Navigation infrastructure
├── bluetooth/api/                    # Bluetooth layer API
├── bluetooth/data/                    # Bluetooth layer data
├── history/api/                       # Notification history API
├── history/data/                      # Notification history data
├── home/ui/                           # Home screen UI
├── notification/api/                  # Core notification API
├── notification/data/                 # Core notification data
├── rules/api/                         # Rule engine API
├── rules/data/                        # Rule engine data
├── rules/ui/                          # Rule engine UI
├── shared-resources/                  # Shared resources
├── tasker/api/                        # Tasker integration API
├── tasker/data/                       # Tasker integration data
├── tasker/ui/                         # Tasker integration UI
├── tools/ui/                          # Utility tools
├── config/                            # Build configuration
│   └── libs.toml                      # Version catalog (dependency versions)
└── build.gradle.kts                   # Root build script
```

---

### Dependency Management

The project uses **Gradle Version Catalog** (libs.toml) for centralized dependency management:

- All dependencies are listed in `mobile/config/libs.toml`
- The root `build.gradle.kts` uses `libs.<dependency>` references
- Updating versions is done via `./gradlew versionCatalogUpdate`

#### Additional dependencies

Folder `${HOME}/PEBBLE/PebbleNotificationCenter2/PebbleCommons` contains additional function used by the project.

---

### Troubleshooting

#### "Could not resolve all files for configuration ':app:releaseRuntimeClasspath'"

The Gradle cache may be corrupted:

```bash
cd PebbleNotificationCenter2/mobile
./gradlew --refresh-dependencies
```

#### "SDK location not found"

Set the ANDROID_HOME environment variable:

```bash
export ANDROID_HOME=/path/to/your/android/sdk
```

#### "Java version '21' isn't supported"

Upgrade to Java 21 or newer:

```bash
# Ubuntu/Debian
sudo apt install openjdk-21-jdk

# macOS
brew install openjdk@21
```

#### "Project sync took too long" or "Gradle is taking a long time"

This is normal on first build due to dependency resolution. Wait a few minutes.

#### "Git LFS: Smudge filter failed"

LFS files weren't properly fetched:

```bash
git lfs pull
```

---

## Compiling the Watch App

The watch app is written in C and compiled for three Pebble platforms: **basalt** (color, 2023+), **diorite** (black & white, 2018-2023), and **emery** (color, 2015-2018). The build system uses the Pebble SDK's custom toolchain.

### Prerequisites

- **Pebble SDK** installed (currently v4.9.169)
  - SDK path: `$HOME/.local/share/pebble-sdk/SDKs/current`
  - Verify installation:

    ```bash
    ls $HOME/.local/share/pebble-sdk/SDKs/current/sdk-core/pebble
    # Expected output: basalt, diorite, emery
    ```

- **Cross-compilation toolchain** (provided by Pebble SDK)
  - ARM Cortex-M3 compiler: `arm-none-eabi-gcc`
  - Located in: `$HOME/.local/share/pebble-sdk/SDKs/current/toolchain/`

### Build Process

The build generates a **.pbw (Pebble Watch)** bundle containing the compiled binaries for all three platforms, along with a unified `watch.pbw` file ready to be flashed to the device.

#### Step 1: Clean the Build

```bash
cd PebbleNotificationCenter2/watch

# Remove previous build artifacts
rm -rf build/ compile.log
```

#### Step 2: Configure and Compile

```bash
cd PebbleNotificationCenter2/watch

# Run the Pebble SDK build system
pebble build -v

# To create a log file to be used for debugging
pebble build -v 2>&1 | ansi2txt | tee compile.log
```

This executes the Pebble SDK's build system with the following phases:

1. **Configuration** — Verifies the Pebble SDK, cross-compiler, and target platforms are correctly installed
2. **Resource Compilation** — Converts bitmap resources (`.png`) into the Pebble SDK's internal format (`.reso`)
   - Generates indexed PNG data for color watches (basalt, emery)
   - Generates grayscale PNG data for monochrome watches (diorite)
3. **Emery Build** — Compiles and links for the Emery platform
4. **Diorite Build** — Compiles and links for the Diorite platform
5. **Basalt Build** — Compiles and links for the Basalt platform
6. **Bundle Creation** — Packages all platform binaries into `watch.pbw`

#### Step 3: Verify the Output

```bash
cd PebbleNotificationCenter2/watch/build

# Check the bundle file
ls -lh watch.pbw

# View individual platform binaries
ls -lh basalt/pebble-app.bin diorite/pebble-app.bin emery/pebble-app.bin
```

Expected output:

```bash
-rw-r--r-- 1 leo leo  14K Jun 19 09:24 basalt/pebble-app.bin
-rw-r--r-- 1 leo leo  14K Jun 19 09:24 diorite/pebble-app.bin
-rw-r--r-- 1 leo leo  14K Jun 19 09:24 emery/pebble-app.bin
-rw-r--r-- 1 leo leo 18K Jun 19 09:24 watch.pbw
```

### Memory Usage

The watch app is compiled with size optimization (`-Os` flag) and memory reporting is available per platform:

| Platform | RAM Usage | Heap Available |
| ---------- | ----------- | ---------------- |
| **Basalt** (color) | 20,072 bytes / 64 KB | ~45 KB |
| **Diorite** (B&W) | 20,056 bytes / 64 KB | ~45 KB |
| **Emery** (color) | 20,076 bytes / 128 KB | ~110 KB |

The memory footprint remains well within the constraints of the Pebble hardware.

### Build Configuration

The build is controlled by the `wscript` file, which defines:

- **Source files** — All `.c` files under `src/`
- **Resources** — All `.png` files under `resources/`
- **JavaScript** — Any `src/pkjs/**/*.js` and `src/common/**/*.js` files
- **Compilation flags** — `-std=c99`, `-mcpu=cortex-m3`, `-mthumb`, `-Os` (size optimization), `-Wall -Wextra -Werror` (strict warnings)
- **Platform flags** — Vary by SDK (e.g., `-DPBL_COLOR` for color screens, `-DPBL_BW` for monochrome)

### Platform-Specific Details

| Platform | Display | SDK Flags |
| ---------- | --------- | ----------- |
| **Basalt** | 144×168 color | `-DPBL_COLOR -DPBL_SDK_FROZEN -DPBL_SDK_3` |
| **Diorite** | 144×168 B&W | `-DPBL_BW -DPBL_SDK_FROZEN -DPBL_SDK_3` |
| **Emery** | 200×228 color | `-DPBL_COLOR -DPBL_SDK_3` |

### Troubleshooting

#### "Configure failed"

Verify the Pebble SDK is correctly installed:

```bash
ls $HOME/.local/share/pebble-sdk/SDKs/current/sdk-core/pebble
```

#### "Cannot find arm-none-eabi-gcc"

Ensure the Pebble SDK toolchain is in your PATH, or set:

```bash
export PATH="$HOME/.local/share/pebble-sdk/SDKs/current/toolchain/bin:$PATH"
```

#### "Resource compilation failed"

Verify PNG files are valid and not corrupted. Check the `resources/` folder for the expected bitmaps:

- `busy.png` - Busy indicator
- `disconnected.png` - Disconnected state
- `error.png` - Error state
- `indicator_unread_*.png` - Unread notification indicators
- `launcher_icon.png` - App icon for the launcher

#### Memory errors

If the heap appears too small for your needs, consider reducing image sizes in `resources/` and rebuilding.

---

## Notes

- The companion app and watch app communicate via the Pebble SDK protocol defined in `protocol.md`.
- The companion app must be installed on a smartphone to sync notifications to the Pebble watch.
- The watch app supports all three Pebble platforms (basalt, diorite, emery) through the unified build process.
- Memory constraints are critical for the watch app; the build system reports memory usage per platform.
