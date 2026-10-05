# BlueBrand Emp Android

Android WebView wrapper for https://bluebrand-emp.web.app

- App name: BlueBrand Emp
- Package: sa.bluebrand.emb.mobile
- Firebase Cloud Messaging enabled
- Camera / barcode web camera access
- File upload + camera capture
- Normal Android downloads
- WebView printing hook for window.print()
- Back navigation and session cookies

## Build with GitHub
1. Create a GitHub repository and upload all files in this folder.
2. Open **Actions** > **Build BlueBrand Emp Android** > **Run workflow**.
3. When it finishes, download the **BlueBrand-Emp-APK** artifact.

## Important
The included `google-services.json` contains Firebase project configuration. Keep the repository private unless you intentionally want the configuration public.

For Play Store/release signing, add a release keystore and signing configuration separately. The included workflow creates a debug APK intended for direct testing/install.
