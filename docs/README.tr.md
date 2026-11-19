# Eldros Market

Paper sunucuları için oyuncu pazarı: **emir defterinde alış ve satış emirleri**, fiyat ve zaman önceliğiyle
eşleşme, emanet (escrow), posta kutusu, geçmiş ve hiçbir şeyi iki kez ödemeyen çökme kurtarması.

> English: [README.md](../README.md)

## Öne çıkanlar

- **Çift taraflı emir defteri.** Oyuncular hem satış teklifi hem alış talebi verir. Yeni emir, karşı taraftaki en iyi
  emirlerle bekleyen emrin fiyatından işlem görür; kalan kısım defterde bekler. Kısmi dolum, fiyat farkı iadesi ve
  tam iadeli iptal var.
- **Hiçbir şey iki kez verilmez.** Her para ve eşya hareketi gerçekleşmeden önce günlüğe yazılır. Çökme veya
  `kill -9` sonrası sonucu kesin olan işlemleri kendisi tamamlar ya da geri alır; kesin olmayanları tahmin etmek
  yerine yönetici kararına (karantina) bırakır. Gerçek sunucularda 24 çökme denemesiyle sınandı:
  [06-qa-report.md](06-qa-report.md).
- **Kendi emirleriniz birbiriyle eşleşmez**; sıradaki yerleri de korunur.
- **Birebir eşya.** Büyülü, isim verilmiş veya özel veri taşıyan eşyalar kendi pazarında işlem görür.
- **Menü ve komutlar**, Türkçe ve İngilizce, oyuncu başına dil, tüm menü düzenleri ayarlanabilir.
- **Hemen al-sat ya da bekle.** Emir defterinde Hemen al / Hemen sat, `/pazar sat` (eldeki deste) ve `/pazar sat hepsi`;
  her biri onay ekranıyla. Açık emirlerin fiyatı değiştirilebilir, eşya eklenebilir.
- **Kolay kullanım.** Pazar listesinde kategoriler ve sıralama, fiyat ve adet için örs penceresi, `2.5k` gibi kısaltmalar,
  birleştirilmiş bildirimler ve girişte "siz yokken" özeti.
- **Yönetici ayarları.** Satış vergisi ve alış emri ücreti (varsayılan kapalı), eşya bazlı fiyat aralığı, kapalı dünya ve
  oyun modları, bakım için alım satım dondurma, PlaceholderAPI yer tutucuları.
- **`/pazaradmin verify`** canlı verideki defter değişmezlerini istediğiniz an denetler.

## Gereksinimler ve test edilen kurulumlar

| | Gereksinim |
|---|---|
| Sunucu | Paper **1.21.4 veya üstü** (1.21.x ve 26.x için tek JAR) |
| Java | 21 veya üstü |
| Ekonomi | Vault veya VaultUnlocked + bir ekonomi eklentisi |
| Depolama | SQLite, dahili (sunucunun SQLite sürücüsü, en az 3.47) |

Yalnızca gerçekten çalıştırılanlar "test edildi" olarak yazılır:

| Paper | Java | Köprü + ekonomi | Durum |
|---|---|---|---|
| 1.21.4 (build 232) | 21 ve 25 | VaultUnlocked 2.20.3 + EssentialsX 2.22.0 | **Test edildi**: gerçek istemci ve bot oyuncular, tüm özellikler, 20 çökme denemesi (Java 25); PlaceholderAPI 2.12.3 |
| 26.3 (build 253) | 25 | VaultUnlocked 2.20.3 + EssentialsX 2.22.0 | **Test edildi**: gerçek istemci: menüler, örs girişi, satış emri, iptal, posta kutusu; eski veritabanının yükseltilmesi. Daha eski bir build ile 4 eşya çökme denemesi |
| 1.21.11 (build 132) | 25 | Vault 1.7.3 + EssentialsX 2.22.0 | **Test edildi** (bot oyuncularla): emir ve eşleşme, kategoriler, sıralama, örsle arama, posta kutusu, dondurma; eski veritabanının yükseltilmesi |
| Diğerleri | | | Henüz test edilmedi |

## Kurulum

