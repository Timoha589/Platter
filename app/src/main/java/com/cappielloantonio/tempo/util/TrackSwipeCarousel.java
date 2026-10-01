package com.cappielloantonio.tempo.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;

import java.util.Objects;

/**
 * Carries a track change on the finger instead of playing it back afterwards.
 * <p>
 * Three slots sit side by side inside a strip - the previous track, the one
 * playing, the next - with only the middle one over the strip, and the other two
 * parked a page away on either side where they are clipped out of sight. A
 * sideways drag moves all three together, so the artwork being pulled towards is
 * on screen and under the thumb the whole way; letting go either carries it the
 * rest of the way and moves the queue, or hands the current track back.
 * <p>
 * When a page lands the slots swap roles rather than their content: the sleeve
 * dragged into the middle stays exactly the view that was being looked at, so
 * nothing is decoded again at the moment it comes to rest, and only the slot
 * that fell off the far side is loaded.
 * <p>
 * <b>The strip pages on its own and tells the player afterwards.</b> Where the
 * next track is comes from the timeline, which can be walked from any position,
 * not from where the player happens to be - so a run of quick swipes moves the
 * strip through the queue at the speed of the finger while the player stays put.
 * The seek is sent once the swiping stops ({@link #SEEK_DEBOUNCE}), which is
 * what keeps a flurry from becoming a flurry of track changes: every one of
 * those would otherwise re-prepare a network stream and set the whole player UI
 * rebuilding itself.
 */
public class TrackSwipeCarousel implements View.OnTouchListener {
    /**
     * Nothing to show in that slot: an end of the queue, or a player that has
     * not been given one yet.
     */
    public static final int NO_TRACK = C.INDEX_UNSET;

    /**
     * A drag that lands past this much of a page commits, however slowly it was
     * made.
     */
    private static final float COMMIT_FRACTION = 0.30f;

    /**
     * A flick this fast commits whatever distance it covered - the gesture most
     * people make when they only want the next track.
     */
    private static final float COMMIT_VELOCITY_DP = 400f;

    private static final long COMMIT_DURATION = 240;
    private static final long RETURN_DURATION = 280;

    /**
     * How long after the last page lands the player is told where to go. Set to
     * the length of the commit animation: a single swipe reaches the player just
     * as the sleeve settles, and a second swipe inside that window replaces the
     * first rather than adding to it.
     */
    private static final long SEEK_DEBOUNCE = COMMIT_DURATION;

    /**
     * What is left of a drag towards an end of the queue that has nothing in it.
     * The strip still gives, so the gesture is answered, but it reads as a wall.
     */
    private static final float OVERSCROLL_FRACTION = 0.30f;

    public interface Callback {
        /**
         * The player being paged, or null while it is not connected.
         */
        @Nullable
        Player player();

        /**
         * Show this track in the slot. Null when the queue has nothing there -
         * the slot is parked off screen in that case, but is still cleared so it
         * carries no leftovers when it comes round again.
         */
        void onBindSlot(@NonNull View slot, @Nullable MediaMetadata metadata);

        /**
         * The gesture has been claimed as a sideways drag.
         */
        void onDragStart();
    }

    private final ViewGroup strip;

    /**
     * Index 0 holds the previous track, 1 the current, 2 the next. The views
     * move between those roles as pages land.
     */
    private final View[] slots;

    /**
     * The queue position each slot is showing, in the same order.
     */
    private final int[] shown = {NO_TRACK, NO_TRACK, NO_TRACK};

    /**
     * And the track that was at that position when the slot was filled.
     * <p>
     * A position on its own only means something inside one timeline. Replacing
     * the queue - playing a single track from a list, starting another album -
     * renumbers everything, and the strip would recognise its own stale position
     * in the new queue and conclude it had nothing to redraw: the artwork stayed
     * on the previous queue's track while the title beside it, which never went
     * through here, read correctly. Positions say where a slot sits; these say
     * what is in it.
     */
    private final String[] shownId = new String[3];

    private final float gapPx;
    private final float commitVelocityPx;
    private final int touchSlop;
    private final Callback callback;

