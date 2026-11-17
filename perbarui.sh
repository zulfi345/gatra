#!/usr/bin/env bash
# perbarui.sh - perbarui pustaka NewPipeExtractor ke versi terbaru, naikkan versionCode,
# push ke GitHub, lalu pantau build sampai selesai.
#
# Pemakaian (di folder Gatra, Termux):
#   bash perbarui.sh           -> hanya bekerja bila ada versi extractor yang lebih baru
#   bash perbarui.sh paksa     -> bangun ulang walau versinya sama (versionCode tetap dinaikkan)
set -u
cd "$(dirname "$0")" || exit 1

APP="app/build.gradle.kts"
DESK="desktop/build.gradle.kts"
PAKSA="${1:-}"

command -v gh >/dev/null 2>&1 || { echo "gh belum terpasang. Jalankan: pkg install gh -y"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "git belum terpasang. Jalankan: pkg install git -y"; exit 1; }
[ -f "$APP" ] || { echo "File $APP tidak ditemukan. Jalankan dari folder Gatra."; exit 1; }

echo "Mengecek versi NewPipeExtractor terbaru..."
TERBARU=$(gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest --jq .tag_name 2>/dev/null)
if [ -z "$TERBARU" ]; then
  TERBARU=$(gh api repos/TeamNewPipe/NewPipeExtractor/tags --jq '.[0].name' 2>/dev/null)
fi
if ! echo "$TERBARU" | grep -qE '^v[0-9]+(\.[0-9]+)+$'; then
  echo "Gagal membaca versi terbaru (hasil: '$TERBARU'). Periksa internet dan 'gh auth status'."
  exit 1
fi

SEKARANG=$(grep -oE 'NewPipeExtractor:v[0-9.]+' "$APP" | head -1 | sed 's/.*://')
echo "Versi sekarang: ${SEKARANG:-tidak diketahui}"
echo "Versi terbaru : $TERBARU"

if [ "$SEKARANG" = "$TERBARU" ] && [ "$PAKSA" != "paksa" ]; then
  echo "Sudah versi terbaru, tidak ada yang perlu diperbarui."
  echo "Kalau YouTube tetap rusak, berarti tim NewPipe belum merilis perbaikannya. Coba lagi nanti."
  echo "(Untuk tetap membangun ulang: bash perbarui.sh paksa)"
  exit 0
fi

# 1. Ganti versi extractor (HP dan PC)
sed -i -E "s/NewPipeExtractor:v[0-9.]+/NewPipeExtractor:$TERBARU/" "$APP"
[ -f "$DESK" ] && sed -i -E "s/NewPipeExtractor:v[0-9.]+/NewPipeExtractor:$TERBARU/" "$DESK"

# 2. Naikkan versionCode supaya APK baru bisa menimpa yang lama
KODE=$(grep -oE 'versionCode = [0-9]+' "$APP" | grep -oE '[0-9]+' | head -1)
KODE=${KODE:-1}
BARU=$((KODE + 1))
sed -i -E "s/(versionCode = )[0-9]+/\1$BARU/" "$APP"
echo "versionCode: $KODE -> $BARU"

# 3. Commit dan push
git add -A
git commit -m "Perbarui NewPipeExtractor ke $TERBARU (versionCode $BARU)" || echo "(tidak ada perubahan baru untuk di-commit)"
if ! git push; then
  echo "Push gagal. Periksa internet dan 'gh auth status', lalu jalankan lagi."
  exit 1
fi

# 4. Cari build yang baru dimulai
SHA=$(git rev-parse HEAD)
echo "Menunggu build dimulai..."
ID=""
for _ in $(seq 1 15); do
  ID=$(gh run list --workflow "Build Gatra APK" --limit 5 --json databaseId,headSha \
        --jq ".[] | select(.headSha==\"$SHA\") | .databaseId" 2>/dev/null | head -1)
  [ -n "$ID" ] && break
  sleep 4
done
if [ -z "$ID" ]; then
  echo "Build belum terlihat. Cek manual: gh run list --limit 3"
  exit 0
fi

# 5. Pantau sampai selesai
echo "Memantau build $ID (Ctrl+C untuk berhenti memantau; build tetap berjalan di GitHub)"
while true; do
  STATUS=$(gh run view "$ID" --json status --jq .status 2>/dev/null)
  [ "$STATUS" = "completed" ] && break
  echo "  ... masih berjalan ($(date +%H:%M:%S))"
  sleep 15
done
HASIL=$(gh run view "$ID" --json conclusion --jq .conclusion 2>/dev/null)

if [ "$HASIL" = "success" ]; then
  echo "Build BERHASIL."
  if [ -d "$HOME/storage/downloads" ]; then
    TUJUAN="$HOME/storage/downloads/Gatra-APK-v$BARU"
  else
    TUJUAN="$HOME/Gatra-APK-v$BARU"
  fi
  if gh run download "$ID" -n Gatra-APK -D "$TUJUAN" >/dev/null 2>&1; then
    echo "APK tersimpan di: $TUJUAN"
    echo "Buka file itu dari aplikasi File, lalu pasang di atas Gatra yang lama."
  else
    echo "Unduh manual: buka tab Actions di GitHub lalu ambil artifact Gatra-APK."
  fi
else
  echo "Build GAGAL ($HASIL). Mengambil log error..."
  gh run view "$ID" --log-failed > "$HOME/log.txt" 2>&1
  echo "---- baris error ----"
  grep "e: file" "$HOME/log.txt" | head -20
  echo "---------------------"
  echo "Log lengkap: ~/log.txt. Kirim baris 'e: file' di atas ke Claude untuk diperbaiki."
fi
echo "Versi PC dibangun terpisah. Cek: gh run list --workflow \"Build Gatra untuk Windows (PC)\" --limit 1"
