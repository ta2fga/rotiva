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

## Lisans

Bu proje açık kaynaktır ve **GNU GPL v3.0** lisansı ile dağıtılır (bkz. `LICENSE`). PSA ECU adresleri ve bazı arıza kodu listeleri GPL-3.0 lisanslı [ludwig-v/arduino-psa-diag](https://github.com/ludwig-v/arduino-psa-diag) projesinden derlenmiştir; kaynaklar `data/SOURCES.md` içindedir.

Katkılar memnuniyetle karşılanır: yeni araç/ECU adresleri, doğrulanmış DID'ler ve çeviriler için pull request açabilirsiniz.

## Test

```
python tests/test_smoke.py          # simülatör -> ELM katmanı (GUI yok)
python tests/test_gui_offscreen.py  # QT_QPA_PLATFORM=offscreen ile GUI oluşturma
```

## Yasal uyarı

Bu yazılım bilgilendirme amaçlıdır ve "olduğu gibi" sunulur. Araç ECU'larına yazma/silme komutları (özellikle arıza kodu silme) araç davranışını etkileyebilir; tüm sorumluluk kullanıcıya aittir. Sürüş sırasında kullanmayın. Citroën/PSA ve ELM327 ilgili sahiplerinin markalarıdır; bu proje onlarla bağlantılı değildir. Üretici veri dosyalarındaki "doğrulanmamış" kayıtlar hatalı olabilir; yalnızca okuma servisleri kullanılır ancak sonuçların doğruluğu garanti edilmez.
