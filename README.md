# OBD2 Tanı Pro

Citroën C-Elysée 2017 1.6 HDi (PSA) için ELM327 tabanlı Windows OBD2 tanı uygulaması. Arayüz Türkçedir.

## Kurulum

```
pip install -r requirements.txt
python main.py
```

Gereksinimler: Python 3.10+, PySide6, pyserial, bleak (Bluetooth LE). Çalıştırılabilir dosya üretmek için `build.bat` (PyInstaller, tek dosya, pencereli) -> `dist\OBD2TaniPro.exe`.

## Kullanım

| Sayfa | Açıklama |
|---|---|
| Bağlantı | COM/Bluetooth (port + baud), Bluetooth LE (Tara), WiFi (varsayılan 192.168.0.10:35000) veya Demo / Simülatör |
| Panom | Seçtiğiniz değerler büyük kartlarda (yeşil/sarı/kırmızı durum). "Değer seç / ekle" ile aranabilir liste; seçim `%APPDATA%\OBDTani\settings.json` içinde saklanır |
| Tüm Değerler | Tüm standart PID ve PSA değerleri, kategori/arama; ★ ile Panom'a ekleyin. Araç desteklemeyenler gri "desteklenmiyor" |
| Canlı Grafik | Seçilen değerlerin zaman grafiği |
| Arıza Kodları | Mod 03/07/0A okuma, Mod 04 silme (onaylı), üretici ECU'larından UDS 19 02 FF |
| PSA Özel | `data/psa_data.json` içindeki DID'ler (servis 22 / KWP 21). Düşük güvenli kayıtlar "doğrulanmamış" etiketlidir |
| Keşif / ECU | ECU adres taraması (10 03 / 10 C0 / 3E 00), servis 21/22 kimlik taraması, bulunanı ad/formül/birimle `%APPDATA%\OBDTani\user_dids.json` dosyasına kaydetme |
| Araç Bilgisi | CALID, ELM sürümü, protokol, voltaj; ECU VIN vermezse elle VIN girişi |
| Kayıt | Seçili değerleri CSV (Excel uyumlu) olarak kaydetme |
| Rapor | Arıza kodları + anlık değerler: HTML / PDF |

Formüller (`A,B,C,D` bayt değişkenleri) `eval` kullanılmadan, kısıtlı bir AST değerlendiricisiyle (`app/safe_eval.py`) hesaplanır.

Veri dosyaları: `data/psa_data.json` (ECU, DID, DTC), `data/dtc_generic.json` (~160 genel kod). İkisi birleştirilir; PSA dosyası öncelikli.

### Demo modu
Gerçek bir C-Elysée'den ölçülen yanıtları taklit eder (VIN yok, üretici ECU'ları yanıtsız). "PSA tam erişim" kutusu üretici ECU'larını da simüle eder.

## ELM327 eşleştirme

- **Bluetooth Classic**: Windows Ayarlar > Bluetooth'tan eşleştirin (PIN genelde 1234 / 0000). Aygıt yöneticisinde oluşan "Giden COM" portunu Bağlantı sayfasından seçin; baud genelde 38400 (bazılarında 9600/115200).
- **Bluetooth LE**: Windows'ta eşleştirmeye gerek yoktur; "Bluetooth LE" > Tara > adaptörü seçin. Komut satırı: `python tools/ble_scan.py`.
- **WiFi**: Adaptörün ağına bağlanın; IP/port genelde 192.168.0.10:35000.
- Kontağı açın (motor kapalı) ve bağlanın. Protokol "Otomatik" bırakılabilir (bu araç: ISO 15765-4 CAN 11/500).

Not: Bazı araç/adaptör çiftlerinde motor ECU'su üretici servislerini (UDS/KWP) desteklemez veya PSA ECU'ları OBD soketindeki bu pinlerden erişilemez; bu durumda uygulama standart OBD verileriyle çalışır ve bilgi şeridi gösterir.

## Temalar

