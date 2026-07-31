package com.jlr.jacobmaps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import androidx.annotation.NonNull;

import org.maplibre.android.geometry.LatLng;

/**
 * Gerçek konum kaynağı.
 *
 * GPS saniyede bir civarı düzeltme veriyor; ekranı doğrudan bu düzeltmelerle sürmek
 * sıçramalı görünür. Bu yüzden burada sadece ham düzeltmeyi yayınlıyoruz; araç
 * konumunu kare kare ilerletip düzeltmeye yumuşakça yaklaştırma işi {@link Simulator}
 * tarafında yapılıyor.
 */
class Locator {

    interface Listener {
        void onFix(LatLng point, double bearingDeg, double speedMps, float accuracy);
    }

    static final int REQ_PERMISSION = 42;

    private final Activity activity;
    private final Listener listener;
    private LocationManager lm;
    private boolean running;

    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location l) {
            listener.onFix(new LatLng(l.getLatitude(), l.getLongitude()),
                    l.hasBearing() ? l.getBearing() : -1,
                    l.hasSpeed() ? l.getSpeed() : -1,
                    l.hasAccuracy() ? l.getAccuracy() : 999f);
        }
        @Override public void onProviderEnabled(@NonNull String p) { }
        @Override public void onProviderDisabled(@NonNull String p) { }
    };

    Locator(Activity a, Listener l) {
        this.activity = a;
        this.listener = l;
    }

    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    void requestPermission() {
        activity.requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMISSION);
    }

    /** @return izin yoksa false */
    boolean start() {
        if (running) return true;
        if (!granted(activity)) return false;
        lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;

        try {
            // İki sağlayıcıyı da dinliyoruz: GPS hassas ama soğuk başlangıcı yavaş,
            // ağ tabanlı olan ilk konumu hemen veriyor.
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener);
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, gpsListener);
            }
        } catch (SecurityException e) {
            return false;
        }
        running = true;
        return true;
    }

    void stop() {
        if (!running || lm == null) return;
        try { lm.removeUpdates(gpsListener); } catch (SecurityException ignored) { }
        running = false;
    }

    boolean isRunning() { return running; }

    /** Son bilinen konum — rota kurarken başlangıç noktası için. */
    LatLng lastKnown() {
        if (!granted(activity)) return null;
        LocationManager m = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (m == null) return null;
        try {
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER}) {
                Location l = m.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best == null ? null : new LatLng(best.getLatitude(), best.getLongitude());
        } catch (SecurityException e) {
            return null;
        }
    }
}
