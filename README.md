# LanBridge: Cross-Platform P2P Local Clipboard & File Transfer

**LanBridge** is an ultra-fast, local-network peer-to-peer (P2P) synchronization bridge between **Windows 11** and **Android**. It enables instant, zero-cloud clipboard synchronization (text, URLs, screenshots) and streaming high-speed file transfers without internet, third-party servers, or account registration.

---

## Key Features

- ⚡ **Instant Clipboard Sync:**
  - **PC → Android:** Near-zero latency sync. Screenshots taken with `Win + Shift + S` or copied text appear immediately in Android's clipboard and Gboard suggestions.
  - **Android → PC:** Full Android 10–14+ background restriction bypass using:
    - **Quick Settings Tile:** Single tap on the "LanBridge" quick tile sends the phone's clipboard to PC in ~50ms.
    - **Context Menu (`ACTION_PROCESS_TEXT`):** Select text anywhere and tap "На ПК".
    - **Share Sheet (`ACTION_SEND`):** Share photos, videos, documents, or links directly to PC from any app.
- 📁 **High-Speed File Streaming:**
  - Direct 64 KB chunk streaming to/from disk without buffering entire files in RAM.
  - End-to-end SHA-256 integrity verification.
  - Drag-and-drop zone on PC with live transfer progress.
  - Default download directory on PC: `Downloads\LanBridge`.
- 🛡️ **Zero-Trust Security (P2P TLS 1.3 / TOFU):**
  - Self-signed X.509 certificate generated on PC.
  - 1-click QR Code pairing with Certificate Pinning (SHA-256 fingerprint) and one-time tokens.
  - Persistent paired credentials stored in `EncryptedSharedPreferences` on Android.
- 🔄 **Echo-Loop Guard:**
  - Deterministic SHA-256 tracking of remote clipboard items ensures no infinite copy-paste loops between devices.
- 📡 **Zero-Config Discovery:**
  - Dual broadcast UDP beacons (port `54321`) across `255.255.255.255` and local subnet interfaces (works over home Wi-Fi routers and phone Wi-Fi hotspots).
- 🎨 **Modern Windows 11 & Material 3 Dark UI:**
  - Desktop: System Tray (`NotifyIcon`), global hotkey `Ctrl + Alt + B`, and modern Quick Settings flyout (`#0F1115`, `#161920`, `#2F81F7`).
  - Mobile: Jetpack Compose Material 3 dark theme with Foreground Service for reliable background operation.

---

## Monorepo Architecture

```
LanBridge/
├── PROTOCOL.md                  # Comprehensive network & security protocol specification
├── README.md                    # Project documentation & user guide
├── .github/
│   └── workflows/
│       └── build.yml            # CI/CD pipeline for Windows .exe and Android .apk
├── desktop-win11/               # Windows 11 WPF application (.NET 8)
│   ├── LanBridge.csproj
│   ├── setup-firewall.ps1       # Automated Windows Firewall configuration script
│   ├── App.xaml / App.xaml.cs
│   ├── MainWindow.xaml / .cs    # Quick Flyout UI, clipboard history, Drag & Drop
│   ├── PairingWindow.xaml / .cs # QR code pairing dialog
│   ├── Core/                    # Network, TLS, WebSockets, UDP broadcaster, Win32 API
│   └── Resources/
└── android-app/                 # Android application (Kotlin + Jetpack Compose)
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── app/
    │   ├── build.gradle.kts
    │   ├── src/main/
    │   │   ├── AndroidManifest.xml
    │   │   ├── java/com/lanbridge/app/
    │   │   │   ├── data/       # Storage & protocol models
    │   │   │   ├── network/    # UDP listener, TLS pinner, WSS client, streaming
    │   │   │   ├── service/    # ForegroundService & Quick Settings TileService
    │   │   │   └── ui/         # Jetpack Compose UI, QR scanner, share intents
    │   │   └── res/
    └── gradle/wrapper/
```

---

## Quick Start Guide

### 1. Windows 11 Host
1. Run PowerShell as Administrator and execute:
   ```powershell
   cd desktop-win11
   .\setup-firewall.ps1
   ```
   This ensures inbound traffic is permitted on TCP `8443` (WSS / File Stream) and UDP `54321` (Discovery).
2. Launch `LanBridge.exe`. An icon will appear in the system tray.
3. Click the tray icon or press `Ctrl + Alt + B` to open the Quick Flyout.
4. Click **«Привязать телефон (QR)»** to display the pairing QR code.

### 2. Android Phone
1. Install and launch `LanBridge-Android.apk`.
2. Grant camera and notification permissions when prompted.
3. Tap **«Сканировать QR-код»** and scan the code displayed on your PC.
4. Once paired, the foreground service will automatically connect.

### 3. Usage
- **Copying on PC:** Press `Ctrl + C` on any text or take a screenshot with `Win + Shift + S`. It instantly arrives on your phone.
- **Copying on Android:** Tap the **LanBridge** Quick Settings Tile in your notification shade, or select text and choose **«На ПК»**.
- **Sending Files:** Drag and drop files into the PC Flyout window or tap **«Отправить файл»** / share via Android Share Sheet.