1. `EldrosMarket-<sürüm>.jar` dosyasını Vault (veya VaultUnlocked) ve ekonomi eklentinizin yanına, `plugins/` içine koyun.
2. Sunucuyu bir kez açın. `plugins/EldrosMarket/` içinde `config.yml`, `lang/` ve `menus/` oluşur.
3. **Oyuncular işlem yapmadan önce `economy.decimal-places` değerini ayarlayın** (2 = kuruş, 0 = tam sayı). Veritabanında
   veri oluştuktan sonra değiştirilemez; kayıtlı her fiyat buna bağlıdır.
4. Diğer ayarlar için düzenleyip `/pazaradmin reload` yazın.

## Komutlar

| Komut | Ne yapar |
|---|---|
| `/pazar` | Menüyü açar |
| `/pazar sat <adet> <fiyat>` | Elinizdeki eşya için satış emri (eşyalar hemen envanterden alınır) |
| `/pazar sat` | Elinizdeki desteyi en iyi alış emirlerine hemen satar (önce onay ekranı) |
| `/pazar sat hepsi` | Envanterde alıcısı olan her şeyi hemen satar (önce onay ekranı) |
| `/pazar al <adet> <fiyat> [eşya]` | Elinizdeki veya adı verilen eşya (ör. `diamond`) için alış emri (para hemen ayrılır) |
| `/pazar emirler` | Aktif emirleriniz |
| `/pazar iptal <no>` | Emri iptal eder; kalan posta kutusuna gider |
| `/pazar posta` | Sizi bekleyenler |
| `/pazar topla [hepsi\|no]` | Posta kutusundan toplar |
| `/pazar dil <tr_TR\|en_US>` | Dilinizi değiştirir |
| `/pazar bildirim` | Canlı pazar bildirimlerini kapatır / açar (posta kutusu etkilenmez) |
| `/pazar yardım` | Komut listesi |

İngilizce adlar da çalışır (`/market sell`, `buy`, `orders`, …). Takma adlar: `/market`, `/em`. Fiyat birim başınadır;
ondalık için nokta kullanılır (`12.50`).

| Yönetici komutu (`/pazaradmin`) | Ne yapar |
|---|---|
| `reload` | Ayarları, dilleri ve menüleri yeniden yükler |
| `verify` | Defter değişmezlerini denetler |
| `safemode [on\|off]` | Güvenli modu gösterir veya açar/kapatır |
| `freeze [on\|off]` | Bakım için alım satımı dondurur: yeni emir, anlık işlem ve düzenleme reddedilir; iptal ve posta kutusu çalışır. Yeniden başlatmada da açık kalır |
| `recovery list` | Yönetici kararı bekleyen işlemler |
| `recovery resolve <op> <applied\|not-applied> [adet]` | Bunlardan birine karar verir (aşağıya bakın) |

## İzinler

