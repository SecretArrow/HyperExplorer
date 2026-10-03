# Hyper Explorer

[![CI](https://github.com/SecretArrow/HyperExplorer/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/HyperExplorer/actions/workflows/ci.yml)
[![E2E](https://github.com/SecretArrow/HyperExplorer/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/HyperExplorer/actions/workflows/e2e.yml)
[![Release](https://github.com/SecretArrow/HyperExplorer/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/HyperExplorer/actions/workflows/release.yml)

**Hyper Explorer** adalah file manager Android modern dengan karakter setara file manager klasik paling populer
(bagian-bagian fitur mengacu blueprint `fileman.md`) — dibangun dari nol dengan identitas sendiri, arsitektur modern,
dan **privasi sebagai fitur inti**: tanpa pelacakan rahasia, tanpa iklan agresif, data tetap di perangkat.

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

Rencana modul berikutnya: `:data-remote` (SMB/FTP/SFTP/WebDAV), `:data-cloud` (Drive/Dropbox/OneDrive),
`:feature-transfer`, `:feature-network`, `:feature-media`, `:feature-tools`, `:feature-settings`.

## Fitur Saat Ini (scaffold MVP)

- Browse internal storage, folder dulu + nama A-Z, thumbnail kategori berkas
- Operasi: buka, navigasi naik, seleksi multi-item, hapus (ke recycle bin yang bisa dipulihkan),
  salin/potong/tempel (dengan resolusi konflik nama), folder baru, refresh
- Buka berkas dengan aplikasi lain (FileProvider)
- Banner permintaan akses "All files" (MANAGE_EXTERNAL_STORAGE) sesuai kebijakan Play
- Tema Material 3 + dynamic color + dark mode

## CI/CD (GitHub Actions)

Semua build dijalankan di CI — **tidak ada build lokal**. Pipeline dirancang hemat waktu:

| Workflow | Pemicu | Isi |
|---|---|---|
| **CI** | push `main` / PR | 3 job paralel: lint (ktlint + Android lint), unit test semua modul, build debug APK |
| **E2E** | push `main` / manual | Instrumented test di emulator Android 14 (API 34) |
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
3. **Manual via UI**: jalankan workflow "Release" (menghasilkan `v0.0.0-runN`).

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

Lihat `fileman.md` Bagian 9 (blueprint lengkap): klien jaringan (SMB2/3, FTP/FTPS, SFTP, WebDAV),
multi-cloud dengan OAuth resmi, vault terenkripsi AES-256 di Android Keystore, transfer PC dengan
autentikasi wajib + auto-off, analisis penyimpanan, manajer aplikasi, pemutar media, dan dukungan TV/layar besar.

## Keamanan & Kepatuhan

- Recycle bin ber-metadata → hapus bisa dipulihkan (tidak ada hapus permanen tanpa konfirmasi)
- Tidak ada telemetry, tidak ada server jaringan yang menyala diam-diam
- Kebijakan yang dipatuhi: Google Play User Data & Device and Network Abuse, UU PDP No. 27/2022

## Lisensi

Hak cipta milik pemilik repo. Lisensi final akan ditentukan oleh pemilik proyek.
