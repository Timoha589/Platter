package com.cappielloantonio.tempo.subsonic.utils;

import com.cappielloantonio.tempo.util.NetworkUtil;

import okhttp3.Interceptor;
import okhttp3.Request;

public class CacheUtil {
    private int maxAge; // 60 seconds
    private int maxStale; // 60 * 60 * 24 * 30 = 30 days (60 seconds * 60 minutes * 24 hours * 30 days)

    public CacheUtil(int maxAge, int maxStale) {
        this.maxAge = maxAge;
        this.maxStale = maxStale;
    }

    public Interceptor onlineInterceptor = chain -> {
        okhttp3.Response response = chain.proceed(chain.request());
        return response.newBuilder()
                .header("Cache-Control", "public, max-age=" + maxAge)
                .removeHeader("Pragma")
                .build();
    };

    public Interceptor offlineInterceptor = chain -> {
        Request request = chain.request();
        if (!isConnected()) {
            request = request.newBuilder()
                    .header("Cache-Control", "public, only-if-cached, max-stale=" + maxStale)
                    .removeHeader("Pragma")
                    .build();
        }
        return chain.proceed(request);
    };

    /*
     * Deciding this here as well, with its own stricter rule, meant the cache
     * layer could force every request to be answered from cache - failing the
     * ones that were not - while the rest of the app considered itself online.
     * One answer, in NetworkUtil.
     */
    private boolean isConnected() {
        return !NetworkUtil.isOffline();
    }
}