| İzin | Varsayılan | Anlamı |
|---|---|---|
| `eldrosmarket.use` | herkes | Pazarı açma ve gezme |
| `eldrosmarket.buy` | herkes | Alış emri |
| `eldrosmarket.sell` | herkes | Satış emri |
| `eldrosmarket.limit.orders.<n>` | — | En fazla `n` aktif emir (en yükseği geçerli) |
| `eldrosmarket.tax.<kademe>` | — | `economy.sales-tax.tiers.<kademe>` vergi oranı (en düşüğü geçerli; açıkça verilmeli) |
| `eldrosmarket.fee.exempt` | kimse | Alış emri ücreti ödemez (op'lara otomatik verilmez) |
| `eldrosmarket.bypass.restrictions` | kimse | Kapalı dünya ve oyun modlarında da pazarı kullanır |
| `eldrosmarket.admin` | op | Tüm yönetici komutları |

## Ayarlar

Vanilla eşya adları her oyuncunun kendi Minecraft dilinde görünür (adları istemci çevirir). Menü araması hem İngilizce
eşya adıyla hem Türkçe kelimelerle çalışır: `elmas kılıç`, `açık mavi yün`, Türkçe harfsiz `kilic`. Türkçe kelimeler
`lang/tr_TR.yml` içindeki `search-aliases` sözlüğünden gelir (yaklaşık 200 kelime); sunucu sahibi ekleyebilir. Tüm anahtarlar `config.yml` içinde açıklamalı. Dil dosyaları `lang/` (MiniMessage), menü düzenleri `menus/` içinde;
ayrıntılar İngilizce README'de. Hatalı bir menü değeri konsola dosya ve anahtar adıyla yazılır, yalnızca o anahtar için
varsayılan kullanılır.

Diğer ayarlar (tamamı `config.yml` içinde açıklamalı, tablolar İngilizce README'de): satış vergisi ve alış emri ücreti
(`economy.*`, varsayılan kapalı), eşya bazlı fiyat aralığı (`limits.per-item`), kapalı dünya ve oyun modları
(`restrictions.*`; creative ve spectator varsayılan kapalı), çevrimiçi oyuncuya parayı otomatik yatırma
(`mailbox.auto-collect-money`, varsayılan kapalı), sayı girişi (`input.method`: örs veya sohbet), bildirimler ve
PlaceholderAPI. Pazar kategorileri `categories.yml` dosyasında: her kategori eşya kimliği desenleriyle tanımlanır
(`*_ore` gibi), ilk eşleşen kazanır.

## Çökme sonrası yöneticinin bilmesi gerekenler

Yarım kalan işlemlerin çoğu bir sonraki açılışta veya oyuncu girişinde kendiliğinden tamamlanır ya da geri alınır.
Bir işlem yalnızca ekonomi eklentisinin veya oyuncu envanterinin değişip değişmediği bilinemediğinde **karantinaya**
alınır. Oyuncu bu kayıtları posta kutusunda "incelemede" olarak görür, toplayamaz.

1. `/pazaradmin recovery list` ile işlemi, oyuncuyu ve tutarı görün.
2. **Gerçek durumu kendiniz kontrol edin** (`/bal <oyuncu>` veya envanter).
3. Karar verin:

| İşlem | `applied` | `not-applied` |
|---|---|---|
| `BUY_CREATE` | Para **çekildi** → posta kutusuna iade edilir | Çekilmedi → yapılacak bir şey yok |
| `CLAIM_MONEY` | Para **ödendi** → kayıt kapanır | Ödenmedi → kayıt yeniden toplanabilir |
| `SELL_CREATE` | Eşyalar **alındı** → `adet` kadar eşya posta kutusuna iade | Eşyalar yerinde → yapılacak bir şey yok |
| `CLAIM_ITEM` | `adet` eşya **teslim edildi** → kayıt o kadar kapanır | Teslim edilmedi → kayıt yeniden toplanabilir |

Karar kesindir; aynı işlemi ikinci kez çözmek reddedilir.

**Ekonomi eklentileri ve çökme.** Eldros kendi kayıtlarını çökmeye karşı korur, ama ekonomi eklentinizin bakiyeyi diske
yazmasını sağlayamaz. Testlerimizde EssentialsX 2.22.0, sert bir çökmeden hemen önceki bakiye değişikliğini kaybetti;
Eldros bu işlemleri karantinaya aldı ve doğru karar `not-applied` idi. 2. adım bu yüzden önemli.

## Desteklenmeyenler

- Oyuncu verisinin yedekten geri yüklenmesi veya envanter senkron eklentileri.
- MySQL, BungeeCord/Velocity ağları, Folia, Spigot.

## Nasıl yapıldı

Kodun, testlerin ve belgelerin büyük kısmı, yazarın yönlendirmesiyle bir yapay zekâ asistanı (Claude) tarafından
yazıldı. Her özellik, [QA raporunda](06-qa-report.md) anlatıldığı gibi gerçek Paper sunucularında test edildi.

## Destek

Hata ve sorular: [GitHub Issues](https://github.com/KTAMurasame/eldros-market/issues). Paper sürümünü, eklenti sürümünü,
ekonomi eklentini ve konsol kaydını (`logs/latest.log`) ekle. Çökme olduysa `/pazaradmin verify` ve
`/pazaradmin recovery list` çıktısını da ekle.

## Lisans

Telif © 2026 Eldros. [GNU Genel Kamu Lisansı v3.0](../LICENSE) (GPL-3.0-only). Şimdilik dış katkı kabul edilmiyor.
