# Gatra Video

Pemutar YouTube tanpa iklan, tanpa login, untuk Android. Tampilan gelap dengan aksen emas dan biru.

## Fitur
- Beranda: video terbaru dari channel yang kamu ikuti, rekomendasi dari video yang pernah kamu tonton, dan kategori (musik, game, berita, dll).
- Tarik layar ke bawah untuk menyegarkan (atau tekan tombol muat ulang): setiap kali ada rekomendasi video baru.
- Foto profil asli channel di setiap video.
- Cari video dan channel, dengan daftar pencarian terakhir.
- Pemutar sendiri (ExoPlayer + NewPipeExtractor): tanpa iklan, pilihan kualitas sampai 1080p, layar penuh.
- Layar kecil (Picture-in-Picture): tekan Home saat video diputar.
- Putar di latar belakang: layar boleh mati atau pindah aplikasi. Ada mini-player di bawah.
- Langganan lokal: disimpan di HP, tanpa akun. Bisa impor dari Google Takeout (`subscriptions.csv`) dan ekspor.
- Riwayat tontonan lokal (judul saja, bukan file video).
- Tidak ada notifikasi video baru. Notifikasi hanya untuk kontrol pemutar dan kemajuan unduhan.
- Layar tetap menyala selama video diputar (kecuali mode audio saja).
- Kualitas Otomatis (menyesuaikan kecepatan internet), Mode Lancar 720p (prabuffer + berkas terkecil), dan cache disk 300 MB.
- Kecepatan putar 0,5x–2x, subtitle, mode audio saja, dan pengatur waktu tidur (menu di pojok video).
- Gestur: ketuk dua kali kiri/kanan = mundur/maju 10 detik; geser kiri = kecerahan, kanan = volume.
- Putar otomatis video berikutnya dan antrean (tombol + di video terkait).
- Koleksi: Tonton nanti, Playlist, Unduhan (tonton tanpa internet), dan Pengaturan.
- Komentar video.
- Rekomendasi Beranda dengan algoritma lokal (skor channel + kata kunci dari riwayat, sinyal durasi tonton, keragaman, eksplorasi 20%). Semua dihitung di HP, tidak ada data yang dikirim.
- Kontrol pemutar rapi: tombol panah = video sebelumnya/berikutnya, tombol pengaturan (gir) ikut hilang bersama kontrol.
- Bahasa audio utama permanen (mis. dubbing Indonesia) di Pengaturan; berubah otomatis bila kamu memilih bahasa lain di pemutar.
- Tab Shorts: video vertikal pendek, digeser atas-bawah (daftar disusun dari pencarian karena YouTube tidak menyediakan daftar Shorts resmi).
- Titik tiga di setiap video, tombol video sebelumnya/berikutnya, dan pilihan bahasa audio (dubbing) bila tersedia.
- SponsorBlock (opsional, mati secara bawaan): melewati bagian sponsor lewat sponsor.ajay.app.
- Gulir tanpa batas: Beranda (Untukmu dan tiap kategori), Pencarian, Channel, Langganan, Riwayat, video terkait di pemutar, dan isi playlist. Halaman berikutnya dimuat otomatis saat daftar hampir habis.
- Halaman channel punya tab Video, Live, dan Playlist. Playlist bisa dibuka, diputar semua, dan digulir tanpa batas.
- Gir di pojok kanan atas video hanya untuk kualitas (Otomatis, 1080p, 720p, 480p, dst). Pengaturan lain (bahasa audio, kecepatan, subtitle, mode audio saja, Mode Lancar, waktu tidur, sebelumnya/berikutnya) ada di gir pada bilah kontrol bawah.
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

## Rekomendasi "Untukmu" (jatah genre)
Beranda dibagi per genre: 70% genre yang paling sering kamu tonton (sesuai porsinya), 20% genre bertetangga, 10% genre baru. Tidak ada batas jumlah per channel; isi tiap jatah boleh dari channel mana saja. Hanya "Untukmu" yang memakai ini. Langganan, kategori, pencarian, dan halaman channel tidak berubah. Angka 70/20/10 ada di `Ranking.kt` (`SHARE_FAVORITE`, `SHARE_NEIGHBOR`, `SHARE_NEW`), daftar genre dan kata-katanya di `Genres.kt`.

## Versi PC (Windows)
Folder `desktop/` berisi Gatra untuk Windows. Proyeknya terpisah dari Android, jadi build APK tidak terpengaruh. Bukan emulator: ini aplikasi Windows asli (Compose Desktop).

1. Push seluruh isi repo seperti biasa. Workflow **Build Gatra untuk Windows (PC)** jalan otomatis (atau tekan **Run workflow** di tab Actions).
2. Setelah centang hijau, unduh artifact **Gatra-Windows**. Isinya:
   - `Gatra-Windows-Portabel.zip`: ekstrak, lalu buka `Gatra\Gatra.exe`. Tidak perlu Java.
   - `Gatra-Windows-Installer.msi`: installer biasa (ada bila langkah MSI berhasil).
3. Pasang **VLC 64-bit** dari videolan.org (sekali saja). Gatra memakai VLC untuk memutar video di PC.

Fitur versi PC saat ini: Beranda per kategori dengan gulir tanpa batas, pencarian, pemutar (putar/jeda, geser waktu, volume, pilihan kualitas sampai 1080p, layar penuh), tanpa iklan dan tanpa login. Langganan, riwayat, Shorts, unduhan, dan komentar belum ada di versi PC.

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
