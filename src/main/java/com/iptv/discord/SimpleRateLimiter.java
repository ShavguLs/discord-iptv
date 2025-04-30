package com.iptv.discord;

public class SimpleRateLimiter {
    private final long minTimeBetweenRequests;
    private long lastRequestTime = 0;
    private final Object lock = new Object();

    public SimpleRateLimiter(double requestsPerSecond) {
        this.minTimeBetweenRequests = (long) (1000.0 / requestsPerSecond);
    }

    public long acquire() {
        synchronized (lock) {
            long currentTime = System.currentTimeMillis();
            long waitTime = Math.max(0, lastRequestTime + minTimeBetweenRequests - currentTime);

            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            lastRequestTime = System.currentTimeMillis();
            return waitTime;
        }
    }

    public boolean tryAcquire() {
        synchronized (lock) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRequestTime >= minTimeBetweenRequests) {
                lastRequestTime = currentTime;
                return true;
            }
            return false;
        }
    }
}