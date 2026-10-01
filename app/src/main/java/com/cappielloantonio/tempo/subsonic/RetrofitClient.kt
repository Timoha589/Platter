package com.cappielloantonio.tempo.subsonic

import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.subsonic.utils.CacheUtil
import com.google.gson.GsonBuilder
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RetrofitClient(subsonic: Subsonic) {
    var retrofit: Retrofit

    init {
        retrofit = Retrofit.Builder()
            .baseUrl(subsonic.url)
            // One converter: Retrofit takes the first factory that answers, and Gson's always does,
            // so a second one listed after it was never consulted.
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss").create()))
            .client(sharedClient)
            .build()
    }

    companion object {
        private const val MAX_AGE = 60
        private const val MAX_STALE = 60 * 60 * 24 * 30 // 30 days
        private const val CACHE_SIZE = 10L * 1024 * 1024

        /*
         * One OkHttpClient for the whole app.
         *
         * A RetrofitClient is built per API client - thirteen of them, one for
         * browsing, one for playlists, one for album lists and so on - and each
         * used to build an OkHttpClient of its own. That meant thirteen
         * connection pools, so a request through BrowsingClient could not reuse
         * the TLS connection AlbumSongListClient had just opened to the same
         * host, and thirteen dispatcher thread pools behind them.
         *
         * Worse, each built its own Cache over the same directory. OkHttp
         * requires a cache directory be owned by one Cache instance at a time:
         * they each keep an open journal and an LRU of their own, and two of
         * them writing to one directory corrupt each other's bookkeeping. The
         * cache was effectively unusable and quietly throwing.
         *
         * Built lazily because the cache directory comes from App, which is not
         * there yet while the class is being loaded.
         */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.MINUTES)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(httpLoggingInterceptor)
                .addInterceptor(CacheUtil(MAX_AGE, MAX_STALE).offlineInterceptor)
                .cache(Cache(App.getContext().cacheDir, CACHE_SIZE))
                .build()
        }

        private val httpLoggingInterceptor: HttpLoggingInterceptor
            get() {
                val loggingInterceptor = HttpLoggingInterceptor()

                /*
                 * Subsonic authenticates in the query string, so every logged
                 * line carried u=, and t=/s= or - in low security mode - the
                 * cleartext p=. At Level.BODY that went to logcat in release
                 * builds too, where any app holding READ_LOGS on older devices,
                 * or a bug report, would pick the credentials straight up.
                 * Logging is now debug-only.
                 */
                loggingInterceptor.level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }

                return loggingInterceptor
            }
    }
}
