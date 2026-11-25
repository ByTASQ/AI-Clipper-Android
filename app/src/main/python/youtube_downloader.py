import json
import os
import re
import time
import yt_dlp

def _write_progress(progress_file, state, percent, message, path=""):
    payload = {
        "state": state,
        "percent": max(0, min(100, int(percent))),
        "message": message,
        "path": path,
        "timestamp": time.time()
    }
    tmp = progress_file + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)
    os.replace(tmp, progress_file)

def _hook(progress_file):
    def callback(d):
        status = d.get("status")
        if status == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            downloaded = d.get("downloaded_bytes") or 0
            percent = (downloaded * 100 / total) if total else 0
            _write_progress(progress_file, "downloading", percent,
                            "Video indiriliyor... %{:.0f}".format(percent))
        elif status == "finished":
            _write_progress(progress_file, "merging", 99, "Ses ve görüntü birleştiriliyor...")
    return callback

def download(url, output_dir, job_id):
    if not re.match(r"^https?://(?:(?:www\.)?youtube\.com|youtu\.be)/", url, re.I):
        raise RuntimeError("Geçerli bir YouTube bağlantısı girilmedi.")

    os.makedirs(output_dir, exist_ok=True)
    progress_file = os.path.join(output_dir, job_id + ".progress.json")
    target = os.path.join(output_dir, job_id + ".%(ext)s")

    _write_progress(progress_file, "starting", 0, "YouTube videosu hazırlanıyor...")

    options = {
        "outtmpl": target,
        "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
        "merge_output_format": "mp4",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "retries": 3,
        "fragment_retries": 3,
        "concurrent_fragment_downloads": 2,
        "progress_hooks": [_hook(progress_file)],
    }

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)

        candidates = []
        requested = info.get("requested_downloads") or []
        for item in requested:
            p = item.get("filepath") or item.get("filename")
            if p:
                candidates.append(p)

        candidates.extend([
            os.path.join(output_dir, job_id + ".mp4"),
            os.path.join(output_dir, job_id + ".mkv"),
            os.path.join(output_dir, job_id + ".webm")
        ])

        existing = [p for p in candidates if p and os.path.isfile(p) and os.path.getsize(p) > 1024]
        if not existing:
            prefix = job_id + "."
            existing = [
                os.path.join(output_dir, name)
                for name in os.listdir(output_dir)
                if name.startswith(prefix) and name.lower().endswith((".mp4", ".mkv", ".webm"))
            ]

        if not existing:
            raise RuntimeError("YouTube videosu indirildi ancak video dosyası bulunamadı.")

        final = max(existing, key=os.path.getsize)
        _write_progress(progress_file, "completed", 100, "Video indirildi.", final)
        return json.dumps({"path": final}, ensure_ascii=False)
    except Exception as exc:
        _write_progress(progress_file, "failed", 0, "İndirme hatası: " + str(exc))
        raise
