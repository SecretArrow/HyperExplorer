# Hyper Explorer

[![CI](https://github.com/SecretArrow/HyperExplorer/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/HyperExplorer/actions/workflows/ci.yml)
[![E2E](https://github.com/SecretArrow/HyperExplorer/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/HyperExplorer/actions/workflows/e2e.yml)
[![Release](https://github.com/SecretArrow/HyperExplorer/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/HyperExplorer/actions/workflows/release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![REUSE status](https://img.shields.io/badge/reuse-REUSE.toml-informational)](https://reuse.software)

**Hyper Explorer** adalah file manager Android modern dengan karakter setara file manager klasik paling populer
(bagian-bagian fitur mengacu blueprint `fileman.md`) — dibangun dari nol dengan identitas sendiri, arsitektur modern,
dan **privasi sebagai fitur inti**: tanpa pelacakan rahasia, tanpa iklan agresif, data tetap di perangkat.

## 100% Free Software

Hyper Explorer adalah **perangkat lunak bebas** (free software) sesuai definisi Free Software
Foundation, berlisensi **GPL-3.0-only**:

- Bebas digunakan, dipelajari, dimodifikasi, dan didistribusikan ulang — selamanya.
- **Tanpa** iklan, telemetry, SDK proprietary, Firebase, atau Google Play Services —
  aman dipakai di perangkat de-Googled.
- Semua dependensi berlisensi open source (Apache-2.0/MIT/BSD); repositori Maven dibatasi
  `google()`, `mavenCentral()`, `gradlePluginPortal()` (lihat `settings.gradle.kts`).
- Setiap file sumber membawa header GPL + `SPDX-License-Identifier: GPL-3.0-only`, dan
  info lisensi mesin-terbaca tersedia di [REUSE.toml](REUSE.toml) (spesifikasi [REUSE](https://reuse.software)).
- Metadata distribusi **F-Droid** tersedia di `fastlane/metadata/android/` (en-US + Bahasa Indonesia).
- Kontribusi otomatis dilisensikan GPL-3.0-only tanpa CLA — lihat [CONTRIBUTING.md](CONTRIBUTING.md).

## Prinsip

1. **Tiru kapabilitas, bukan identitas** — nama, logo, kode, dan aset 100% original.
2. **Privasi by design** — tanpa SDK analytics/iklan yang mengumpulkan data secara default.
3. **Patuh platform** — Scoped Storage, izin granular, dan kebijakan Google Play dipatuhi sejak hari pertama.
4. **Keamanan by design** — semua fitur jaringan wajib autentikasi, server transfer hanya aktif saat diminta pengguna.

## Arsitektur Modul

| Modul | Jenis | Isi |
|---|---|---|
| `:app` | Android app | MainActivity, manifest, ikon, wiring UI |
| `:core-model` | Kotlin JVM | `FileNode`, `FileCategory` (model netral sumber berkas) |
| `:core-common` | Kotlin JVM | `PathUtils`, `ConflictStrategy` (overwrite/rename/skip) |
| `:core-ui` | Android lib | Tema Material 3 (dynamic color), komponen `FileRow`, `EmptyState` |
| `:data-local` | Kotlin JVM | `FileOperations` (copy/move/delete/rename), `FileRepository` + recycle bin ber-metadata |
| `:feature-browser` | Android lib | `BrowserState` (state holder) + `BrowserScreen` (Compose UI) |
| `:data-remote` | Kotlin JVM | Klien jaringan (network clients) FTP/SFTP/SMB/WebDAV: commons-net/sshj/SMBJ/OkHttp |
| `:feature-network` | Android lib | UI koneksi jaringan (connections UI) + penyimpanan kredensial terenkripsi (encrypted store) |
| `:feature-transfer` | Android lib | Server FTP lokal (RFC 959 subset, auth wajib, auto-off) + layar kontrol |
| `:feature-media` | Android lib | Penampil gambar/teks + pemutar audio/video (Media3 ExoPlayer) |
| `:feature-tools` | Android lib | Analisis penyimpanan, ZIP engine, vault terenkripsi AES-256-GCM |
| `:feature-apps` | Android lib | Manajer aplikasi + backup APK |
| `:feature-settings` | Android lib | Tema, bahasa (EN/ID), kebijakan privasi |

Rencana modul berikutnya: `:data-cloud` (Nextcloud/Drive/Dropbox/OneDrive).

## Fitur Saat Ini (scaffold MVP)

- Browse internal storage, folder dulu + nama A-Z, thumbnail kategori berkas
- Operasi: buka, navigasi naik, seleksi multi-item, hapus (ke recycle bin yang bisa dipulihkan),
  salin/potong/tempel (dengan resolusi konflik nama), folder baru, refresh
- Buka berkas dengan aplikasi lain (FileProvider)
- Banner permintaan akses "All files" (MANAGE_EXTERNAL_STORAGE) sesuai kebijakan Play
- Tema Material 3 + dynamic color + dark mode
- Network locations — browse & manage SMB / FTP / SFTP / WebDAV connections with encrypted credentials
- Encrypted vault — file locking/unlocking with AES-256-GCM (Android Keystore), integrity verification,
  documented container format (no lock-in), per-file random data key wrapped by a device key
- Audio/video player (Media3 ExoPlayer) with graceful error & missing-file handling

## CI/CD (GitHub Actions)

Semua build dijalankan di CI — **tidak ada build lokal**. Pipeline dirancang hemat waktu:

| Workflow | Pemicu | Isi |
|---|---|---|
| **CI** | push `main` / PR | 3 job paralel: lint (ktlint + Android lint), unit test semua modul, build debug APK |
| **E2E** | push `main` / manual | Instrumented test di emulator Android 11 (API 30, image `aosp_atd`) |
| **Release** | tag `v*` / manual | Build APK+AAB release, buat GitHub Release + changelog otomatis |
| **Auto Fix** | CI gagal / manual | `ktlintFormat` otomatis, commit & push hasil perbaikan |

### Hemat waktu build
- Cache Gradle otomatis (`gradle/actions/setup-gradle`) + build cache + paralel antar modul
- Job lint / unit / build berjalan **paralel**, bukan berurutan
- `concurrency` membatalkan run lama pada PR yang sama
- `org.gradle.parallel=true`, `org.gradle.caching=true` di `gradle.properties`

### Cara rilis otomatis
1. **Otomatis via commit**: dorong commit ke `main` dengan pesan `[release] v0.1.0 ...` — CI akan membuat tag lalu workflow Release menjalankan build & publish.
2. **Manual via tag**: `git tag v0.1.0 && git push origin v0.1.0`.
3. **Manual via UI**: jalankan workflow "Release" (pilih ref tag atau branch).

> **Catatan**: Tag yang dibuat job CI memakai `GITHUB_TOKEN`, dan event dari `GITHUB_TOKEN`
> tidak memicu workflow lain (aturan GitHub). Agar alur no.1 memicu Release otomatis,
> isi secret repo **`RELEASE_TOKEN`** dengan PAT ber-scope `repo` — job auto-tag akan
> memakainya untuk mendorong tag. Tanpa itu, gunakan cara no.2/no.3.

### Signing release (opsional)
Set secrets repo berikut agar APK/AAB ditandatangani; tanpa itu hasilnya unsigned:
- `RELEASE_KEYSTORE_BASE64` (base64 dari file keystore)
- `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`

## Menjalankan Lokal (opsional)

```bash
./gradlew assembleDebug      # build debug
./gradlew test               # unit test semua modul
./gradlew ktlintFormat       # perbaiki gaya kode
./gradlew connectedDebugAndroidTest  # e2e (butuh perangkat/emulator)
```

## Roadmap

Lihat `fileman.md` Bagian 9 (blueprint lengkap). Sudah tuntas: klien jaringan (SMB2/3, FTP, SFTP, WebDAV),
vault terenkripsi AES-256 di Android Keystore, pemutar media, transfer PC dengan autentikasi wajib + auto-off,
analisis penyimpanan, manajer aplikasi. Berikutnya: `:data-cloud` (Nextcloud/OAuth), sinkronisasi,
dukungan TV/layar besar, serta distribusi **F-Droid** dengan build reproducible.

## Keamanan & Kepatuhan

- Recycle bin ber-metadata → hapus bisa dipulihkan (tidak ada hapus permanen tanpa konfirmasi)
- Vault: enkripsi AES-256-GCM, DEK acak per berkas di-wrap kunci perangkat (Android Keystore), AAD mengikat
  DEK ke identitas entri (anti-swap), verifikasi integritas, format kontainer terdokumentasi (anti lock-in)
- Tidak ada telemetry, tidak ada server jaringan yang menyala diam-diam
- Kebijakan yang dipatuhi: Google Play User Data & Device and Network Abuse, UU PDP No. 27/2022

## Lisensi

Copyright (C) 2025-2026 Hyper Explorer contributors

Hyper Explorer adalah perangkat lunak bebas: Anda dapat mendistribusikan ulang dan/atau
memodifikasinya berdasarkan ketentuan **GNU General Public License versi 3**
([GPL-3.0-only](https://www.gnu.org/licenses/gpl-3.0)) sebagaimana diterbitkan oleh Free
Software Foundation. Tidak ada jaminan apa pun — lihat teks lisensi untuk detail lengkap.

- Teks lengkap lisensi: [LICENSE](LICENSE)
- Setiap file sumber membawa header GPL + `SPDX-License-Identifier: GPL-3.0-only`
- Info lisensi mesin-terbaca: [REUSE.toml](REUSE.toml) (standar [REUSE](https://reuse.software))
- Kontribusi Anda otomatis dilisensikan GPL-3.0-only — lihat [CONTRIBUTING.md](CONTRIBUTING.md)
- Laporan kerentanan: lihat [SECURITY.md](SECURITY.md)