Kenar çubuğunun altındaki **Tema** listesinden (renk örnekli) tema seçilir; değişiklik anında uygulanır, yeniden başlatma gerekmez ve seçim `settings.json` içinde saklanır.

- **Gece Mavisi** (varsayılan, koyu)
- **Aydınlık** (açık zemin, mavi vurgu)
- **Grafit Turuncu** (koyu grafit, turuncu vurgu)
- **Orman** (yeşil tonlu koyu, zümrüt vurgu)
- **Yüksek Kontrast** (siyah zemin, sarı vurgu, kalın kenarlık; araçta gün ışığı için)

Durum renkleri (normal/uyarı/kritik) her temada okunaklı kalacak şekilde ayrı ayarlanmıştır. Yeni tema eklemek için `app/ui/theme.py` içindeki `PALETTES` sözlüğüne bir palet eklemek yeterlidir.

## Sürüş kaydı ve puanlama

**Sürüş** sayfası yolculuklarınızı kaydeder, 0-100 arası puanlar ve Türkçe koçluk ipuçları verir.

> **Güvenlik uyarısı:** Sürüş sırasında uygulamayı elle kullanmayın. Bağlantıyı ve kaydı park halindeyken başlatın; yalnızca büyük, etkileşimsiz **Sürüş modu** ekranına bakın (hız, devir, tahmini vites, canlı puan). Dikkatiniz her zaman yolda olmalıdır.

