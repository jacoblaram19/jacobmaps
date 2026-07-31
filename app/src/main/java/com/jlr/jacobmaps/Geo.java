package com.jlr.jacobmaps;

import org.maplibre.android.geometry.LatLng;

import java.util.List;

/** Küresel geometri yardımcıları. Hepsi tahsissiz çalışır, kare döngüsünde çağrılıyor. */
final class Geo {

    private static final double R = 6371008.8;   // ortalama dünya yarıçapı (m)

    private Geo() { }

    /** İki nokta arası metre (haversine). */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double distance(LatLng a, LatLng b) {
        return distance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
    }

    /** a'dan b'ye pusula açısı, derece (0=kuzey). */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    static double bearing(LatLng a, LatLng b) {
        return bearing(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
    }

    /**
     * İki açı arasındaki en kısa fark (-180..180). Kamera dönüşünü yumuşatırken
     * 359°'den 1°'ye geçerken uzun yoldan dönmesini engelliyor.
     */
    static double angleDelta(double from, double to) {
        double d = (to - from + 540.0) % 360.0 - 180.0;
        return d;
    }

    /** Üstel sönümlemeli açı takibi; kare süresinden bağımsız olsun diye dt kullanıyor. */
    static double smoothAngle(double current, double target, double rate, double dt) {
        double k = 1.0 - Math.exp(-rate * dt);
        return (current + angleDelta(current, target) * k + 360.0) % 360.0;
    }

    /** İki nokta arasında oransal konum. */
    static LatLng lerp(LatLng a, LatLng b, double t) {
        return new LatLng(
                a.getLatitude() + (b.getLatitude() - a.getLatitude()) * t,
                a.getLongitude() + (b.getLongitude() - a.getLongitude()) * t);
    }

    /** Metre cinsinden ileri kaydırma — kamerayı aracın önüne almak için. */
    static LatLng offset(LatLng from, double bearingDeg, double meters) {
        double d = meters / R;
        double b = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(from.getLatitude());
        double lon1 = Math.toRadians(from.getLongitude());
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d)
                + Math.cos(lat1) * Math.sin(d) * Math.cos(b));
        double lon2 = lon1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        return new LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2));
    }

    /** Bir listedeki toplam uzunluk (m). */
    static double length(List<LatLng> pts) {
        double s = 0;
        for (int i = 1; i < pts.size(); i++) s += distance(pts.get(i - 1), pts.get(i));
        return s;
    }
}
