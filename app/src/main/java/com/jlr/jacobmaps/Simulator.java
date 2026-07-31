package com.jlr.jacobmaps;

import android.view.Choreographer;

/**
 * Rota üzerinde sanal araç sürer.
 *
 * Kareleri {@link Choreographer} ile alıyoruz, yani ekran tazelemesine kilitli — konumu
 * saniyede bir güncelleyip aradaki kareleri uydurmak yerine her karede gerçek konumu
 * hesaplıyoruz. Akıcılığın asıl sebebi bu.
 *
 * Hız sabit değil: manevraya yaklaşınca yavaşlıyor, düzlükte açılıyor. İvme sınırlı
 * olduğu için kamera ani sıçramıyor.
 */
class Simulator {

    interface Listener {
        /** @param distance rota başından kat edilen metre, @param speed m/s */
        void onTick(double distance, double speed, double dt);
        void onFinished();
    }

    private static final double CRUISE = 90 / 3.6;     // düzlükte hedef hız (m/s)
    private static final double TURN_SPEED = 35 / 3.6; // manevra öncesi hız
    private static final double SLOW_ZONE = 220;       // manevraya kaç m kala yavaşla
    private static final double ACCEL = 3.0;           // m/s² — hızlanma/yavaşlama sınırı

    private final NavRoute route;
    private final Listener listener;

    private double distance;
    private double speed;
    private double multiplier = 1.0;

    /** Canlı kipte hız/konum GPS'ten geliyor, iç hız modeli devre dışı. */
    private boolean live = false;
    private double fixDistance = -1;   // son GPS düzeltmesinin rota üzerindeki yeri
    private double fixSpeed = 0;       // son GPS hızı (m/s)
    private double sinceFix = 0;       // son düzeltmeden beri geçen süre
    private boolean running;
    private long lastFrameNs;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long nowNs) {
            if (!running) return;

            double dt = lastFrameNs == 0 ? 0 : (nowNs - lastFrameNs) / 1_000_000_000.0;
            lastFrameNs = nowNs;
            // Uygulama arka plandan dönerse dev bir dt gelip aracı ışınlamasın.
            if (dt > 0.25) dt = 0.25;

            step(dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    Simulator(NavRoute route, Listener listener) {
        this.route = route;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    boolean isRunning() { return running; }

    void setMultiplier(double m) { multiplier = m; }

    void setLive(boolean l) { live = l; if (l) multiplier = 1.0; }

    boolean isLive() { return live; }

    /** Yeni GPS düzeltmesi: hedefi güncelle, sıçratma. */
    void onFix(double snappedDistance, double speedMps) {
        fixDistance = snappedDistance;
        fixSpeed = Math.max(0, speedMps);
        sinceFix = 0;
        if (distance <= 0) distance = snappedDistance;   // ilk düzeltmede yerine otur
    }

    double getMultiplier() { return multiplier; }

    double getDistance() { return distance; }

    void reset() {
        distance = 0;
        speed = 0;
    }

    /**
     * Canlı kip: iki GPS düzeltmesi arasında ölü hesapla ilerliyor, düzeltme gelince
     * oraya sıçramak yerine sönümlemeyle yaklaşıyor. Ekranda takılma/geri tepme olmuyor.
     */
    private void stepLive(double dt) {
        if (fixDistance < 0) { listener.onTick(distance, speed, dt); return; }
        sinceFix += dt;

        // GPS hızına yumuşak geçiş
        speed += (fixSpeed - speed) * Math.min(1, 2.5 * dt);
        distance += speed * dt;

        // Düzeltmenin şu ana kadar taşınmış hâli; ona doğru yavaşça çek
        double predicted = fixDistance + fixSpeed * sinceFix;
        distance += (predicted - distance) * Math.min(1, 1.2 * dt);

        double total = route.geometryLength();
        if (distance > total) distance = total;
        listener.onTick(distance, speed, dt);
    }

    private void step(double dt) {
        if (live) { stepLive(dt); return; }
        double toManeuver = route.distanceToNextStep(distance);
        double target = toManeuver < SLOW_ZONE
                ? TURN_SPEED + (CRUISE - TURN_SPEED) * (toManeuver / SLOW_ZONE)
                : CRUISE;

        // Hızı ivme sınırıyla hedefe yaklaştır.
        double maxDelta = ACCEL * dt * multiplier;
        double diff = target - speed;
        speed += Math.max(-maxDelta, Math.min(maxDelta, diff));
        if (speed < 0) speed = 0;

        distance += speed * dt * multiplier;

        double total = route.geometryLength();
        if (distance >= total) {
            distance = total;
            listener.onTick(distance, 0, dt);
            stop();
            listener.onFinished();
            return;
        }
        listener.onTick(distance, speed, dt);
    }
}
