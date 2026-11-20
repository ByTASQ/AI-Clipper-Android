import json
import re
from youtube_transcript_api import YouTubeTranscriptApi

def _video_id(value):
    value = (value or "").strip()
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", value):
        return value

    patterns = [
        r"(?:youtube\\.com/watch\\?[^#]*?v=)([A-Za-z0-9_-]{11})",
        r"(?:youtu\\.be/)([A-Za-z0-9_-]{11})",
        r"(?:youtube\\.com/shorts/)([A-Za-z0-9_-]{11})",
        r"(?:youtube\\.com/embed/)([A-Za-z0-9_-]{11})",
    ]

    for pattern in patterns:
        match = re.search(pattern, value)
        if match:
            return match.group(1)

    return None


def fetch(url_or_video_id, languages=None):
    video_id = _video_id(url_or_video_id)
    if not video_id:
        raise RuntimeError("YouTube video kimliği alınamadı.")

    languages = languages or ["tr", "tr-TR", "en", "en-US"]

    api = YouTubeTranscriptApi()

    try:
        transcript = api.fetch(video_id, languages=languages)
        items = transcript.to_raw_data()
    except AttributeError:
        items = api.get_transcript(video_id, languages=languages)

    result = []
    for item in items:
        text = str(item.get("text", "")).strip()
        start = float(item.get("start", 0.0))
        duration = float(item.get("duration", 0.0))
        if text and duration > 0:
            result.append({
                "start": start,
                "end": start + duration,
                "text": text
            })

    if not result:
        raise RuntimeError("YouTube için kullanılabilir altyazı bulunamadı.")

    return json.dumps(result, ensure_ascii=False)