    /**
     * Everything that is not a sideways drag - taps, and the vertical drags the
     * bottom sheet lives on - stays with the detector the host already had.
     */
    @Nullable
    private final GestureDetector fallback;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downX;
    private float downY;

    /**
     * How far the strip currently is from rest. Shared by all three slots.
     */
    private float offset;

    private boolean dragging;

    @Nullable
    private VelocityTracker velocityTracker;

    @Nullable
    private ValueAnimator animator;

    /**
     * What a running animation still owes when it ends - rotating the slots.
     * Held apart from the animation so it can be cut short and still leave
     * behind the state it was going to.
     */
    @Nullable
    private Runnable pendingEnd;

    /**
     * A queue change that arrived mid-drag. Redrawing under a finger that is
     * still moving would snatch the strip away from it, so the change is held
     * until the gesture is over.
     */
    private boolean syncPending;

    /**
     * Where the strip has paged to but the player has not been sent yet. While
     * this is set the strip is deliberately ahead of the player, and anything
     * the player reports is about where it still is rather than where we are
     * going.
     */
    private int pendingSeekIndex = NO_TRACK;

    /**
     * When the strip last heard from the finger. A gesture is only ever ended by
     * the event that ends it, and an event stream can simply stop - a view taken
     * out from under a touch, an event the platform drops. Everything the strip
     * defers while a drag is in progress would be deferred for good.
     */
    private long lastTouchAt;

    /**
     * How long a drag can go without an event before the strip stops believing
     * in it. Far longer than any gap between real move events, and far shorter
     * than a user would sit looking at artwork that had stopped changing.
     */
    private static final long GESTURE_TIMEOUT = 1000;

    private final Runnable seekRunnable = new Runnable() {
        @Override
        public void run() {
            int index = pendingSeekIndex;
            pendingSeekIndex = NO_TRACK;

            seekTo(index);
        }
    };

