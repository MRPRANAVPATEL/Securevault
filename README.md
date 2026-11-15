# SecureVault

Private Android vault for text notes, scripts, audio and files. 100% offline.

## What it does
- Locks with fingerprint / face / screen PIN every time you open it (also after 60 s in the background and when the screen turns off)
- Notes and scripts: write and edit inside the app
- Audio: record inside the app, or import a file
- Any other file: import, store, export later
- Everything is encrypted with AES-256-GCM, key kept in the Android Keystore
- No internet permission, no cloud, no backup, screenshots blocked

## Get the APK (no Android Studio needed)
1. Create a new GitHub repository.
2. Upload everything in this folder (keep the `.github` folder).
3. Open the **Actions** tab, run **Build APK** (it also runs on every push to `main`).
4. When it finishes, open the run and download the **SecureVault-apk** artifact.
5. Unzip it, copy `app-debug.apk` to the phone and install it
   (allow "Install unknown apps" for your file manager or browser).

## Notes
- Uninstalling the app deletes the vault and its key. Export what you need first.
- "Export" saves a decrypted copy, so keep it somewhere safe.
- Recording uses a temporary plain file in the app's private cache; it is encrypted and deleted when you tap Stop & save.
