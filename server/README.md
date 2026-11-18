# AI Clipper YouTube Download Backend

Bu servis Android uygulamasının YouTube URL'sini sunucu tarafında güncel yt-dlp ile çözer ve MP4 olarak döndürür.

## Kurulum

Python 3.11+:

    pip install -r requirements.txt

Sunucuda güncel yt-dlp kurulmalı:

    python -m pip install -U yt-dlp

Çalıştırma:

    uvicorn youtube_downloader:app --host 0.0.0.0 --port 9010

Endpoint:

    POST /aiclipper/youtube/download

JSON:

    {"url":"https://www.youtube.com/watch?v=..."}

Yanıt MP4 dosyasıdır.

## Cloudflare Tunnel

9010 portunu mevcut Cloudflare Tunnel üzerinden yayınlayın. Android uygulaması:

    https://api2.elektronikatolyesi.site/aiclipper/youtube/download

adresini kullanacak şekilde yapılandırılmıştır.

Not: Bu endpoint'i internete açacaksanız rate limit ve erişim anahtarı eklemeniz önerilir.
