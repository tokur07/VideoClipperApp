# Video Clipper Pro (Android APK)

Aplikasi pemotong video Android untuk TikTok, YouTube Shorts, dan Instagram Reels dengan dukungan:
- **Sumber Multi-Platform:** Galeri HP, YouTube (hingga 4K DASH via `yt-dlp` + `FFmpeg`), direct stream (`.mp4`, `.m3u8`), dan tombol **Share** langsung dari aplikasi YouTube.
- **Pratinjau & Pemotongan Presisi:** Pemutar `ExoPlayer` bawaan, mode *Single Trim*, *Batch Multi-Timestamp*, dan *Auto-Split Series*.
- **Tata Letak 9:16:** *Fit + Blurred Background* dan *Full Center Crop*.
- **Teks Auto & Manual:** Auto-Subtitle CC YouTube, impor `.SRT` manual, teks *Hook* judul + *Auto-Part*, dan *Watermark* di area *safe-zone*.
- **Background Rendering:** Berjalan di latar belakang menggunakan *Foreground Service* dengan progres notifikasi.

## Cara Build APK di GitHub Actions
1. Unggah seluruh isi folder `VideoClipperApp` ini ke root repositori GitHub Anda.
2. Buka tab **Actions** -> pilih **Build Android APK** -> klik **Run workflow**.
3. Setelah selesai, unduh file APK pada bagian **Artifacts** (`VideoClipper-Pro-apk`).
