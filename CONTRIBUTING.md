# Berkontribusi ke Hyper Explorer

Terima kasih sudah ingin berkontribusi! Hyper Explorer adalah proyek **free software**
— semua bentuk kontribusi dipersilakan: kode, dokumentasi, desain, terjemahan, dan pengujian.

## Lisensi kontribusi

- Dengan mengirimkan kontribusi (via Pull Request), Anda menyatakan karya tersebut
  dirilis di bawah **GPL-3.0-only** (GNU General Public License v3), sejalan dengan
  lisensi proyek. Tidak ada perjanjian kontributor (CLA) tambahan yang diperlukan.
- Setiap file sumber baru wajib membawa header GPL + `SPDX-License-Identifier: GPL-3.0-only`
  di bagian atas file — salin saja dari file sumber lain yang sudah ada.
- Kontribusi tidak boleh memasukkan kode yang lisensinya tidak kompatibel dengan GPL-3.0.

## Aturan dependensi (wajib — proyek ini 100% free software)

- Hanya boleh menambahkan dependensi berlisensi open source yang disetujui OSI
  (Apache-2.0, MIT, BSD, LGPL kompatibel-GPL, dsb.).
- **Dilarang keras**: SDK proprietary, Firebase / Google Play Services, SDK iklan,
  analytics, crash-reporting proprietary, atau bentuk telemetri apa pun.
- Repositori Maven dibatasi: `google()`, `mavenCentral()`, `gradlePluginPortal()`
  (lihat `settings.gradle.kts`). Menambahkan repositori baru butuh justifikasi di PR.

## Gaya kode & kualitas

- Format otomatis: `./gradlew ktlintFormat` (CI juga punya workflow Auto Fix).
- Wajib lulus sebelum review: `./gradlew ktlintCheck test`.
- Tambahkan unit test untuk setiap fitur/perbaikan logika; utamakan test JVM agar build cepat.
- Semua build diverifikasi di GitHub Actions — tidak perlu dan tidak disarankan build lokal.

## Alur kerja

1. Fork repo atau buat branch dari `main` (contoh: `feat/recycle-ui`, `fix/paste-conflict`).
2. Commit kecil dan deskriptif; bahasa Indonesia atau Inggris sama-sama boleh.
3. Buka Pull Request ke `main` — CI (lint + unit test + build) harus hijau.
4. Jelaskan *apa* dan *mengapa* di deskripsi PR; sertakan tangkapan layar untuk perubahan UI.
