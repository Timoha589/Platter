package com.cappielloantonio.tempo.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.fragment.app.DialogFragment;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.DialogDownloadDirectoryBinding;
import com.cappielloantonio.tempo.interfaces.DialogClickCallback;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

@OptIn(markerClass = UnstableApi.class)
public class DownloadDirectoryDialog extends DialogFragment {
    private final DialogClickCallback dialogClickCallback;

    /*
     * For the system, which rebuilds a dialog left open across a rotation or
     * a process restart through the empty constructor - there was none, and
     * the app crashed. The callback does not survive that; the dialog still
     * does its own work, and only the caller's follow-up is skipped.
     */
    public DownloadDirectoryDialog() {
        this(null);
    }

    public DownloadDirectoryDialog(DialogClickCallback dialogClickCallback) {
        this.dialogClickCallback = dialogClickCallback;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        DialogDownloadDirectoryBinding bind = DialogDownloadDirectoryBinding.inflate(getLayoutInflater());

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(bind.getRoot())
                .setTitle(R.string.download_directory_dialog_title)
                .setPositiveButton(R.string.download_directory_dialog_positive_button, null)
                .setNegativeButton(R.string.download_directory_dialog_negative_button, null)
                .create();
    }

    @Override
    public void onResume() {
        super.onResume();
        setButtonAction();
    }

    private void setButtonAction() {
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) getDialog();

        if (dialog != null) {
            Button positiveButton = dialog.getButton(Dialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                if (dialogClickCallback != null) dialogClickCallback.onPositiveClick();
                dialog.dismiss();
            });

            Button negativeButton = dialog.getButton(Dialog.BUTTON_NEGATIVE);
            negativeButton.setOnClickListener(v -> {
                if (dialogClickCallback != null) dialogClickCallback.onNegativeClick();
                dialog.dismiss();
            });
        }
    }
}
