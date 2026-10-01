package com.cappielloantonio.tempo.subsonic.utils;

import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;

import retrofit2.Response;

public final class ResponseUtil {
    private static final String STATUS_FAILED = "failed";

    private ResponseUtil() {
    }

    /**
     * The Subsonic payload of a response, or null when there is nothing usable
     * to read from it.
     * <p>
     * A Subsonic server reports its own errors inside an HTTP 200: a wrong
     * password, an endpoint the server does not implement, and an unknown id
     * all come back as
     * {@code {"subsonic-response":{"status":"failed","error":{...}}}}. Checking
     * {@link Response#isSuccessful()} alone therefore reads an authentication
     * failure as an empty library - which is how a mistyped password used to
     * present as a home screen with every section missing and no message. This
     * checks the envelope as well as the HTTP status.
     * <p>
     * It also absorbs a body that never parsed into a payload at all - an HTML
     * error page from a reverse proxy, an empty 200 - which reaches Java as a
     * null {@code subsonic-response}.
     */
    @Nullable
    public static SubsonicResponse body(@Nullable Response<ApiResponse> response) {
        if (response == null || !response.isSuccessful() || response.body() == null) return null;

        SubsonicResponse subsonicResponse = response.body().getSubsonicResponse();

        if (subsonicResponse == null) return null;
        if (STATUS_FAILED.equalsIgnoreCase(subsonicResponse.getStatus())) return null;
        if (subsonicResponse.getError() != null) return null;

        return subsonicResponse;
    }
}
