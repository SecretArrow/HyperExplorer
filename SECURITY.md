# Kebijakan Keamanan

## Versi yang didukung

| Versi | Status |
|---|---|
| 0.1.x | Pra-rilis — laporkan masalah keamanan langsung ke `main` |

## Melaporkan kerentanan

1. **Jangan** buat issue publik untuk kerentanan.
2. Gunakan **GitHub Security Advisory** (tab *Security* → *Report a vulnerability*)
   atau hubungi pemilik repo secara pribadi.
3. Sertakan: versi/commit, langkah reproduksi, dampak, dan — bila memungkinkan —
   saran perbaikan.
4. Target respon awal: **7 hari**. Perbaikan dirilis secepatnya dan diumumkan
   di catatan rilis (GitHub Releases).

## Filosofi keamanan proyek

- **Tanpa telemetry**: aplikasi tidak pernah mengirim data pengguna ke mana pun.
- **Tanpa server latar belakang**: tidak ada komponen jaringan yang menyala diam-diam.
  Semua fitur server (transfer PC, sharing) hanya aktif saat pengguna memintanya,
  wajib autentikasi, dan mati otomatis setelah selesai/idle.
- Kredensial jaringan & kunci disimpan terenkripsi (Android Keystore) — tidak pernah plain-text.
- Hapus berkas melewati recycle bin yang bisa dipulihkan; hapus permanen selalu butuh konfirmasi.
- Kepatuhan yang diacu: Google Play User Data policy, UU PDP No. 27/2022 (Indonesia).
