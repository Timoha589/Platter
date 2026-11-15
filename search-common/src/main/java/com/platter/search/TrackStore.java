package com.platter.search;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A snapshot plus a small, atomically persisted overlay of journal changes. */
public final class TrackStore<T> {
    public static final long JOURNAL_POLL_MS = 60_000;
    public static final long DISCOVERY_POLL_MS = 5 * JOURNAL_POLL_MS;
    private static final int COMPACT_AFTER = 3000;
    private static final Gson JSON = new Gson();

    private final File file;
    private final File changesFile;
    private final TrackSnapshot<T> snapshot;
    private final Replace replace;
    private volatile TrackSnapshot<T>.Index base;
    private volatile boolean discarded;
    private Overlay overlay;

    @FunctionalInterface
    public interface Replace {
        void move(File source, File destination) throws IOException;
    }

    public TrackStore(File file, TrackSnapshot<T> snapshot, Replace replace) {
        this.file = file;
        this.changesFile = new File(file.getPath() + ".changes");
        this.snapshot = snapshot;
        this.replace = replace;
    }

    public boolean ready() { return base != null; }
    public boolean exists() { return !discarded && file.isFile(); }
    public long modifiedAt() { return file.lastModified(); }
    public synchronized String cursor() { return overlay == null ? null : overlay.cursor; }

    public void load() throws IOException {
        if (discarded) throw new IOException("Track snapshot no longer authorized");
        TrackSnapshot<T>.Index loaded = snapshot.read(file);
        Overlay applied = null;
        if (changesFile.isFile()) {
            try {
                applied = readOverlay(loaded.generation());
            } catch (IOException | RuntimeException invalid) {
                // A new base can survive an interrupted reset. Its next sync starts at a new checkpoint.
            }
        }
        synchronized (this) {
            if (discarded) throw new IOException("Track snapshot no longer authorized");
            base = loaded;
            overlay = applied;
        }
    }

    public synchronized void invalidate() {
        base = null;
        overlay = null;
    }

    /** For access changes the cached metadata must also disappear across restarts. */
    public synchronized void discardBaseline() throws IOException {
        discarded = true;
        invalidate();
        if (!file.delete() && file.exists()) throw new IOException("Could not discard the old track snapshot");
        if (!changesFile.delete() && changesFile.exists()) throw new IOException("Could not discard the old track changes");
    }

    public synchronized List<TrackIndex.Match<T>> find(String query, TrackIndex.Distance distance) throws IOException {
        if (base == null) return Collections.emptyList();
        if (overlay == null) return base.find(file, query, distance);
        List<TrackIndex.Match<T>> matches = new ArrayList<>(overlay.index.find(query, distance));
        matches.addAll(base.find(file, query, distance, overlay::inBase));
        return matches;
    }

