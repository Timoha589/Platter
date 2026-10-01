package com.cappielloantonio.tempo.subsonic.api.bookmarks;

import android.util.Log;

import com.cappielloantonio.tempo.subsonic.RetrofitClient;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;

import java.util.List;

import retrofit2.Call;

public class BookmarksClient {
    private static final String TAG = "BookmarksClient";

    private final Subsonic subsonic;
    private final BookmarksService bookmarksService;

    public BookmarksClient(Subsonic subsonic) {
        this.subsonic = subsonic;
        this.bookmarksService = new RetrofitClient(subsonic).getRetrofit().create(BookmarksService.class);
    }

    public Call<ApiResponse> getPlayQueue() {
        Log.d(TAG, "getPlayQueue()");

        if (OpenSubsonicExtensionsUtil.isIndexBasedQueueExtensionAvailable()) {
            return bookmarksService.getPlayQueueByIndex(subsonic.getParams());
        }

        return bookmarksService.getPlayQueue(subsonic.getParams());
    }

    /**
     * Saves the queue on the server.
     *
     * @param currentIndex position of the playing track, or -1 when it is not
     *                     known; the index-based endpoint needs it whenever the
     *                     queue is not empty
     * @param current      id of the playing track, for servers without the
     *                     indexBasedQueue extension
     * @param position     playback position within that track, in milliseconds
     */
    public Call<ApiResponse> savePlayQueue(List<String> ids, int currentIndex, String current, long position) {
        Log.d(TAG, "savePlayQueue()");

        boolean byIndex = OpenSubsonicExtensionsUtil.isIndexBasedQueueExtensionAvailable();
        boolean post = OpenSubsonicExtensionsUtil.isFormPostExtensionAvailable();

        if (byIndex) {
            // The spec requires currentIndex unless the queue is being cleared.
            Integer index = (ids == null || ids.isEmpty() || currentIndex < 0) ? null : currentIndex;

            return post
                    ? bookmarksService.savePlayQueueByIndexPost(subsonic.getParams(), ids, index, position)
                    : bookmarksService.savePlayQueueByIndex(subsonic.getParams(), ids, index, position);
        }

        return post
                ? bookmarksService.savePlayQueuePost(subsonic.getParams(), ids, current, position)
                : bookmarksService.savePlayQueue(subsonic.getParams(), ids, current, position);
    }
}
