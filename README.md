# Film Merkezi ? Android TV / Google TV Eklentiler

Android i?in ba??ms?z, imzal? site ve kaynak ??z?mleyici paketleri. P/R kodlar? Tizen ile ayn? s?ray? izler; Android paketleri ba??ms?z s?r?mlenir. Tizen JavaScript paketleri Android'e kurulmaz.

## Mevcut kurulum kapsam?

| Site eklentisi | Kaynak eklentisi | Paket s?r?m? |
|---|---|---|
| P002 | R002 | 1.0.0 |
| P003 | R003 | 1.0.0 |
| P004 | R004 | 1.0.0 |
| P005 | R005 | 1.0.0 |

Minimum uygulama **0.12.2 / 38**, Android API 23. Kurulabilir paketlerin tek kayna?? [imzal? indeks](filmmerkezi-android-repository.json) dosyas?d?r. Site adlar? ve adresleri paket metadatas?ndad?r; APK i?inde sabit bir site katalo?u gerektirmez.

<!-- film-merkezi-android-repository: filmmerkezi-android-repository.json -->

[Uygulama ve APK](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/releases) ? [SDK ve paket s?zle?mesi](https://github.com/enesk4yh4n-lab/Film-Merkezi-AndroidTV-GoogleTV/blob/main/docs/android-plugin-sdk.md).

Depo ke?fi otomatik kurulum veya g?ven onay? de?ildir. Yay?nc? anahtar? kullan?c? taraf?ndan ayr?ca onaylan?r. Yay?nc? RSA SPKI SHA-256:

```text
d31d23cccc49043e3846a9b799e1cea9464f8cd29e04daa55dd5d05c1a96fc1f
```

## Yay?n kurallar?

- Haz?rlama ve test iste?i yay?n onay? say?lmaz. Push, dosya y?kleme, Release, PR ve depo ayarlar? i?in ilgili kapsama a??k kullan?c? onay? gerekir.
- S?r?m numaras? yay?n onay?ndan sonra, mevcut imzal? indeks kontrol edilerek belirlenir. Onay ?ncesi ?al??malar konu ad?yla yerelde tutulur.
- Yay?mlanm?? paket, manifest veya imzal? indeks ayn? s?r?m/revizyonda de?i?tirilmez. A??klama d?zenlemek imzal? paketleri yeniden yay?mlamak de?ildir.
- Site/??z?mleyici P/R e?le?mesi ve mevcut kimlik korunur. Kald?r?lm?? paketlerin yeniden yay?mlanmas? ayr?ca kapsam/onay gerektirir.
- Anahtarlar, ?ifreler, hesap/cihaz bilgileri ve ham test g?nl?kleri depoya y?klenmez. TLS, imza, hash, ba??ml?l?k ve geri d?n?? kontrolleri korunur.

Paket kald?rmak cihazdaki izleme kay?tlar?n? uzaktan silmez. Eski ve yeni paket ayn? sa?lay?c? kimli?ine sahipse eski site paketi G?ncelleme Merkezi'nden kald?r?l?p yeni paket kurulmal?d?r; uygulama verileri silinmemelidir.

## Geli?tirme

`runtime/` ?al??t?r?labilir kodun kaynaklar?d?r. `pilot/packages.json` mevcut denemenin tan?mlar?d?r. `sources/` alt?nda kalan tarih? tan?mlar kurulum indeksi de?ildir; yerel eski paket dosyalar? otomatik yay?mlanmaz.

Ana uygulama deposundaki paketleme arac? kapal? bir kapsam ve yeni bir deneme ??kt? dizini ister. ?zel anahtarlar depo d???nda kal?r. G?ncelleme indirme ve uygulama kullan?c? se?imiyle yap?l?r.

Eklenti s?reci uygulama UID'sini kullan?r; g?venilmeyen kod i?in bir g?venlik sandbox'? de?ildir. Paket kurulumu yay?nc?ya g?venmeyi gerektirir. Kullan?c? verisi, telemetri veya ikinci oynat?c? eklenmez.
