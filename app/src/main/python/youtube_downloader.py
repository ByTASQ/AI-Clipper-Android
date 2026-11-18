import os
import yt_dlp


def download(url, output_dir, cookie_file=None, user_agent=None):
    os.makedirs(output_dir, exist_ok=True)

    output = os.path.join(
        output_dir,
        "aiclipper_source.%(ext)s"
    )

    # YouTube currently restricts several GVS clients with PO-token
    # requirements. web_safari exposes HLS formats which can be fetched
    # without a PO token, while android_vr/tv provide additional fallbacks.
    options = {
        "outtmpl": output,
        "noplaylist": True,
        "quiet": False,
        "no_warnings": False,
        "retries": 3,
        "fragment_retries": 8,
        "extractor_retries": 3,
        "forceipv4": True,
        "extractor_args": {
            "youtube": {
                "player_client": [
                    "web_safari",
                    "android_vr",
                    "tv",
                    "android",
                ]
            }
        },
        "format": (
            "best[protocol^=m3u8]/"
            "best[ext=mp4]/"
            "best"
        ),
        "merge_output_format": "mp4",
    }

    if cookie_file and os.path.exists(cookie_file):
        options["cookiefile"] = cookie_file

    if user_agent:
        options["http_headers"] = {
            "User-Agent": user_agent
        }

    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        prepared = ydl.prepare_filename(info)

        if os.path.exists(prepared):
            return prepared

        base, _ = os.path.splitext(prepared)
        mp4 = base + ".mp4"

        if os.path.exists(mp4):
            return mp4

        candidates = [
            name for name in os.listdir(output_dir)
            if name.startswith("aiclipper_source") and name.endswith(".mp4")
        ]
        if candidates:
            candidates.sort(
                key=lambda name: os.path.getmtime(os.path.join(output_dir, name)),
                reverse=True,
            )
            return os.path.join(output_dir, candidates[0])

        raise RuntimeError(
            "yt-dlp indirdi fakat MP4 dosyası bulunamadı."
        )
