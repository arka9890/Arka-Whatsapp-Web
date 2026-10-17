# whatsapp-web

Klien WhatsApp Web Android berbasis Kotlin dan WebView. Pada versi ini sudah tersedia kunci aplikasi PIN/biometrik, penyimpanan metadata terenkripsi, dan pengelolaan beberapa akun melalui profil WebView yang terisolasi.

## Struktur repo

```text
.
├── .github/workflows/build-apk.yml
├── whatsapp-web-client/                  # Proyek Android
│   ├── app/src/main/java/.../accounts/   # penyimpanan akun dan profil WebView
│   ├── app/src/main/java/.../security/   # derivasi PIN dan security store
│   ├── app/src/main/res/                 # layout dan resource
│   └── tools/badge_hook.test.js           # tes hook badge Node.js
└── README.md
```

## Build APK

Memerlukan JDK 17 dan Android SDK 34.

```bash
cd whatsapp-web-client
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Hasil debug: `app/build/outputs/apk/debug/app-debug.apk`.

Tes hook badge:

```bash
node tools/badge_hook.test.js
```

## Catatan keamanan

- Metadata aplikasi dan hash PIN disimpan di `EncryptedSharedPreferences` dengan master key Android Keystore.
- Cookie dan storage WhatsApp dipisahkan per akun memakai AndroidX WebKit `MULTI_PROFILE` bila didukung versi Android System WebView perangkat.
- Storage Chromium/WebView **tidak dienkripsi oleh aplikasi secara terpisah**; lihat [dokumentasi klien](whatsapp-web-client/README.md) untuk batasan dan fitur yang belum tersedia.
- Keystore rilis (`whatsapp-web-client/keystore.jks`, PKCS12, password `android`) di-commit agar setiap build CI memakai identitas tanda tangan yang sama, sehingga APK baru bisa dipasang sebagai update di atas build sebelumnya. Karena repo ini publik, file itu bukan rahasia: siapa pun yang memilikinya bisa menandatangani APK yang diterima Android sebagai update. Ganti dengan keystore privat sebelum dipakai untuk distribusi luas.
