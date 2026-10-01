package com.cappielloantonio.tempo.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.broadcast.receiver.ConnectivityStatusBroadcastReceiver;
import com.cappielloantonio.tempo.databinding.ActivityMainBinding;
import com.cappielloantonio.tempo.helper.view.MiniPlayerClearance;
import com.cappielloantonio.tempo.service.LikedTracksCache;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.ui.activity.base.BaseActivity;
import com.cappielloantonio.tempo.ui.dialog.ConnectionAlertDialog;
import com.cappielloantonio.tempo.ui.dialog.ServerUnreachableDialog;
import com.cappielloantonio.tempo.ui.dialog.UpdateDialog;
import com.cappielloantonio.tempo.ui.fragment.PlayerBottomSheetFragment;
import com.cappielloantonio.tempo.update.AppUpdater;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.ServerAddress;
import com.cappielloantonio.tempo.viewmodel.MainViewModel;
import com.cappielloantonio.tempo.viewmodel.SearchViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

@UnstableApi
public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivityLogs";
    private static final String UPDATE_DIALOG_TAG = "update_dialog";

    public ActivityMainBinding bind;
    private MainViewModel mainViewModel;

    private FragmentManager fragmentManager;
    private NavHostFragment navHostFragment;
    private BottomNavigationView bottomNavigationView;
    public NavController navController;
    private BottomSheetBehavior bottomSheetBehavior;
    private MiniPlayerClearance miniPlayerClearance;

    /* A notification tap that arrived before there was a queue to show */
    private boolean openPlayerWhenQueueLoads;

    ConnectivityStatusBroadcastReceiver connectivityStatusBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);

        bind = ActivityMainBinding.inflate(getLayoutInflater());
        View view = bind.getRoot();
        setContentView(view);

        mainViewModel = new ViewModelProvider(this).get(MainViewModel.class);

        connectivityStatusBroadcastReceiver = new ConnectivityStatusBroadcastReceiver(this);
        connectivityStatusReceiverManager(true);

        init();
        checkConnectionType();
        checkUpdate();

        handleShowPlayerIntent(getIntent());
        handleShowDownloadsIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        setIntent(intent);
        handleShowPlayerIntent(intent);
        handleShowDownloadsIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        initService();
    }

    @Override
    protected void onStop() {
        /*
         * A request that never got its queue dies with the visit rather than
         * waiting around to throw the player open at whatever gets played next.
         */
        openPlayerWhenQueueLoads = false;
        super.onStop();
    }

    /**
     * Opens the player for a tap on the media notification.
     * <p>
     * On a warm start the sheet is already sitting at the bottom of the screen
     * and can simply be pulled up. On a cold one there is no queue restored
     * yet, so the request is held until the sheet is given something to show -
     * see {@link #setBottomSheetInPeek(Boolean)}, which is the point at which
     * that happens.
     */
    private void handleShowPlayerIntent(Intent intent) {
        if (intent == null || !Constants.ACTION_SHOW_PLAYER.equals(intent.getAction())) return;

        // Consume it, or a configuration change would replay the same request.
        intent.setAction(null);

        if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            openPlayerWhenQueueLoads = true;
        } else {
            expandBottomSheet();
        }
    }

    /**
     * Opens the downloads tab for a tap on the "downloaded" notification, over
     * whatever the app was showing - the player included. Signed out there is
     * no tab to open, and the tap just brings the app up.
     */
    private void handleShowDownloadsIntent(Intent intent) {
        if (intent == null || !Constants.ACTION_SHOW_DOWNLOADS.equals(intent.getAction())) return;

        // Consume it, or a configuration change would replay the same request.
        intent.setAction(null);

        if (!isSignedIn()) return;

        if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        bottomNavigationView.setSelectedItemId(R.id.downloadFragment);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pingServer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connectivityStatusReceiverManager(false);
        bind = null;
    }

    @Override
    public void onBackPressed() {
        if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED)
            collapseBottomSheetDelayed();
        else
            super.onBackPressed();
    }

    public void init() {
        fragmentManager = getSupportFragmentManager();

        initBottomSheet();
        initNavigation();

        if (isSignedIn()) {
            goFromLogin();
        } else {
            goToLogin();
        }
    }

    private boolean isSignedIn() {
        return Preferences.getPassword() != null || (Preferences.getToken() != null && Preferences.getSalt() != null);
    }

    // BOTTOM SHEET/NAVIGATION
    private void initBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.player_bottom_sheet));
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback);
        fragmentManager.beginTransaction().replace(R.id.player_bottom_sheet, new PlayerBottomSheetFragment(), "PlayerBottomSheet").commit();

        checkBottomSheetAfterStateChanged();
    }

    public void setBottomSheetInPeek(Boolean isVisible) {
        if (isVisible) {
            /* Arrived here from the notification: go straight past the peek. */
            if (openPlayerWhenQueueLoads) {
                openPlayerWhenQueueLoads = false;
                expandBottomSheet();
            } else {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                syncMiniPlayerClearance(false);
            }
        } else {
            hideBottomSheet();
        }
    }

    /**
     * Hiding is refused outright while the sheet is not hideable, so every
     * programmatic dismissal has to lift the guard the expanded state puts up.
     */
    public void hideBottomSheet() {
        bottomSheetBehavior.setHideable(true);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        syncMiniPlayerClearance(false);
    }

    /**
     * Gives the screens their room for the mini player back, or takes it away,
     * once the sheet has settled.
     * <p>
     * Called from the sheet's callback and again straight after the sheet is
     * told where to go: before its first layout the sheet takes a new state on
     * the spot and reports nothing, which is exactly how a cold start with an
     * empty queue hides it - so the callback alone left home with a gap for a
     * mini player that was never there.
     * <p>
     * Only the callback animates: it is the one that hears of a mini player
     * that was on screen and has just slid off it. Placed without moving,
     * there was nothing on screen to follow.
     */
    private void syncMiniPlayerClearance(boolean animate) {
        if (miniPlayerClearance == null) return;

        switch (bottomSheetBehavior.getState()) {
            case BottomSheetBehavior.STATE_HIDDEN:
                miniPlayerClearance.setMiniPlayerShown(false, animate);
                break;
            case BottomSheetBehavior.STATE_COLLAPSED:
            case BottomSheetBehavior.STATE_EXPANDED:
                miniPlayerClearance.setMiniPlayerShown(true, animate);
                break;
        }
    }

    public void setBottomSheetVisibility(boolean visibility) {
        if (visibility) {
            findViewById(R.id.player_bottom_sheet).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.player_bottom_sheet).setVisibility(View.GONE);
        }
    }

    private void checkBottomSheetAfterStateChanged() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> {
            /*
             * This settles the sheet's opening state, and runs after the rest
             * of startup has had its say. A notification tap may have pulled
             * the player up by now, and the settle must not push it back down.
             */
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) return;

            setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        };
        handler.postDelayed(runnable, 100);
    }

    public void collapseBottomSheetDelayed() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        handler.postDelayed(runnable, 100);
    }

    public void expandBottomSheet() {
        // Before the sheet moves, so the window is already growing back as it rises.
        hideKeyboard();

        bottomSheetBehavior.setHideable(false);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        syncMiniPlayerClearance(false);
    }

    /**
     * Puts the keyboard away as the player comes up.
     * <p>
     * The window resizes around the keyboard (adjustResize), and the full player
     * is laid out in whatever height that leaves. Search keeps its field focused
     * and the keyboard up, and the mini player sits right above it - so a tap on
     * the mini player opened the player squeezed into the strip over the keys,
     * with the keyboard still there. This runs for every way the sheet rises -
     * the tap, a drag, the notification - and does nothing unless a keyboard is
     * actually showing, since a drag calls it on every frame.
     */
    private void hideKeyboard() {
        View decorView = getWindow().getDecorView();
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decorView);

        if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())) return;

        WindowCompat.getInsetsController(getWindow(), decorView).hide(WindowInsetsCompat.Type.ime());
    }

    public boolean isBottomSheetCollapsed() {
        return bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED;
    }

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
            new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View view, int state) {
                    PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");

                    setContentDrawn(state != BottomSheetBehavior.STATE_EXPANDED);

                    syncMiniPlayerClearance(true);

                    switch (state) {
                        case BottomSheetBehavior.STATE_HIDDEN:
                            resetMusicSession();
                            break;
                        case BottomSheetBehavior.STATE_COLLAPSED:
                            /*
                             * Dragging the mini player down is how playback is
                             * stopped, so hiding is armed again the moment the
                             * sheet is small enough for that to be the gesture
                             * the user meant.
                             */
                            bottomSheetBehavior.setHideable(true);

                            if (playerBottomSheetFragment != null)
                                playerBottomSheetFragment.goBackToFirstPage();
                            break;
                        case BottomSheetBehavior.STATE_EXPANDED:
                            /*
                             * A flick down from the full player must not throw
                             * the queue away however hard it is thrown; the most
                             * it can do is collapse the sheet.
                             */
                            bottomSheetBehavior.setHideable(false);
                            break;
                        case BottomSheetBehavior.STATE_SETTLING:
                        case BottomSheetBehavior.STATE_DRAGGING:
                        case BottomSheetBehavior.STATE_HALF_EXPANDED:
                            break;
                    }

                    syncBottomSheetDecor();
                }

                @Override
                public void onSlide(@NonNull View view, float slideOffset) {
                    // Above zero the sheet is rising past the mini player; below it, being dismissed.
                    if (slideOffset > 0) hideKeyboard();

                    animateBottomSheet(slideOffset);
                    animateBottomNavigation(slideOffset);
                }
            };

    /**
     * Stops drawing the screen underneath once the player covers it.
     * <p>
     * The player paints an opaque background over the nav host, which carries
     * the home feed and its artwork, and every frame of every animation in the
     * player was compositing that hidden feed first. The container is only made
     * invisible, so the fragments inside it keep their state and scroll
     * position; it is drawn again the instant the sheet starts moving, which is
     * always a change of state away from expanded.
     */
    private void setContentDrawn(boolean isDrawn) {
        if (bind == null) return;

        bind.navHostFragment.setVisibility(isDrawn ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Puts what the sheet's position decides - the mini player, the navigation
     * bar, the screen underneath - where its settled state says they belong.
     * <p>
     * All three used to be moved only from onSlide, frame by frame. The sheet
     * does not report a slide when it is placed without moving: after the
     * activity is recreated (a rotation, a theme change, the app coming back
     * from being killed) it is put straight back expanded, over a player view
     * inflated fresh with the mini player showing - which then sat across the
     * top of the full player until the sheet was next dragged. The same went
     * for a sheet opened before the player's view existed.
     */
    public void syncBottomSheetDecor() {
        switch (bottomSheetBehavior.getState()) {
            case BottomSheetBehavior.STATE_EXPANDED:
                animateBottomSheet(1f);
                animateBottomNavigation(1f);
                setContentDrawn(false);
                break;
            case BottomSheetBehavior.STATE_COLLAPSED:
                animateBottomSheet(0f);
                animateBottomNavigation(0f);
                setContentDrawn(true);
                break;
        }
    }

    private void animateBottomSheet(float slideOffset) {
        PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
        View header = playerBottomSheetFragment != null ? playerBottomSheetFragment.getPlayerHeader() : null;

        // No view yet: the fragment syncs itself once it has one.
        if (header == null) return;

        float condensedSlideOffset = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.2f)) / 0.2f;
        header.setAlpha(1 - condensedSlideOffset);
        header.setVisibility(condensedSlideOffset > 0.99 ? View.GONE : View.VISIBLE);
    }

    /*
     * The height used to be threaded through a field on the callback that was
     * never assigned — the method only ever reassigned its own parameter — so
     * the "cache" was dead code. It is read straight from the view instead,
     * and the offset maths simplifies to height * slideOffset.
     */
    private void animateBottomNavigation(float slideOffset) {
        if (slideOffset < 0 || bind == null) return;

        bind.bottomNavigation.setTranslationY(bind.bottomNavigation.getHeight() * slideOffset);
    }

    private void initNavigation() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        navHostFragment = (NavHostFragment) fragmentManager.findFragmentById(R.id.nav_host_fragment);

        miniPlayerClearance = new MiniPlayerClearance(getResources());
        syncMiniPlayerClearance(false);
        Objects.requireNonNull(navHostFragment).getChildFragmentManager()
                .registerFragmentLifecycleCallbacks(miniPlayerClearance.getFragmentCallbacks(), true);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        /*
         * In questo modo intercetto il cambio schermata tramite navbar e se il bottom sheet è aperto,
         * lo chiudo
         */
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED && (
                    destination.getId() == R.id.homeFragment ||
                            destination.getId() == R.id.libraryFragment ||
                            destination.getId() == R.id.searchFragment ||
                            destination.getId() == R.id.downloadFragment)
            ) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });

        NavigationUI.setupWithNavController(bottomNavigationView, navController);

        /*
         * The same navigation NavigationUI installs, plus the keyboard for
         * Search: the tab is a way to start typing, so it opens with the field
         * ready for it. Also called for a tap on the tab already showing.
         */
        bottomNavigationView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.searchFragment) {
                new ViewModelProvider(this).get(SearchViewModel.class).requestKeyboard();
            }
            return NavigationUI.onNavDestinationSelected(item, navController);
        });
    }

    public void setBottomNavigationBarVisibility(boolean visibility) {
        if (visibility) {
            bottomNavigationView.setVisibility(View.VISIBLE);
        } else {
            bottomNavigationView.setVisibility(View.GONE);
        }
    }

    private void initService() {
        MediaManager.check(getMediaBrowserListenableFuture());

        getMediaBrowserListenableFuture().addListener(() -> {
            try {
                getMediaBrowserListenableFuture().get().addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
                            setBottomSheetInPeek(true);
                        }
                    }
                });
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void goToLogin() {
        hideBottomSheet();
        setBottomNavigationBarVisibility(false);
        setBottomSheetVisibility(false);

        if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.landingFragment) {
            navController.navigate(R.id.action_landingFragment_to_loginFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.settingsFragment) {
            navController.navigate(R.id.action_settingsFragment_to_loginFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.homeFragment) {
            navController.navigate(R.id.action_homeFragment_to_loginFragment);
        }
    }

    private void goToHome() {
        bottomNavigationView.setVisibility(View.VISIBLE);

        if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.landingFragment) {
            navController.navigate(R.id.action_landingFragment_to_homeFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.loginFragment) {
            navController.navigate(R.id.action_loginFragment_to_homeFragment);
        }
    }

    public void goFromLogin() {
        setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        goToHome();
    }

    public void quit() {
        resetUserSession();
        resetMusicSession();
        resetViewModel();
        goToLogin();
    }

    private void resetUserSession() {
        Preferences.setServerId(null);
        Preferences.setSalt(null);
        Preferences.setToken(null);
        Preferences.setPassword(null);
        Preferences.setServer(null);
        Preferences.setLocalAddress(null);
        Preferences.setInUseServerAddress(null);
        Preferences.setUser(null);

        // TODO Enter all settings to be reset
        Preferences.clearOpenSubsonicExtensions();
        Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_100);
        Preferences.setSkipSilenceMode(false);
        Preferences.setDataSavingMode(false);
        Preferences.setLikedTracksCacheEnabled(false);
    }

    private void resetMusicSession() {
        MediaManager.reset(getMediaBrowserListenableFuture());
    }

    private void hideMusicSession() {
        MediaManager.hide(getMediaBrowserListenableFuture());
    }

    private void resetViewModel() {
        this.getViewModelStore().clear();
    }

    // CONNECTION
    private void connectivityStatusReceiverManager(boolean isActive) {
        if (isActive) {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectivityStatusBroadcastReceiver, filter);
        } else {
            unregisterReceiver(connectivityStatusBroadcastReceiver);
        }
    }

    /*
     * First which address - the local one if the server has one and it answers
     * - then the ping itself, on that address. The ping's failure is now only
     * ever "the server is unreachable": choosing between addresses is
     * ServerAddress's job, which also does it while the app is closed.
     */
    private void pingServer() {
        if (Preferences.getToken() == null) return;

        ServerAddress.check(changed -> {
            if (isFinishing() || isDestroyed()) return;

            mainViewModel.ping().observe(this, subsonicResponse -> {
                if (subsonicResponse == null) {
                    if (Preferences.showServerUnreachableDialog()) {
                        ServerUnreachableDialog dialog = new ServerUnreachableDialog();
                        dialog.show(getSupportFragmentManager(), null);
                    }
                } else {
                    onServerReached(subsonicResponse);
                }
            });
        });
    }

    /**
     * Records what the server that just answered a ping can do.
     * <p>
     * The extension list used to be fetched once from {@code onCreate}, before
     * the ping that decides whether the server speaks OpenSubsonic at all had
     * come back - and it was never re-fetched, so signing into a second server
     * ran the whole session on the first one's capabilities. Reading it off the
     * ping keeps the two answers together and refreshes them on every resume.
     */
    private void onServerReached(SubsonicResponse subsonicResponse) {
        boolean isOpenSubsonic = subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic();

        Preferences.setOpenSubsonic(isOpenSubsonic);

        if (isOpenSubsonic) {
            getOpenSubsonicExtensions();
        } else {
            Preferences.clearOpenSubsonicExtensions();
        }

        /*
         * Likes set or taken off on another device only reach the liked tracks
         * cache through the server, so it is brought into line whenever the
         * server is known to be there to ask.
         */
        LikedTracksCache.sync(this);
    }

    private void getOpenSubsonicExtensions() {
        if (Preferences.getToken() != null) {
            mainViewModel.getOpenSubsonicExtensions().observe(this, openSubsonicExtensions -> {
                if (openSubsonicExtensions != null) {
                    Preferences.setOpenSubsonicExtensions(openSubsonicExtensions);
                }
            });
        }
    }

    /*
     * Looks at Platter's own GitHub releases and, when a newer one exists,
     * offers it. "Later" silences the offer for a day; Settings > Check for
     * updates asks again whenever it is tapped.
     */
    private void checkUpdate() {
        if (!Preferences.isUpdateReminderDue()) return;

        AppUpdater.check((info, error) -> {
            if (info == null || isFinishing() || isDestroyed()) return;

            FragmentManager fragmentManager = getSupportFragmentManager();
            if (fragmentManager.isStateSaved() || fragmentManager.findFragmentByTag(UPDATE_DIALOG_TAG) != null) return;

            new UpdateDialog().show(fragmentManager, UPDATE_DIALOG_TAG);
        });
    }

    /*
     * NetworkInfo.getType() has been deprecated since API 28 and reports the
     * VPN transport rather than the underlying one when a VPN is up, so a
     * Wi-Fi connection behind a VPN used to trigger the alert. MusicUtil
     * already reads the transport through NetworkCapabilities; this matches it.
     */
    private void checkConnectionType() {
        if (!Preferences.isWifiOnly()) return;

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager == null) return;

        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = network != null ? connectivityManager.getNetworkCapabilities(network) : null;

        // Nothing connected at all is not "you are about to spend mobile data",
        // so the alert stays tied to an active non Wi-Fi transport.
        if (capabilities == null) return;

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            ConnectionAlertDialog dialog = new ConnectionAlertDialog();
            dialog.show(getSupportFragmentManager(), null);
        }
    }
}