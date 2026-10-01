package com.cappielloantonio.tempo.interfaces;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.subsonic.models.Starred2;

@Keep
public interface StarredCallback {
    /**
     * @param starred the library's starred items, or null when the request
     *                failed. Null means "ask again", not "nothing is starred" -
     *                an empty library answers with an empty {@link Starred2}.
     */
    void onResult(@Nullable Starred2 starred);
}
