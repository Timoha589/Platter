package com.cappielloantonio.tempo.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.DialogUpdateBinding;
import com.cappielloantonio.tempo.update.AppUpdater;
import com.cappielloantonio.tempo.update.UpdateInfo;
import com.cappielloantonio.tempo.update.UpdatePhase;
import com.cappielloantonio.tempo.update.UpdateState;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Offers the release AppUpdater found and shows the download as it happens.
 * Closing it does not stop the download; the system's own install prompt takes
 * over when it finishes.
 */
public class UpdateDialog extends DialogFragment {
    private DialogUpdateBinding bind;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        bind = DialogUpdateBinding.inflate(getLayoutInflater());

        return new MaterialAlertDialogBuilder(requireActivity())
                .setView(bind.getRoot())
                .setTitle(R.string.update_dialog_title)
                .setPositiveButton(R.string.update_button_install, null)
                .setNegativeButton(R.string.update_button_later, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();

        UpdateInfo info = AppUpdater.getPending();

        // The process was restarted behind the dialog; there is nothing to offer.
        if (info == null) {
            dismissAllowingStateLoss();
            return;
        }

        bind.updateSummary.setText(getString(R.string.update_dialog_summary,
                info.getVersion(), BuildConfig.VERSION_NAME));

        if (!info.getNotes().isEmpty()) {
            bind.updateNotes.setText(info.getNotes());
            bind.updateNotes.setVisibility(View.VISIBLE);
        }

        AppUpdater.getState().observe(this, state -> render(info, state));
    }

    @Override
    public void onResume() {
        super.onResume();

        // Back from "Install unknown apps": carry on without a second tap.
        UpdateInfo info = AppUpdater.getPending();
        UpdateState state = AppUpdater.getState().getValue();

        if (info != null && state != null
                && state.getPhase() == UpdatePhase.NEEDS_PERMISSION
                && AppUpdater.canInstall(requireContext())) {
            AppUpdater.install(requireContext(), info);
        }
    }

    private void render(UpdateInfo info, UpdateState state) {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;

        View install = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        View later = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        boolean working = state.getPhase() == UpdatePhase.DOWNLOADING
                || state.getPhase() == UpdatePhase.INSTALLING;

        install.setEnabled(!working);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(
                state.getPhase() == UpdatePhase.FAILED ? R.string.update_button_retry : R.string.update_button_install);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(
                working ? R.string.update_button_hide : R.string.update_button_later);

        install.setOnClickListener(v -> AppUpdater.install(requireContext(), info));
        later.setOnClickListener(v -> {
            if (!working) Preferences.setUpdateReminder();
            dismiss();
        });

        bind.updateProgress.setVisibility(working ? View.VISIBLE : View.GONE);
        bind.updateStatus.setVisibility(View.VISIBLE);

        switch (state.getPhase()) {
            case DOWNLOADING:
                bind.updateProgress.setIndeterminate(false);
                bind.updateProgress.setProgress(state.getProgress());
                bind.updateStatus.setText(getString(R.string.update_status_downloading,
                        state.getProgress(), Formatter.formatShortFileSize(requireContext(), info.getSize())));
                break;
            case INSTALLING:
                bind.updateProgress.setIndeterminate(true);
                bind.updateStatus.setText(R.string.update_status_installing);
                break;
            case NEEDS_PERMISSION:
                bind.updateStatus.setText(R.string.update_status_permission);
                break;
            case FAILED:
                bind.updateStatus.setText(getString(R.string.update_status_failed, state.getError()));
                break;
            default:
                bind.updateStatus.setVisibility(View.GONE);
        }
    }
}
