import os
import yt_dlp

def download(url, output_dir, cookie_file=None, user_agent=None):
    os.makedirs(output_dir, exist_ok=True)

    output = os.path.join(output_dir, "aiclipper_source.%(ext)s")

    options = {
        "outtmpl": output,
        "noplaylist": True,
        "quiet": False,
        "no_warnings": False,
        "retries": 3,
        "fragment_retries": 5,
        "extractor_retries": 3,
        "forceipv4": True,
        "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
        "merge_output_format": "mp4",
        "extractor_args": {
            "youtube": {
                "player_client": ["web_safari", "android_vr", "android", "web"]
            }
        },
        "http_headers": {
            "Referer": "https://www.youtube.com/",
            "Origin": "https://www.youtube.com/",
            "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        },
    }

    if cookie_file and os.path.exists(cookie_file):
        options["cookiefile"] = cookie_file

    if user_agent:
        options["http_headers"]["User-Agent"] = user_agent

    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        prepared = ydl.prepare_filename(info)

        if os.path.exists(prepared):
            return prepared

        base, _ = os.path.splitext(prepared)
        mp4 = base + ".mp4"

        if os.path.exists(mp4):
            return mp4

        raise RuntimeError("yt-dlp indirdi fakat MP4 dosyası bulunamadı.")
