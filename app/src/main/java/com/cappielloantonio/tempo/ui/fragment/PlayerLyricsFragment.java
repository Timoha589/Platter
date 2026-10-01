package com.cappielloantonio.tempo.ui.fragment;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerLyricsBinding;
import com.cappielloantonio.tempo.helper.view.LyricsIntroDotsView;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Line;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.subsonic.models.StructuredLyrics;
import com.cappielloantonio.tempo.util.LyricsUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.util.SwipeGestureUtil;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;


@OptIn(markerClass = UnstableApi.class)
public class PlayerLyricsFragment extends Fragment {
    private static final String TAG = "PlayerLyricsFragment";

    private InnerFragmentPlayerLyricsBinding bind;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private MediaBrowser mediaBrowser;

    /*
     * One ticker for the life of the view.
     *
     * A handler and a runnable used to be built afresh for every lyric that
     * arrived, while the previous runnable was still queued on the previous
     * handler - and since both read the fields rather than their own copies, the
     * old post put the new runnable back on the queue alongside the post that
     * was already there. Every cycle doubled the number of ticks; a few tracks
     * in, the panel was recomputing itself hundreds of times a second, which is
     * exactly the budget scrolling needs.
     */
    private final Handler syncLyricsHandler = new Handler(Looper.getMainLooper());
    private boolean syncLyricsTicking;

