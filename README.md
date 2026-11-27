# Film Merkezi Android TV / Google TV Eklentiler

Film Merkezi Android için bağımsız, imzalı Kotlin site ve çözümleyici paketleri.
**27 Kasım 2026:** Yayımlanmış 32 site/çözümleyici paketi inceleme ve düzeltme için kaldırıldı. Güncel imzalı indeks boştur; bu depodan kurulabilir eklenti sunulmamaktadır. Tizen JavaScript paketleriyle uyumlu değildir.

İndeks: [filmmerkezi-android-repository.json](filmmerkezi-android-repository.json).
Keşif işareti bir güven veya otomatik kurulum onayı değildir. Kullanıcı yayıncı anahtarını uygulamada ayrıca kabul eder.

<!-- film-merkezi-android-repository: filmmerkezi-android-repository.json -->

Uygulama: [Film-Merkezi-AndroidTV-GoogleTV](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV).

Yayıncı RSA SPKI SHA-256:

```text
d31d23cccc49043e3846a9b799e1cea9464f8cd29e04daa55dd5d05c1a96fc1f
```

Güncel indekste **0 paket** bulunur. Bu kaldırma işlemi cihazlara daha önce kurulmuş eklentileri veya izleme kayıtlarını uzaktan silmez.

`runtime/src`, `sources` ve SDK örneği geliştirme kaynakları olarak korunmuştur. Kurulum dosyalarını sunan `packages` dizini ve eski `builtin-v1.0.0` yayın etiketi kaldırılmıştır. Yerel paket kopyaları inceleme için korunur; Git'e yeniden eklenmez.

[SDK / bağımsız yayıncı / ağ / JSON sözleşmesi](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/blob/main/docs/android-plugin-sdk.md) · [Bağımsız örnek](examples/minimal-site/Example.java).

Kaynaklar ana uygulamanın yanında `Android-Eklentiler` klasörüne alınarak veya `FM_ADDONS_DIR` ile derlenir:

```text
android/gradlew -p android :native:exportSiteSpecs :addonRuntime:assembleDebug :pluginApi:jar
node tools/build-android-addons.cjs --key <depo-disinda>/publisher.pem --addons <eklenti-deposu>
```

Komutlar geliştirme referansıdır; şu anda paket yayını yapılmaz. Yeniden yayın kararı verilirse sürümler artırılmalı ve yerel yayın dışlama kuralı bilinçli olarak değiştirilmelidir. Özel anahtarlar bu depoya, APK'ya veya CI'a girmez.

Paketler güvenilen yayıncı kodudur. Ayrı Android süreci aynı UID'yi kullanır ve kötü niyetli kod için güvenlik sandbox'ı değildir. İmza, sürüm/hash/bağımlılık kontrolleri uygulanır; kullanıcı güveni gereklidir.
