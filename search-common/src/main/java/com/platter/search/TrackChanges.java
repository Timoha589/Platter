package com.platter.search;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.util.List;

/** Server-side track journal response. */
public final class TrackChanges {
    @SerializedName("cursor") public String cursor;
    @SerializedName("reset") public boolean reset;
    @SerializedName("resetReason") public String resetReason;
    @SerializedName("hasMore") public boolean hasMore;
    @SerializedName("changes") public List<Change> changes;

    public void validate(String previous) throws IOException {
        if (cursor == null || cursor.isEmpty() || changes == null || changes.size() > 100
                || (reset && (hasMore || !changes.isEmpty()))
                || (cursor.equals(previous) && (hasMore || !changes.isEmpty()))) {
            throw new IOException("Invalid track journal page");
        }
    }

    /** Older servers may omit the reason for a reset. Treat an unknown reset as a possible access change. */
    public boolean discardsBaseline() {
        return reset && !"history".equals(resetReason);
    }

    public String unchangedCheckpoint(String previous) throws IOException {
        validate(previous);
        if (reset || hasMore || !changes.isEmpty()) throw new IOException("Library changed during the initial sync");
        return cursor;
    }

    public static final class Change {
        @SerializedName("id") public String id;
        @SerializedName("deleted") public boolean deleted;
        @SerializedName("song") public JsonObject song;
    }
}
