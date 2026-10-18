# Build the bn APK (Android Studio)

## 1. One-time setup
1. Install Android Studio (latest stable) from developer.android.com/studio. It bundles the JDK.
2. Open it, accept the licences, let it install the Android SDK. When asked, install **Android SDK Platform 34**.

## 2. Open the project
- File > Open > choose the **`android`** folder (the one containing `settings.gradle.kts`). NOT the outer `bn` folder.
- Wait for "Gradle sync". It needs internet once to download Gradle, the Android plugin and libraries. After that it works offline.
- Fix-ups if sync complains: click the blue "Install missing SDK" link; if it asks to upgrade the Android Gradle Plugin, you can accept.

## 3. Run the tests
Right-click `app/src/test/java/com/bn/app` > **Run Tests**. They cover the SMS parser, signature check and credits.

## 4. Try it on a real phone
USSD and SMS can't be tested on an emulator.
1. Phone: Settings > About > tap Build number 7 times > Developer options > USB debugging ON.
2. Plug in, accept the prompt, pick the phone at the top of Studio, press the green Run button.
3. In the app: grant the permissions, then add credits with a token from the admin page (see FIRST_RUN_CHECKLIST.md).
4. Send the test offer update (README: "Testing a Ksh1 receipt") and pay Ksh1.

## 5. Make the release APK people install
1. Build > **Generate Signed App Bundle / APK** > **APK** > Next.
2. Create new keystore: choose a file (e.g. `bn-release.jks`), set passwords, alias `bn`, validity 25+ years. 
3. **Back the keystore up in two places. If you lose it you can never ship an update that installs over the old app.**
4. Choose build variant **release**, finish. The file appears in `android/app/release/app-release.apk`.

(Optional, for command-line builds: copy `keystore.properties.example` to `keystore.properties`, fill it in, then Build > Select Build Variant > release.)

## 6. Install on merchant phones
1. Send `app-release.apk` (WhatsApp/Bluetooth/USB, or the download page in `site/`).
2. Phone: allow "Install unknown apps" for the app used to open it. Play Protect may warn; choose "Install anyway".
3. Open bn, grant SMS / Phone / Notification permissions, then add credits with a token from the admin page.

## 7. Keep it alive in the background
- The app already runs a foreground service (the "bn is running" notification). Don't hide that notification.
- Settings > Apps > bn > Battery > **Unrestricted**.
- Tecno / Infinix / itel / Xiaomi / Oppo / Samsung: also turn on **Autostart** and **lock bn in the recent-apps screen**, or the phone kills it.
- Keep the SIM in with airtime and signal. Data is not needed.

## 8. Shipping updates
- Raise `versionCode` (1 -> 2 -> 3) and `versionName` in `app/build.gradle.kts` every release, sign with the SAME keystore.
- Copy the APK to `site/downloads/bn.apk`, commit, push (Netlify republishes).

## Things to know
- Google Play will not accept an app with these SMS/call permissions, so this is distributed by direct download only.
- If you later raise `targetSdk` to 35, Android 15 limits `dataSync` foreground services to about 6 hours per day. Stay on 34 or switch the service type before doing that.
- The code has never been compiled on a device (see README). Expect small fixes on first sync; send me the error text and I'll fix it.
