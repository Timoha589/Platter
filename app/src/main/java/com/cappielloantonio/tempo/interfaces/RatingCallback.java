package com.cappielloantonio.tempo.interfaces;

import androidx.annotation.Keep;

@Keep
public interface RatingCallback {
    default void onError() {}
    default void onSuccess() {}
}
