package com.cappielloantonio.tempo.ui.fragment;

import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;

import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentSettingsBinding;
import com.cappielloantonio.tempo.deemix.DeemixClient;
import com.cappielloantonio.tempo.deemix.DeemixQuota;
import com.cappielloantonio.tempo.deemix.DeemixRequest;
import com.cappielloantonio.tempo.deemix.DeemixResult;
import com.cappielloantonio.tempo.interfaces.DialogClickCallback;
import com.cappielloantonio.tempo.interfaces.ScanCallback;
import com.cappielloantonio.tempo.service.SmartDownloads;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.dialog.DeleteDownloadStorageDialog;
import com.cappielloantonio.tempo.ui.dialog.DownloadStorageDialog;
import com.cappielloantonio.tempo.ui.dialog.HomeRearrangementDialog;
import com.cappielloantonio.tempo.ui.dialog.StreamingCacheStorageDialog;
import com.cappielloantonio.tempo.ui.dialog.UpdateDialog;
import com.cappielloantonio.tempo.update.AppUpdater;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.PremiumServer;
import com.cappielloantonio.tempo.util.UIUtil;
import com.cappielloantonio.tempo.viewmodel.SettingViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String TAG = "SettingsFragment";

    private MainActivity activity;
    private SettingViewModel settingViewModel;
    private FragmentSettingsBinding bind;

    private ActivityResultLauncher<Intent> someActivityResultLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        someActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentSettingsBinding.inflate(inflater, container, false);
        settingViewModel = new ViewModelProvider(requireActivity()).get(SettingViewModel.class);

        /*
         * PreferenceFragmentCompat builds the list itself, from a context
         * carrying the preference theme overlay, so it is hosted inside the
         * page rather than being the page - which is what leaves room for the
         * toolbar above it.
         */
        View list = super.onCreateView(inflater, bind.settingsContainer, savedInstanceState);

        if (list != null) {
            bind.settingsContainer.addView(list, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        initToolbar();
        initListView();

        return bind.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initToolbar() {
        bind.toolbar.setNavigationOnClickListener(view -> activity.navController.navigateUp());
    }

    private void initListView() {
        /*
         * DESIGN.md gets its boundaries from surface colour, not rules, and the
         * rows already sit on their own card - the stock divider would draw a
         * line across it.
         */
        setDivider(null);
        setDividerHeight(0);

        /*
         * The bottom bar and the mini player are both hidden on this screen, so
         * the app-wide 142dp reservation for them would just be dead canvas
         * under the last card.
         */
        getListView().setClipToPadding(false);
        getListView().setPadding(0, 0, 0,
                (int) getResources().getDimension(R.dimen.settings_list_padding_bottom));
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(false);
        activity.setBottomSheetVisibility(false);
    }

    @Override
    public void onResume() {
        super.onResume();

        checkEqualizer();
        checkCacheStorage();
        checkStorage();

        setStreamingCacheSize();
        setAppLanguage();
        setVersion();

        actionLogout();
        actionScan();
        actionRearrangeHome();
        actionChangeStreamingCacheStorage();
        actionChangeDownloadStorage();
        actionDeleteDownloadStorage();
        actionKeepScreenOn();
        actionDeemix();
        actionCheckUpdate();
    }

    @Override
    public void onStop() {
        super.onStop();
        activity.setBottomSheetVisibility(true);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.global_preferences, rootKey);

        setSmartDownloadCacheSizes();

        EditTextPreference deemixPassword = findPreference("deemix_password");
        if (deemixPassword != null) {
            deemixPassword.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        }

        /*
         * Which rows a category ends up showing decides which of them round off
         * at the top and bottom of its card, so the three environment checks
         * have to have run before the cards are shaped. They are idempotent and
         * run again in onResume, where they also refresh their summaries.
         */
        checkEqualizer();
        checkCacheStorage();
        checkStorage();

        // My Wave runs next to Timoha Premium only; its address means nothing elsewhere.
        Preference waveUrl = findPreference("wave_url");
        if (waveUrl != null) waveUrl.setVisible(PremiumServer.isInUse());

        applyCardLayouts();
    }

    /**
     * Turns each category into a single graphite card: the rows of a group are
     * given the top / middle / bottom face of one card, and a lone row gets the
     * fully rounded one. Preferences that are only explanatory - no title, not
     * selectable - are lifted out of the card and set as a paragraph under the
     * heading, so a card holds only what can actually be touched.
     */
    private void applyCardLayouts() {
        PreferenceScreen screen = getPreferenceScreen();

        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference child = screen.getPreference(index);

            if (!(child instanceof PreferenceGroup)) continue;

            PreferenceGroup group = (PreferenceGroup) child;
            List<Preference> rows = new ArrayList<>();
            boolean afterCard = false;

            for (int position = 0; position < group.getPreferenceCount(); position++) {
                Preference preference = group.getPreference(position);

                if (!preference.isVisible()) continue;

                if (isNote(preference)) {
                    preference.setLayoutResource(afterCard
                            ? R.layout.preference_platter_note_footer
                            : R.layout.preference_platter_note);
                } else {
                    afterCard = true;

                    if (!(preference instanceof SeekBarPreference)) rows.add(preference);
                }
            }

            for (int position = 0; position < rows.size(); position++) {
                boolean first = position == 0;
                boolean last = position == rows.size() - 1;

                rows.get(position).setLayoutResource(first && last
                        ? R.layout.preference_platter_single
                        : first
                        ? R.layout.preference_platter_top
                        : last
                        ? R.layout.preference_platter_bottom
                        : R.layout.preference_platter_middle);
            }
        }
    }

    private boolean isNote(Preference preference) {
        return !preference.isSelectable() && preference.getTitle() == null;
    }

    private void checkEqualizer() {
        Preference equalizer = findPreference("equalizer");

        if (equalizer == null) return;

        Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);

        if ((intent.resolveActivity(requireActivity().getPackageManager()) != null)) {
            equalizer.setOnPreferenceClickListener(preference -> {
                someActivityResultLauncher.launch(intent);
                return true;
            });
        } else {
            equalizer.setVisible(false);
        }
    }

    private void checkCacheStorage() {
        Preference storage = findPreference("streaming_cache_storage");

        if (storage == null) return;

        try {
            if (requireContext().getExternalFilesDirs(null)[1] == null) {
                storage.setVisible(false);
            } else {
                storage.setSummary(Preferences.getStreamingCacheStoragePreference() == 0 ? R.string.streaming_cache_storage_internal_dialog_negative_button : R.string.streaming_cache_storage_external_dialog_positive_button);
            }
        } catch (Exception exception) {
            storage.setVisible(false);
        }
    }

    private void checkStorage() {
        Preference storage = findPreference("download_storage");

        if (storage == null) return;

        try {
            if (requireContext().getExternalFilesDirs(null)[1] == null) {
                storage.setVisible(false);
            } else {
                storage.setSummary(Preferences.getDownloadStoragePreference() == 0 ? R.string.download_storage_internal_dialog_negative_button : R.string.download_storage_external_dialog_positive_button);
            }
        } catch (Exception exception) {
            storage.setVisible(false);
        }
    }

    private void setStreamingCacheSize() {
        ListPreference streamingCachePreference = findPreference("streaming_cache_size");

        if (streamingCachePreference != null) {
            streamingCachePreference.setSummaryProvider(new Preference.SummaryProvider<ListPreference>() {
                @Nullable
                @Override
                public CharSequence provideSummary(@NonNull ListPreference preference) {
                    CharSequence entry = preference.getEntry();

                    if (entry == null) return null;

                    long currentSizeMb = DownloadUtil.getStreamingCacheSize(requireActivity()) / (1024 * 1024);

                    return getString(R.string.settings_summary_streaming_cache_size, entry, String.valueOf(currentSizeMb));
                }
            });
        }
    }

    private void setAppLanguage() {
        ListPreference localePref = findPreference("language");

        if (localePref == null) return;

        Map<String, String> locales = UIUtil.getLangPreferenceDropdownEntries(requireContext());

        if (locales.isEmpty()) {
            localePref.setVisible(false);
            return;
        }

        CharSequence[] entries = locales.keySet().toArray(new CharSequence[0]);
        CharSequence[] entryValues = locales.values().toArray(new CharSequence[0]);

        localePref.setEntries(entries);
        localePref.setEntryValues(entryValues);
        localePref.setDefaultValue(entryValues[0]);

        /*
         * The stored value starts out as the placeholder "default" from
         * global_preferences.xml, which is not a language tag: feeding it to
         * Locale.forLanguageTag() produced an undetermined locale and left the
         * row with a blank summary. The language actually in force is the one
         * AppCompat is applying, with the system default behind it.
         */
        localePref.setSummary(currentLanguageName());

        localePref.setOnPreferenceChangeListener((preference, newValue) -> {
            LocaleListCompat appLocale = LocaleListCompat.forLanguageTags((String) newValue);
            AppCompatDelegate.setApplicationLocales(appLocale);
            preference.setSummary(Locale.forLanguageTag((String) newValue).getDisplayLanguage());
            return true;
        });
    }

    private String currentLanguageName() {
        LocaleListCompat applied = AppCompatDelegate.getApplicationLocales();
        Locale locale = applied.isEmpty() ? Locale.getDefault() : applied.get(0);

        return locale != null ? locale.getDisplayLanguage() : "";
    }

    private void setVersion() {
        findPreference("version").setSummary(BuildConfig.VERSION_NAME);
    }

    /**
     * Asks GitHub for a newer release on tap. The answer goes in the row's
     * summary; when there is one, the update dialog opens as well.
     */
    private void actionCheckUpdate() {
        Preference update = findPreference("update");
        if (update == null) return;

        update.setOnPreferenceClickListener(preference -> {
            preference.setSummary(R.string.settings_update_checking);

            AppUpdater.check((info, error) -> {
                // The screen may have been left while GitHub was answering.
                if (bind == null) return;

                if (error != null) {
                    preference.setSummary(getString(R.string.settings_update_error, error));
                } else if (info == null) {
                    preference.setSummary(getString(R.string.settings_update_current, BuildConfig.VERSION_NAME));
                } else {
                    preference.setSummary(getString(R.string.settings_update_available, info.getVersion()));
                    new UpdateDialog().show(getChildFragmentManager(), "update_dialog");
                }
            });
            return true;
        });
    }

    /**
     * The Deemix plus account and what is left of today's quota, asked for on
     * every visit - the quota moves with every download, from here or the site.
     * Tapping the row, or changing the address or password, signs in afresh.
     */
    private void actionDeemix() {
        Preference account = findPreference("deemix_account");
        if (account == null) return;

        account.setOnPreferenceClickListener(preference -> {
            DeemixClient.retry();
            refreshDeemixAccount();
            return true;
        });

        Preference.OnPreferenceChangeListener resignIn = (preference, value) -> {
            // The listener runs before the value is stored; read it once it is.
            new Handler(Looper.getMainLooper()).post(() -> {
                DeemixClient.retry();
                refreshDeemixAccount();
            });
            return true;
        };
        findPreference("deemix_url").setOnPreferenceChangeListener(resignIn);
        findPreference("deemix_password").setOnPreferenceChangeListener(resignIn);

        refreshDeemixAccount();
    }

    private DeemixRequest deemixRequest;

    private void refreshDeemixAccount() {
        Preference account = findPreference("deemix_account");
        if (account == null) return;

        if (deemixRequest != null) deemixRequest.cancel();
        account.setSummary(R.string.settings_deemix_account_loading);

        deemixRequest = DeemixClient.usage(result -> {
            if (!isAdded()) return;

            if (result.isOk()) {
                kotlin.Pair<String, String> who = DeemixClient.account();
                String name = who != null ? who.getFirst() : Preferences.getUser();
                String role = DeemixQuota.role(requireContext(), who != null ? who.getSecond() : null);
                account.setSummary(getString(R.string.settings_deemix_account_summary, name, role,
                        DeemixQuota.describe(requireContext(), result.getValue())));
                return;
            }

            DeemixResult.Failure failure = result.getFailure();
            if (failure == DeemixResult.Failure.NO_ACCOUNT) {
                account.setSummary(getString(R.string.settings_deemix_account_rejected, Preferences.getUser()));
            } else if (failure == DeemixResult.Failure.UNREACHABLE) {
                java.util.List<String> tried = DeemixClient.candidates();
                account.setSummary(getString(R.string.settings_deemix_account_unreachable,
                        tried.isEmpty() ? "—" : android.text.TextUtils.join(", ", tried)));
            } else {
                account.setSummary(getString(R.string.settings_deemix_account_error, String.valueOf(result.getMessage())));
            }
        });
    }

    private void actionLogout() {
        findPreference("logout").setOnPreferenceClickListener(preference -> {
            activity.quit();
            return true;
        });
    }

    private void actionScan() {
        findPreference("scan_library").setOnPreferenceClickListener(preference -> {
            settingViewModel.launchScan(new ScanCallback() {
                @Override
                public void onError(Exception exception) {
                    findPreference("scan_library").setSummary(exception.getMessage());
                }

                @Override
                public void onSuccess(boolean isScanning, long count) {
                    findPreference("scan_library").setSummary(getString(R.string.settings_scan_progress, count));
                    if (isScanning) getScanStatus();
                }
            });

            return true;
        });
    }

    /**
     * Which sections Home shows, and in what order. This used to be a button
     * that appeared at the foot of Home five seconds after the tab opened -
     * leaving the tab before that, or any rebuild of the view, meant it simply
     * was not there. It is a preference, so it lives in the preferences.
     */
    private void actionRearrangeHome() {
        Preference rearrangement = findPreference("home_rearrangement");

        if (rearrangement == null) return;

        rearrangement.setOnPreferenceClickListener(preference -> {
            HomeRearrangementDialog dialog = new HomeRearrangementDialog();
            dialog.show(requireActivity().getSupportFragmentManager(), null);
            return true;
        });
    }

    /**
     * The smart download cache is sized in tracks, so its choices are worded
     * with the same plurals as every other track count, which an array
     * resource cannot do. A smaller size applies at once, not at the next
     * track played.
     */
    private void setSmartDownloadCacheSizes() {
        ListPreference cacheSize = findPreference("smart_download_cache_size");

        if (cacheSize == null) return;

        CharSequence[] values = cacheSize.getEntryValues();
        CharSequence[] entries = new CharSequence[values.length];

        for (int index = 0; index < values.length; index++) {
            int count = Integer.parseInt(values[index].toString());
            entries[index] = getResources().getQuantityString(R.plurals.download_track_count, count, count);
        }

        cacheSize.setEntries(entries);

        android.content.Context app = requireContext().getApplicationContext();

        cacheSize.setOnPreferenceChangeListener((preference, newValue) -> {
            // The new size is only stored once this returns, so the trim waits a turn.
            new Handler(Looper.getMainLooper()).post(() -> SmartDownloads.trim(app));
            return true;
        });
    }

    private void actionChangeStreamingCacheStorage() {
        findPreference("streaming_cache_storage").setOnPreferenceClickListener(preference -> {
            StreamingCacheStorageDialog dialog = new StreamingCacheStorageDialog(new DialogClickCallback() {
                @Override
                public void onPositiveClick() {
                    findPreference("streaming_cache_storage").setSummary(R.string.streaming_cache_storage_external_dialog_positive_button);
                }

                @Override
                public void onNegativeClick() {
                    findPreference("streaming_cache_storage").setSummary(R.string.streaming_cache_storage_internal_dialog_negative_button);
                }
            });
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionChangeDownloadStorage() {
        findPreference("download_storage").setOnPreferenceClickListener(preference -> {
            DownloadStorageDialog dialog = new DownloadStorageDialog(new DialogClickCallback() {
                @Override
                public void onPositiveClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_external_dialog_positive_button);
                }

                @Override
                public void onNegativeClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_internal_dialog_negative_button);
                }
            });
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionDeleteDownloadStorage() {
        findPreference("delete_download_storage").setOnPreferenceClickListener(preference -> {
            DeleteDownloadStorageDialog dialog = new DeleteDownloadStorageDialog();
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void getScanStatus() {
        settingViewModel.getScanStatus(new ScanCallback() {
            @Override
            public void onError(Exception exception) {
                findPreference("scan_library").setSummary(exception.getMessage());
            }

            @Override
            public void onSuccess(boolean isScanning, long count) {
                findPreference("scan_library").setSummary(getString(R.string.settings_scan_progress, count));
                if (isScanning) getScanStatus();
            }
        });
    }

    private void actionKeepScreenOn() {
        findPreference("always_on_display").setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean) {
                if ((Boolean) newValue) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
            return true;
        });
    }
}
