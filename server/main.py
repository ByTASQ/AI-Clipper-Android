import os
import secrets
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

app = FastAPI(title="AI Clipper Free YouTube Downloader")
TOKEN = os.getenv("AICLIPPER_DOWNLOAD_TOKEN", "").strip()


class DownloadRequest(BaseModel):
    url: str


def check_token(value: str | None) -> None:
    if not TOKEN:
        raise HTTPException(status_code=503, detail="AICLIPPER_DOWNLOAD_TOKEN sunucuya ayarlanmamis.")
    if not value or not secrets.compare_digest(value, TOKEN):
        raise HTTPException(status_code=401, detail="Yetkisiz istek.")


def normalize_url(value: str) -> str:
    value = value.strip()
    if not value:
        raise HTTPException(status_code=400, detail="YouTube URL bos.")

    if value.startswith("https://youtu.be/"):
        video_id = value.split("youtu.be/", 1)[1].split("?", 1)[0].split("/", 1)[0]
        if video_id:
            return "https://www.youtube.com/watch?v=" + video_id

    if "youtube.com/" in value or "www.youtube.com/" in value:
        return value

    raise HTTPException(status_code=400, detail="Gecerli bir YouTube URL girin.")


@app.get("/health")
def health():
    return {"ok": True, "service": "aiclipper-youtube-downloader"}


@app.post("/download")
def download(request: DownloadRequest, x_aiclipper_token: str | None = Header(default=None)):
    check_token(x_aiclipper_token)
    url = normalize_url(request.url)
    work_dir = Path(tempfile.mkdtemp(prefix="aiclipper_"))
    output = work_dir / "%(title).80s_%(id)s.%(ext)s"

    try:
        command = [
            "yt-dlp",
            "--no-playlist",
            "--retries", "3",
            "--fragment-retries", "10",
            "--extractor-retries", "3",
            "--force-ipv4",
            "--merge-output-format", "mp4",
            "-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
            "-o", str(output),
            url,
        ]

        result = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=3600,
        )

        if result.returncode != 0:
            raise HTTPException(status_code=502, detail=result.stdout[-2500:].strip())

        files = [p for p in work_dir.iterdir() if p.is_file() and p.suffix.lower() == ".mp4"]
        if not files:
            raise HTTPException(status_code=502, detail="yt-dlp MP4 dosyasi olusturmadi.")

        file_path = max(files, key=lambda p: p.stat().st_mtime)
        return FileResponse(path=file_path, media_type="video/mp4", filename="AIClipper_source.mp4")
    except HTTPException:
        shutil.rmtree(work_dir, ignore_errors=True)
        raise
    except subprocess.TimeoutExpired:
        shutil.rmtree(work_dir, ignore_errors=True)
        raise HTTPException(status_code=504, detail="YouTube indirme zaman asimina ugradi.")
    except Exception as exc:
        shutil.rmtree(work_dir, ignore_errors=True)
        raise HTTPException(status_code=500, detail=str(exc))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8787)
