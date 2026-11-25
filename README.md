# NFC QR MSP Test App

Test app for **MSPs** (the merchant's acquirer and its POS). An Android phone plays the customer's bank app for
Tap2Pay via Raast: it opens on a Tap2Pay NFC tap (even when closed or locked), checks every APDU and the QR the POS
sends, and shows the data a bank app would use. The app does not make or authorize a payment.

The app is [`bank-app`](bank-app/README.md), label **NFC QR Test App**, built on the shared protocol code in `core`.
Its README has the screens and the full scenario matrix.

The CSP side (a phone that plays the POS, to test a bank app) is the separate **NFC QR CSP Test App**
(Tap2Pay POS Simulator).

## Requirements

- Android 8.0 (API 26) or newer phone with NFC and Host Card Emulation (HCE).
- A Tap2Pay-capable POS that pushes a Raast QR over NFC (for example Urovo i9100 or Sunmi P3), or a second phone
  running the NFC QR CSP Test App.
- To build: JDK 17, Android SDK platform 33, and Android build tools. The Gradle wrapper is included.

## Build and install

1. Clone this repository and set `ANDROID_HOME` to your Android SDK, or add `local.properties` containing
   `sdk.dir=/path/to/Android/sdk`.
2. The checked-in `gradle.properties` pins a macOS Homebrew JDK path. If your JDK 17 is elsewhere, change or remove
   `org.gradle.java.home`, or override it on the Gradle command line.
3. From the repository root, run:

   ```bash
   ./gradlew :core:test :bank-app:assembleDebug
   ```

   On Windows use Git Bash for `gradlew` (this repository has no `gradlew.bat`). For example, with your actual JDK path:

   ```bash
   ./gradlew "-Dorg.gradle.java.home=C:/Program Files/Microsoft/jdk-17" :core:test :bank-app:assembleDebug
   ```

4. Install the APK:

   ```bash
   adb install -r bank-app/build/outputs/apk/debug/bank-app-debug.apk
   ```

## Run a test

1. Enable NFC and open **NFC QR Test App**. The status card should say it is ready for a tap. If it warns that
   another app registers the same AID (a bank app, the Tapsys Tap2Pay Test Kit), uninstall or disable that app.
2. Start a Tap2Pay sale on the POS and hold the phone on the reader. The app opens by itself when the POS sends a QR.
3. The tap screen shows **1 NFC communication** (every APDU and status word, checked against the spec),
   **2 QR validation** (every tag checked) and **3 Data extracted** (the data a bank app would use). Use **Copy log**
   or **Copy all logs** when reporting a result.

## Repository modules

| Module | Purpose |
| --- | --- |
| `bank-app` | The customer-phone test app (applicationId `pk.paysys.tap2pay.bankapp`): HCE service, tap screens, history. |
| `core` | Plain Java, unit-tested: HCE responder, APDU and QR parsing and checks, NFC/QR reports, tap history, log text. |

The earlier Tapsys Tap2Pay Test Kit (`phone-test`) is in this repository's history up to the commit before the one
that replaced it.

## License

The source code is licensed under the [GNU Affero General Public License version 3](LICENSE). See
[third-party notices](docs/THIRD_PARTY_NOTICES.md) for the Gradle wrapper and the Inter font. The Tapsys name,
wordmark, and icon identify the test app.
