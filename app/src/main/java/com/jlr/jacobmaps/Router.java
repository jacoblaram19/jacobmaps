package com.jlr.jacobmaps;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.geometry.LatLng;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OSRM üzerinden rota çeker.
 *
 * Rota motorunu kendimiz yazmıyoruz — OSRM zaten OpenStreetMap verisiyle çalışıyor ve
 * Türkiye yollarını biliyor. İleride çevrimdışı istendiğinde bu sınıfın arkasına
 * GraphHopper/BRouter konabilir; çağıran taraf değişmez.
 */
class Router {

    interface Callback {
        void onRoute(NavRoute route);
        void onError(String message);
    }

    private static final String BASE = "https://router.project-osrm.org/route/v1/driving/";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    void route(final LatLng from, final LatLng to, final Callback cb) {
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                String url = String.format(Locale.US,
                        "%s%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
                        BASE, from.getLongitude(), from.getLatitude(),
                        to.getLongitude(), to.getLatitude());

                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(25000);
                c.setRequestProperty("User-Agent", "JacobMaps/1.0");

                int code = c.getResponseCode();
                if (code != 200) {
                    fail(cb, "rota servisi " + code + " döndü");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }

                final NavRoute route = parse(sb.toString());
                if (route == null) fail(cb, "rota bulunamadı");
                else ui.post(() -> cb.onRoute(route));

            } catch (Exception e) {
                fail(cb, "bağlantı yok veya rota alınamadı");
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void fail(Callback cb, String msg) {
        ui.post(() -> cb.onError(msg));
    }

    private NavRoute parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!"Ok".equals(root.optString("code"))) return null;
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) return null;

        JSONObject r = routes.getJSONObject(0);
        JSONArray coords = r.getJSONObject("geometry").getJSONArray("coordinates");
        List<LatLng> pts = new ArrayList<>(coords.length());
        for (int i = 0; i < coords.length(); i++) {
            JSONArray c = coords.getJSONArray(i);
            // GeoJSON sırası [boylam, enlem]
            pts.add(new LatLng(c.getDouble(1), c.getDouble(0)));
        }

        List<NavRoute.Step> steps = new ArrayList<>();
        JSONArray legs = r.optJSONArray("legs");
        if (legs != null) {
            for (int l = 0; l < legs.length(); l++) {
                JSONArray ss = legs.getJSONObject(l).optJSONArray("steps");
                if (ss == null) continue;
                for (int i = 0; i < ss.length(); i++) {
                    JSONObject s = ss.getJSONObject(i);
                    JSONObject m = s.optJSONObject("maneuver");
                    steps.add(new NavRoute.Step(
                            m == null ? "" : m.optString("type", ""),
                            m == null ? "" : m.optString("modifier", ""),
                            s.optString("name", ""),
                            s.optDouble("distance", 0)));
                }
            }
        }

        return new NavRoute(pts, steps, r.optDouble("distance", 0), r.optDouble("duration", 0));
    }
}
