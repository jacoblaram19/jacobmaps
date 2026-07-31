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
import org.maplibre.android.gestures.MoveGestureDetector;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
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
    /** ~20 km yarıçapı ekrana sığdıran yakınlaştırma (41° enlem için). */
    private static final double RADIUS_ZOOM = 11.6;

    private MapView mapView;
    private MapLibreMap map;
    private Style style;

    private View bannerView, bottomCard, sideButtons, approachBar;
    private ImageView maneuverIcon;
    private TextView maneuverDist, maneuverRoad;
    private TextView title, subtitle, btnPrimary, btnSecondary, btnTheme, btnRecenter, btnOffline, btnView;
    private LaneView laneView;
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
    private boolean offlineBusy = false;

    /** Kamera kipi: sürüş takibi, 20 km çevre, tüm rota. */
    private enum Cam { FOLLOW, RADIUS, ROUTE }
    private Cam camMode = Cam.FOLLOW;
    /** Kullanıcı haritayı elle gezdirdiyse kamera ona karışmıyor. */
    private boolean freeRoam = false;

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

            // Kullanıcı haritayı elle gezdirirse otomatik kamera susar; jest kaynaklı
            // hareketler bu dinleyiciye düşer, bizim moveCamera çağrılarımız düşmez.
            map.addOnMoveListener(new MapLibreMap.OnMoveListener() {
                @Override public void onMoveBegin(@NonNull MoveGestureDetector d) {
                    if (navigating) {
                        freeRoam = true;
                        btnRecenter.setText("konum");
                        btnRecenter.setTextSize(11f);
                    }
                }
                @Override public void onMove(@NonNull MoveGestureDetector d) { }
                @Override public void onMoveEnd(@NonNull MoveGestureDetector d) { }
            });

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
        approachBar = findViewById(R.id.approach_bar);
        laneView = findViewById(R.id.lanes);
        btnOffline = findViewById(R.id.btn_offline);
        btnView = findViewById(R.id.btn_view);
        maneuverDist = findViewById(R.id.maneuver_dist);
        maneuverRoad = findViewById(R.id.maneuver_road);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        btnPrimary = findViewById(R.id.btn_primary);
        btnSecondary = findViewById(R.id.btn_secondary);
        btnTheme = findViewById(R.id.btn_theme);
        btnRecenter = findViewById(R.id.btn_recenter);

        btnPrimary.setOnClickListener(v -> onPrimary());
        btnOffline.setOnClickListener(v -> downloadOffline());
        btnView.setOnClickListener(v -> cycleCam());
        btnSecondary.setOnClickListener(v -> cycleSpeed());
        btnTheme.setOnClickListener(v -> { dark = !dark; applyUiTheme(); loadStyle(); });
        btnRecenter.setOnClickListener(v -> {
            if (navigating) {
                freeRoam = false;
                camMode = Cam.FOLLOW;
                btnView.setText("20km");
                snapToFollow();
            } else if (route != null) {
                fitRoute();
            }
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
        // Yeni stil yüklenirken eski Style nesnesi geçersiz kalıyor; kare döngüsü
        // ona dokunursa MapLibre "newer style is loading" diye çöküyordu.
        style = null;
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
        tint(btnOffline, card);
        tint(btnView, card);

        title.setTextColor(text);
        subtitle.setTextColor(dim);
        maneuverDist.setTextColor(text);
        maneuverRoad.setTextColor(dim);
        btnTheme.setTextColor(text);
        btnRecenter.setTextColor(text);
        btnOffline.setTextColor(text);
        btnView.setTextColor(text);
        btnSecondary.setTextColor(text);
        btnSecondary.getBackground().setTint(dark ? 0x22FFFFFF : 0x18000000);
    }

    private void tint(View v, int color) {
        Drawable d = v.getBackground();
        if (d instanceof GradientDrawable) ((GradientDrawable) d.mutate()).setColor(color);
        else if (d != null) d.mutate().setTint(color);
    }

    // ---- harita katmanları ------------------------------------------------

    /** Stil yüklü ve geçerli mi — kaynaklara dokunmadan önce şart. */
    private boolean styleReady() {
        return style != null && style.isFullyLoaded();
    }

    private void setupLayers() {
        if (!styleReady()) return;

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
        if (!styleReady() || route == null) return;
        GeoJsonSource src = (GeoJsonSource) style.getSource("route-src");
        if (src != null) src.setGeoJson(toLine(route.points));
    }

    private void drawDestination() {
        if (!styleReady() || destination == null) return;
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
                title.setText("İstanbul - Enez");
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

        camMode = Cam.FOLLOW;
        freeRoam = false;
        btnView.setText("20km");
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
        freeRoam = false;
        camMode = Cam.FOLLOW;
        btnRecenter.setText("◎");
        btnRecenter.setTextSize(20f);
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

    /** 20 km çevre → tüm rota → sürüş takibi. */
    private void cycleCam() {
        if (!navigating) { if (route != null) fitRoute(); return; }
        freeRoam = false;
        btnRecenter.setText("◎");
        btnRecenter.setTextSize(20f);

        switch (camMode) {
            case FOLLOW:
                camMode = Cam.RADIUS;
                btnView.setText("rota");
                break;
            case RADIUS:
                camMode = Cam.ROUTE;
                btnView.setText("sürüş");
                fitRoute();
                break;
            default:
                camMode = Cam.FOLLOW;
                btnView.setText("20km");
                snapToFollow();
                break;
        }
    }

    /** Sürüş kamerasına yumuşak dönüş. */
    private void snapToFollow() {
        if (map == null || route == null || sim == null) return;
        double d = sim.getDistance();
        camBearing = route.bearingAt(d, 55);
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
                navCamera(route.positionAt(d), camBearing)), 650);
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

        // Serbest dolaşımdaysa ya da tüm rota görünümündeyse kameraya karışma.
        if (!freeRoam && camMode == Cam.FOLLOW) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(navCamera(pos, camBearing)));
        } else if (!freeRoam && camMode == Cam.RADIUS) {
            // 20 km çevre: kuzey yukarı, düz bakış, araç merkezde.
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    new CameraPosition.Builder()
                            .target(pos).zoom(RADIUS_ZOOM).tilt(0).bearing(0).build()));
        }

        // Kat edilen çizgi her karede değil, saniyede ~8 kez güncellensin.
        long now = SystemClock.uptimeMillis();
        if (now - lastTraveledUpdate > 120) {
            lastTraveledUpdate = now;
            GeoJsonSource src = styleReady() ? (GeoJsonSource) style.getSource("traveled-src") : null;
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

    /**
     * Üst bant. Tasarım ölçütü: 110 km/s'te giderken bir saniyelik bakışta
     * "ne kadar kaldı / ne yapacağım / hangi şerit" cevaplanmalı. Bu yüzden
     * eşikler mesafeye değil SÜREYE bağlı — 400 m şehirde uzak, otoyolda 13 saniye.
     */
    private void updateBanner(double d, double speed) {
        NavRoute.Step next = route.nextStep(d);
        double toTurn = route.distanceToNextStep(d);
        double remain = route.geometryLength() - d;

        // Manevraya kalan süre; durunca sonsuza gitmesin diye taban hız var.
        double t = toTurn / Math.max(speed, 8.0);

        // 20 sn uzakta mavi, 4 sn kala kırmızı.
        double ramp = clamp01((20.0 - t) / 16.0);
        int color = rampColor(ramp);

        maneuverIcon.setImageResource(iconFor(next));
        maneuverIcon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        maneuverDist.setText(formatDistance(toTurn));
        maneuverDist.setTextColor(ramp > 0.55 ? color : (dark ? 0xFFE6EDF3 : 0xFF0B1620));
        maneuverRoad.setText(next == null || next.name.isEmpty()
                ? describe(next) : describe(next) + " · " + next.name);

        // Yaklaşma çubuğu: rakamı okumadan mesafe hissi.
        approachBar.setPivotX(0f);
        approachBar.setScaleX((float) ramp);
        tint(approachBar, color);

        // Şerit rehberliği yalnızca dönüş/sapakta ve manevraya ~15 sn kala.
        boolean showLanes = next != null && next.lanes != null
                && isTurnLike(next) && t < 30.0;
        if (showLanes) {
            laneView.setColors(color, dark ? 0x4DFFFFFF : 0x33000000,
                    dark ? 0x1AFFFFFF : 0x0F000000);
            laneView.setLanes(next.lanes);
            if (laneView.getVisibility() != View.VISIBLE) {
                laneView.setVisibility(View.VISIBLE);
                laneView.setAlpha(0f);
                laneView.animate().alpha(1f).setDuration(180).start();
            }
        } else if (laneView.getVisibility() == View.VISIBLE) {
            laneView.setVisibility(View.GONE);
        }

        title.setText(formatDistance(remain) + " kaldı");
        double secs = etaSeconds(remain);
        subtitle.setText(String.format(Locale.US, "%.0f km/sa · tahmini varış %s inşallah · %s",
                speed * 3.6, clockAfter(secs), humanDuration(secs)));
    }

    /** Düz devam eden adımlarda şerit göstermiyoruz — zaten akıp gidiyor. */
    private boolean isTurnLike(NavRoute.Step s) {
        String type = s.type == null ? "" : s.type;
        String mod = s.modifier == null ? "" : s.modifier;
        if (type.contains("ramp") || type.equals("fork") || type.equals("merge")
                || type.equals("roundabout") || type.equals("rotary")
                || type.equals("end of road")) {
            return true;
        }
        if (type.equals("turn")) return mod.contains("left") || mod.contains("right");
        return false;
    }

    private double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Mavi → kehribar → kırmızı. */
    private int rampColor(double t) {
        int blue = dark ? 0xFF4DA3FF : 0xFF0B7BE0;
        int amber = 0xFFE3A008;
        int red = 0xFFE5484D;
        return t < 0.5 ? lerpColor(blue, amber, t / 0.5)
                       : lerpColor(amber, red, (t - 0.5) / 0.5);
    }

    private int lerpColor(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Kalan süre: anlık hızdan değil, rotanın kendi tahmininden oranlanıyor. */
    private double etaSeconds(double remain) {
        double total = route.geometryLength();
        return total > 0 ? route.duration * (remain / total) : 0;
    }

    /** Varış saati — "ne kadar kaldı"dan çok "saat kaçta varırım" sorusunun cevabı. */
    private String clockAfter(double secs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.SECOND, (int) Math.round(secs));
        return String.format(Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private String humanDuration(double secs) {
        int mins = (int) Math.round(secs / 60.0);
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

    private int iconFor(NavRoute.Step s) {
        if (s == null) return R.drawable.ic_straight;
        if ("arrive".equals(s.type)) return R.drawable.ic_arrive;
        if ("merge".equals(s.type)) return R.drawable.ic_merge;
        String mod = s.modifier == null ? "" : s.modifier;
        if (mod.contains("sharp left")) return R.drawable.ic_sharp_left;
        if (mod.contains("sharp right")) return R.drawable.ic_sharp_right;
        if (mod.contains("slight left")) return R.drawable.ic_slight_left;
        if (mod.contains("slight right")) return R.drawable.ic_slight_right;
        if (mod.contains("left")) return R.drawable.ic_left;
        if (mod.contains("right")) return R.drawable.ic_right;
        return R.drawable.ic_straight;
    }

    // ---- çevrimdışı harita ------------------------------------------------

    /**
     * Marmara/Trakya koridorunu (İstanbul → Edirne → Enez) çevrimdışı indirir.
     *
     * MapLibre'nin OfflineManager'ı stil + sınır + zoom aralığı için gereken bütün
     * karoları yerel veritabanına çekiyor; sonrasında aynı stil internetsiz açılıyor.
     * z13'te tabela/yol adları okunuyor, daha yükseği yolculuk için gereksiz yer kaplar.
     */
    private void downloadOffline() {
        if (offlineBusy) { toast("indirme sürüyor"); return; }
        offlineBusy = true;
        btnOffline.setText("0%");

        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(new LatLng(41.70, 29.65))   // kuzeydoğu: İstanbul'un doğusu
                .include(new LatLng(40.30, 25.80))   // güneybatı: Enez / Saros
                .build();

        OfflineTilePyramidRegionDefinition def = new OfflineTilePyramidRegionDefinition(
                dark ? STYLE_DARK : STYLE_LIGHT, bounds, 5, 13,
                getResources().getDisplayMetrics().density);

        OfflineManager om = OfflineManager.getInstance(this);
        om.setOfflineMapboxTileCountLimit(200000);
        om.createOfflineRegion(def, "marmara".getBytes(),
                new OfflineManager.CreateOfflineRegionCallback() {
            @Override public void onCreate(OfflineRegion region) {
                region.setObserver(new OfflineRegion.OfflineRegionObserver() {
                    @Override public void onStatusChanged(OfflineRegionStatus status) {
                        long req = status.getRequiredResourceCount();
                        long done = status.getCompletedResourceCount();
                        int pct = req > 0 ? (int) (100 * done / req) : 0;
                        if (status.isComplete()) {
                            offlineBusy = false;
                            btnOffline.setText("✓");
                            subtitle.setText(String.format(Locale.US,
                                    "Marmara çevrimdışı hazır · %.0f MB",
                                    status.getCompletedResourceSize() / 1048576.0));
                        } else {
                            btnOffline.setText(pct + "%");
                        }
                    }
                    @Override public void onError(OfflineRegionError error) {
                        offlineBusy = false;
                        btnOffline.setText("indir");
                        subtitle.setText("indirme hatası: " + error.getMessage());
                    }
                    @Override public void mapboxTileCountLimitExceeded(long limit) {
                        offlineBusy = false;
                        btnOffline.setText("indir");
                        subtitle.setText("karo sınırı aşıldı (" + limit + ")");
                    }
                });
                region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                subtitle.setText("Marmara indiriliyor (İstanbul → Enez)");
            }
            @Override public void onError(String error) {
                offlineBusy = false;
                btnOffline.setText("indir");
                subtitle.setText("bölge açılamadı: " + error);
            }
        });
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
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