    private final Runnable syncLyricsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!syncLyricsTicking || bind == null) return;

            long untilNext = displaySyncedLyrics();

            syncLyricsHandler.postDelayed(this, Math.max(MIN_TICK, Math.min(SYNC_TICK, untilNext)));
        }
    };

    private static final long SYNC_TICK = 250;
    private static final long MIN_TICK = 16;

    /**
     * How long the words are left where the reader put them once they stop
     * scrolling. Following the song again the instant a finger lifts is what
     * makes a lyric impossible to read ahead in.
     */
    private static final long AUTO_SCROLL_HOLD = 3000;

    /**
     * When the reader last moved the words themselves. A fling carries on after
     * the finger has gone, and lands well inside the hold.
     */
    private long lastUserScrollAt;

    /*
     * The sung line is left at its own size and the rest are shrunk back from
     * it. Enlarging the sung line instead is what cut long lines off: the column
     * clips its children, so anything scaled past its own bounds lost its ends.
     * Nothing here scales above 1, so nothing can be clipped.
     */
    private static final float INACTIVE_SCALE = 0.88f;
    private static final float INACTIVE_ALPHA = 0.35f;
    private static final long LINE_ANIMATION_DURATION = 240;

    private final List<TextView> lineViews = new ArrayList<>();
    private StructuredLyrics renderedLyrics;
    private int activeLineIndex = -1;

    /**
     * The dots - and the 3, 2, 1 after them - that stand in for the words
     * until the first line is sung, and when that is on the lyric's clock. Null when the singing starts too soon
     * for a wait to be worth showing.
     */
    @Nullable
    private LyricsIntroDotsView introDots;
    private int introEnd;

    /** An intro shorter than this goes straight to the first line. */
    private static final int SHORTEST_INTRO = 3000;

    /**
     * The three pieces the panel is drawn from, each kept as its own observer
     * delivers it.
     */
    @Nullable
    private String lyrics;
    @Nullable
    private LyricsList lyricsList;
    @Nullable
    private String description;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerLyricsBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        initLyricsGestures();

        bind.nowPlayingSongLyricsSrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) v.post(this::centreColumn);
        });

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initPanelContent();
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();

    }

    @Override
    public void onResume() {
        super.onResume();
        bindMediaController();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopSyncTicker();
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        stopSyncTicker();

        lineViews.clear();
        renderedLyrics = null;
        activeLineIndex = -1;

        bind = null;
    }

    /**
     * Three things share the words, in front of the scroll they belong to.
     * <p>
     * A tap moves the track to the line under the finger; a drag holds the
     * column still for a few seconds afterwards; and underneath both, the claim
     * that keeps the sheet from taking a scroll the text can still use.
     * <p>
     * All of it is hung off the scroll view rather than off the lines, so the
     * lines stay unclickable. A clickable child would swallow the touch before
     * the scroll view ever saw it, and the claim - which is what lets the words
     * be scrolled at all - would never run.
     */
    private void initLyricsGestures() {
        View.OnTouchListener claim = SwipeGestureUtil.verticalScrollClaim(bind.nowPlayingSongLyricsSrollView);

        GestureDetector gestures = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent event) {
                seekToLineAt(event.getY());
                return false;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent from, @NonNull MotionEvent to, float distanceX, float distanceY) {
                // Every move, so the hold is measured from the last of them.
                lastUserScrollAt = to.getEventTime();
                return false;
            }
        });

        bind.nowPlayingSongLyricsSrollView.setOnTouchListener((view, event) -> {
            gestures.onTouchEvent(event);

            return claim.onTouch(view, event);
        });
    }

    /**
     * Moves the track to the line the reader pointed at. The nearest line rather
     * than a strict hit, so the gaps between them are not dead.
     */
    private void seekToLineAt(float y) {
        if (bind == null || mediaBrowser == null || renderedLyrics == null || lineViews.isEmpty()) return;

        // Untimed lines have nowhere to seek to - a missing start can arrive
        // as 0, which would throw the track back to its beginning.
        if (!LyricsUtil.isSynced(renderedLyrics)) return;

        int contentY = (int) y + bind.nowPlayingSongLyricsSrollView.getScrollY();
        int columnTop = bind.nowPlayingSongLyricsLines.getTop();

        // Above the first line or below the last: empty page, not a lyric.
        if (contentY < columnTop || contentY > columnTop + bind.nowPlayingSongLyricsLines.getHeight()) return;

        int nearest = -1;
        int nearestDistance = Integer.MAX_VALUE;

        for (int index = 0; index < lineViews.size(); index++) {
            View lineView = lineViews.get(index);
            int centre = columnTop + lineView.getTop() + lineView.getHeight() / 2;
            int distance = Math.abs(contentY - centre);

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }

        seekToLine(nearest);
    }

    private void seekToLine(int index) {
        List<Line> lines = renderedLyrics.getLine();

        if (lines == null || index < 0 || index >= lines.size()) return;

        Line line = lines.get(index);
        if (line == null || line.getStart() == null) return;

        /*
         * The clock the lines are matched against is the playback position plus
         * the file's offset, so the position that lands on this line is its own
         * start less that offset.
         */
        mediaBrowser.seekTo(Math.max(0, line.getStart() - renderedLyrics.getOffset()));

        // The highlight is the answer to the tap, and waiting a quarter second
        // for the ticker to notice would read as the tap having missed.
        setActiveLine(index);
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void startSyncTicker() {
        if (syncLyricsTicking) return;

        syncLyricsTicking = true;
        syncLyricsHandler.postDelayed(syncLyricsRunnable, SYNC_TICK);
        requestFullRefreshRate(true);
    }

    private void stopSyncTicker() {
        syncLyricsTicking = false;
        syncLyricsHandler.removeCallbacks(syncLyricsRunnable);
        requestFullRefreshRate(false);
    }

    /*
     * A panel that picks its own refresh rate - the S24's LTPO screen among
     * them - guesses it from how often the app sends frames, unless the window
     * names one. The player sends a frame or two a second for the clock, so the
     * guess kept sliding to 24Hz: traced on the device, the panel swung between
     * 24Hz and 120Hz every two seconds whatever the lyric was doing, and every
     * handover that landed in a 24Hz stretch played at 24 frames a second. Each
     * of those frames took 2-4ms to make; they were simply being shown 41.6ms
     * apart.
     *
     * Asking views to vote for a higher category did not move it on that
     * device; a rate in hertz on the window does. The request only counts while
     * the window is sending frames, so a lyric at rest still lets the panel
     * idle - and it is withdrawn the moment the words stop being followed.
     */
    private void requestFullRefreshRate(boolean full) {
        Activity activity = getActivity();
        if (activity == null) return;

        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();

        float rate = full ? highestRefreshRate(window) : 0f;
        if (params.preferredRefreshRate == rate) return;

        params.preferredRefreshRate = rate;
        window.setAttributes(params);
    }

    /** The fastest the panel can go at the resolution it is running at now. */
    private static float highestRefreshRate(Window window) {
        Display display = window.getDecorView().getDisplay();
        if (display == null) return 0f;

        Display.Mode current = display.getMode();
        float highest = 0f;

        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                highest = Math.max(highest, mode.getRefreshRate());
            }
        }

        return highest;
    }

    /**
     * Runs the ticker exactly when there is something for it to do: timed words
     * being looked at, and a player to read the clock off. The page sits behind
     * the artwork most of the time, and a highlight nobody can see is not worth
     * four ticks a second.
     */
    private void updateSyncTicker() {
        if (isResumed() && mediaBrowser != null && LyricsUtil.isSynced(renderedLyrics)) startSyncTicker();
        else stopSyncTicker();
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                mediaBrowser = mediaBrowserListenableFuture.get();
                updateSyncTicker();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    /*
     * Three separate observers feeding three fields, rather than a description
     * observer added from inside the lyrics one. Nesting them registered a fresh
     * description observer for every lyric that arrived, each holding on to the
     * lyric it was created with, so a later description update redrew the panel
     * from whichever stale copy happened to answer last - and the pile of them
     * grew for as long as the player was open.
     */
    private void initPanelContent() {
        if (OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
            playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), value -> {
                lyricsList = value;
                lyrics = null;

                resetScroll();
                renderPanel();
            });
        } else {
            playerBottomSheetViewModel.getLiveLyrics().observe(getViewLifecycleOwner(), value -> {
                lyrics = value;
                lyricsList = null;

                resetScroll();
                renderPanel();
            });
        }

        playerBottomSheetViewModel.getLiveDescription().observe(getViewLifecycleOwner(), value -> {
            description = value;
            renderPanel();
        });
    }

    /**
     * Only when the words themselves change. A description arriving for the same
     * track must not throw a synced lyric back to its first line.
     */
    private void resetScroll() {
        if (bind != null) bind.nowPlayingSongLyricsSrollView.scrollTo(0, 0);
    }

    private void renderPanel() {
        if (bind == null) return;

        if (lyrics != null && !lyrics.trim().equals("")) {
            showTextBlock(MusicUtil.getReadableLyrics(lyrics), Gravity.CENTER_HORIZONTAL);
            bind.emptyDescriptionImageView.setVisibility(View.GONE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
        } else if (LyricsUtil.pick(lyricsList) != null) {
            showSyncedLines(LyricsUtil.pick(lyricsList));
            bind.emptyDescriptionImageView.setVisibility(View.GONE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
        } else if (description != null && !description.trim().equals("")) {
            // A description is prose, not verse - it stays ranged left.
            showTextBlock(MusicUtil.getReadableLyrics(description), Gravity.START);
            bind.emptyDescriptionImageView.setVisibility(View.GONE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
        } else {
            showNothing();
            bind.emptyDescriptionImageView.setVisibility(View.VISIBLE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.VISIBLE);
        }

        updateSyncTicker();
    }

    private void showTextBlock(CharSequence text, int gravity) {
        bind.nowPlayingSongLyricsTextView.setText(text);
        bind.nowPlayingSongLyricsTextView.setGravity(gravity);
        bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
        bind.nowPlayingSongLyricsLines.setVisibility(View.GONE);

        clearSyncedLines();
    }

    private void showNothing() {
        bind.nowPlayingSongLyricsTextView.setVisibility(View.GONE);
        bind.nowPlayingSongLyricsLines.setVisibility(View.GONE);

        clearSyncedLines();
    }

    private void clearSyncedLines() {
        bind.nowPlayingSongLyricsLines.removeAllViews();
        bind.nowPlayingSongLyricsLines.setPadding(0, 0, 0, 0);
        lineViews.clear();
        renderedLyrics = null;
        activeLineIndex = -1;
        introDots = null;
    }

    /**
     * Rebuilds the column of lines, but only when the lyric itself has changed:
     * the observers feeding this fire again on every description update, and
     * rebuilding would drop the highlight and replay its animation each time.
     */
    private void showSyncedLines(StructuredLyrics lyrics) {
        bind.nowPlayingSongLyricsTextView.setVisibility(View.GONE);
        bind.nowPlayingSongLyricsLines.setVisibility(View.VISIBLE);

        if (lyrics == renderedLyrics) return;

        bind.nowPlayingSongLyricsLines.removeAllViews();
        lineViews.clear();
        activeLineIndex = -1;
        renderedLyrics = lyrics;
        introDots = null;

        LayoutInflater inflater = LayoutInflater.from(requireContext());

        /*
         * Lines start dimmed only when something will light them up. Untimed
         * lyrics come through here too, and no line of them is ever the sung
         * one - left at the resting state, the whole lyric read as greyed out.
         */
        boolean synced = LyricsUtil.isSynced(lyrics);
        float alpha = synced ? INACTIVE_ALPHA : 1f;
        float scale = synced ? INACTIVE_SCALE : 1f;

        if (synced) addIntroDots(lyrics);

        for (Line line : lyrics.getLine()) {
            if (line == null || line.getValue() == null) continue;

            TextView lineView = (TextView) inflater.inflate(R.layout.item_player_lyrics_line, bind.nowPlayingSongLyricsLines, false);
            lineView.setText(line.getValue().trim());
            lineView.setAlpha(alpha);
            lineView.setScaleX(scale);
            lineView.setScaleY(scale);

            /*
             * A TextView reports that it renders overlapping content, so the
             * renderer fades one by drawing it into an offscreen buffer first
             * and compositing that. These lines are text on nothing - no
             * background, no shadow, no spans - so the alpha can be applied
             * straight to the draw operations, and the buffer, which was being
             * allocated per line per frame, goes away.
             */
            lineView.forceHasOverlappingRendering(false);

            bind.nowPlayingSongLyricsLines.addView(lineView);
            lineViews.add(lineView);
        }

        centreColumn();
    }

    /**
     * Opens the column with the waiting dots when the words are a while
     * coming. The start is the earliest timed line rather than the first one
     * listed, in case a file lists them out of order.
     */
    private void addIntroDots(StructuredLyrics lyrics) {
        int firstStart = Integer.MAX_VALUE;

        for (Line line : lyrics.getLine()) {
            if (line != null && line.getValue() != null && line.getStart() != null) {
                firstStart = Math.min(firstStart, line.getStart());
            }
        }

        if (firstStart == Integer.MAX_VALUE || firstStart < SHORTEST_INTRO) return;

        introEnd = firstStart;
        introDots = new LyricsIntroDotsView(requireContext());
        bind.nowPlayingSongLyricsLines.addView(introDots, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /*
     * Half a panel of nothing above the first line and below the last, so that
     * whatever is being sung can sit in the middle at every point of the song -
     * the dots before the words, the first line once they start, the last line
     * at the end - instead of being pinned to the top edge until the column has
     * scrolled far enough to centre anything. Untimed lyrics have nothing being
     * sung and keep reading from the top.
     */
    private void centreColumn() {
        if (bind == null) return;

        int pad = LyricsUtil.isSynced(renderedLyrics) ? bind.nowPlayingSongLyricsSrollView.getHeight() / 2 : 0;
        View column = bind.nowPlayingSongLyricsLines;

        if (column.getPaddingTop() != pad || column.getPaddingBottom() != pad) {
            column.setPadding(0, pad, 0, pad);
        }

        // Once the padding is laid out: straight to what is being sung, no glide.
        column.post(() -> {
            View resting = restingView();
            if (bind != null && resting != null) bind.nowPlayingSongLyricsSrollView.scrollTo(0, scrollTargetFor(resting));
        });
    }

    /** What belongs in the middle: the sung line, else the dots, else the first line. */
    @Nullable
    private View restingView() {
        if (activeLineIndex >= 0 && activeLineIndex < lineViews.size()) return lineViews.get(activeLineIndex);
        if (introDots != null) return introDots;

        return lineViews.isEmpty() ? null : lineViews.get(0);
    }

    /**
     * Hands the highlight from one line to the next, and follows it down the
     * column. Both only happen on a change of line, so the quarter-second tick
     * driving this neither restarts the animation nor fights a manual scroll.
     */
    private void setActiveLine(int index) {
        if (index == activeLineIndex) return;

        if (activeLineIndex >= 0 && activeLineIndex < lineViews.size()) {
            animateLine(lineViews.get(activeLineIndex), false);
        }

        activeLineIndex = index;

        if (index >= 0 && index < lineViews.size()) animateLine(lineViews.get(index), true);

        // The dots give way to the first line, and come back for a seek into the intro.
        if (introDots != null) {
            introDots.animate().alpha(index < 0 ? 1f : 0f).setDuration(LINE_ANIMATION_DURATION).start();
            if (index >= 0) introDots.setTimeLeft(0, false);
        }

        View resting = restingView();
        if (resting != null && !isReaderHoldingPosition()) {
            bind.nowPlayingSongLyricsSrollView.smoothScrollTo(0, scrollTargetFor(resting));
        }
    }

    /**
     * Whether the words were moved by hand recently enough that pulling them
     * back to the sung line would be taking them away from someone reading.
     */
    private boolean isReaderHoldingPosition() {
        return SystemClock.uptimeMillis() - lastUserScrollAt < AUTO_SCROLL_HOLD;
    }

    /*
     * Text is rasterised at the size it lands on screen, so a line being scaled
     * is a line whose every glyph is drawn again at a new size on every frame -
     * two lines of large bold type, fifteen sizes each, all on the render thread
     * at the moment the column is also scrolling. That is what dropped the frame
     * rate on each change of line. Inside a layer the words are drawn once and
     * the animation only stretches the picture of them; the layer is let go at
     * the end, so a line at rest is sharp text again.
     */
    private void animateLine(TextView lineView, boolean active) {
        lineView.animate()
                .alpha(active ? 1f : INACTIVE_ALPHA)
                .scaleX(active ? 1f : INACTIVE_SCALE)
                .scaleY(active ? 1f : INACTIVE_SCALE)
                .setDuration(LINE_ANIMATION_DURATION)
                .withLayer()
                .start();
    }

    /**
     * @return the scroll offset that puts {@code lineView} in the middle of the
     * visible area - reachable for every line, given the column's half-panel
     * padding at both ends
     */
    private int scrollTargetFor(View lineView) {
        int centre = bind.nowPlayingSongLyricsLines.getTop() + lineView.getTop() + lineView.getHeight() / 2;

        return Math.max(0, centre - bind.nowPlayingSongLyricsSrollView.getHeight() / 2);
    }

    /**
     * @return how long until the next line starts, so the ticker can wake for
     * it rather than up to a quarter of a second after it - the count before
     * the first line ends on the beat, and the first line has to light on it
     */
    private long displaySyncedLyrics() {
        StructuredLyrics lyrics = renderedLyrics;

        if (lyrics == null || lineViews.isEmpty() || mediaBrowser == null) return SYNC_TICK;

        // The offset shifts every timing at once when a file's timings run
        // ahead of, or behind, the audio.
        int timestamp = (int) (mediaBrowser.getCurrentPosition()) + lyrics.getOffset();
        List<Line> lines = lyrics.getLine();

        int active = -1;
        long untilNext = SYNC_TICK;

        for (int index = 0; index < lines.size() && index < lineViews.size(); index++) {
            Line line = lines.get(index);
            if (line == null || line.getStart() == null) continue;

            if (line.getStart() < timestamp) active = index;
            else untilNext = Math.min(untilNext, line.getStart() - timestamp + 1);
        }

        setActiveLine(active);

        if (introDots != null && active < 0) {
            introDots.setTimeLeft(introEnd - timestamp, mediaBrowser.isPlaying());
        }

        return mediaBrowser.isPlaying() ? untilNext : SYNC_TICK;
    }

}