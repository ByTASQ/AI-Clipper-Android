import os
import time
import json
import yt_dlp


def download(url, output_dir, cookie_file=None, user_agent=None):
    os.makedirs(output_dir, exist_ok=True)

    output = os.path.join(
        output_dir,
        "aiclipper_source_%d.%%(ext)s" % time.time_ns()
    )
    progress_path = os.path.join(
        output_dir,
        "aiclipper_download_progress.json"
    )

    def write_progress(status, downloaded=0, total=0, percent=0.0):
        try:
            payload = {
                "status": status,
                "downloaded": int(downloaded or 0),
                "total": int(total or 0),
                "percent": max(0.0, min(100.0, float(percent or 0.0)))
            }
            tmp = progress_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(payload, f)
            os.replace(tmp, progress_path)
        except Exception:
            pass

    def progress_hook(data):
        try:
            status = data.get("status", "")
            downloaded = data.get("downloaded_bytes", 0) or 0
            total = (
                data.get("total_bytes")
                or data.get("total_bytes_estimate")
                or 0
            )

            percent = 0.0
            if total > 0:
                percent = downloaded * 100.0 / total
            else:
                raw = str(data.get("_percent_str", "0%"))
                raw = raw.replace("%", "").strip()
                try:
                    percent = float(raw)
                except Exception:
                    percent = 0.0

            if status == "finished":
                percent = 100.0

            write_progress(status, downloaded, total, percent)
        except Exception:
            pass

    # Android/Chaquopy tarafında ayrı bir ffmpeg binary'sine güvenmeden
    # doğrudan MP4 alınabilen formatları tercih et. bestvideo+bestaudio
    # seçimi, Python ortamında harici ffmpeg yoksa indirme sonrası
    # birleştirme aşamasında takılabiliyordu.
    options = {
        "outtmpl": output,
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "retries": 3,
        "fragment_retries": 3,
        "extractor_retries": 2,
        "socket_timeout": 15,
        "progress_hooks": [progress_hook],
        "forceipv4": True,
        "format": (
            "best[ext=mp4]/"
            "bestvideo[ext=mp4]+bestaudio[ext=m4a]/"
            "best"
        ),
        "merge_output_format": "mp4",
        "extractor_args": {
            "youtube": {
                "player_client": [
                    "web_safari",
                    "android_vr",
                    "android",
                    "web"
                ]
            }
        },
        "http_headers": {
            "Referer": "https://www.youtube.com/",
            "Origin": "https://www.youtube.com/",
            "Accept-Language": (
                "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        },
    }

    if cookie_file and os.path.exists(cookie_file):
        options["cookiefile"] = cookie_file

    if user_agent:
        options["http_headers"]["User-Agent"] = user_agent

    write_progress("starting", 0, 0, 0)

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
            prepared = ydl.prepare_filename(info)

            if os.path.exists(prepared):
                write_progress("finished", 1, 1, 100)
                return prepared

            base, _ = os.path.splitext(prepared)
            mp4 = base + ".mp4"

            if os.path.exists(mp4):
                write_progress("finished", 1, 1, 100)
                return mp4

            # yt-dlp bazen uzantıyı formatın gerçek çıktısına göre değiştirir.
            # Aynı kaynak adına sahip video dosyasını güvenli biçimde bul.
            stem = os.path.splitext(prepared)[0]
            candidates = [
                name for name in os.listdir(output_dir)
                if name.startswith(os.path.basename(stem) + ".")
            ]
            for name in candidates:
                candidate = os.path.join(output_dir, name)
                if os.path.isfile(candidate) and os.path.getsize(candidate) > 0:
                    write_progress("finished", 1, 1, 100)
                    return candidate

            raise RuntimeError(
                "yt-dlp indirdi fakat kullanılabilir video dosyası bulunamadı."
            )
    except Exception:
        write_progress("error", 0, 0, 0)
        raise
