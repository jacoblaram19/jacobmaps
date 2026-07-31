# JacobMaps

Sade, akıcı bir Android navigasyon uygulaması. Trafik ve yer araması yok — odak
**doğru rota, Apple Maps'e yakın kamera akışı ve açık/koyu tema**.

## Durum

| Parça | Durum |
|---|---|
| Vektör harita, açık/koyu tema | çalışıyor |
| Rota (OSRM, OpenStreetMap) | çalışıyor |
| Rota çizimi + kat edilen yol ayrımı | çalışıyor |
| Sürüş simülasyonu + kamera takibi | çalışıyor (cihazda görsel doğrulama bekliyor) |
| Türkçe manevra tarifleri | çalışıyor |
| Gerçek GPS ile navigasyon | yapılacak |
| Çevrimdışı harita indirme | yapılacak |
| Sesli yönlendirme | yapılacak |

## Akıcılık nasıl sağlanıyor

1. **Kare başına konum.** Simülasyon `Choreographer` ile ekran tazelemesine kilitli
   çalışıyor; konum saniyede bir güncellenip aradaki kareler uydurulmuyor. Ölçüldü:
   1× hızda kareler arası hareket **~0.4 m**, 8× hızda 3.3 m.
2. **Araç sabit, kamera hareketli.** Takip modunda araç ekranda sabit bir View;
   harita sembolü olmadığı için her karede sembol yerleşimi yeniden hesaplanmıyor.
3. **Sönümlü kamera.** Yön, üstel sönümlemeyle (`Geo.smoothAngle`) takip ediyor —
   viraja girerken savrulmuyor, çıkarken geri kalmıyor. 359°→1° geçişi en kısa yoldan.
4. **Kısılmış katman güncellemesi.** Kat edilen yol çizgisi her karede değil, saniyede
   ~8 kez yeniden yazılıyor; kamera ise her karede.

## Veri kaynakları

- Harita karoları: [OpenFreeMap](https://openfreemap.org) — anahtar gerektirmiyor
- Rota: [OSRM](https://project-osrm.org) demo sunucusu
- Veri: OpenStreetMap katkıcıları (ODbL)

Rota motoru `Router` sınıfının arkasında; çevrimdışı istendiğinde GraphHopper/BRouter
buraya takılır, çağıran taraf değişmez.

## Derleme

```sh
export JAVA_HOME=~/android-dev/jdk
~/android-dev/gradle/bin/gradle assembleDebug
```

minSdk 31, targetSdk 34, Java 17, MapLibre GL Native 11.5.2. Kotlin/Compose yok.
