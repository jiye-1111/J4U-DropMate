package com.jiye.dropmate.nav;

import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;

/** FusedLocationListener 구현(Kotlin 에서 override 가 인식되지 않아 Java 로 작성). 콜백을 Kotlin 람다로 넘긴다. */
public final class PleosLocationListener implements FusedLocationListener {
    public interface Sink {
        void fix(double lat, double lon, float heading, long timeMillis);
        void error(String message);
    }
    private final Sink sink;
    public PleosLocationListener(Sink sink) { this.sink = sink; }
    @Override public void onChanged(LocationData d) { sink.fix(d.getLatitude(), d.getLongitude(), d.getHeading(), d.getTimestampMillis()); }
    @Override public void onError(Exception e) { sink.error(e.getClass().getSimpleName()); }
}
