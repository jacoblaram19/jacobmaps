package com.jlr.jacobmaps;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JacobMaps — tek ekranlı navigasyon.
 *
 * Akıcılığın üç dayanağı var:
 *  1) Simülasyon/konum her karede (Choreographer) ilerliyor, saniyelik sıçrama yok.
 *  2) Takip modunda araç ekranda sabit bir noktada duruyor ve hareketi kamera yapıyor;
 *     araç bir harita sembolü değil, üstte duran sabit bir View. Böylece her karede
 *     sembol yerleşimi yeniden hesaplanmıyor.
 *  3) Kamera açısı üstel sönümlemeyle takip ediyor, viraja girerken savrulmuyor.
 */
public class MainActivity extends Activity {

    // Açık/koyu için anahtarsız, ücretsiz vektör karo stilleri.
    private static final String STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty";
    private static final String STYLE_DARK  = "https://tiles.openfreemap.org/styles/dark";

    private static final LatLng ISTANBUL = new LatLng(41.0082, 28.9784);
    private static final LatLng ENEZ     = new LatLng(40.7256, 26.0800);

    private static final double NAV_ZOOM = 16.2;
    private static final double NAV_TILT = 58.0;
    /** Aracın ekranda durduğu yer: alttan yukarı doğru oranı. */
    private static final double PUCK_AT = 0.72;

    private MapView mapView;
    private MapLibreMap map;
    private Style style;

    private View bannerView, bottomCard, sideButtons;
    private TextView maneuverIcon, maneuverDist, maneuverRoad;
    private TextView title, subtitle, btnPrimary, btnSecondary, btnTheme, btnRecenter;
    private ImageView puck;

    private final Router router = new Router();
    private NavRoute route;
    private Simulator sim;

    private LatLng origin = ISTANBUL;
    private LatLng destination = ENEZ;

    private boolean dark = true;
    private boolean navigating = false;
    private double camBearing = 0;
    private long lastTraveledUpdate = 0;

    // ---- yaşam döngüsü ----------------------------------------------------

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        bindViews();
        addPuck();
        applyUiTheme();

