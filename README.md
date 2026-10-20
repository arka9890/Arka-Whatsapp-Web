# whatsapp-web

Klien WhatsApp Web Android berbasis Kotlin dan WebView. Pada versi ini sudah tersedia kunci aplikasi PIN/biometrik, penyimpanan metadata terenkripsi, dan pengelolaan beberapa akun melalui profil WebView yang terisolasi. Untuk Windows ada klien terpisah (Go + WebView2) dengan installer wizard dan update otomatis.

## Struktur repo

```text
.
├── .github/workflows/               # build-apk.yml (Android) dan build-windows.yml (Windows)
├── whatsapp-web-client/             # Proyek Android
│   ├── app/src/main/java/.../accounts/   # penyimpanan akun dan profil WebView
│   ├── app/src/main/java/.../security/   # derivasi PIN dan security store
│   ├── app/src/main/res/                 # layout dan resource
│   └── tools/badge_hook.test.js           # tes hook badge Node.js
├── windows/                         # Klien Windows (Go + WebView2)
│   ├── main.go                      # jendela WebView2 + cek update GitHub Releases
│   ├── build.sh                     # exe + installer
│   └── installer/                   # skrip NSIS: wizard, uninstaller, upgrade versi lama
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

## Klien Windows

Terpisah dari aplikasi Android: aplikasi **meluncurkan Chrome milik pengguna** dalam mode app window (jendela tanpa alamat) langsung ke `web.whatsapp.com`, dengan profil Chrome terisolasi di `%LocalAppData%\WhatsAppWebClient\chrome-profile` — terpisah dari profil Chrome harian dan tidak ikut terhapus saat uninstall. Bila Chrome tidak terpasang, aplikasi jatuh balik ke satu jendela WebView2 (mesin Chromium bawaan Windows) dengan profil di `%LocalAppData%\WhatsAppWebClient\profile`. Butuh Go 1.21+ dan [NSIS](https://nsis.sourceforge.io) (`makensis`):

```bash
cd windows
go test ./...          # tes logika versi update
./build.sh 1.0.0       # butuh makensis di PATH
```

Hasil di `windows/dist/`:

- `WhatsAppWebClient.exe` — aplikasi, bisa dijalankan tanpa instalasi.
- `WhatsAppWebClient-Setup-1.0.0.exe` — installer wizard (sambutan → lisensi → folder → selesai), per-user ke `%LocalAppData%\Programs\WhatsAppWebClient`, plus pintasan menu mulai/desktop dan entri Add/Remove Programs.

**Installer menangani versi lama**: kalau instalasi sebelumnya terdeteksi, uninstaller-nya dijalankan otomatis (senyap) sebelum file baru ditulis, jadi tidak ada file dan entri yang menumpuk. Profil WebView2 di `%LocalAppData%\WhatsAppWebClient\profile` sengaja tidak dihapus saat uninstall, supaya login WhatsApp tetap ada.

**Update otomatis**: aplikasi mengecek `releases/latest` di GitHub saat dibuka. Bila ada tag versi lebih baru, dialog menawarkan mengunduh installer dan menjalankannya (aplikasi menutup diri supaya file exe tidak terkunci). Tag `v*` yang di-push membuat CI (`build-windows.yml`) membangun installer dan menerbitkannya sebagai GitHub Release dengan asset `*.exe`. Ganti `updateRepo` di `windows/main.go` bila repo pindah.

WebView2 Runtime (sudah bawaan Windows 11 dan Edge) hanya dibutuhkan untuk jalur fallback; butuh Microsoft Edge WebView2 Runtime; kalau belum ada, aplikasi menampilkan tautan unduhan resmi Microsoft.

## Catatan keamanan

- Metadata aplikasi dan hash PIN disimpan di `EncryptedSharedPreferences` dengan master key Android Keystore.
- Cookie dan storage WhatsApp dipisahkan per akun memakai AndroidX WebKit `MULTI_PROFILE` bila didukung versi Android System WebView perangkat.
- Storage Chromium/WebView **tidak dienkripsi oleh aplikasi secara terpisah**; lihat [dokumentasi klien](whatsapp-web-client/README.md) untuk batasan dan fitur yang belum tersedia.
- Keystore rilis (`whatsapp-web-client/keystore.jks`, PKCS12, password `android`) di-commit agar setiap build CI memakai identitas tanda tangan yang sama, sehingga APK baru bisa dipasang sebagai update di atas build sebelumnya. Karena repo ini publik, file itu bukan rahasia: siapa pun yang memilikinya bisa menandatangani APK yang diterima Android sebagai update. Ganti dengan keystore privat sebelum dipakai untuk distribusi luas.