    public File temporary() throws IOException {
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create track cache directory");
        }
        return File.createTempFile("tracks-", ".tmp", directory);
    }

    public TrackSnapshot<T>.Writer writer(File temp) throws IOException { return snapshot.writer(temp); }

    public void commitBaseline(File temp, TrackSnapshot<T>.Index fresh, String cursor) throws IOException {
        Overlay next = cursor == null ? null : new Overlay(cursor, Collections.emptyMap(), Collections.emptySet());
        File staged = next == null ? null : writeOverlay(fresh.generation(), next);
        try {
            synchronized (this) {
                replace.move(temp, file);
                base = fresh;
                discarded = false;
                overlay = null;
                if (staged != null) {
                    replace.move(staged, changesFile);
                    overlay = next;
                } else changesFile.delete();
            }
        } finally {
            if (staged != null) staged.delete();
        }
    }

    /** Persists the page and its cursor together. A failed page can be requested again. */
    public boolean apply(TrackChanges page) throws IOException {
        TrackSnapshot<T>.Index current;
        Overlay previous;
        synchronized (this) {
            current = base;
            previous = overlay;
        }
        if (current == null || previous == null || page.reset) throw new IOException("Track journal needs a baseline");
        page.validate(previous.cursor);
        if (previous.cursor.equals(page.cursor)) return false;
        Map<String, T> songs = new LinkedHashMap<>(previous.songs);
        Set<String> deleted = new HashSet<>(previous.deleted);
        for (TrackChanges.Change change : page.changes) {
            if (change == null || change.id == null || change.id.isEmpty() || change.deleted == (change.song != null)) {
                throw new IOException("Invalid track journal change");
            }
            if (change.deleted) {
                songs.remove(change.id);
                deleted.add(change.id);
            } else {
                T song = JSON.fromJson(change.song, snapshot.trackType());
                if (song == null || !change.id.equals(snapshot.idOf(song))) throw new IOException("Mismatched track journal ID");
                deleted.remove(change.id);
                songs.put(change.id, song);
            }
        }
        Overlay next = new Overlay(page.cursor, songs, deleted);
        if (songs.size() + deleted.size() >= COMPACT_AFTER) compact(current, next);
        else {
            File temp = writeOverlay(current.generation(), next);
            try {
                synchronized (this) {
                    if (base != current) throw new IOException("Track snapshot was replaced");
                    replace.move(temp, changesFile);
                    overlay = next;
                }
            } finally {
                temp.delete();
            }
        }
        return !page.changes.isEmpty();
    }

    private void compact(TrackSnapshot<T>.Index current, Overlay next) throws IOException {
        File temp = temporary();
        try {
            TrackSnapshot<T>.Index fresh;
            try (TrackSnapshot<T>.Writer writer = writer(temp)) {
                current.copyTo(file, writer, next::inBase);
                writer.add(new ArrayList<>(next.songs.values()));
                fresh = writer.finish();
            }
            commitBaseline(temp, fresh, next.cursor);
        } finally {
            temp.delete();
        }
    }

    private Overlay readOverlay(long generation) throws IOException {
        try (InputStreamReader input = new InputStreamReader(new GZIPInputStream(new FileInputStream(changesFile)), StandardCharsets.UTF_8)) {
            JsonObject document = JsonParser.parseReader(input).getAsJsonObject();
            if (generation != document.get("generation").getAsLong()) return null;
            Map<String, T> songs = new LinkedHashMap<>();
            Set<String> deleted = new HashSet<>();
            for (JsonElement record : document.getAsJsonArray("songs")) {
                T song = JSON.fromJson(record, snapshot.trackType());
                songs.put(snapshot.idOf(song), song);
            }
            for (JsonElement record : document.getAsJsonArray("deleted")) deleted.add(record.getAsString());
            return new Overlay(document.get("cursor").getAsString(), songs, deleted);
        }
    }

    private File writeOverlay(long generation, Overlay next) throws IOException {
        File temp = temporary();
        try {
            JsonObject document = new JsonObject();
            document.addProperty("generation", generation);
            document.addProperty("cursor", next.cursor);
            JsonArray songs = new JsonArray();
            for (T song : next.songs.values()) songs.add(JSON.toJsonTree(song, snapshot.trackType()));
            document.add("songs", songs);
            document.add("deleted", JSON.toJsonTree(next.deleted));
            try (FileOutputStream raw = new FileOutputStream(temp);
                 GZIPOutputStream gzip = new GZIPOutputStream(raw);
                 OutputStreamWriter output = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                JSON.toJson(document, output);
                output.flush();
                gzip.finish();
                raw.getFD().sync();
            }
            return temp;
        } catch (IOException | RuntimeException failed) {
            temp.delete();
            throw failed;
        }
    }

    private final class Overlay {
        final String cursor;
        final Map<String, T> songs;
        final Set<String> deleted;
        final TrackIndex<T> index;

        Overlay(String cursor, Map<String, T> songs, Set<String> deleted) {
            this.cursor = cursor;
            this.songs = songs;
            this.deleted = deleted;
            this.index = snapshot.indexOf(songs.values());
        }

        boolean inBase(String id) { return !songs.containsKey(id) && !deleted.contains(id); }
    }
}
