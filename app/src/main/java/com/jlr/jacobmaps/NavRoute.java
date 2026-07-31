package com.jlr.jacobmaps;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * Çözümlenmiş rota: geometri, kümülatif mesafeler ve manevra adımları.
 *
 * Kümülatif mesafe dizisini bir kez kuruyoruz; simülasyon her karede "şu kadar metre
 * gittim, neredeyim" diye soruyor ve bu ikili aramayla anında cevaplanıyor.
 */
class NavRoute {

    /** Kavşaktaki tek bir şerit: hangi yönlere izin veriyor ve rotamız için geçerli mi. */
    static class Lane {
        final boolean valid;
        final String[] indications;
        Lane(boolean valid, String[] indications) {
            this.valid = valid;
            this.indications = indications;
        }
    }

    /** Tek bir manevra adımı. */
    static class Step {
        final String type;        // turn, merge, roundabout, arrive ...
        final String modifier;    // left, right, slight left ...
        final String name;        // yol adı
        final double distance;    // bu adımın uzunluğu (m)
        double startAt;           // rota başından bu adımın başına olan mesafe (m)
        List<Lane> lanes;         // manevra kavşağındaki şeritler, yoksa null

        Step(String type, String modifier, String name, double distance) {
            this.type = type;
            this.modifier = modifier;
            this.name = name;
            this.distance = distance;
        }
    }

    final List<LatLng> points;
    final double[] cum;           // cum[i] = başlangıçtan points[i]'ye mesafe
    final List<Step> steps;
    final double distance;        // toplam metre
    final double duration;        // saniye (OSRM tahmini)

    NavRoute(List<LatLng> points, List<Step> steps, double distance, double duration) {
        this.points = points;
        this.steps = steps;
        this.distance = distance;
        this.duration = duration;

        cum = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cum[i] = cum[i - 1] + Geo.distance(points.get(i - 1), points.get(i));
        }

        double acc = 0;
        for (Step s : steps) { s.startAt = acc; acc += s.distance; }
    }

    /** Geometrinin gerçek uzunluğu (OSRM'in verdiği mesafeden ufak sapabilir). */
    double geometryLength() {
        return cum.length == 0 ? 0 : cum[cum.length - 1];
    }

    /** Rota başından d metre ilerideki konum. */
    LatLng positionAt(double d) {
        if (points.isEmpty()) return null;
        if (d <= 0) return points.get(0);
        double total = geometryLength();
        if (d >= total) return points.get(points.size() - 1);

        int i = indexFor(d);
        double segStart = cum[i], segEnd = cum[i + 1];
        double t = segEnd > segStart ? (d - segStart) / (segEnd - segStart) : 0;
        return Geo.lerp(points.get(i), points.get(i + 1), t);
    }

    /**
     * d metredeki yön. Tek segmente bakmak virajlarda titriyor; bu yüzden biraz
     * ileriye bakıp oradaki noktayla arasındaki açıyı alıyoruz.
     */
    double bearingAt(double d, double lookAheadMeters) {
        LatLng here = positionAt(d);
        LatLng ahead = positionAt(Math.min(d + lookAheadMeters, geometryLength()));
        if (here == null || ahead == null) return 0;
        if (Geo.distance(here, ahead) < 1.0) {
            // Yolun sonundayız; son segmentin yönünü koru.
            int n = points.size();
            if (n >= 2) return Geo.bearing(points.get(n - 2), points.get(n - 1));
            return 0;
        }
        return Geo.bearing(here, ahead);
    }

    /** d metreye kadar kat edilen kısmın geometrisi — "gidilen yol" çizgisi için. */
    List<LatLng> traveled(double d) {
        List<LatLng> out = new ArrayList<>();
        if (points.isEmpty()) return out;
        double total = geometryLength();
        if (d >= total) return new ArrayList<>(points);

        int i = indexFor(d);
        for (int k = 0; k <= i; k++) out.add(points.get(k));
        LatLng p = positionAt(d);
        if (p != null) out.add(p);
        return out;
    }

    /** d metredeyken sıradaki manevra; yoksa null. */
    Step nextStep(double d) {
        for (Step s : steps) {
            if (s.startAt > d + 0.5) return s;
        }
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    /** Sıradaki manevraya kalan mesafe. */
    double distanceToNextStep(double d) {
        for (Step s : steps) {
            if (s.startAt > d + 0.5) return s.startAt - d;
        }
        return Math.max(0, geometryLength() - d);
    }

    /**
     * GPS noktasını rotaya izdüşürüp "rota başından kaç metre" değerini verir.
     *
     * Tüm rotayı taramak yerine son bilinen konumun etrafındaki pencereye bakıyoruz:
     * 291 km'lik rotada 4749 nokta var, her düzeltmede hepsini taramak gereksiz ve
     * rotanın kendisiyle kesiştiği yerlerde yanlış eşleşme üretir.
     */
    double snapTo(LatLng p, double hint, double window) {
        if (points.size() < 2) return 0;
        double total = geometryLength();
        double lo = Math.max(0, hint - window), hi = Math.min(total, hint + window);
        int i0 = indexFor(lo), i1 = indexFor(hi);

        double bestD = Double.MAX_VALUE, bestAt = hint;
        for (int i = i0; i <= i1 && i < points.size() - 1; i++) {
            LatLng a = points.get(i), b = points.get(i + 1);
            double segLen = cum[i + 1] - cum[i];
            if (segLen <= 0) continue;
            // Küçük mesafelerde düzlem yaklaşımı yeterli.
            double t = projectT(a, b, p);
            LatLng q = Geo.lerp(a, b, t);
            double d = Geo.distance(p, q);
            if (d < bestD) { bestD = d; bestAt = cum[i] + segLen * t; }
        }
        return bestAt;
    }

    /** Noktanın a-b parçası üzerindeki oransal izdüşümü (0..1). */
    private double projectT(LatLng a, LatLng b, LatLng p) {
        double kx = Math.cos(Math.toRadians(a.getLatitude()));
        double ax = a.getLongitude() * kx, ay = a.getLatitude();
        double bx = b.getLongitude() * kx, by = b.getLatitude();
        double px = p.getLongitude() * kx, py = p.getLatitude();
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 <= 0) return 0;
        double t = ((px - ax) * dx + (py - ay) * dy) / len2;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    private int indexFor(double d) {
        int lo = 0, hi = cum.length - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= d) lo = mid; else hi = mid;
        }
        return Math.min(lo, points.size() - 2);
    }
}