    /**
     * @param clipToStrip whether the strip should keep the parked slots inside
     *                    its own bounds. Needed wherever an ancestor turns
     *                    clipping off - a child is confined by its parent's
     *                    clipChildren flag, not by its own, so a strip in a bar
     *                    that lets its ripples spill would let the neighbouring
     *                    track spill with them. Pass false where something wider
     *                    up the tree does the clipping and the sleeves are meant
     *                    to travel past the strip's edge.
     */
    public TrackSwipeCarousel(@NonNull ViewGroup strip, @NonNull Callback callback, float gapPx, boolean clipToStrip, @Nullable GestureDetector fallback, @NonNull View... slots) {
        if (slots.length != 3) throw new IllegalArgumentException("A carousel takes three slots");

        this.strip = strip;
        this.slots = slots.clone();
        this.gapPx = gapPx;
        this.callback = callback;
        this.fallback = fallback;
        this.touchSlop = ViewConfiguration.get(strip.getContext()).getScaledTouchSlop();
        this.commitVelocityPx = COMMIT_VELOCITY_DP * strip.getResources().getDisplayMetrics().density;

        if (clipToStrip) {
            strip.setOutlineProvider(ViewOutlineProvider.BOUNDS);
            strip.setClipToOutline(true);
        }

        // A page is only as wide as the strip once the strip has been measured,
        // so the parked slots are put back in place on every layout pass - and
        // the outline the clip is taken from is rebuilt against the new size.
        strip.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (clipToStrip) strip.invalidateOutline();
            if (!dragging && animator == null) applyOffset();
        });
    }

    /**
     * Fills all three slots from the queue and puts the strip back at rest. For
     * the first draw, and for a queue that no longer lines up with what is on
     * screen at all.
     */
    public void bindAll() {
        finishAnimation();

        offset = 0;

        bindSlot(1, currentIndex());
        bindNeighbours();

        applyOffset();
    }

    /**
     * Reloads only what is parked off screen. The track in the middle has not
     * changed, but what sits on either side of it may have - a queue added to,
     * shuffle switched on, a repeat mode that opens or closes an end.
     */
    public void bindNeighbours() {
        bindSlot(0, neighbourIndex(shown[1], -1));
        bindSlot(2, neighbourIndex(shown[1], 1));
    }

    /**
     * Brings the strip back in line with the player. Called for every change the
     * player reports: the page turn is played out when the queue moved to a
     * track already parked beside the middle one, and the strip is redrawn
     * outright when it went somewhere else.
     */
    public void sync() {
        forgetAbandonedGesture();

        // The strip is ahead of the player on purpose. It will be caught up with
        // by the seek that is already scheduled.
        if (pendingSeekIndex != NO_TRACK) return;

        if (dragging) {
            /*
             * A seek this strip asked for, landing exactly where the strip
             * already is: there is nothing to redraw but the neighbours, and no
             * reason to interrupt the gesture in progress. Anything else really
             * did move the queue somewhere else, and waits for the finger.
             */
            if (isShowing(1, currentIndex())) bindNeighbours();
            else syncPending = true;

            return;
        }

        finishAnimation();

        /*
         * Nothing is being dragged and nothing is travelling, so the strip can
         * only be at rest. If it is not, a page was interrupted somewhere on its
         * way and the slot in the middle is not the one on screen - which is
         * exactly what a frozen sleeve looks like. Redrawn from the player
         * rather than left where it stopped.
         */
        if (offset != 0) {
            bindAll();
            return;
        }

        int index = currentIndex();

        if (isShowing(1, index)) {
            bindNeighbours();
            return;
        }

        int step = 0;

        if (isShowing(2, index)) step = 1;
        else if (isShowing(0, index)) step = -1;

        if (step == 0) {
            bindAll();
            return;
        }

        final int direction = step;

        animateTo(-direction * pageWidth(), COMMIT_DURATION, () -> {
            rotate(direction);
            bindNeighbours();
        });
    }

    /**
     * Lets go of a drag nothing has been heard from for a while. Without this
     * one dropped ACTION_UP would leave the strip waiting on a finger that is no
     * longer there, holding back every queue change after it.
     */
    private void forgetAbandonedGesture() {
        if (!dragging) return;
        if (SystemClock.uptimeMillis() - lastTouchAt <= GESTURE_TIMEOUT) return;

        dragging = false;
        syncPending = false;

        releaseVelocityTracker();
    }

    /**
     * Drops any gesture and animation in flight, and sends the player a page the
     * strip had already turned - the swipe was made, and a view going away is no
     * reason to lose it.
     */
    public void cancel() {
        finishAnimation();
        releaseVelocityTracker();
        dragging = false;

        handler.removeCallbacks(seekRunnable);

        int index = pendingSeekIndex;
        pendingSeekIndex = NO_TRACK;

        seekTo(index);
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        lastTouchAt = event.getEventTime();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A page still settling is put where it was headed, so a second
                // swipe starts from a strip at rest rather than from one that is
                // about to move on its own.
                finishAnimation();

                downX = event.getX();
                downY = event.getY();
                dragging = false;

                releaseVelocityTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);

                forward(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);

                if (!dragging) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;

                    // Anything meant as vertical belongs to the sheet.
                    if (Math.abs(dx) <= touchSlop || Math.abs(dx) <= Math.abs(dy)) {
                        forward(event);
                        return true;
                    }

                    dragging = true;

                    // Count from where the drag was recognised, so the artwork
                    // does not jump a slop's worth on the first frame.
                    downX += Math.signum(dx) * touchSlop;

                    if (view.getParent() != null) {
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    }

                    cancelForward(event);
                    callback.onDragStart();
                }

                offset = resist(event.getX() - downX);
                applyOffset();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    float velocityX = 0;

                    if (velocityTracker != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        velocityTracker.computeCurrentVelocity(1000);
                        velocityX = velocityTracker.getXVelocity();
                    }

                    release(velocityX);
                } else {
                    forward(event);
                }

                dragging = false;
                releaseVelocityTracker();
                return true;

            default:
                // A second finger, once the drag is ours, is nothing the
                // detector should still be hearing about.
                if (!dragging) forward(event);
                return true;
        }
    }

    /**
     * A drag never runs past a single page, and one heading into an empty end of
     * the queue only partly lands.
     */
    private float resist(float dx) {
        float page = pageWidth();

        if (shown[dx < 0 ? 2 : 0] == NO_TRACK) dx *= OVERSCROLL_FRACTION;

        return Math.max(-page, Math.min(page, dx));
    }

    private void release(float velocityX) {
        // The queue moved out from under the gesture. Whatever the drag was
        // heading for is no longer there to commit to, so it is handed back and
        // the change caught up with once the strip is still.
        if (syncPending) {
            syncPending = false;
            animateTo(0, RETURN_DURATION, this::sync);
            return;
        }

        float page = pageWidth();
        boolean flungForward = velocityX <= -commitVelocityPx;
        boolean flungBack = velocityX >= commitVelocityPx;

        int direction = 0;

        if (offset < 0 && (-offset >= page * COMMIT_FRACTION || flungForward)) direction = 1;
        else if (offset > 0 && (offset >= page * COMMIT_FRACTION || flungBack)) direction = -1;

        // A flick back the way the drag came undoes it, however far it had got.
        if (direction == 1 && flungBack) direction = 0;
        if (direction == -1 && flungForward) direction = 0;

        if (direction != 0 && shown[direction > 0 ? 2 : 0] == NO_TRACK) direction = 0;

        if (direction == 0) {
            animateTo(0, RETURN_DURATION, null);
            return;
        }

        final int committed = direction;

        // Where the strip is going, decided here and sent to the player once the
        // swiping stops. The timer starts as the finger lifts, so one swipe on
        // its own reaches the player just as the sleeve lands.
        scheduleSeek(shown[committed > 0 ? 2 : 0]);

        animateTo(-committed * page, COMMIT_DURATION, () -> {
            rotate(committed);
            bindNeighbours();
        });
    }

    /**
     * Hands each slot the role of its neighbour: the page dragged into the
     * middle becomes the current track, and the one that fell off the far side
     * comes round to be filled again.
     */
    private void rotate(int direction) {
        View previousView = slots[0];
        View currentView = slots[1];
        View nextView = slots[2];

        int previousIndex = shown[0];
        int currentIndex = shown[1];
        int nextIndex = shown[2];

        String previousId = shownId[0];
        String currentId = shownId[1];
        String nextId = shownId[2];

        if (direction > 0) {
            slots[0] = currentView;
            slots[1] = nextView;
            slots[2] = previousView;

            shown[0] = currentIndex;
            shown[1] = nextIndex;
            shown[2] = previousIndex;

            shownId[0] = currentId;
            shownId[1] = nextId;
            shownId[2] = previousId;
        } else {
            slots[0] = nextView;
            slots[1] = previousView;
            slots[2] = currentView;

            shown[0] = nextIndex;
            shown[1] = previousIndex;
            shown[2] = currentIndex;

            shownId[0] = nextId;
            shownId[1] = previousId;
            shownId[2] = currentId;
        }

        offset = 0;
        applyOffset();
    }

    /**
     * Whether that slot is already showing the track now at {@code index} - the
     * same place in the queue, and the same track in that place.
     */
    private boolean isShowing(int role, int index) {
        if (index == NO_TRACK || shown[role] != index) return false;

        return Objects.equals(shownId[role], mediaIdAt(index));
    }

    private void bindSlot(int role, int index) {
        shown[role] = index;
        shownId[role] = mediaIdAt(index);

        /*
         * The middle slot is always shown, empty queue and all - it is filled
         * with nothing rather than taken away. Only the parked neighbours
         * disappear when there is nothing to park there, so a drag towards an
         * end of the queue pulls on an empty edge.
         */
        if (role != 1) slots[role].setVisibility(index == NO_TRACK ? View.INVISIBLE : View.VISIBLE);

        callback.onBindSlot(slots[role], metadataAt(index));
    }

    private void applyOffset() {
        float page = pageWidth();

        for (int i = 0; i < slots.length; i++) {
            slots[i].setTranslationX((i - 1) * page + offset);
        }
    }

    private float pageWidth() {
        int width = strip.getWidth();

        return (width > 0 ? width : strip.getResources().getDisplayMetrics().widthPixels) + gapPx;
    }

    /* ------------------------------------------------------------------ *
     * The queue, read straight off the timeline
     * ------------------------------------------------------------------ */

    private int currentIndex() {
        Player player = callback.player();

        if (player == null || player.getCurrentTimeline().isEmpty()) return NO_TRACK;

        return player.getCurrentMediaItemIndex();
    }

    /**
     * Where a skip from {@code fromIndex} would land, worked out on the timeline
     * rather than asked of the player, so the strip can walk on past a track the
     * player has not been moved to yet. Repeat and shuffle are honoured the way
     * the player itself honours them for an explicit skip - repeating one track
     * does not trap the queue on it.
     */
    private int neighbourIndex(int fromIndex, int direction) {
        Player player = callback.player();
        if (player == null) return NO_TRACK;

        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty() || fromIndex < 0 || fromIndex >= timeline.getWindowCount()) return NO_TRACK;

        int repeatMode = player.getRepeatMode() == Player.REPEAT_MODE_ONE
                ? Player.REPEAT_MODE_OFF
                : player.getRepeatMode();
        boolean shuffle = player.getShuffleModeEnabled();

        return direction > 0
                ? timeline.getNextWindowIndex(fromIndex, repeatMode, shuffle)
                : timeline.getPreviousWindowIndex(fromIndex, repeatMode, shuffle);
    }

    /**
     * What identifies a track across queues, where its position does not.
     */
    @Nullable
    private String mediaIdAt(int index) {
        Player player = callback.player();

        if (player == null || index == NO_TRACK) return null;

        Timeline timeline = player.getCurrentTimeline();
        if (index < 0 || index >= timeline.getWindowCount()) return null;

        return player.getMediaItemAt(index).mediaId;
    }

    @Nullable
    private MediaMetadata metadataAt(int index) {
        Player player = callback.player();

        if (player == null || index == NO_TRACK) return null;

        Timeline timeline = player.getCurrentTimeline();
        if (index < 0 || index >= timeline.getWindowCount()) return null;

        return player.getMediaItemAt(index).mediaMetadata;
    }

    private void scheduleSeek(int index) {
        if (index == NO_TRACK) return;

        pendingSeekIndex = index;

        handler.removeCallbacks(seekRunnable);
        handler.postDelayed(seekRunnable, SEEK_DEBOUNCE);
    }

    private void seekTo(int index) {
        if (index == NO_TRACK) return;

        Player player = callback.player();
        if (player == null) return;

        if (index < 0 || index >= player.getMediaItemCount()) return;

        player.seekToDefaultPosition(index);
    }

    /* ------------------------------------------------------------------ */

    private void animateTo(float target, long duration, @Nullable Runnable end) {
        pendingEnd = end;

        ValueAnimator running = ValueAnimator.ofFloat(offset, target);
        running.setDuration(duration);
        running.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        running.addUpdateListener(animation -> {
            offset = (float) animation.getAnimatedValue();
            applyOffset();
        });
        running.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == running) animator = null;

                Runnable owed = pendingEnd;
                pendingEnd = null;

                if (owed != null) owed.run();
            }
        });

        animator = running;
        running.start();
    }

    /**
     * Puts a page that is still travelling where it was going. The end action
     * settles the strip itself, so cutting the animation short costs the
     * remaining frames and nothing else.
     */
    private void finishAnimation() {
        ValueAnimator running = animator;
        if (running == null) return;

        animator = null;
        running.end();
    }

    private void forward(MotionEvent event) {
        if (fallback != null) fallback.onTouchEvent(event);
    }

    /**
     * Tells the detector the gesture is gone, so a drag claimed here is not also
     * read as a tap, or as a pull on the sheet, when the finger lifts.
     */
    private void cancelForward(MotionEvent event) {
        if (fallback == null) return;

        MotionEvent cancelled = MotionEvent.obtain(event);
        cancelled.setAction(MotionEvent.ACTION_CANCEL);

        fallback.onTouchEvent(cancelled);
        cancelled.recycle();
    }

    private void releaseVelocityTracker() {
        if (velocityTracker == null) return;

        velocityTracker.recycle();
        velocityTracker = null;
    }
}
