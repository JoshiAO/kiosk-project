<h1 align="center">
  <br>
  Enterprise Device Kiosk Management
  <br>
</h1>

<h4 align="center">A complete device management ecosystem featuring a native Android Kiosk Launcher and a real-time React web dashboard.</h4>

<p align="center">
  <a href="#key-features--business-impact">Key Features</a> •
  <a href="#technical-marvels--architecture">Architecture</a> •
  <a href="#ux--design-philosophy">UX & Design</a> •
  <a href="#technologies-used">Technologies Used</a>
</p>

An enterprise-grade platform engineered to lock down, manage, and monitor a fleet of Android devices. It replaces standard operating system launchers with a highly restrictive Kiosk Mode, while granting administrators complete control from a remote web dashboard.

Built to solve the complexities of deploying dedicated devices (e.g. digital signage, POS systems, public terminals), this platform guarantees zero-trust security, real-time telemetry, and seamless over-the-air application updates.

[**Connect on LinkedIn**](https://www.linkedin.com/in/joshua-ocampo-b67210384)

---

## Key Features & Business Impact

*   **Complete Device Lockdown**: Operates as the Android Device Owner and Default Launcher, entirely blocking access to the status bar, system settings, and unauthorized applications.
*   **Real-Time Fleet Telemetry**: Automatically tracks and reports critical device health metrics including battery levels, online status, heartbeats, and application crash logs.
*   **Silent Over-The-Air (OTA) Updates**: Features a bespoke Silent Installer that bypasses standard Android prompts, allowing administrators to push APK updates to the entire fleet instantly from the dashboard.
*   **Dynamic App Provisioning**: App shortcuts and permissions are controlled entirely via the cloud. Administrators can assign allowed apps and push configuration changes without ever touching the physical devices.
*   **Advanced Hardware Security**: Implements strict PIN-based lockouts and hardware identity verification (IMEI & Serial Number tracking) to prevent unauthorized tampering.

## Technical Marvels & Architecture

This platform was meticulously architected to maintain persistent connections and execute system-level Android commands reliably, all while keeping cloud bandwidth costs negligible.

### 1. Robust Android Systems Engineering
*   **Device Owner Provisioning (dpm):** Leverages Android's Device Policy Manager to enforce true Kiosk Mode, enabling the app to silently install other applications and restrict OS-level features.
*   **Resilient Background Workers:** Utilizes Android `WorkManager` for guaranteed background execution of telemetry syncing and OTA update polling, ensuring devices always recover from reboots and network drops.
*   **Custom Crash Detection:** Integrates a localized crash watcher that intercepts application failures, automatically logging them to the cloud and intelligently rolling back to stable APK versions if a crash loop is detected.

### 2. High-Performance Web Dashboard
*   **Instant Cloud Synchronization:** Powered by Firebase Realtime subscriptions, allowing the web dashboard to reflect device statuses (Online/Offline) and battery changes the absolute second they happen.
*   **Dynamic App Injection:** Uses a modular component architecture in React 19 to instantly render allowed apps and configuration settings pushed from the cloud, with built-in Google Drive link sanitization for direct APK downloads.

### 3. Zero-Trust Security & Provisioning
*   **Cryptographic Project Activation:** New devices cannot join the fleet until they undergo a secure activation handshake verifying a SHA256 project hash against the Firestore database.
*   **Strict Security Rules:** The backend is fortified with Firebase Security Rules that ensure devices can only write to their specific telemetry documents, completely preventing lateral data access.

## UX & Design Philosophy

*   **Administrator Ergonomics**: The web dashboard employs glassmorphism design trends and intuitive data grids to prevent cognitive overload when managing hundreds of devices simultaneously.
*   **Frictionless Device Experience**: The Android Launcher is designed to be invisible. When an allowed app is launched, it consumes the full screen; when it crashes, the Kiosk silently recovers and relaunches it without exposing the underlying OS.

## Technologies Used

### Frontend Dashboard
- **React 19 (Vite, TypeScript)**: Lightning-fast UI rendering and bundling.
- **Custom CSS Architecture**: Glassmorphism, CSS Variables, Keyframe Animations.
- **Lucide React**: Crisp, lightweight scalable iconography.

### Android Kiosk App
- **Kotlin & Jetpack Compose**: Modern declarative UI for the locked-down launcher and settings dialogs.
- **Android Device Policy Manager (DPM)**: Core APIs for enterprise lockdown and silent app installations.
- **Room Database**: Local persistence for caching app configurations and crash logs offline.
- **WorkManager**: Reliable background execution for telemetry and sync tasks.

### Backend & Cloud
- **Firebase Firestore**: Scalable NoSQL database with real-time listeners.
- **Firebase Authentication**: Secure admin logins.

## Let's Connect

I specialize in building full-stack applications that solve real business problems with elegant, scalable code. If you are looking for an engineer who understands both deep technical architecture and high-level business impact, I would love to chat.

[**Contact Me via Email**](mailto:joshi.ao@outlook.ph) | [**View My Portfolio**](https://eikofisherman.web.app/)

## License

**All Rights Reserved.**

This repository and its source code are the proprietary property of the author. It is published publicly strictly for educational and portfolio review purposes. You may not copy, reproduce, distribute, compile, or utilize this software for any personal or commercial purposes without explicit written consent from the author. 

---
*Built as a showcase of modern full-stack development, distributed systems, and Android enterprise engineering.*