        mapView.onCreate(b);
        mapView.getMapAsync(m -> {
            map = m;
            map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setLogoEnabled(false);
            map.getUiSettings().setAttributionEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(true);

            map.setCameraPosition(new CameraPosition.Builder()
                    .target(ISTANBUL).zoom(9.5).build());

            loadStyle();

            map.addOnMapLongClickListener(p -> {
                if (navigating) return true;
                destination = p;
                subtitle.setText(String.format(Locale.US,
                        "hedef: %.4f, %.4f", p.getLatitude(), p.getLongitude()));
                btnPrimary.setText("Rota kur");
                drawDestination();
                return true;
            });
        });
    }

    private void bindViews() {
        mapView = findViewById(R.id.map);
        bannerView = findViewById(R.id.banner);
        bottomCard = findViewById(R.id.bottom_card);
        sideButtons = findViewById(R.id.side_buttons);
        maneuverIcon = findViewById(R.id.maneuver_icon);
        maneuverDist = findViewById(R.id.maneuver_dist);
        maneuverRoad = findViewById(R.id.maneuver_road);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        btnPrimary = findViewById(R.id.btn_primary);
        btnSecondary = findViewById(R.id.btn_secondary);
        btnTheme = findViewById(R.id.btn_theme);
        btnRecenter = findViewById(R.id.btn_recenter);

        btnPrimary.setOnClickListener(v -> onPrimary());
        btnSecondary.setOnClickListener(v -> cycleSpeed());
        btnTheme.setOnClickListener(v -> { dark = !dark; applyUiTheme(); loadStyle(); });
        btnRecenter.setOnClickListener(v -> {
            if (route != null && !navigating) fitRoute();
        });
    }

    /** Araç: harita sembolü değil, ekranda sabit duran bir View. */
    private void addPuck() {
        puck = new ImageView(this);
        puck.setImageResource(R.drawable.puck);
        int size = (int) (54 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER;
        puck.setLayoutParams(lp);
        puck.setVisibility(View.GONE);
        ((FrameLayout) findViewById(R.id.root)).addView(puck, 1);

        puck.post(() -> {
            int h = findViewById(R.id.root).getHeight();
            puck.setTranslationY((float) (h * (PUCK_AT - 0.5)));
        });
    }

    // ---- stil ve tema -----------------------------------------------------

    private void loadStyle() {
        if (map == null) return;
        map.setStyle(new Style.Builder().fromUri(dark ? STYLE_DARK : STYLE_LIGHT), s -> {
            style = s;
            // Stil değişince kaynak/katmanlar sıfırlanır, yeniden kuruyoruz.
            setupLayers();
            if (route != null) drawRoute();
            drawDestination();
        });
    }

    private void applyUiTheme() {
        int card = dark ? 0xE6151C24 : 0xF2FFFFFF;
        int text = dark ? 0xFFE6EDF3 : 0xFF0B1620;
        int dim  = dark ? 0xFF9BA7B4 : 0xFF5B6B7A;

        tint(bottomCard, card);
        tint(bannerView, card);
        tint(btnTheme, card);
        tint(btnRecenter, card);

        title.setTextColor(text);
        subtitle.setTextColor(dim);
        maneuverDist.setTextColor(text);
        maneuverRoad.setTextColor(dim);
        maneuverIcon.setTextColor(dark ? 0xFF4DA3FF : 0xFF0B7BE0);
        btnTheme.setTextColor(text);
        btnRecenter.setTextColor(text);
        btnSecondary.setTextColor(text);
        btnSecondary.getBackground().setTint(dark ? 0x22FFFFFF : 0x18000000);
    }

    private void tint(View v, int color) {
        Drawable d = v.getBackground();
        if (d instanceof GradientDrawable) ((GradientDrawable) d.mutate()).setColor(color);
        else if (d != null) d.mutate().setTint(color);
    }

    // ---- harita katmanları ------------------------------------------------

    private void setupLayers() {
        if (style == null) return;

        style.addImage("pin", drawableToBitmap(R.drawable.pin));

        style.addSource(new GeoJsonSource("route-src"));
        style.addSource(new GeoJsonSource("traveled-src"));
        style.addSource(new GeoJsonSource("dest-src"));

        int casing = dark ? 0xFF0A0F14 : 0xFFFFFFFF;
        int main   = dark ? 0xFF4DA3FF : 0xFF0B7BE0;
        int done   = dark ? 0xFF37424D : 0xFFB6C2CF;

        style.addLayer(new LineLayer("route-casing", "route-src").withProperties(
                PropertyFactory.lineColor(casing),
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));

        style.addLayer(new LineLayer("route-main", "route-src").withProperties(
                PropertyFactory.lineColor(main),
                PropertyFactory.lineWidth(8.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));

        // Kat edilen kısım üstte ve sönük — kalan yol böylece öne çıkıyor.
        style.addLayer(new LineLayer("route-done", "traveled-src").withProperties(
                PropertyFactory.lineColor(done),
                PropertyFactory.lineWidth(8.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));

        style.addLayer(new SymbolLayer("dest-layer", "dest-src").withProperties(
                PropertyFactory.iconImage("pin"),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)));
    }

    private Bitmap drawableToBitmap(int resId) {
        Drawable d = getDrawable(resId);
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bm;
    }

    private void drawRoute() {
        if (style == null || route == null) return;
        GeoJsonSource src = (GeoJsonSource) style.getSource("route-src");
        if (src != null) src.setGeoJson(toLine(route.points));
    }

    private void drawDestination() {
        if (style == null || destination == null) return;
        GeoJsonSource src = (GeoJsonSource) style.getSource("dest-src");
        if (src != null) {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(
                    destination.getLongitude(), destination.getLatitude())));
        }
    }

    private LineString toLine(List<LatLng> pts) {
        List<Point> ps = new ArrayList<>(pts.size());
        for (LatLng p : pts) ps.add(Point.fromLngLat(p.getLongitude(), p.getLatitude()));
        return LineString.fromLngLats(ps);
    }

    // ---- akış -------------------------------------------------------------

    private void onPrimary() {
        if (navigating) { stopNavigation(); return; }
        if (route == null) { fetchRoute(); return; }
        startNavigation();
    }

    private void fetchRoute() {
        btnPrimary.setText("Rota hesaplanıyor…");
        subtitle.setText("OSRM üzerinden çiziliyor");
        router.route(origin, destination, new Router.Callback() {
            @Override public void onRoute(NavRoute r) {
                route = r;
                drawRoute();
                fitRoute();
                title.setText("İstanbul → Enez");
                subtitle.setText(String.format(Locale.US, "%.0f km · %.0f sa %.0f dk · %d manevra",
                        r.distance / 1000, Math.floor(r.duration / 3600),
                        Math.floor((r.duration % 3600) / 60), r.steps.size()));
                btnPrimary.setText("Sürüşü başlat");
            }
            @Override public void onError(String message) {
                btnPrimary.setText("Tekrar dene");
                subtitle.setText(message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void fitRoute() {
        if (map == null || route == null || route.points.isEmpty()) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : route.points) b.include(p);
        int padH = (int) (40 * getResources().getDisplayMetrics().density);
        int padTop = (int) (90 * getResources().getDisplayMetrics().density);
        int padBottom = (int) (200 * getResources().getDisplayMetrics().density);
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                b.build(), padH, padTop, padH, padBottom), 900);
    }

    private void startNavigation() {
        if (route == null || map == null) return;
        navigating = true;

        sim = new Simulator(route, new Simulator.Listener() {
            @Override public void onTick(double d, double speed, double dt) {
                updateFrame(d, speed, dt);
            }
            @Override public void onFinished() {
                subtitle.setText("Vardınız");
                stopNavigation();
            }
        });
        sim.reset();
        sim.setMultiplier(8);

        bannerView.setVisibility(View.VISIBLE);
        bannerView.setAlpha(0f);
        bannerView.animate().alpha(1f).setDuration(260).start();
        puck.setVisibility(View.VISIBLE);
        btnPrimary.setText("Bitir");
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText("8×");

        // Genel görünümden sürüş kamerasına yumuşak geçiş, sonra kare döngüsü devralır.
        camBearing = route.bearingAt(0, 60);
        CameraPosition cp = navCamera(route.positionAt(0), camBearing);
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 1100);
        mapView.postDelayed(() -> { if (navigating) sim.start(); }, 1150);
    }

    private void stopNavigation() {
        navigating = false;
        if (sim != null) sim.stop();
        puck.setVisibility(View.GONE);
        bannerView.setVisibility(View.GONE);
        btnSecondary.setVisibility(View.GONE);
        btnPrimary.setText("Sürüşü başlat");
        if (map != null) {
            CameraPosition cp = new CameraPosition.Builder()
                    .tilt(0).bearing(0).build();
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 700);
        }
        fitRoute();
    }

    private void cycleSpeed() {
        if (sim == null) return;
        double m = sim.getMultiplier();
        double next = m >= 64 ? 1 : m * 4;
        sim.setMultiplier(next);
        btnSecondary.setText(((int) next) + "×");
    }

    /** Her karede: konum, kamera, bant. */
    private void updateFrame(double d, double speed, double dt) {
        if (map == null || route == null) return;

        LatLng pos = route.positionAt(d);
        double target = route.bearingAt(d, 55);
        // Sönümleme: viraja girerken kamera savrulmasın, çıkarken geri kalmasın.
        camBearing = Geo.smoothAngle(camBearing, target, 3.2, dt);

        map.moveCamera(CameraUpdateFactory.newCameraPosition(navCamera(pos, camBearing)));

        // Kat edilen çizgi her karede değil, saniyede ~8 kez güncellensin.
        long now = SystemClock.uptimeMillis();
        if (now - lastTraveledUpdate > 120) {
            lastTraveledUpdate = now;
            GeoJsonSource src = style == null ? null
                    : (GeoJsonSource) style.getSource("traveled-src");
            if (src != null) src.setGeoJson(toLine(route.traveled(d)));
        }

        updateBanner(d, speed);
    }

    /**
     * Sürüş kamerası. Kamerayı aracın biraz ilerisine kilitliyoruz; böylece araç ekranın
     * alt üçte birinde kalıp önündeki yol görünüyor (padding API'sine bağımlı kalmadan).
     */
    private CameraPosition navCamera(LatLng pos, double bearing) {
        LatLng ahead = Geo.offset(pos, bearing, 260);
        return new CameraPosition.Builder()
                .target(ahead)
                .zoom(NAV_ZOOM)
                .tilt(NAV_TILT)
                .bearing(bearing)
                .build();
    }

    private void updateBanner(double d, double speed) {
        NavRoute.Step next = route.nextStep(d);
        double toTurn = route.distanceToNextStep(d);
        double remain = route.geometryLength() - d;

        maneuverIcon.setText(arrowFor(next));
        maneuverDist.setText(formatDistance(toTurn));
        maneuverRoad.setText(next == null || next.name.isEmpty()
                ? describe(next) : describe(next) + " · " + next.name);

        title.setText(formatDistance(remain) + " kaldı");
        subtitle.setText(String.format(Locale.US, "%.0f km/sa · varış %s",
                speed * 3.6, eta(remain, speed)));
    }

    private String eta(double remain, double speed) {
        double v = Math.max(speed, 60 / 3.6);
        int mins = (int) Math.round(remain / v / 60.0);
        if (mins < 60) return mins + " dk";
        return (mins / 60) + " sa " + (mins % 60) + " dk";
    }

    private String formatDistance(double m) {
        if (m < 1000) return Math.round(m / 10.0) * 10 + " m";
        return String.format(Locale.US, "%.1f km", m / 1000.0);
    }

    private String describe(NavRoute.Step s) {
        if (s == null) return "";
        String mod = s.modifier == null ? "" : s.modifier;
        switch (s.type) {
            case "arrive": return "Vardınız";
            case "depart": return "Yola çıkın";
            case "roundabout": case "rotary": return "Göbekten devam";
            case "merge": return "Katılın";
            case "fork": return mod.contains("left") ? "Soldan devam" : "Sağdan devam";
            case "on ramp": return "Bağlantı yoluna girin";
            case "off ramp": return "Çıkışı kullanın";
            default: break;
        }
        if (mod.contains("sharp left")) return "Keskin sola dönün";
        if (mod.contains("sharp right")) return "Keskin sağa dönün";
        if (mod.contains("slight left")) return "Hafif sola";
        if (mod.contains("slight right")) return "Hafif sağa";
        if (mod.contains("left")) return "Sola dönün";
        if (mod.contains("right")) return "Sağa dönün";
        return "Düz devam";
    }

    private String arrowFor(NavRoute.Step s) {
        if (s == null) return "↑";
        if ("arrive".equals(s.type)) return "◎";
        String mod = s.modifier == null ? "" : s.modifier;
        if (mod.contains("sharp left")) return "↰";
        if (mod.contains("sharp right")) return "↱";
        if (mod.contains("slight left")) return "↖";
        if (mod.contains("slight right")) return "↗";
        if (mod.contains("left")) return "←";
        if (mod.contains("right")) return "→";
        return "↑";
    }

    // ---- MapView yaşam döngüsü --------------------------------------------

    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() {
        if (sim != null) sim.stop();
        mapView.onPause();
        super.onPause();
    }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onDestroy() { mapView.onDestroy(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        mapView.onSaveInstanceState(out);
    }
}
