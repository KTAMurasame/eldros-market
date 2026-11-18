# QA Raporu — Eldros Market 0.1.0-SNAPSHOT (F5)

> Tarih: 2026-11-18 · Dal: `f4-gui` · Yapı: [04-test-plan.md](04-test-plan.md) §9.
> Etiketler: **[Doğrulandı]** bu raporda çalıştırıldı ve sonucu görüldü · **[Gözlem]** gerçek sağlayıcı/sunucu davranışı, garanti değil · **[Ölçülmedi]** / **[Test edilmedi]** yapılmadı, nedeni yazılı.

## 1. Ortam

| Öğe | Değer |
|---|---|
| Donanım | AMD Ryzen 7 3750H, 13,9 GB RAM |
| Diskler | C: Samsung MZVLB256 NVMe SSD · D: WD10SPSX SATA HDD |
| İşletim sistemi | Windows 11 Pro 10.0.26300 |
| JDK | OpenJDK 25.0.5 (derleme ve tüm sunucular). Bytecode hedefi Java 21 (major 65) |
| Paper | 1.21.4-232 (12d8fe0) · 1.21.11-132 (c5eb079) · 26.3-253 (63e41db) |
| Ekonomi | EssentialsX 2.22.0 |
| Köprü | 1.21.4 ve 26.3: VaultUnlocked 2.20.3 · 1.21.11: Vault 1.7.3-b131 |
| Sunucu verisi | D: (HDD). Performans düzeneği C: (SSD) geçici dizininde çalıştı |
| Sunucu ayarı | `server-ip=127.0.0.1`, whitelist açık; eşleşme testinde geçici olarak `online-mode=false` (yalnızca yerel), sonra geri alındı |

## 2. Otomatik testler [Doğrulandı]

