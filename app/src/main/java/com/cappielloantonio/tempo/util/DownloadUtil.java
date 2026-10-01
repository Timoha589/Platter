package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;

import com.cappielloantonio.tempo.service.DownloaderManager;

import java.io.File;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.concurrent.Executors;

@UnstableApi
public final class DownloadUtil {

    public static final String DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel";

    private static final String STREAMING_CACHE_CONTENT_DIRECTORY = "streaming_cache";
    private static final String DOWNLOAD_CONTENT_DIRECTORY = "downloads";

    private static DataSource.Factory dataSourceFactory;
    private static DataSource.Factory httpDataSourceFactory;
    private static DatabaseProvider databaseProvider;
    private static File streamingCacheDirectory;
    private static File downloadDirectory;
    private static Cache downloadCache;
    private static SimpleCache streamingCache;
    private static DownloadManager downloadManager;
    private static DownloadManager smartDownloadManager;
    private static DownloaderManager downloaderManager;
    private static DownloadNotificationHelper downloadNotificationHelper;

    public static boolean useExtensionRenderers() {
        return true;
    }

    public static RenderersFactory buildRenderersFactory(Context context, boolean preferExtensionRenderer) {
        @DefaultRenderersFactory.ExtensionRendererMode int extensionRendererMode =
                useExtensionRenderers()
                        ? (preferExtensionRenderer ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;

        return new DefaultRenderersFactory(context.getApplicationContext()).setExtensionRendererMode(extensionRendererMode);
    }

    public static synchronized DataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) {
            CookieManager cookieManager = new CookieManager();
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            CookieHandler.setDefault(cookieManager);
            /*
             * Every track is fetched from the server address in use at the
             * moment it is opened, not the one it was queued on - so a queue
             * put together at home keeps playing after leaving it, and moves
             * back when the phone is home again. Below the caches on purpose:
             * they keep keying a track by the URL it was queued with, and a
             * track cached at home is not fetched again away.
             */
            httpDataSourceFactory = new ResolvingDataSource.Factory(
                    new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
                    dataSpec -> {
                        Uri uri = ServerAddress.rewrite(dataSpec.uri);
                        return uri.equals(dataSpec.uri) ? dataSpec : dataSpec.withUri(uri);
                    });
        }

