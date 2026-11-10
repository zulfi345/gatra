# Gatra

Pemutar YouTube tanpa iklan, tanpa login, untuk Android. Tampilan gelap dengan aksen emas dan biru.

## Fitur
- Beranda: video terbaru dari channel yang kamu ikuti, rekomendasi dari video yang pernah kamu tonton, dan kategori (musik, game, berita, dll).
- Cari video dan channel, dengan daftar pencarian terakhir.
- Pemutar sendiri (ExoPlayer + NewPipeExtractor): tanpa iklan, pilihan kualitas sampai 1080p, layar penuh.
- Layar kecil (Picture-in-Picture): tekan Home saat video diputar.
- Putar di latar belakang: layar boleh mati atau pindah aplikasi. Ada mini-player di bawah.
- Langganan lokal: disimpan di HP, tanpa akun. Bisa impor dari Google Takeout (`subscriptions.csv`) dan ekspor.
- Riwayat tontonan lokal (judul saja, bukan file video). Tidak ada video yang diunduh.
- Tidak ada notifikasi video baru. Satu-satunya notifikasi: kontrol pemutar saat video berjalan.
- Buka tautan YouTube: tombol Bagikan dari aplikasi lain, atau ketuk tautan youtube.com / youtu.be.

## Cara build lewat GitHub (cukup dari HP)
1. Buat repo baru di GitHub. Pilih **Private** (keystore penandatangan ada di repo).
2. Taruh seluruh isi folder proyek ini di repo, termasuk folder tersembunyi `.github`.
   - Lewat Termux:
     ```
     pkg install git unzip
     unzip Gatra.zip
     cd Gatra
     git init
     git add -A
     git commit -m "Gatra"
     git branch -M main
     git remote add origin https://github.com/NAMAKAMU/NAMAREPO.git
     git push -u origin main
     ```
   - Unggah lewat web GitHub tidak bisa membuka zip. Folder harus diunggah satu per satu, jadi Termux lebih mudah.
3. Buka tab **Actions** di repo. Workflow "Build Gatra APK" jalan otomatis setelah push (atau tekan **Run workflow**).
4. Setelah centang hijau (sekitar 5 sampai 10 menit pertama), buka run-nya, turun ke **Artifacts**, unduh **Gatra-APK**, ekstrak, lalu pasang `Gatra.apk`.
5. Update berikutnya: ubah kode, push lagi, unduh APK baru, pasang di atas yang lama (tanda tangan sama, data tetap aman).

## Kalau video berhenti bisa diputar
YouTube sering mengubah sistemnya. Perbaikannya biasanya cukup menaikkan versi NewPipeExtractor di `app/build.gradle.kts`:
```
implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
```
Cek versi terbaru di https://github.com/TeamNewPipe/NewPipeExtractor/releases, ganti angkanya, push, lalu build ulang.

## Kalau build gagal
Buka run yang gagal di tab Actions, salin bagian log yang berwarna merah (baris `error:` atau `e: file://...`), lalu kirim ke Claude untuk diperbaiki.

## Catatan
- Aplikasi ini tidak resmi dan tidak berafiliasi dengan YouTube/Google. Memblokir iklan melanggar ketentuan layanan YouTube; pakai untuk keperluan pribadi dan jangan dibagikan luas.
- Keystore `gatra-release.jks` (kata sandi `gatra2026`) hanya untuk pemakaian pribadi. Jangan jadikan repo publik.
- Notifikasi Android 13+: kalau kamu ingin kontrol pemutar muncul di layar kunci, izinkan notifikasi untuk Gatra di pengaturan HP. Tanpa izin, pemutaran latar belakang tetap jalan.
- Aliran video diambil per potongan (`YtDataSource.kt`) seperti NewPipe. Jika video tertentu gagal diputar dengan kode kesalahan HTTP, bagian itulah yang pertama diperiksa.

## Logo
Ikon aplikasi dan logo di bar atas memakai lambang Gatra (elang dan pusaran biru). Berkas: `app/src/main/res/drawable-*dpi/ic_launcher_fg.png` (ikon) dan `drawable-nodpi/logo_emblem.png` (di dalam aplikasi). Ganti berkas itu untuk mengubah logo.

## Struktur kode
- `data/` : model, database lokal (Room), koneksi ke extractor, repositori (pencarian, channel, feed RSS, langganan).
- `player/` : `PlaybackController` (pilih stream + kualitas), `YtDataSource` (unduh per potongan), `PlaybackService` (latar belakang).
- `ui/` : tema emas-biru, komponen, Beranda, Cari, Langganan, Riwayat, Channel, Pemutar, navigasi.