`mvn -o clean verify` (JDK 25): **169 test, 0 başarısız, 0 hata.** ArchUnit (core'da Bukkit yok) ve enforcer dahil.

- Çekirdek kurtarma vakaları (04 §3.1–§3.4, §3.6, §3.8) sahte dünya ile `CrashRecoveryTest`, `MarketOperationsTest`, `SafeModeTest` içinde.
- F4'te eklenenler: `BookViewTest` (özetler, derinlik, ön izleme; ön izlemenin `plan()` ile 2.000 rastgele emirde birebir aynı sonucu verdiği özellik testi) ve geçmiş sorgusu testi.
- `PerfHarness` varsayılan olarak kapalı; §6'da ayrıca çalıştırıldı.

**İkili imza kontrolü (BinaryCheck)** [Doğrulandı]: Yayın JAR'ı 167 API referansı, 1.21.4 / 1.21.11 / 26.3 sunucu sınıf yollarına karşı **eksik = 0**.

**Yayın JAR'ı içeriği** [Doğrulandı]: `com/eldros/market/paper/debug/**` yayın JAR'ında **0 sınıf**. Yalnızca `-Pcrashtest` profili içerir. Not: test JAR'ından yayın JAR'ına geçerken `clean` gerekir (shade, eski çıktıyı girdi olarak kullanabiliyor).

## 3. Gerçek istemci testleri

1.21.4, gerçek istemci (test oyuncusu; eşleşme için ikinci hesap). Sonuçlar oyuncunun gözlemi + sunucu kaydı + veritabanı ile doğrulandı.

### 3.1 Komutlar ve eşleşme [Doğrulandı]
| Madde | Sonuç |
|---|---|
| Alış/satış emri, iptal, iade, posta kutusundan para ve eşya toplama | Geçti |
| İki oyuncu eşleşmesi: bekleyen emrin fiyatından işlem (60'lık alış 50'lik satışla 50'den) | Geçti |
| Kısmi dolum; kalan kısım defterde | Geçti |
| Fiyat iyileştirmesi iadesi (alıcıya 40) | Geçti |
| Kendi emriyle eşleşmeme (tek hesapla) | Geçti |
| Bakiyeler kuruşu kuruşuna (10.600 / 9.400), `verify` temiz | Geçti |
| TR/EN geçişi; tab önerileri dili izliyor | Hata bulundu ve düzeltildi (2bb3f7b), sonra geçti |

### 3.2 Menüler (F4) [Doğrulandı]
Ana menü, pazar, katalog + arama, emir defteri, emir oluşturma (sohbetten fiyat girişi), onay, emirlerim (Shift ile iptal), posta kutusu, geçmiş + filtre, dil: **hepsi çalıştı.** Menüden açılan emir (#9, 55) veritabanında doğru.

### 3.3 Güvenlik ve dupe kontrol listesi (04 §5)
| Madde | Durum | Not |
|---|---|---|
| Shift-click, 1–9, F, Q, çift tıklama, sürükleme | **Geçti** | Hiçbir ikon envantere geçmedi; kapanışta silinen ikon kaydı 0 |
| Orta tık, Ctrl+Q | Test edilmedi | Aynı iptal yolundan geçer (tüm `InventoryClickEvent` iptal); ayrıca denenmedi |
| İşlem sırasında menüyü kapatma / bağlantı kesme / ölme / dünya değiştirme | Kısmen | Bağlantı kesilmesi, çökme testlerinde (§4) her turda yaşandı; ölme ve dünya değiştirme denenmedi |
| İki istemcinin aynı emri eşzamanlı doldurması | Test edilmedi | Gerçek istemciyle denenmedi. Motor tek thread; çekirdek testleri kapsıyor |
| Makro hızında tıklama → tek işlem | **Geçti** | Hızlı tıklamada tek emir oluştu (oyuncu kilidi + 200 ms debounce) |
| Envanter doluyken teslim alma | **Geçti** | Tam dolu: 0 eklendi, kayıt AVAILABLE kaldı. 1 boş yuva: 66'dan 64 teslim, 2 aynı kayıtta (version arttı), `qty_effective=64` |
| Eşya isminde MiniMessage / `§` / click etiketi | **Geçti** | `<red>…</red> §4x <click:run_command:/op …>` düz metin göründü, işlenmedi |
| Dolu shulker reddi | **Geçti** | `/pazar sat` emir oluşturmadı |
| Engellenen PDC namespace | Test edilmedi | Varsayılan liste boş; gerçek bir eklentiyle denenmedi |
| Karantinadaki posta kaydını teslim alma | Kısmen | Gerçek sunucuda karantina oluştu ve admin çözdü (§4.3); oyuncunun karantinadaki kaydı toplama denemesi yalnızca çekirdek testinde (D-Q-1) |
| İzinsiz admin komutu | **Geçti** | `/pazaradmin verify` oyuncu tarafından reddedildi |
| İşlem sırasında başka eklentinin bakiyeyi değiştirmesi | Test edilmedi | — |
| Tüm SQL parametreli | **Geçti (inceleme)** | Oyuncu/kullanıcı değeri birleştiren sorgu yok; `JdbcJournal.mailboxUpdate` ve `tradesOf` yalnızca sabit parçalar ve `long` birleştirir |

**Bu testte bulunan ve düzeltilen hata:** "Hepsini topla", envanter doluyken ilk eşya kaydında duruyor, arkasındaki **para** kayıtlarını da toplamıyordu. Düzeltme: envanter dolunca kalan eşya kayıtları atlanır, para kayıtları toplanmaya devam eder. Derlendi; **gerçek istemciyle yeniden doğrulanmadı** (§8).

## 4. Çökme testleri (gerçek sunucu, `kill -9` eşdeğeri) [Doğrulandı]

**Yöntem:** Test JAR'ı (`-Pcrashtest`) + `-Deldros.debug.crashpoint=<nokta>`. Nokta gelince `Runtime.halt(137)`: kapanış kancası yok, eklenti kapatma yok, JVM belleği atılır (`kill -9` ile aynı süreç etkisi; işletim sistemi önbelleği iki durumda da kalır; elektrik kesintisi simüle edilmedi). Ardından normal açılış + (eşya vakalarında) oyuncu girişi, `eldrosdebug state`, `marketadmin verify`. Para vakaları iki kez daha yeniden başlatıldı. Paper 1.21.4, EssentialsX 2.22.0, VaultUnlocked 2.20.3, veri HDD'de.

**Not:** Çöken süreç, son konsol satırlarını çoğu zaman diske yazamıyor; "öncesi" durumu bir önceki vakanın bitiş kaydından alındı. Çıkış kodu 137 her vakada çöküşü kanıtlıyor.

### 4.1 Para (oyuncu çevrimdışı, konsoldan) — 10/10 beklenen
| Vaka (04) | Nokta | Beklenen | Sonuç | Bakiye |
|---|---|---|---|---|
| V-W-1 | BUY_PENDING_WRITTEN | QUARANTINED | QUARANTINED | 9.790 → 9.790 (çekim olmadı) |
| V-W-3 | BUY_PROVIDER_DONE | QUARANTINED | QUARANTINED | 9.790 → 9.790 **(çekim kayboldu, bkz. 4.4)** |
| V-W-5 | BUY_EFFECT_LOGGED | RECOVERED, iade posta kutusuna | RECOVERED, 10,00 iade | 9.790 → 9.780 |
| V-W-6 | BUY_IN_TRANSACTION | RECOVERED | RECOVERED, 10,00 iade | 9.780 → 9.770 |
| V-W-7 | BUY_COMMITTED | APPLIED, emir aktif | APPLIED, emir aktif | 9.800 → 9.790 |
| D-W-1 | MONEY_CLAIMING_WRITTEN | QUARANTINED | QUARANTINED | yatırma olmadı |
| D-W-3 | MONEY_PROVIDER_DONE | QUARANTINED | QUARANTINED | **(yatırma kayboldu, bkz. 4.4)** |
| D-W-5 | MONEY_EFFECT_LOGGED | RECOVERED, CLAIMED | RECOVERED, CLAIMED | +100 kalıcı |
| D-W-6 | MONEY_IN_TRANSACTION | RECOVERED, CLAIMED | RECOVERED, CLAIMED | +100 kalıcı |
| D-W-7 | MONEY_COMMITTED | APPLIED | APPLIED (kurtarma 0) | +10 kalıcı |

Her vakada: ikinci ve üçüncü açılış **0 kurtarma**, durum değişmedi; `verify` temiz; aynı işlem için ikinci Vault çağrısı yok (karantina otomatik tekrar etmez).

### 4.2 Eşya (oyuncu çevrimiçi, gerçek istemci) — 8/8 beklenen
| Vaka | Nokta | Beklenen | Sonuç | Elmas (envanter + posta + emir) |
|---|---|---|---|---|
| S-W-1 | SELL_PENDING_WRITTEN | ABORTED | ABORTED | 64 + 0 + 0 |
| S-W-2 | SELL_REMOVED_UNSAVED | ABORTED (çıkarma kalıcı değil) | ABORTED | 64 + 0 + 0 |
| S-W-3 | SELL_SAVED | RECOVERED, `RECOVERY_REFUND`, emir yok | RECOVERED, 2 adet iade | 62 + 2 + 0 |
| S-W-5 | SELL_COMMITTED | APPLIED, emir aktif | APPLIED | 60 + 2 + 2 |
| I-W-1 | ITEM_CLAIMING_WRITTEN | ABORTED → AVAILABLE | ABORTED, AVAILABLE | 60 + 2 + 2 |
| I-W-2 | ITEM_ADDED_UNSAVED | ABORTED → AVAILABLE | ABORTED, AVAILABLE | 60 + 2 + 2 |
| I-W-3 | ITEM_SAVED | RECOVERED, teslim edilmiş sayılır | RECOVERED, CLAIMED, `qty_effective=2` | 62 + 0 + 2 |
| I-W-5 | ITEM_COMMITTED | APPLIED, yalnızca işaretçi temizliği | APPLIED, CLAIMED | 64 + 0 + 0 |

**Temel değişmez (04 §3.4):** Toplam her adımda 64; hiçbir eşya hem envanterde hem posta kutusunda sayılmadı; `verify` her adımda temiz; yetim işaretçi uyarısı yok.

### 4.3 Admin çözümü (gerçek sunucu) [Doğrulandı]
Karantinadaki 4 işlem (2 BUY_CREATE, 2 CLAIM_MONEY) `recovery resolve … not-applied` ile çözüldü (EssentialsX bakiyesi çekim/yatırma olmadığını gösteriyordu). Para toplama kayıtları yeniden AVAILABLE oldu; bakiye değişmedi; aynı işlemi ikinci kez çözme denemesi **reddedildi** ("RESOLVED_NOT_APPLIED, not QUARANTINED").

### 4.4 Sağlayıcı gözlemi: EssentialsX 2.22.0 [Gözlem]
- Süreç, Vault `withdraw`/`deposit` yanıtından **hemen sonra** öldüğünde (V-W-3, D-W-3) EssentialsX bakiye değişikliği **diske yazılmamıştı**; yeniden açılışta değişiklik yoktu.
- Birkaç milisaniye sonraki noktalarda (effect log fsync sonrası: V-W-5/6, D-W-5/6) değişiklik kalıcıydı.
- **Sonuç:** Eldros'un karantina kararı doğru (para yaratılmadı, kaybolmadı). Ama admin, karantinadaki bir para işlemini çözerken **oyuncunun gerçek bakiyesine bakmalı**; bu testte doğru karar `not-applied` idi.
- **Kalan risk (garanti dışı, 04 §3.7):** Effect log CONFIRMED yazıldıktan sonra sağlayıcı kaydı hâlâ kaybolursa, kurtarma CONFIRMED'e güvenir: BUY'da iade fazladan para, CLAIM_MONEY'de kayıp olur. Bu testte gözlenmedi; penceresi sağlayıcının yazma gecikmesine bağlı ve ölçülmedi. Dokümanda "ekonomi eklentisinin kalıcılığı Eldros'un garantisi dışında" olarak yazılacak.

### 4.5 Paper 26.3 (build 253), gerçek istemci — 4/4 beklenen
Ek tur (2026-11-18), VaultUnlocked 2.20.3 + EssentialsX 2.22.0, Java 25.

| Vaka | Nokta | Sonuç | Elmas (envanter + posta) |
|---|---|---|---|
| S-W-2 | SELL_REMOVED_UNSAVED | ABORTED | 77 + 1 |
| S-W-3 | SELL_SAVED | RECOVERED, 2 adet `RECOVERY_REFUND` | 75 + 3 |
| I-W-2 | ITEM_ADDED_UNSAVED | ABORTED, kayıt AVAILABLE | 75 + 3 |
| I-W-3 | ITEM_SAVED | RECOVERED, CLAIMED, `qty_effective=1` | 76 + 2 |

Toplam her adımda 78; `verify` temiz; karantina yok.

### 4.6 Çalıştırılmayan çökme vakaları
V-W-0/2/4/8/9, D-W-0/2/4/8/9, I-W-4/6/7/8, S-W-4/6/7/8, P-8, P-13, P-15 gerçek sunucuda çalıştırılmadı. Çekirdek testleri (sahte dünya) bu pencerelerin çoğunu kapsıyor. 26.3'te para vakaları ve I-W-1/I-W-5 çalıştırılmadı.

## 5. Uyumluluk matrisi (04 §6.2)

| # | Paper | Java | Köprü + sağlayıcı | Statik | IT | İstemci duman | Çökme | Durum |
|---|---|---|---|---|---|---|---|---|
| C-1 | 1.21.4 | 21 | Vault + EssentialsX | ☑ | — | ☐ | ☐ | Test edilmedi |
| C-2 | 1.21.4 | 21 | VaultUnlocked + EssentialsX | ☑ | — | ☑ | ☐ (Java 25'te ☑) | **Test edildi**: Java 21'de açılış, menüler, "hepsini topla"; tam akış ve 18 çökme vakası Java 25'te |
| C-2' | 1.21.4 | 25 | VaultUnlocked + EssentialsX | ☑ | — | ☑ | ☑ | **Test edildi** |
| C-5 | 1.21.11 | 21 | Vault + EssentialsX | ☑ | — | ☐ (açılış ☑) | ☐ | Açılış + komut dumanı (Java 21 ve 25) |
| C-13 | 26.3 | 25 | Vault + EssentialsX | ☑ | — | ☐ | ☐ | Test edilmedi |
| C-14 | 26.3 | 25 | VaultUnlocked + EssentialsX | ☑ | — | ☑ | ☑ (4 eşya vakası) | **Test edildi** |
| Diğerleri | 1.21.8, 26.1.2, 26.2 | — | — | — | — | — | — | Test edilmedi |

Java 21: Eclipse Temurin 21.0.13+9 (SHA-256 Adoptium API ile doğrulandı).
"IT" (`eldros-market-it` sunucu içi eklentisi) yazılmadı; yerine gerçek sunucu duman testi + `eldrosdebug` kullanıldı.
Kural (04 §8.6): Listing yalnızca "Test edildi" satırlarını gösterir: **Paper 1.21.4 (Java 21 ve 25) ve Paper 26.3 (Java 25), VaultUnlocked + EssentialsX ile.**

## 6. Performans (04 §7)

**Yöntem:** `PerfHarness` (JUnit, `System.nanoTime`, ısınma + sabit seed 20261118). **JMH değil** (§7.2'deki fork/iterasyon ayarları uygulanmadı). JDK 25, C: NVMe SSD. Windows zamanlayıcı çözünürlüğü nedeniyle 1 µs altındaki değerler kaba.

| Metrik | Hedef | p50 | p95 | p99 | Maks | n | Koşul |
|---|---|---|---|---|---|---|---|
| Eşleşme `plan()` (10k emir, D-M) | p99 < 1 ms | 0,000 ms | 0,002 ms | 0,003 ms | 7,8 ms | 200.000 | Bellek |
| Self-trade, 10 kendi emri tepede (D-ST) | p99 < 50 ms | 0,004 ms | 0,007 ms | 0,022 ms | 13,7 ms | 20.000 | Bellek |
| Self-trade, 1.000 kendi emri tepede | p99 < 50 ms | 0,001 ms | 0,008 ms | 0,012 ms | 1,1 ms | 20.000 | Bellek |
| BUY_CREATE uçtan uca (PENDING commit + fsync + settlement commit), koşu 1 | p99 < 50 ms (settlement TX hedefi) | 5,45 ms | 8,31 ms | 23,2 ms | 67,8 ms | 5.000 | SSD, WAL, synchronous=FULL |
| aynı, koşu 2 | | 11,07 ms | 17,06 ms | 31,7 ms | 287,2 ms | 5.000 | |
| aynı, koşu 3 | | 10,07 ms | 14,25 ms | 25,2 ms | 87,2 ms | 5.000 | |
| 50k aktif emirle açılış (aç + kurtarma + defter) | < 3 sn | 219 ms | — | — | 440 ms | 3 | SSD |

Hedefler karşılandı. Maks değerlerde tekil sıçramalar var (287 ms); nedeni ölçülmedi (GC veya disk).

**Ölçülmedi:** HDD'de uçtan uca işlem; main thread maliyeti / GUI tıklaması; `saveData` (F0'da ölçüldü: p50 ~16–24 ms); GUI eylemi başına `saveData` sayısı (sayaç yok); yük altında TPS; şablon çözümleme süresi; bellek sızıntısı (1.000 giriş/çıkış); spark profili (R-4, R-5).

## 7. Başarısız maddeler

| Madde | Neden | Durum |
|---|---|---|
| Tab önerileri `/pazar` ile hep Türkçe | Alias dili zorluyordu | **Düzeltildi** (2bb3f7b), oyunda doğrulandı |
| "Hepsini topla" envanter doluyken para kayıtlarını atlıyordu | İlk reddedilen eşyada duruyordu | **Düzeltildi**, oyunda doğrulandı (1.21.4, Java 21: eşya kaydı AVAILABLE kaldı, para CLAIMED) |
| Arama yalnızca İngilizce eşya kimliğiyle çalışıyor (`elmas` bulmuyor) | Sunucuda Türkçe eşya adları yok; eşya isimleri istemci tarafında çevriliyor | **Kabul edilen sınırlama**; arama mesajı "İngilizce adıyla arayın, örn. diamond" olarak değiştirildi |

## 8. Test edilmeyen maddeler

- 1.21.8, 26.1.2, 26.2; orijinal Vault ile 1.21.4 ve 26.3; Java 21 ile çökme testi.
- 1.21.11'de gerçek istemci testi ve çökme testi.
- `eldros-market-it` sunucu içi test eklentisi (yazılmadı).
- §3.3'te "Test edilmedi" ve "Kısmen" olarak işaretlenen dupe maddeleri.
- §4.5'teki çökme vakaları; hız sınırı kuyruğu (P-14/P-15); "hepsini al" ortasında çökme (P-8/P-13).
- Sürüm geçişleri M-1/M-2 (1.21.x → 26.3 aynı DB); düşürme reddi yalnızca 26.3 → 1.21.4 için denendi (F3).
- §6'daki ölçülmeyen performans satırları.

## 9. Bilinen sınırlamalar

- **Ekonomi sağlayıcısının kalıcılığı garanti dışı** (04 §3.7). EssentialsX 2.22.0'ın çökmeden hemen önceki değişikliği kaybettiği gözlendi (§4.4). Karantinadaki para işlemlerinde admin oyuncu bakiyesini kontrol etmeli.
- **Oyuncu verisinin yedekten geri yüklenmesi veya envanter senkron eklentileri** (U-5) desteklenmez; tespit edilemez.
- Çöken süreç son konsol satırlarını yazamayabilir (Paper'ın asenkron logu); kurtarma kararları konsola değil veritabanına ve effect log'a dayanır.
- Vanilla eşya adları oyuncunun istemci dilinde görünür (eklenti dili yalnızca menü metinlerini değiştirir); arama İngilizce eşya kimliğiyle yapılır.
- Katalog, veri gerektiren eşyaları (iksir, büyülü kitap, kafa vb.) listelemez; bunlar "elindeki eşya" ile işlem görür.
