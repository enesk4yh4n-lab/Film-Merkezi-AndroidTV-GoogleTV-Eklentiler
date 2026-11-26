# Film Merkezi Android TV / Google TV Eklentiler

Film Merkezi Android için bağımsız, imzalı Kotlin site ve çözümleyici paketleri.
Android SDK v1 için 16 siteye ait 32 site/çözümleyici paketi içerir. En az Film Merkezi 0.12.0 / versionCode 36 gerekir. Tizen JavaScript paketleriyle uyumlu değildir.

İndeks: [filmmerkezi-android-repository.json](filmmerkezi-android-repository.json).
Keşif işareti bir güven veya otomatik kurulum onayı değildir. Kullanıcı yayıncı anahtarını uygulamada ayrıca kabul eder.

<!-- film-merkezi-android-repository: filmmerkezi-android-repository.json -->

Uygulama: [Film-Merkezi-AndroidTV-GoogleTV](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV).

Yayıncı RSA SPKI SHA-256:

```text
d31d23cccc49043e3846a9b799e1cea9464f8cd29e04daa55dd5d05c1a96fc1f
```

Uygulamada Güncelleme Merkezi → Adresle depo ekle üzerinden bu GitHub depo adresini girin; yayıncıyı kabul edin, istediğiniz siteyi veya tüm uyumlu siteleri kurun. Uygula ve yeniden aç ile etkinleştirin. Hiçbir depo kendiliğinden seçilmez.

`sources/*/addon.json` yayın metadata'sıdır; `runtime/src` yerleşik adaptörlerin kaynak kodudur; `packages` imzalı, değiştirilemez sürümleri içerir. Tek DEX artifact birden çok manifest tarafından kullanılabilir ve cihazda hash ile tek kopya tutulur. Her site kendi çözümleyici sürümünü açık bağımlılık olarak bildirir.

[SDK / bağımsız yayıncı / ağ / JSON sözleşmesi](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/blob/main/docs/android-plugin-sdk.md) · [Bağımsız örnek](examples/minimal-site/Example.java).

Kaynaklar ana uygulamanın yanında `Android-Eklentiler` klasörüne alınarak veya `FM_ADDONS_DIR` ile derlenir:

```text
android/gradlew -p android :native:exportSiteSpecs :addonRuntime:assembleDebug :pluginApi:jar
node tools/build-android-addons.cjs --key <depo-disinda>/publisher.pem --addons <eklenti-deposu>
```

Komutlar ana uygulama deposundan çalıştırılır. Önceden yayımlanmış `1.0.0` dosyalarını yeniden üretip üzerine yazmayın; değişen paket metadata'sında sürümü artırın. Özel anahtarlar bu depoya, APK'ya veya CI'a girmez. Yeni APK veya bütün sitelerin canlı oynatma başarısı bu indeksin varlığından çıkarılamaz; güncel uygulama teslim raporuna bakın.

Paketler güvenilen yayıncı kodudur. Ayrı Android süreci aynı UID'yi kullanır ve kötü niyetli kod için güvenlik sandbox'ı değildir. İmza, sürüm/hash/bağımlılık kontrolleri uygulanır; kullanıcı güveni gereklidir.
