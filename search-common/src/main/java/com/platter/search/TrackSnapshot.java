package com.platter.search;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/** A compact index in memory with complete track records stored separately in the snapshot. */
public final class TrackSnapshot<T> {
    public static final int PAGE_SIZE = 500;
    public static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    public static final long RETRY_AFTER_MS = 60_000;

    private static final int MAGIC = 0x504C5403;
    private static final int HEADER_BYTES = 24;
    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private static final Gson JSON = new Gson();

    private final Class<T> type;
    private final Function<T, String> id;
    private final Function<T, String> title;
    private final Function<T, String> artistLine;
    private final Function<T, String> artist;
    private final Function<T, String> album;

    public TrackSnapshot(Class<T> type, Function<T, String> id, Function<T, String> title,
                         Function<T, String> artistLine, Function<T, String> artist, Function<T, String> album) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.artistLine = artistLine;
        this.artist = artist;
        this.album = album;
    }

    public static String filename(String server, String user) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((server + "\n" + user).getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("tracks-v3-");
            for (byte b : digest) {
                name.append(Character.forDigit((b >>> 4) & 15, 16));
                name.append(Character.forDigit(b & 15, 16));
            }
            return name.append(".bin").toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static int trackCount(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            return readCount(input);
        }
    }

    private static int readCount(RandomAccessFile input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Unknown track snapshot format");
        int count = input.readInt();
        if (input.length() < HEADER_BYTES || count < 0 || count > (input.length() - HEADER_BYTES) / 5) {
            throw new IOException("Invalid track count");
        }
        return count;
    }

    public Index read(File file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        long generation;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int count = readCount(input);
            generation = input.readLong();
            long metadataOffset = input.readLong();
            if (metadataOffset < HEADER_BYTES || metadataOffset >= input.length()) throw new IOException("Missing indexed metadata");
            input.seek(metadataOffset);
            try (DataInputStream metadata = new DataInputStream(new BufferedInputStream(new GZIPInputStream(inputStream(input))))) {
                for (int i = 0; i < count; i++) {
                    long offset = metadata.readLong();
                    if (offset < HEADER_BYTES || offset >= metadataOffset) throw new IOException("Invalid track offset");
                    entries.add(new Entry(readString(metadata), readString(metadata), name(names, readString(metadata)),
                            name(names, readString(metadata)), name(names, readString(metadata)), offset));
                }
                if (metadata.read() != -1) throw new IOException("Unexpected data after indexed metadata");
            }
        }
        return new Index(entries, generation);
    }

    private T readTrack(RandomAccessFile input) throws IOException {
        byte[] bytes = readRecord(input);
        try (InputStreamReader reader = new InputStreamReader(
                new InflaterInputStream(new ByteArrayInputStream(bytes)), StandardCharsets.UTF_8)) {
            T track = JSON.fromJson(reader, type);
            if (track == null) throw new IOException("Missing track record");
            return track;
        }
    }

    private static byte[] readRecord(RandomAccessFile input) throws IOException {
        int size = input.readInt();
        if (size <= 0 || size > MAX_RECORD_BYTES || size > input.length() - input.getFilePointer()) {
            throw new IOException("Invalid track record");
        }
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    String idOf(T song) { return id.apply(song); }
    Class<T> trackType() { return type; }
    TrackIndex<T> indexOf(Iterable<T> tracks) { return new TrackIndex<>(tracks, id, title, artistLine, artist, album); }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length == -1) return null;
        if (length < 0 || length > MAX_RECORD_BYTES) throw new IOException("Invalid metadata field");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Metadata field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    // The outer RandomAccessFile remains open while the metadata stream is closed.
    private static InputStream inputStream(RandomAccessFile file) {
        return new InputStream() {
            @Override public int read() throws IOException { return file.read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                return file.read(bytes, offset, length);
            }
        };
    }

    private static OutputStream outputStream(RandomAccessFile file) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { file.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                file.write(bytes, offset, length);
            }
        };
    }

    private Entry entry(T track, long offset, Map<String, String> names) {
        return new Entry(id.apply(track), title.apply(track), name(names, artistLine.apply(track)),
                name(names, artist.apply(track)), name(names, album.apply(track)), offset);
    }

    private static String name(Map<String, String> pool, String value) {
        return value == null ? null : pool.computeIfAbsent(value, key -> key);
    }

    public final class Index {
        private final TrackIndex<Entry> tracks;
        private final long generation;
        private final List<Entry> entries;

        private Index(List<Entry> entries, long generation) {
            this.generation = generation;
            this.entries = entries;
            tracks = new TrackIndex<>(entries, e -> e.id, e -> e.title, e -> e.artistLine, e -> e.artist, e -> e.album);
        }

        public List<TrackIndex.Match<T>> find(File file, String query, TrackIndex.Distance distance) throws IOException {
            return find(file, query, distance, id -> true);
        }

        long generation() { return generation; }

        List<TrackIndex.Match<T>> find(File file, String query, TrackIndex.Distance distance, Predicate<String> visible) throws IOException {
            List<TrackIndex.Match<Entry>> matches = tracks.find(query, distance, entry -> visible.test(entry.id));
            if (matches.isEmpty()) return Collections.emptyList();
            List<TrackIndex.Match<T>> results = new ArrayList<>(matches.size());
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                verify(input);
                for (TrackIndex.Match<Entry> match : matches) {
                    input.seek(match.song.offset);
                    results.add(new TrackIndex.Match<>(readTrack(input), match.edits, match.prefix));
                }
            }
            return results;
        }

        void copyTo(File file, Writer writer, Predicate<String> visible) throws IOException {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                verify(input);
                for (Entry entry : entries) {
                    if (!visible.test(entry.id)) continue;
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Track index compaction interrupted");
                    input.seek(entry.offset);
                    writer.copy(entry, readRecord(input));
                }
            }
        }

        private void verify(RandomAccessFile input) throws IOException {
            readCount(input);
            if (input.readLong() != generation) throw new IOException("Track snapshot was replaced");
        }
    }

    public Writer writer(File temporary) throws IOException {
        return new Writer(temporary);
    }

    public final class Writer implements Closeable {
        private final RandomAccessFile output;
        private final Deflater deflater;
        private final long generation = UUID.randomUUID().getLeastSignificantBits();
        private final List<Entry> entries = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();
        private final Map<String, String> names = new HashMap<>();
        private int offset;

        private Writer(File temporary) throws IOException {
            output = new RandomAccessFile(temporary, "rw");
            deflater = new Deflater(Deflater.BEST_SPEED);
            try {
                output.setLength(0);
                output.writeInt(MAGIC);
                output.writeInt(0);
                output.writeLong(generation);
                output.writeLong(0);
            } catch (IOException failure) {
                close();
                throw failure;
            }
        }

        public int offset() {
            return offset;
        }

        public boolean add(List<T> page) throws IOException {
            if (page.isEmpty()) return false;
            int size = entries.size();
            for (T track : page) {
                if (track == null) continue;
                String trackId = id.apply(track);
                if (trackId == null || !ids.add(trackId)) continue;
                entries.add(entry(track, output.getFilePointer(), names));
                byte[] json = JSON.toJson(track, type).getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(json.length);
                deflater.reset();
                try (DeflaterOutputStream compressed = new DeflaterOutputStream(buffer, deflater)) {
                    compressed.write(json);
                }
                if (buffer.size() > MAX_RECORD_BYTES) throw new IOException("Track record is too large");
                output.writeInt(buffer.size());
                output.write(buffer.toByteArray());
            }
            if (entries.size() == size) throw new IOException("The server did not advance the track catalogue");
            offset += page.size();
            return true;
        }

        private void copy(Entry entry, byte[] bytes) throws IOException {
            if (!ids.add(entry.id)) throw new IOException("Duplicate track in snapshot");
            entries.add(new Entry(entry.id, entry.title, entry.artistLine, entry.artist, entry.album, output.getFilePointer()));
            output.writeInt(bytes.length);
            output.write(bytes);
        }

        public Index finish() throws IOException {
            long metadataOffset = output.getFilePointer();
            try (DataOutputStream metadata = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(outputStream(output))))) {
                for (Entry entry : entries) {
                    metadata.writeLong(entry.offset);
                    writeString(metadata, entry.id);
                    writeString(metadata, entry.title);
                    writeString(metadata, entry.artistLine);
                    writeString(metadata, entry.artist);
                    writeString(metadata, entry.album);
                }
            }
            output.seek(4);
            output.writeInt(entries.size());
            output.writeLong(generation);
            output.writeLong(metadataOffset);
            output.getFD().sync();
            return new Index(entries, generation);
        }

        @Override public void close() throws IOException {
            deflater.end();
            output.close();
        }
    }

    private static final class Entry {
        final String id;
        final String title;
        final String artistLine;
        final String artist;
        final String album;
        final long offset;

        Entry(String id, String title, String artistLine, String artist, String album, long offset) {
            this.id = id;
            this.title = title;
            this.artistLine = artistLine;
            this.artist = artist;
            this.album = album;
            this.offset = offset;
        }
    }
}