        return httpDataSourceFactory;
    }

    public static synchronized DataSource.Factory getDataSourceFactory(Context context) {
        if (dataSourceFactory == null) {
            context = context.getApplicationContext();

            DefaultDataSource.Factory upstreamFactory = new DefaultDataSource.Factory(context, getHttpDataSourceFactory());

            if (Preferences.getStreamingCacheSize() > 0) {
                CacheDataSource.Factory streamCacheFactory = new CacheDataSource.Factory()
                        .setCache(getStreamingCache(context))
                        .setUpstreamDataSourceFactory(upstreamFactory);

                ResolvingDataSource.Factory resolvingFactory = new ResolvingDataSource.Factory(
                        new StreamingCacheDataSource.Factory(streamCacheFactory),
                        dataSpec -> {
                            DataSpec.Builder builder = dataSpec.buildUpon();
                            builder.setFlags(dataSpec.flags & ~DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN);
                            return builder.build();
                        }
                );

                dataSourceFactory = buildReadOnlyCacheDataSource(resolvingFactory, getDownloadCache(context));
            } else {
                dataSourceFactory = buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache(context));
            }
        }

        return dataSourceFactory;
    }

    public static synchronized DownloadNotificationHelper getDownloadNotificationHelper(Context context) {
        if (downloadNotificationHelper == null) {
            downloadNotificationHelper = new DownloadNotificationHelper(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID);
        }

        return downloadNotificationHelper;
    }

    public static synchronized DownloadManager getDownloadManager(Context context) {
        ensureDownloadManagerInitialized(context);
        return downloadManager;
    }

    public static synchronized DownloadManager getSmartDownloadManager(Context context) {
        ensureDownloadManagerInitialized(context);
        return smartDownloadManager;
    }

    public static synchronized DownloaderManager getDownloadTracker(Context context) {
        ensureDownloadManagerInitialized(context);
        return downloaderManager;
    }

    private static synchronized Cache getDownloadCache(Context context) {
        if (downloadCache == null) {
            File downloadContentDirectory = new File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY);
            downloadCache = new SimpleCache(downloadContentDirectory, new NoOpCacheEvictor(), getDatabaseProvider(context));
        }

        return downloadCache;
    }

    private static synchronized SimpleCache getStreamingCache(Context context) {
        if (streamingCache == null) {
            File streamingCacheDirectory = new File(getStreamingCacheDirectory(context), STREAMING_CACHE_CONTENT_DIRECTORY);

            streamingCache = new SimpleCache(
                    streamingCacheDirectory,
                    new LeastRecentlyUsedCacheEvictor(Preferences.getStreamingCacheSize() * 1024 * 1024),
                    getDatabaseProvider(context)
            );
        }

        return streamingCache;
    }

    private static synchronized void ensureDownloadManagerInitialized(Context context) {
        if (downloadManager == null) {
            downloadManager = new DownloadManager(
                    context,
                    getDatabaseProvider(context),
                    getDownloadCache(context),
                    getHttpDataSourceFactory(),
                    Executors.newFixedThreadPool(6)
            );

            /*
             * Smart download keeps its own list, in its own table, over the
             * same cache. Every download a DownloadManager holds is one its
             * DownloadService puts a progress notification up for, so the
             * tracks saved while listening could not share a list with the
             * user's own downloads without announcing themselves.
             */
            smartDownloadManager = new DownloadManager(
                    context,
                    new DefaultDownloadIndex(getDatabaseProvider(context), "Smart"),
                    new DefaultDownloaderFactory(
                            new CacheDataSource.Factory()
                                    .setCache(getDownloadCache(context))
                                    .setUpstreamDataSourceFactory(getHttpDataSourceFactory()),
                            Executors.newFixedThreadPool(2)
                    )
            );

            downloaderManager = new DownloaderManager(context, getHttpDataSourceFactory(), downloadManager, smartDownloadManager);
        }
    }

    private static synchronized DatabaseProvider getDatabaseProvider(Context context) {
        if (databaseProvider == null) {
            databaseProvider = new StandaloneDatabaseProvider(context);
        }

        return databaseProvider;
    }

    private static synchronized File getStreamingCacheDirectory(Context context) {
        if (streamingCacheDirectory == null) {
            if (Preferences.getStreamingCacheStoragePreference() == 0) {
                streamingCacheDirectory = context.getExternalFilesDirs(null)[0];
                if (streamingCacheDirectory == null) {
                    streamingCacheDirectory = context.getFilesDir();
                }
            } else {
                try {
                    streamingCacheDirectory = context.getExternalFilesDirs(null)[1];
                } catch (Exception exception) {
                    streamingCacheDirectory = context.getExternalFilesDirs(null)[0];
                    Preferences.setStreamingCacheStoragePreference(0);
                }

            }
        }

        return streamingCacheDirectory;
    }

    private static synchronized File getDownloadDirectory(Context context) {
        if (downloadDirectory == null) {
            if (Preferences.getDownloadStoragePreference() == 0) {
                downloadDirectory = context.getExternalFilesDirs(null)[0];
                if (downloadDirectory == null) {
                    downloadDirectory = context.getFilesDir();
                }
            } else {
                try {
                    downloadDirectory = context.getExternalFilesDirs(null)[1];
                } catch (Exception exception) {
                    downloadDirectory = context.getExternalFilesDirs(null)[0];
                    Preferences.setDownloadStoragePreference(0);
                }

            }
        }

        return downloadDirectory;
    }

    private static CacheDataSource.Factory buildReadOnlyCacheDataSource(DataSource.Factory upstreamFactory, Cache cache) {
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static synchronized void eraseDownloadFolder(Context context) {
        File directory = getDownloadDirectory(context);

        ArrayList<File> files = listFiles(directory, new ArrayList<>());

        for (File file : files) {
            file.delete();
        }
    }

    private static synchronized ArrayList<File> listFiles(File directory, ArrayList<File> files) {
        if (directory.isDirectory()) {
            File[] list = directory.listFiles();

            if (list != null) {
                for (File file : list) {
                    if (file.isFile() && file.getName().toLowerCase().endsWith(".exo")) {
                        files.add(file);
                    } else if (file.isDirectory()) {
                        listFiles(file, files);
                    }
                }
            }
        }

        return files;
    }

    public static synchronized long getStreamingCacheSize(Context context) {
        return getStreamingCache(context).getCacheSpace();
    }
}
