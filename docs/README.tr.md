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
  yerine yönetici kararına (karantina) bırakır. Gerçek sunucularda 22 çökme senaryosunda denendi:
  [06-qa-report.md](06-qa-report.md).
- **Kendi emirleriniz birbiriyle eşleşmez**; sıradaki yerleri de korunur.
- **Birebir eşya.** Büyülü, isim verilmiş veya özel veri taşıyan eşyalar kendi pazarında işlem görür.
- **Menü ve komutlar**, Türkçe ve İngilizce, oyuncu başına dil, tüm menü düzenleri ayarlanabilir.
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
| 1.21.4 (build 232) | 21 ve 25 | VaultUnlocked 2.20.3 + EssentialsX 2.22.0 | **Test edildi**: gerçek oyuncu, menüler, iki oyunculu alım satım, 18 çökme senaryosu (çökme testleri Java 25'te) |
| 26.3 (build 253) | 25 | VaultUnlocked 2.20.3 + EssentialsX 2.22.0 | **Test edildi**: gerçek oyuncu, menüler, 4 eşya çökme senaryosu |
| 1.21.11 (build 132) | 21 ve 25 | Vault 1.7.3 + EssentialsX 2.22.0 | Açılıyor, komutlar yanıt veriyor; oyunda denenmedi |
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
| `/pazar al <adet> <fiyat> [eşya]` | Elinizdeki veya adı verilen eşya (ör. `diamond`) için alış emri (para hemen ayrılır) |
| `/pazar emirler` | Aktif emirleriniz |
| `/pazar iptal <no>` | Emri iptal eder; kalan posta kutusuna gider |
| `/pazar posta` | Sizi bekleyenler |
| `/pazar topla [hepsi\|no]` | Posta kutusundan toplar |
| `/pazar dil <tr_TR\|en_US>` | Dilinizi değiştirir |
| `/pazar yardım` | Komut listesi |

İngilizce adlar da çalışır (`/market sell`, `buy`, `orders`, …). Takma adlar: `/market`, `/em`. Fiyat birim başınadır;
ondalık için nokta kullanılır (`12.50`).

| Yönetici komutu (`/pazaradmin`) | Ne yapar |
|---|---|
| `reload` | Ayarları, dilleri ve menüleri yeniden yükler |
| `verify` | Defter değişmezlerini denetler |
| `safemode [on\|off]` | Güvenli modu gösterir veya açar/kapatır |
| `recovery list` | Yönetici kararı bekleyen işlemler |
| `recovery resolve <op> <applied\|not-applied> [adet]` | Bunlardan birine karar verir (aşağıya bakın) |

## İzinler

| İzin | Varsayılan | Anlamı |
|---|---|---|
| `eldrosmarket.use` | herkes | Pazarı açma ve gezme |
| `eldrosmarket.buy` | herkes | Alış emri |
| `eldrosmarket.sell` | herkes | Satış emri |
| `eldrosmarket.limit.orders.<n>` | — | En fazla `n` aktif emir (en yükseği geçerli) |
| `eldrosmarket.admin` | op | Tüm yönetici komutları |

## Ayarlar

Vanilla eşya adları her oyuncunun kendi Minecraft dilinde görünür (adları istemci çevirir). Menü araması hem İngilizce
eşya adıyla hem Türkçe kelimelerle çalışır: `elmas kılıç`, `açık mavi yün`, Türkçe harfsiz `kilic`. Türkçe kelimeler
`lang/tr_TR.yml` içindeki `search-aliases` sözlüğünden gelir (yaklaşık 200 kelime); sunucu sahibi ekleyebilir. Tüm anahtarlar `config.yml` içinde açıklamalı. Dil dosyaları `lang/` (MiniMessage), menü düzenleri `menus/` içinde;
ayrıntılar İngilizce README'de. Hatalı bir menü değeri konsola dosya ve anahtar adıyla yazılır, yalnızca o anahtar için
varsayılan kullanılır.

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
- MySQL / sunucu ağı (ayrı Pro sürümü için planlanıyor).

## Lisans

Telif © 2026 Eldros. [GNU Genel Kamu Lisansı v3.0](../LICENSE) (GPL-3.0-only). Şimdilik dış katkı kabul edilmiyor.
