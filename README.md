# Film Merkezi Android TV / Google TV Eklentiler

Film Merkezi Android için bağımsız, imzalı Kotlin site ve çözümleyici paketleri. Tizen JavaScript paketleri Android'e kurulmaz; P/R kodları ve sıra Tizen ile aynıdır.

**27 Kasım 2026 pilotu:** Yalnız aşağıdaki dört site / sekiz paket hazırlanmıştır. Önceden kaldırılan diğer eklentiler tekrar yayımlanmaz.

| Sağlayıcı / çözümleyici | Site | Android paket sürümü |
|---|---|---|
| P002 / R002 | [PuhuTV](https://puhutv.com/) | 1.0.0 |
| P003 / R003 | [NOW](https://www.nowtv.com.tr/) | 1.0.0 |
| P004 / R004 | [Filmci Baba](https://filmizle.ink/) | 1.0.0 |
| P005 / R005 | [Star TV](https://www.startv.com.tr/) | 1.0.0 |

**Minimum uygulama: 0.12.2 / versionCode 38**, Android 6.0 / API 23. Android eklenti sürümleri Tizen sürümlerinden bağımsızdır. Paket kodları küçük harfle `p002`, `r002` biçiminde saklanır; arayüzde P002/R002 olarak gösterilir.

İndeks: [filmmerkezi-android-repository.json](filmmerkezi-android-repository.json). Keşif işareti otomatik kurulum veya güven onayı değildir; yayıncı anahtarı uygulamada ayrıca kabul edilir.

<!-- film-merkezi-android-repository: filmmerkezi-android-repository.json -->

Uygulama ve APK: [Film-Merkezi-AndroidTV-GoogleTV](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV).

[Gerçek testler, sınırlamalar ve komutlar](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/blob/main/docs/pilot-p002-p005-report.md): dört gerçek kaynakla kısa Media3 oynatımı; imzalı DEX paketleriyle ayrı süreç testi; özgün Tizen çıktılarıyla kayıtlı yanıt karşılaştırması. Gerçek ARM TV ve uzun oynatma henüz doğrulanmadı.

Yayıncı RSA SPKI SHA-256:

```text
d31d23cccc49043e3846a9b799e1cea9464f8cd29e04daa55dd5d05c1a96fc1f
```

`P004` eski Filmci Baba kayıtlarını korumak için `filmcibaba` site kimliğini kullanır. Eski `site-filmcibaba` hâlâ kuruluysa önce Güncelleme Merkezi'nden eski site paketini kaldırın; sonra P004'ü kurun. Uygulamayı/verilerini silmeyin. Depodan kaldırmak cihazdaki eski kurulumları uzaktan silmez.

Geliştirme kaynakları `runtime/`, pilot tanımları `pilot/packages.json` içindedir. Eski `sources/` tanımları geliştirme referansıdır; kurulum indeksi değildir. Yerelde kalan eski `packages/site-*` ve `packages/resolver-*` dosyaları Git tarafından dışlanır.

Ana uygulama ve bu depo yan yana `Android`, `Android-Eklentiler` dizinlerine alınabilir; alternatif olarak `FM_ADDONS_DIR` kullanılır. Pilot paketleme aracı kapsamı açıkça ister ve canlı depoyu çıktı dizini olarak kabul etmez:

```text
android/gradlew -p android :addonRuntime:assembleDebug :pluginApi:jar
node tools/build-pilot-addons.cjs --key <depo-disi>/publisher.pem --out <yeni-deneme-dizini> --scope p002,p003,p004,p005 --revision 35
```

Komutlar ana uygulama deposunda çalıştırılır. İmzalı sürümler değiştirilemez; yeni kodda sürüm artırılır. Özel anahtarlar depoya, APK'ya veya CI'a girmez.

[SDK / bağımsız yayıncı / ağ / JSON sözleşmesi](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/blob/main/docs/android-plugin-sdk.md) · [Bağımsız örnek](examples/minimal-site/Example.java).

Paketler güvenilen yayıncı kodudur. Ayrı Android süreci aynı UID'yi kullanır; kötü niyetli kod için güvenlik sandbox'ı değildir. İmza, sürüm/hash/bağımlılık kontrolleri korunur. Yerel HTTP, DRM/üyelik/coğrafi kısıt aşma, telemetri ve ikinci oynatıcı eklenmez.
