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

    double getMultiplier() { return multiplier; }

    double getDistance() { return distance; }

    void reset() {
        distance = 0;
        speed = 0;
    }

    private void step(double dt) {
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
