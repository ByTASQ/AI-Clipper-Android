# AI Clipper Android

Android uygulaması: videolardan otomatik kısa klipler üretmek için geliştirilen kişisel AI Clipper projesi.

## Hedef
- Video seçme ve önizleme
- 9:16 Shorts / Reels / TikTok çıktısı
- Otomatik konuşma/metin analizi
- En iyi bölümleri tespit etme
- Otomatik altyazı
- Klip oluşturma ve dışa aktarma

## İlk sürüm
0.1.0 — Android Compose iskeleti ve temel arayüz.

## Teknoloji
Kotlin + Jetpack Compose + Android Media/FFmpeg altyapısı (aşamalı eklenecek).

## YouTube yayınlama

Onaylı yayın akışı eklendi:
- Google hesabı ile YouTube OAuth bağlantısı
- Üretilen klibi uygulama içinde bir kez izleme
- İzleme tamamlanmadan "Yayınla" butonu aktif olmaz
- Onay sonrası MP4 doğrudan YouTube kanalına yüklenir
- Başlık, açıklama ve hashtagler AI çıktısından kullanılır
- Onaylanan video herkese açık olarak yayınlanır

### Google Cloud kurulumu
1. Google Cloud Console'da bir proje oluştur.
2. **YouTube Data API v3** API'sini etkinleştir.
3. OAuth consent screen'i yapılandır.
4. Android OAuth client oluştururken uygulamanın package name'i `com.bytasq.aiclipper` ve Android SHA-1 sertifika parmak izini ekle.
5. Uygulamayı imzalı APK ile kullanacaksan ilgili release keystore SHA-1 değerini de OAuth istemcisine tanımla.
6. Test kullanıcıları gerekiyorsa YouTube hesabını OAuth consent screen'deki test kullanıcılarına ekle.

Uygulama YouTube şifresini saklamaz; Google OAuth yetkilendirmesi kullanır.
