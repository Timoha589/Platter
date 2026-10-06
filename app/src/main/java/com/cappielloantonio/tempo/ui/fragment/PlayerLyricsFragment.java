package com.cappielloantonio.tempo.ui.fragment;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
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
     * Whether the reader is looking around rather than following the song:
     * from the first drag until the hold has run out. While they are, nothing is
     * blurred and the column stays where they put it.
     */
    private boolean browsing;

    private final Runnable endBrowsing = () -> {
        browsing = false;

        if (bind == null) return;

        applyLineStyles();
        followActiveLine();
    };

    /*
     * The sung line is left at its own size and strength and the rest are
     * shrunk back from it, dimmed, and blurred the more the further they are
     * from it - the blur is what tells the eye where the song is without
     * anything being highlighted. Nothing here scales above 1, so nothing can be
     * clipped.
     */
    private static final float INACTIVE_SCALE = 0.92f;
    private static final float INACTIVE_ALPHA = 0.34f;
    private static final float BROWSE_ALPHA = 0.6f;
    private static final float BLUR_STEP_DP = 0.9f;
    private static final int BLUR_LINES = 5;
    private static final long LINE_ANIMATION_DURATION = 320;

    /** The sung line rests this far down the view, not in the middle: what is coming matters more than what has gone. */
    private static final float ANCHOR = 0.26f;

    /**
     * A change of line moves every line up together, the ones under the new
     * line a little behind their upper neighbour, so the column pours into place
     * instead of sliding as one board.
     */
    private static final long MOVE_DURATION = 700;
    private static final long STAGGER = 85;
    private static final int STAGGER_LINES = 7;
    private static final int MOVE_REACH = 12;
    private static final PathInterpolator SETTLE = new PathInterpolator(0.3f, 0f, 0.15f, 1f);

    /** What each line is doing right now, parallel to {@link #lineViews}. */
    private static class LineState {
        float blur;
        ValueAnimator blurAnimator;
        ObjectAnimator move;
    }

    private final List<LineState> lineStates = new ArrayList<>();

    /** The first placement of the column is a jump; every one after it pours. */
    private boolean placed;

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
        syncLyricsHandler.removeCallbacks(endBrowsing);
        browsing = false;

        cancelLineAnimations();
        lineViews.clear();
        lineStates.clear();
        renderedLyrics = null;
        activeLineIndex = -1;
        placed = false;

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
                holdForReader();
                return false;
            }
        });

        bind.nowPlayingSongLyricsSrollView.setOnTouchListener((view, event) -> {
            gestures.onTouchEvent(event);

            return claim.onTouch(view, event);
        });
    }

    /** The reader has the words: unblurred and still for a while after their last move. */
    private void holdForReader() {
        syncLyricsHandler.removeCallbacks(endBrowsing);

        if (!browsing) {
            browsing = true;
            applyLineStyles();
        }

        syncLyricsHandler.postDelayed(endBrowsing, AUTO_SCROLL_HOLD);
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

        // The reader has chosen where to be: the column follows the song again from there.
        syncLyricsHandler.removeCallbacks(endBrowsing);
        boolean wasBrowsing = browsing;
        browsing = false;

        // The highlight is the answer to the tap, and waiting a quarter second
        // for the ticker to notice would read as the tap having missed.
        if (index == activeLineIndex) {
            applyLineStyles();
            if (wasBrowsing) followActiveLine();
        } else {
            setActiveLine(index);
        }
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
        bind.nowPlayingSongLyricsTextView.setGravity(gravity == Gravity.CENTER_HORIZONTAL && isLeftAligned() ? Gravity.START : gravity);
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
        cancelLineAnimations();
        bind.nowPlayingSongLyricsLines.removeAllViews();
        bind.nowPlayingSongLyricsLines.setPadding(0, 0, 0, 0);
        lineViews.clear();
        lineStates.clear();
        placed = false;
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

        cancelLineAnimations();
        bind.nowPlayingSongLyricsLines.removeAllViews();
        lineViews.clear();
        lineStates.clear();
        activeLineIndex = -1;
        placed = false;
        renderedLyrics = lyrics;
        introDots = null;

        LayoutInflater inflater = LayoutInflater.from(requireContext());

        /*
         * Lines start dimmed only when something will light them up. Untimed
         * lyrics come through here too, and no line of them is ever the sung
         * one - left at the resting state, the whole lyric read as greyed out.
         */
        boolean synced = LyricsUtil.isSynced(lyrics);
        boolean leftAligned = isLeftAligned();
        float textSize = lineTextSize(leftAligned);

        if (synced) addIntroDots(lyrics);

        int position = 0;
        for (Line line : lyrics.getLine()) {
            if (line == null || line.getValue() == null) continue;

            TextView lineView = (TextView) inflater.inflate(R.layout.item_player_lyrics_line, bind.nowPlayingSongLyricsLines, false);
            lineView.setText(line.getValue().trim());
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
            lineView.setGravity(leftAligned ? Gravity.START : Gravity.CENTER_HORIZONTAL);

            // Lines shrink toward the edge they are read from: the left one, or the middle when they are centred.
            lineView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                v.setPivotX(leftAligned ? 0f : v.getWidth() / 2f);
                v.setPivotY(v.getHeight() / 2f);
            });

            if (synced) {
                lineView.setAlpha(INACTIVE_ALPHA);
                lineView.setScaleX(INACTIVE_SCALE);
                lineView.setScaleY(INACTIVE_SCALE);
            }

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

            LineState state = new LineState();
            lineStates.add(state);

            // Before the first line is sung every one of them is further from it than the last.
            if (synced) setBlur(lineView, state, blurFor(position));
            position++;
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
        introDots.setLeftAligned(isLeftAligned());
        bind.nowPlayingSongLyricsLines.addView(introDots, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /*
     * Room above the first line for what is being sung to sit at its anchor, and
     * below the last for the same - the dots before the words, the first line
     * once they start, the last line at the end - instead of being pinned to the
     * top edge until the column has scrolled far enough to hold anything there.
     * Untimed lyrics have nothing being sung and keep reading from the top.
     */
    private void centreColumn() {
        if (bind == null) return;

        boolean synced = LyricsUtil.isSynced(renderedLyrics);
        int height = bind.nowPlayingSongLyricsSrollView.getHeight();
        int top = synced ? anchorPx() : 0;
        int bottom = synced ? Math.max(0, height - top) : 0;
        View column = bind.nowPlayingSongLyricsLines;

        if (column.getPaddingTop() != top || column.getPaddingBottom() != bottom) {
            column.setPadding(0, top, 0, bottom);
        }

        // Once the padding is laid out: straight to what is being sung, no glide.
        column.post(() -> {
            View resting = restingView();
            if (bind != null && resting != null) {
                bind.nowPlayingSongLyricsSrollView.scrollTo(0, scrollTargetFor(resting));
                placed = true;
            }
        });
    }

    /** Where the sung line's top rests, from the top of the view. */
    private int anchorPx() {
        return (int) (bind.nowPlayingSongLyricsSrollView.getHeight() * ANCHOR);
    }

    /** Beside the player, on the right, the words keep to the left; under it they are centred. */
    private boolean isLeftAligned() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** As wide as the page allows, between 22 and 32sp - the desktop's rule, with the phone's smaller ceiling. */
    private float lineTextSize(boolean leftAligned) {
        float screenDp = getResources().getConfiguration().screenWidthDp;
        float widthDp = (leftAligned ? screenDp * 0.5f : screenDp) - 48f;

        return Math.max(22f, Math.min(32f, widthDp * 0.075f));
    }

    /** What belongs at the anchor: the sung line, else the dots, else the first line. */
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

        activeLineIndex = index;

        applyLineStyles();

        // The dots give way to the first line, and come back for a seek into the intro.
        if (introDots != null) {
            introDots.animate().alpha(index < 0 ? 1f : 0f).setDuration(LINE_ANIMATION_DURATION).start();
            if (index >= 0) introDots.setTimeLeft(0, false);
        }

        if (!browsing) followActiveLine();
    }

    /**
     * Brings every line to what it should be now: the sung one whole and sharp,
     * the rest smaller, dimmer and blurrier the further they are from it - and,
     * while the reader is looking around, all of them readable.
     */
    private void applyLineStyles() {
        boolean synced = LyricsUtil.isSynced(renderedLyrics);

        for (int index = 0; index < lineViews.size(); index++) {
            TextView lineView = lineViews.get(index);
            boolean sung = !synced || index == activeLineIndex;

            float alpha = sung ? 1f : browsing ? BROWSE_ALPHA : INACTIVE_ALPHA;
            float scale = sung ? 1f : INACTIVE_SCALE;

            /*
             * Text is rasterised at the size it lands on screen, so a line being
             * scaled is a line whose every glyph is drawn again at a new size on
             * every frame. Inside a layer the words are drawn once and the
             * animation only stretches the picture of them; the layer is let go
             * at the end, so a line at rest is sharp text again.
             */
            lineView.animate()
                    .alpha(alpha)
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(LINE_ANIMATION_DURATION)
                    .withLayer()
                    .start();

            animateBlur(index, synced && !sung && !browsing ? blurFor(index) : 0f);
        }
    }

    /** How blurred line [index] is when it is not being sung: a step more for each line away from the sung one, up to a ceiling. */
    private float blurFor(int index) {
        return Math.min(Math.abs(index - activeLineIndex), BLUR_LINES) * BLUR_STEP_DP * getResources().getDisplayMetrics().density;
    }

    /** Blur is a render effect: from Android 12. Earlier, the dimming and the size alone say where the song is. */
    private void animateBlur(int index, float target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;

        TextView lineView = lineViews.get(index);
        LineState state = lineStates.get(index);

        if (state.blurAnimator != null) state.blurAnimator.cancel();
        if (Math.abs(state.blur - target) < 0.01f) return;

        // Lines far from the screen have nobody to see them change.
        if (Math.abs(index - Math.max(activeLineIndex, 0)) > MOVE_REACH) {
            setBlur(lineView, state, target);
            return;
        }

        state.blurAnimator = ValueAnimator.ofFloat(state.blur, target);
        state.blurAnimator.setDuration(LINE_ANIMATION_DURATION);
        state.blurAnimator.addUpdateListener(animation -> setBlur(lineView, state, (float) animation.getAnimatedValue()));
        state.blurAnimator.start();
    }

    private void setBlur(View view, LineState state, float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;

        state.blur = radius;
        Blur.apply(view, radius);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Blur {
        static void apply(View view, float radius) {
            view.setRenderEffect(radius < 0.5f ? null : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL));
        }
    }

    /**
     * Takes the column to the sung line in one jump and lets each line walk
     * back to where it was: the ones above the new line and the line itself
     * first, the ones under it one after another. Where the line is far off - a
     * seek across the song - there is nothing to pour, and it is simply there.
     */
    private void followActiveLine() {
        View resting = restingView();
        if (bind == null || resting == null) return;

        int height = bind.nowPlayingSongLyricsSrollView.getHeight();
        int target = scrollTargetFor(resting);
        int delta = target - bind.nowPlayingSongLyricsSrollView.getScrollY();

        if (delta == 0) return;

        if (!placed || Math.abs(delta) > height * 1.5f) {
            placed = true;
            bind.nowPlayingSongLyricsSrollView.scrollTo(0, target);
            return;
        }

        int before = bind.nowPlayingSongLyricsSrollView.getScrollY();
        bind.nowPlayingSongLyricsSrollView.scrollTo(0, target);
        int moved = bind.nowPlayingSongLyricsSrollView.getScrollY() - before;

        if (moved != 0) pourLines(moved);
    }

    /** The column has just been scrolled [moved] px at once: every line is shifted back by as much, and walks home. */
    private void pourLines(int moved) {
        for (int index = 0; index < lineViews.size(); index++) {
            LineState state = lineStates.get(index);
            if (state.move != null) state.move.cancel();

            View lineView = lineViews.get(index);

            if (Math.abs(index - Math.max(activeLineIndex, 0)) > MOVE_REACH) {
                lineView.setTranslationY(0f);
                continue;
            }

            float start = lineView.getTranslationY() + moved;
            lineView.setTranslationY(start);

            // The dots stand before line 0, like the lines above the sung one.
            long wait = index <= activeLineIndex ? 0 : Math.min(index - activeLineIndex, STAGGER_LINES) * STAGGER;

            state.move = ObjectAnimator.ofFloat(lineView, View.TRANSLATION_Y, start, 0f);
            state.move.setDuration(MOVE_DURATION);
            state.move.setStartDelay(wait);
            state.move.setInterpolator(SETTLE);
            state.move.start();
        }

        if (introDots != null) {
            float start = introDots.getTranslationY() + moved;
            introDots.setTranslationY(start);

            ObjectAnimator dots = ObjectAnimator.ofFloat(introDots, View.TRANSLATION_Y, start, 0f);
            dots.setDuration(MOVE_DURATION);
            dots.setInterpolator(SETTLE);
            dots.start();
        }
    }

    private void cancelLineAnimations() {
        for (int index = 0; index < lineViews.size() && index < lineStates.size(); index++) {
            LineState state = lineStates.get(index);
            if (state.move != null) state.move.cancel();
            if (state.blurAnimator != null) state.blurAnimator.cancel();

            lineViews.get(index).animate().cancel();
        }
    }

    /**
     * @return the scroll offset that puts the top of {@code lineView} at the
     * anchor - reachable for every line, given the column's padding at both
     * ends
     */
    private int scrollTargetFor(View lineView) {
        return Math.max(0, bind.nowPlayingSongLyricsLines.getTop() + lineView.getTop() - anchorPx());
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