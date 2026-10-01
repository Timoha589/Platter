package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerQueueBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.adapter.PlayerSongQueueAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DragHandleTouch;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

@UnstableApi
public class PlayerQueueFragment extends Fragment implements ClickCallback {
    private static final String TAG = "PlayerQueueFragment";

    private InnerFragmentPlayerQueueBinding bind;

    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private PlayerSongQueueAdapter playerSongQueueAdapter;
    private CleanQueueFooterAdapter cleanQueueFooterAdapter;

    @Nullable
    private MediaBrowser mediaBrowser;

    /**
     * Keeps the green row on the track that is playing while the queue is open,
     * rather than only when the page comes back into view.
     */
    private final Player.Listener playbackListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            updateNowPlayingItem();
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            updateNowPlayingItem();
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerQueueBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        initQueueRecyclerView();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateNowPlayingItem();
    }

    @Override
    public void onStop() {
        if (mediaBrowser != null) mediaBrowser.removeListener(playbackListener);
        mediaBrowser = null;

        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();
                if (bind == null) return;

                this.mediaBrowser = mediaBrowser;
                mediaBrowser.addListener(playbackListener);
                updateNowPlayingItem();

                initShuffleButton(mediaBrowser);
                cleanQueueFooterAdapter.setOnClick(() -> cleanQueue(mediaBrowser));
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void initQueueRecyclerView() {
        bind.playerQueueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.playerQueueRecyclerView.setHasFixedSize(true);

        playerSongQueueAdapter = new PlayerSongQueueAdapter(this);
        cleanQueueFooterAdapter = new CleanQueueFooterAdapter();
        bind.playerQueueRecyclerView.setAdapter(new ConcatAdapter(playerSongQueueAdapter, cleanQueueFooterAdapter));
        playerBottomSheetViewModel.getQueueSong().observe(getViewLifecycleOwner(), queue -> {
            if (queue != null) {
                playerSongQueueAdapter.setItems(queue.stream().map(item -> (Child) item).collect(Collectors.toList()));
                updateNowPlayingItem();
            }
        });

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            int originalPosition = -1;
            int fromPosition = -1;
            int toPosition = -1;

            /*
             * Rows are picked up by their right-hand end the moment it is
             * touched (see PlayerSongQueueAdapter). Holding a row down first as
             * well would make a long press anywhere else a second, slower way
             * of doing the same thing - and one that fires while reading.
             */
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            /* The "clear" row under the tracks is neither dragged nor swiped away. */
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (viewHolder.getBindingAdapter() != playerSongQueueAdapter) return 0;

                return super.getMovementFlags(recyclerView, viewHolder);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                if (target.getBindingAdapter() != playerSongQueueAdapter) return false;

                if (originalPosition == -1) {
                    originalPosition = viewHolder.getBindingAdapterPosition();
                }

                fromPosition = viewHolder.getBindingAdapterPosition();
                toPosition = target.getBindingAdapterPosition();

                /*
                 * Per spostare un elemento nella coda devo:
                 *    - Spostare graficamente la traccia da una posizione all'altra con PlayerSongQueueAdapter.moveItem()
                 *    - Spostare nel db la traccia, tramite QueueRepository
                 *    - Notificare il Service dell'avvenuto spostamento con MusicPlayerRemote.moveSong()
                 *
                 * In onMove prendo la posizione di inizio e fine, ma solo al rilascio dell'elemento procedo allo spostamento
                 * In questo modo evito che ad ogni cambio di posizione vada a riscrivere nel db
                 * Al rilascio dell'elemento chiamo il metodo clearView()
                 */

                int from = fromPosition;
                int to = toPosition;
                DragHandleTouch.moveKeepingScroll(recyclerView, () -> playerSongQueueAdapter.moveItem(from, to));

                return false;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);

                if (originalPosition != -1 && fromPosition != -1 && toPosition != -1) {
                    MediaManager.swap(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), originalPosition, toPosition);

                    // A row carried past the playing one changes which side
                    // of it - played or still to come - it is drawn on.
                    playerSongQueueAdapter.refreshPlaybackState();
                }

                originalPosition = -1;
                fromPosition = -1;
                toPosition = -1;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                MediaManager.remove(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), viewHolder.getBindingAdapterPosition());
                viewHolder.getBindingAdapter().notifyDataSetChanged();
            }
        });

        itemTouchHelper.attachToRecyclerView(bind.playerQueueRecyclerView);
        playerSongQueueAdapter.setOnStartDragListener(itemTouchHelper::startDrag);
    }

    private void initShuffleButton(MediaBrowser mediaBrowser) {
        bind.playerShuffleQueueFab.setOnClickListener(view -> {
            int startPosition = mediaBrowser.getCurrentMediaItemIndex() + 1;
            int endPosition = playerSongQueueAdapter.getItems().size() - 1;

            if (startPosition < endPosition) {
                ArrayList<Integer> pool = new ArrayList<>();

                for (int i = startPosition; i <= endPosition; i++) {
                    pool.add(i);
                }

                while (pool.size() >= 2) {
                    int fromPosition = (int) (Math.random() * (pool.size()));
                    int positionA = pool.get(fromPosition);
                    pool.remove(fromPosition);

                    int toPosition = (int) (Math.random() * (pool.size()));
                    int positionB = pool.get(toPosition);
                    pool.remove(toPosition);

                    Collections.swap(playerSongQueueAdapter.getItems(), positionA, positionB);
                    playerSongQueueAdapter.notifyItemMoved(positionA, positionB);
                }

                MediaManager.shuffle(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), startPosition, endPosition);
            }
        });
    }

    private void cleanQueue(MediaBrowser mediaBrowser) {
        int startPosition = mediaBrowser.getCurrentMediaItemIndex() + 1;
        int endPosition = playerSongQueueAdapter.getItems().size();

        if (startPosition >= endPosition) return;

        MediaManager.removeRange(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), startPosition, endPosition);
        // The second argument is a count. It was given the end position, so
        // the list was told more rows had gone than it had after the start.
        playerSongQueueAdapter.notifyItemRangeRemoved(startPosition, endPosition - startPosition);
    }

    /** The "clear the queue" row at the foot of the list. */
    private static class CleanQueueFooterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Nullable
        private Runnable onClick;

        void setOnClick(@Nullable Runnable onClick) {
            this.onClick = onClick;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_player_queue_clean, parent, false);
            view.setOnClickListener(v -> {
                if (onClick != null) onClick.run();
            });

            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return 1;
        }
    }

    private void updateNowPlayingItem() {
        if (mediaBrowser == null || playerSongQueueAdapter == null) return;

        playerSongQueueAdapter.setCurrentIndex(mediaBrowser.getCurrentMediaItemIndex());
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
    }
}