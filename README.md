# SecureVault

SecureVault is a native, offline Android vault for notes, editable scripts, audio recordings, and files. It runs on Android 11 (API 30) or later and uses the phone’s screen lock to authenticate. Android decides which enrolled biometrics meet the strong authentication requirement and also offers your PIN, pattern, or password.

## Upload the source and build a test APK

1. Extract `SecureVault-Codex-source.zip`. Copy the **contents** of the `SecureVault` folder into the root of your GitHub checkout, so `app/`, `settings.gradle.kts`, `.github/`, and `.gitignore` are at the repository root. Commit and push to the `main` branch using Git or GitHub Desktop.
2. Open the repository’s **Actions** tab and select **Build SecureVault APK**. Pushing to `main` starts a build automatically. To start one manually, choose **Run workflow** and select `main`.
3. Open the successful run. Under **Artifacts**, download **SecureVault-apk** and extract `app-debug.apk` from the downloaded ZIP.

## Build a signed release APK

Use a release signing key you control to distribute installable APKs that can receive updates. If you already have a suitable SecureVault release key, use that key. To create a new key, run this command on your computer with a JDK installed. Keep a private backup of the keystore and passwords.

```sh
keytool -genkeypair -keystore securevault-release.jks -storetype JKS \
  -alias securevault -keyalg RSA -keysize 3072 -validity 10000
```

Encode the keystore as one line in a private terminal using Bash or Git Bash on Windows.

```sh
base64 < securevault-release.jks | tr -d '\r\n'
```

In the GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret**. Create these four secrets. Keep the keystore, encoded output, and passwords out of the source repository.

| Secret | Value |
| --- | --- |
| `SECUREVAULT_KEYSTORE_BASE64` | The single line of Base64 output from the command above |
| `SECUREVAULT_KEYSTORE_PASSWORD` | Your keystore password |
| `SECUREVAULT_KEY_ALIAS` | `securevault` if you used the command above, or the alias in your existing keystore |
| `SECUREVAULT_KEY_PASSWORD` | Your private key password, which may be the same as the keystore password |

Open **Actions → Build SecureVault signed release → Run workflow** and choose `main`. On the successful run, download **SecureVault-release-apk** under **Artifacts** and extract `SecureVault-release.apk`. The workflow verifies the APK signature before uploading it. For future releases, increase `versionCode` in `app/build.gradle.kts` and use the same signing key.

## Install on the phone

1. Transfer the extracted APK to your phone and open it in **My Files**. Follow Android’s installation prompts and keep Google Play Protect enabled.
2. Open **SecureVault**. Set a phone screen lock in Settings if prompted. Tap **Unlock** and authenticate to open the vault.

If you installed a test APK already, export any vault items you want to keep before switching to a release signed with a different key. Android requires a matching signing key for in-place updates. Uninstalling the old APK clears its vault.

## Build locally

Install a JDK 17, Gradle 8.7, and Android SDK Platform 34 with Build Tools 34.0.0. Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to the SDK. From the project root, run:

```sh
gradle assembleDebug --stacktrace
```

The build produces `app/build/outputs/apk/debug/app-debug.apk`. Run Gradle 8.7 directly. The test build uses a debug signing key with app debugging disabled. GitHub-hosted runners can generate different debug keys across runs.

## Use the vault

- Tap **Add** to create a note or script, record AAC audio, or import a local document. Imported scripts can be up to 5 MB (5,000,000 bytes). The editor accepts UTF-8 and UTF-16 with a byte-order mark and saves text as UTF-8. Long documents are editable one page at a time.
- Tap an item to edit text, play audio, or view file details. Use its menu to delete the item or export a **decrypted copy** through Android’s file picker. The exported copy is stored outside the vault.
- SecureVault locks when the screen turns off or after 60 seconds in the background. The lock screen covers the app and keeps the current draft in memory. Save a draft to retain it across an app process restart. Playback and unsaved recordings stop when the app leaves the foreground.
- Item data and the index are encrypted with AES-256-GCM and a device-local Android Keystore key. Android backup and device transfer of app files are disabled. Uninstalling SecureVault or clearing its app data removes the vault. Export any files you want to keep before either action.
