# WhatsApp Web Client for Android

Aplikasi Android Kotlin yang membungkus WhatsApp Web resmi dalam WebView. Fitur keamanan berikut sudah diimplementasikan pada cabang ini.

## Fitur yang tersedia

- **Kunci aplikasi:** saat pertama kali dibuka, pengguna membuat dan mengonfirmasi PIN aplikasi 6 digit. PIN dapat dipakai sebagai fallback biometrik kuat bila perangkat mendukungnya.
- **Pembatasan percobaan PIN:** setelah 5 percobaan salah, input PIN dikunci selama 5 menit. Status lockout disimpan terenkripsi.
- **Auto-lock:** aplikasi meminta autentikasi lagi setelah 30 detik berada di latar belakang (ditinggalkan), bukan saat sedang dipakai. WebView tidak dibuat/dibuka sebelum autentikasi berhasil.
- **Badge unread:** jumlah chat belum terbaca dibaca dari judul dokumen WhatsApp Web (`(3) WhatsApp`) oleh `app/src/main/assets/badge_hook.js` yang dipasang lewat `evaluateJavascript` saat halaman mulai/selesai dimuat, lalu diteruskan ke `UnreadBadgeManager` (ShortcutBadger + notifikasi `setNumber`).
- **Penyimpanan metadata terenkripsi:** hash PIN bersalt (PBKDF2-HMAC-SHA-256) serta daftar/nama akun disimpan melalui `EncryptedSharedPreferences`; master key dikelola Android Keystore.
- **Multi-akun:** tiap akun memakai profil WebView tersendiri melalui AndroidX WebKit `MULTI_PROFILE`, termasuk cookie dan data browsing yang dipisahkan. Pengguna dapat menambah, memilih, dan menghapus akun.
- **Fail-closed untuk isolasi:** akun tambahan tidak dibuat atau dibuka bila Android System WebView pada perangkat tidak mendukung profil terpisah. Perbarui System WebView untuk mengaktifkan fitur tersebut; aplikasi tidak diam-diam membagikan cookie.
- **WebView yang diperkeras:** file access dan mixed content dinonaktifkan, cleartext traffic ditolak, dan navigasi dibatasi ke domain WhatsApp yang diizinkan.
- **Badge unread:** bridge badge menggunakan `WebMessageListener` yang dibatasi ke origin HTTPS `web.whatsapp.com` dan frame utama; bila fitur ini tidak didukung WebView, badge native dinonaktifkan.

## Batas keamanan yang perlu diketahui

AndroidX WebView menyediakan **isolasi profil**, bukan enkripsi penuh atas storage Chromium. Daftar akun, nama akun, salt/hash PIN, dan metadata aplikasi dienkripsi oleh Android Keystore; cookie, IndexedDB/local storage, dan cache WhatsApp dikelola oleh WebView di sandbox aplikasi dan **tidak dienkripsi oleh aplikasi secara terpisah**. Karena itu proyek ini tidak mengklaim bahwa sesi WhatsApp terlindungi dari perangkat yang sudah di-root atau proses aplikasi yang sedang berjalan.

Menghapus akun meminta WebView menghapus profilnya; sebagian operasi WebView berlangsung asinkron. Pada profil default, aplikasi membersihkan cookie, Web Storage, dan cache karena profil default tidak dapat dihapus seluruhnya.

Aplikasi tidak mengaktifkan `FLAG_SECURE`, sehingga screenshot tetap diizinkan. Penguncian aplikasi juga bukan pengganti screen lock Android.

## Fitur yang belum diimplementasikan

Proxy per akun, telemetri opt-in, deteksi/peringatan root, pemeriksaan update GitHub dan verifikasi APK masih belum tersedia. `UpdateCheckWorker` saat ini hanya mencatat versi lokal.

## Build dan test

Persyaratan: JDK 17, Android SDK 34, dan Android System WebView pada perangkat/emulator.

```bash
cd whatsapp-web-client
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

Tes skrip badge (Node.js):

```bash
node tools/badge_hook.test.js
```

Build release CI memakai keystore tetap yang di-commit (`whatsapp-web-client/keystore.jks`, PKCS12, password `android`), sehingga artifact CI dapat dipasang sebagai update di atas build CI sebelumnya. Konsekuensinya: di repo publik ini tanda tangan rilis bukan rahasia — ganti keystore privat bila ingin dipakai untuk distribusi luas.

## Struktur kode terkait

```text
app/src/main/java/com/whatsapp/webclient/
├── accounts/
│   ├── WhatsAppAccountStore.kt
│   └── WebViewProfileManager.kt
├── security/
│   ├── PinHasher.kt
│   └── SecurityStore.kt
├── LockActivity.kt
├── MainActivity.kt
├── SecureWebViewClient.kt
└── WhatsAppApp.kt
```

Lisensi: MIT.
