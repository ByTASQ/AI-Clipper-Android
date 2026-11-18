import os
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask
from pydantic import BaseModel, HttpUrl

app = FastAPI(title="AI Clipper YouTube Downloader")

DOWNLOAD_ROOT = Path(os.getenv("AI_CLIPPER_DOWNLOAD_ROOT", tempfile.gettempdir())) / "ai_clipper"
DOWNLOAD_ROOT.mkdir(parents=True, exist_ok=True)

class DownloadRequest(BaseModel):
    url: HttpUrl

def run_ytdlp(url: str, output: Path) -> Path:
    cmd = [
        "yt-dlp",
        "--no-playlist",
        "--no-warnings",
        "--retries", "5",
        "--fragment-retries", "5",
        "--merge-output-format", "mp4",
        "-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
        "-o", str(output / "%(id)s.%(ext)s"),
        url,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "YouTube indirme başarısız.")[-2000:]
        raise RuntimeError(detail)

    mp4_files = list(output.glob("*.mp4"))
    if not mp4_files:
        raise RuntimeError("yt-dlp tamamlandı ancak MP4 oluşturulmadı.")
    return max(mp4_files, key=lambda p: p.stat().st_mtime)

@app.post("/aiclipper/youtube/download")
def download_youtube(request: DownloadRequest):
    job_dir = DOWNLOAD_ROOT / uuid.uuid4().hex
    job_dir.mkdir(parents=True, exist_ok=True)

    try:
        video = run_ytdlp(str(request.url), job_dir)
        return FileResponse(
            video,
            media_type="video/mp4",
            filename="aiclipper_source.mp4",
            background=BackgroundTask(shutil.rmtree, job_dir, ignore_errors=True),
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="YouTube indirme zaman aşımına uğradı.")
    except Exception as exc:
        shutil.rmtree(job_dir, ignore_errors=True)
        raise HTTPException(status_code=502, detail=str(exc))
