# AcikBulucu v2.1 (bulur + kendi onarır)

| Komut | Ne yapar |
|---|---|
| `/aciklaribul` | Tarar, KRİTİK/UYARI/ÖNERİ listeler. Onarılabilenlere `[otomatik onarılabilir]` yazar |
| `/aciklaribul onizle` | Onarılacakları değiştirmeden listeler |
| `/aciklaribul onar` | Numaralı onarım listesini gösterir |
| `/aciklaribul onar 4 8` | Sadece seçtiğin maddeleri YEDEK ALARAK onarır |
| `/aciklaribul onar hepsi` | Onarılabilen her şeyi uygular, Skript'i yeniden yükler |
| `/aciklaribul geriyukle` | Son onarımı geri alır |

Kısayollar: `/acikbul`, `/audit`, `/açıklarıbul`. Yetkiler: `acikbulucu.use`, `acikbulucu.onar` (varsayılan op).

## Otomatik onarılanlar (hepsi yedekli)
- Skript: `%player_name%` -> `%player%`, karışık tab/boşluk girintisi, BOM işareti
- Skript: (sayı) kopya dosyaları sadeleştirme, çift scoreboard'u kapatma (eskisine `-` eklenir)
- Skript: eksik claim script'i üretme (`claim.sk`, fiyatlar config.yml'den)
- server.properties: boş şifreli RCON'u kapatma, view-distance'ı 8'e çekme
- Dünya spawn'ı havadaysa/lavdaysa yakında güvenli bloğa taşıma

## Bilerek otomatik yapılmayanlar
Eklenti silme/indirme (skyblock çakışması, eksik eklentiler), op alma, auth ayarları.
Bunlar veri kaybettirebilir ya da güvenlik riski taşır, rapor sadece ne yapılacağını söyler.

## Yedek ve geri alma
Her onarım `plugins/AcikBulucu/yedek/<tarih>/` altına kaydedilir.

## Otomatik tarama
`config.yml`: `otomatik-tarama.aktif` (varsayılan açık, 30 dk). Kritik açık çıkarsa op'lara haber verir.
`otomatik-onar: true` yaparsan güvenli onarımlar kendiliğinden uygulanır (varsayılan kapalı).

## Derleme
GitHub: repoya yükle -> Actions -> Artifacts'tan `AcikBulucu.jar` indir -> `plugins/` klasörüne at.
Ya da JDK 17 + Maven ile `mvn package`.
