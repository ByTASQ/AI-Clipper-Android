# AI Clipper - ucretsiz YouTube indirme sunucusu

Bu servis YouTube indirme islemini OpenAI API kullanmadan yapar. YouTube indirme asamasinda OpenAI kredisi harcanmaz.

## Gerekenler

- Windows 10/11
- Python 3.11+
- Internet baglantisi

## Kurulum

1. Bu server klasorunu Windows bilgisayarina alin.
2. Guvenli bir token belirleyin.
3. Komut isteminde:

    set AICLIPPER_DOWNLOAD_TOKEN=KENDI_TOKENIN
    server\start_server.bat

Sunucu 0.0.0.0:8787 portunda calisir.

Test:
http://127.0.0.1:8787/health

## Telefon

Ayni Wi-Fi'da:
http://BILGISAYAR_IP_ADRESI:8787

Internet uzerinden kullanacaksaniz bu kendi sunucunuzu Cloudflare Tunnel gibi bir tunel ile yayinlayabilirsiniz. Eski veya harici bir AI Clipper adresine bagimlilik yoktur.

Telefon uygulamasinda sunucu adresi ve ayni token ayarlanir.

## Guvenlik

Sunucuyu internete token olmadan acmayin. AICLIPPER_DOWNLOAD_TOKEN zorunludur.

## Kota

Bu servis OpenAI kullanmaz. YouTube indirme icin OpenAI kredisi gerekmez. Internet trafigi ve sunucunun calisma maliyeti size aittir.

## Kullanim

Yalnizca indirme hakkiniz olan veya YouTube'un izin verdigi icerikleri indirin. yt-dlp, YouTube tarafindaki istemci/PO token degisikliklerinden etkilenebilir.
