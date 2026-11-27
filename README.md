# Film Merkezi — Android TV / Google TV Eklentiler

Android için bağımsız, imzalı site ve kaynak çözümleyici paketleri. P/R kodları Tizen ile aynı sırayı izler; Android paketleri bağımsız sürümlenir. Tizen JavaScript paketleri Android'e kurulmaz.

## Mevcut kurulum kapsamı

| Site eklentisi | Kaynak eklentisi | Paket sürümü |
|---|---|---|
| P002 | R002 | 1.0.0 |
| P003 | R003 | 1.0.0 |
| P004 | R004 | 1.0.0 |
| P005 | R005 | 1.0.0 |

Minimum uygulama **0.12.2 / 38**, Android API 23. Kurulabilir paketlerin tek kaynağı [imzalı indeks](filmmerkezi-android-repository.json) dosyasıdır. Site adları ve adresleri paket metadatasındadır; APK içinde sabit bir site kataloğu gerektirmez.

<!-- film-merkezi-android-repository: filmmerkezi-android-repository.json -->

[Uygulama ve APK](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/releases) · [SDK ve paket sözleşmesi](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/blob/main/docs/android-plugin-sdk.md).

Depo keşfi otomatik kurulum veya güven onayı değildir. Yayıncı anahtarı kullanıcı tarafından ayrıca onaylanır. Yayıncı RSA SPKI SHA-256:

```text
d31d23cccc49043e3846a9b799e1cea9464f8cd29e04daa55dd5d05c1a96fc1f
```

## Yayın kuralları

- Hazırlama ve test isteği yayın onayı sayılmaz. Push, dosya yükleme, Release, PR ve depo ayarları için ilgili kapsama açık kullanıcı onayı gerekir.
- Sürüm numarası yayın onayından sonra, mevcut imzalı indeks kontrol edilerek belirlenir. Onay öncesi çalışmalar konu adıyla yerelde tutulur.
- Yayımlanmış paket, manifest veya imzalı indeks aynı sürüm/revizyonda değiştirilmez. Açıklama düzenlemek imzalı paketleri yeniden yayımlamak değildir.
- Site/çözümleyici P/R eşleşmesi ve mevcut kimlik korunur. Kaldırılmış paketlerin yeniden yayımlanması ayrıca kapsam/onay gerektirir.
- Anahtarlar, şifreler, hesap/cihaz bilgileri ve ham test günlükleri depoya yüklenmez. TLS, imza, hash, bağımlılık ve geri dönüş kontrolleri korunur.

Paket kaldırmak cihazdaki izleme kayıtlarını uzaktan silmez. Eski ve yeni paket aynı sağlayıcı kimliğine sahipse eski site paketi Güncelleme Merkezi'nden kaldırılıp yeni paket kurulmalıdır; uygulama verileri silinmemelidir.

## Geliştirme

`runtime/` çalıştırılabilir kodun kaynaklarıdır. `pilot/packages.json` mevcut denemenin tanımlarıdır. `sources/` altında kalan tarihî tanımlar kurulum indeksi değildir; yerel eski paket dosyaları otomatik yayımlanmaz.

Ana uygulama deposundaki paketleme aracı kapalı bir kapsam ve yeni bir deneme çıktı dizini ister. Özel anahtarlar depo dışında kalır. Güncelleme indirme ve uygulama kullanıcı seçimiyle yapılır.

Eklenti süreci uygulama UID'sini kullanır; güvenilmeyen kod için bir güvenlik sandbox'ı değildir. Paket kurulumu yayıncıya güvenmeyi gerektirir. Kullanıcı verisi, telemetri veya ikinci oynatıcı eklenmez.