- **Kayıt:** Her döngüde devir ve hız (>= 3 Hz hedefi), daha seyrek olarak yük, soğutma suyu, MAP ve MAF okunur; her örnek zaman damgalıdır. Veriler `%APPDATA%\OBDTani	rips\` altına CSV + JSON özet olarak yazılır.
- **Başlatma / bitirme:** Hız 5 km/s'yi aşınca otomatik başlar (Sürüş sayfasından kapatılabilir, varsayılan açık). Sürüş **yalnızca "Sürüşü bitir" ile** sona erer; ışıkta/trafikte duruşlar aynı sürüşte rölanti sayılır.
- **Bağlantı kopması:** Kayıt duraklar, "Bağlantı koptu – yeniden bağlanılıyor" gösterilir ve adaptöre otomatik yeniden bağlanılır; bağlanınca aynı sürüşe devam edilir.
- **Çökme koruması:** Sürüş her 30 sn'de kısmen kaydedilir. Uygulama beklenmedik kapanırsa bir sonraki açılışta yarım kalan sürüşe devam edebilir, bitirip kaydedebilir veya silebilirsiniz.
- **Vites tahmini:** ECU vites bilgisi vermez; oran = hız / (devir/1000) değerinden 5 kümeyle (çevrimiçi k-means) tahmin edilir. Başlangıç oranları (8, 15, 22.5, 30, 38 km/s per 1000 d/dk) **tahmindir**; stabil örneklerle otomatik düzelir ve `settings.json` içinde saklanır. Kavrama/boşta/rölantide vites "-" görünür.
- **Puan:** Hızlanma, Frenleme, Vites kullanımı/devir, Rölanti, Soğuk motor, Hız istikrarı/Eko alt puanlarının ağırlıklı ortalamasıdır. Olaylar: sert hızlanma (> 2.5 m/s²), sert frenleme (< -3.0 m/s²), 3500 d/dk üstü, düşük devirde zorlanma (< 1300 d/dk, 3. vites üstü, yük > %70), geç/erken vites büyütme (dizel için ideal 2000-2500 d/dk), soğuk motora yüklenme, 2 dk üstü rölanti, otoyolda dengesiz hız. Cezalar km başına normalize edilir; tüm eşikler `app/scoring.py` içindeki `CONFIG` sözlüğündedir.
- **Ayrıntı ve rapor:** Sürüş listesinden bir kayıt seçince puan dağılımı, ipuçları, hız/devir/vites grafiği (olay işaretli) görünür; **HTML rapor** olarak dışa aktarılabilir.
- **Demo:** Demo/Simülatör modu, şehir + otoyol sürüşü, vites geçişleri ve birkaç sert olay içeren gerçekçi bir sürüş döngüsü üretir.

## Android

`android/` klasöründe masaüstü uygulamanın yerel Android sürümü (Kotlin + Jetpack Compose, Material 3) bulunur. ELM327, vites tahmini, puanlama ve koçluk mantığı masaüstünden taşınmıştır; üzerine GPS/barometre/ivmeölçer ile yükseklik, eğim ve viraj analizi eklenmiştir. Arayüz Türkçedir.

### Kurulum (APK yükleme)
1. [Releases](https://github.com/ta2fga/psa-obd2-tani/releases) sayfasından en son `PSA-OBD2-Tani-vX.Y.Z.apk` dosyasını telefona indirin (ya da Actions sekmesindeki `PSA-OBD2-Tani-debug` artifact'ını kullanın).
2. Telefonda "Bilinmeyen kaynaklardan yüklemeye izin ver" ayarını açıp APK'yı yükleyin (Android 8.0 / API 26 ve üstü).
3. Kendiniz derlemek için: `cd android && ./gradlew testDebugUnitTest assembleDebug` (JDK 17+, Android SDK 36). APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

### İzinler
İlk açılışta gerekçeleriyle birlikte istenir:
- **Konum (hassas)**: hız, rota, yükseklik ve eğim için; ekran kapalıyken kayıt (ön plan servisi, konum türü) için gereklidir.
- **Yakındaki cihazlar (Bluetooth)**: BLE OBD adaptörünü bulmak ve bağlanmak için. Demo ve Telefon modu bu izin olmadan çalışır.
- **Bildirimler**: kayıt sürerken kalıcı bildirim (Sürüşü bitir / Bağlantıyı kes düğmeleri) için.

### Kullanım
- Üstteki durum çubuğundan **Bağlan**: *OBD adaptörü* (BLE tarama; gerçek adaptör adı `OBDII`, servis FFF0, bildirim FFF1, yazma FFF2 - yazmalar yanıtlıdır; FFE0/FFE1 ve 18F0 yedektir), *Demo (simülatör)* veya *Telefon modu* (OBD yok, hız GPS'ten).
- **Panom**: sabitlenmiş büyük değer kartları ("Değer seç"). **Tüm Değerler**: gruplu, aramalı liste; yıldız ile panoya sabitleme. Yakıt ray basıncı bar cinsindendir.
- **Arıza Kodları**: standart OBD2 03/07/0A okuma ve onaylı silme. (Masaüstündeki ECU tarayıcı taşınmamıştır.)
- **Sürüş**: büyük, etkileşim gerektirmeyen sürüş ekranı (hız, devir, vites, yükseklik, eğim, boyuna/yanal g). Sürüş hız 5 km/s'yi aşınca otomatik başlar (Ayarlar'dan kapatılabilir) ve **yalnızca "Sürüşü bitir"** ile sona erer. OBD kopunca OBD verisi durur, aynı sürüşe otomatik yeniden bağlanılır; kayıt 30 sn'de bir diske yazılır, uygulama kapanırsa bir sonraki açılışta devam/bitir/sil sorulur.
- **Geçmiş**: sürüş listesi ve rapor: puan ve alt puanlar, mesafe/süre, toplam çıkış-iniş, en yüksek/düşük nokta, en dik yokuş/iniş, yokuş-düz-iniş yüzdeleri, yükseklik profili, hız-devir-vites grafiği (olay işaretli), uygulama içi OpenStreetMap haritası (osmdroid), Türkçe koçluk ipuçları. Rapor HTML olarak paylaşılabilir.
  - **Harita**: rota hız veya eğime göre renklenir (küçük gösterge), başlangıç yeşil, bitiş kırmızı; sert fren / sert hızlanma / sert viraj / motoru zorlama işaretlerine dokununca Türkçe bilgi balonu açılır. Rota sınırlarına otomatik yakınlaşır, rapor içinde iki parmakla yakınlaştırılır, "Tam ekran" düğmesi vardır. Harita karoları internetten indirilir (INTERNET izni, disk önbelleği açık; kullanıcı aracısı uygulama kimliğidir) ve "© OpenStreetMap katkıda bulunanlar" atfı gösterilir. İnternet yoksa önbellekteki karolar kullanılır; rota çizgisi yine görünür.
  - **Grafikler**: yükseklik profili ve hız/devir/vites grafiğinde yatay ızgara + sol eksende değerler, altta mesafe (km) / süre (dk) etiketleri bulunur. Grafiğe dokunun veya sürükleyin: dikey imleç, nokta ve "Yükseklik: 812 m · Mesafe: 3,4 km · Eğim: +4,2%" ipucu görünür; imleç haritada da işaretlenir.
  - **Demo modu** tepeli, kapalı bir çevrim yolu (yaklaşık 8 km, eğim %6-7'ye kadar) üretir; harita ve profil demoda da görülebilir.
- **Ayarlar**: 5 tema (Gece Mavisi, Aydınlık, Grafit Turuncu, Orman, Yüksek Kontrast), otomatik başlatma, birimler (km/s-mph, °C-°F), vites öğrenmesini sıfırlama.

### Sensörler ve ölçüm
- İvme: `TYPE_LINEAR_ACCELERATION` (yoksa ivme eksi alçak geçirgen yerçekimi). Yerçekimi ve GPS yönü/hız değişimiyle araç eksenlerine izdüşüm yapılır; ileri eksen düz yolda hızlanma/frenlemeyle otomatik kalibre edilir (telefon yönelimi önemsizdir).
- Konum: Play Services varsa FusedLocationProvider, yoksa LocationManager (1 Hz, yüksek doğruluk).
- Yükseklik: GPS mutlak değeri barometre göreli değişimiyle Kalman süzgecinde birleştirilir; çıkış/iniş 2,5 m histerezisle, eğim en az 50 m'lik pencerede hesaplanır.
- Puanlama: masaüstü kuralları + eğim bağlamı (dik yokuşta sert hızlanma daha az cezalı; yokuşta düşük devir; uzun inişte sürekli fren, motor freni önerisi), ivmeölçerle doğrulanan sert hızlanma/frenleme ve 0,35 g üstü sert viraj.

### Sürüm yayınlama
`main`'e her push'ta GitHub Actions testleri çalıştırır ve debug APK'yı artifact olarak yükler. `v*` etiketi (ör. `v1.0.0`) itildiğinde aynı APK `PSA-OBD2-Tani-<etiket>.apk` adıyla GitHub Release'e eklenir (debug anahtarıyla imzalı).

## Lisans

Bu proje açık kaynaktır ve **GNU GPL v3.0** lisansı ile dağıtılır (bkz. `LICENSE`). PSA ECU adresleri ve bazı arıza kodu listeleri GPL-3.0 lisanslı [ludwig-v/arduino-psa-diag](https://github.com/ludwig-v/arduino-psa-diag) projesinden derlenmiştir; kaynaklar `data/SOURCES.md` içindedir.

Katkılar memnuniyetle karşılanır: yeni araç/ECU adresleri, doğrulanmış DID'ler ve çeviriler için pull request açabilirsiniz.

## Test

```
python tests/test_smoke.py          # simülatör -> ELM katmanı + sürüş/vites/puan birim testleri (GUI yok)
python tests/test_gui_offscreen.py  # QT_QPA_PLATFORM=offscreen ile GUI oluşturma
```

## Yasal uyarı

Bu yazılım bilgilendirme amaçlıdır ve "olduğu gibi" sunulur. Araç ECU'larına yazma/silme komutları (özellikle arıza kodu silme) araç davranışını etkileyebilir; tüm sorumluluk kullanıcıya aittir. Sürüş sırasında kullanmayın. Citroën/PSA ve ELM327 ilgili sahiplerinin markalarıdır; bu proje onlarla bağlantılı değildir. Üretici veri dosyalarındaki "doğrulanmamış" kayıtlar hatalı olabilir; yalnızca okuma servisleri kullanılır ancak sonuçların doğruluğu garanti edilmez.
