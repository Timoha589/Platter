package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.BottomSheetPremiumLoginBinding;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.PremiumServer;
import com.cappielloantonio.tempo.util.ServerSignIn;
import com.cappielloantonio.tempo.viewmodel.LoginViewModel;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.UUID;

/**
 * Signs in to Timoha Premium with a username and a password - the address is
 * already known. The server is added to the list only once it has accepted
 * them, so a mistyped password does not leave a broken entry behind.
 */
@UnstableApi
public class PremiumLoginBottomSheetDialog extends BottomSheetDialogFragment {
    public static final String TAG = "PremiumLoginBottomSheetDialog";

    private BottomSheetPremiumLoginBinding bind;
    private LoginViewModel loginViewModel;

    private boolean signingIn = false;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        // Open all the way, so the keyboard never covers the button.
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        bind = BottomSheetPremiumLoginBinding.inflate(inflater, container, false);
        loginViewModel = new ViewModelProvider(requireActivity()).get(LoginViewModel.class);

        bind.premiumSignInButton.setOnClickListener(v -> signIn());
        // An error is about what was typed; typing again answers it.
        TextWatcher clearError = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (bind != null) bind.premiumErrorTextView.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        bind.premiumUsernameEditText.addTextChangedListener(clearError);
        bind.premiumPasswordEditText.addTextChangedListener(clearError);
        bind.premiumPasswordEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            signIn();
            return true;
        });

        return bind.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void signIn() {
        if (signingIn) return;

        String username = text(bind.premiumUsernameEditText.getText()).trim();
        String password = text(bind.premiumPasswordEditText.getText());
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            showError(getString(R.string.premium_login_required));
            return;
        }

        Server server = new Server(UUID.randomUUID().toString(), PremiumServer.NAME, username, password,
                PremiumServer.ADDRESS, null, System.currentTimeMillis(), false);

        setWorking(true);
        ServerSignIn.signIn(server, new ServerSignIn.Callback() {
            @Override
            public void onSignedIn() {
                loginViewModel.addServer(server);
                if (!isAdded()) return;

                MainActivity activity = (MainActivity) requireActivity();
                dismissAllowingStateLoss();
                activity.goFromLogin();
            }

            @Override
            public void onFailed(@NonNull Exception exception) {
                if (bind == null) return;
                setWorking(false);
                showError(ServerSignIn.isWrongCredentials(exception)
                        ? getString(R.string.premium_login_wrong_credentials)
                        : getString(R.string.premium_login_failed, String.valueOf(exception.getMessage())));
            }
        });
    }

    private void setWorking(boolean working) {
        signingIn = working;
        bind.premiumSignInButton.setEnabled(!working);
        bind.premiumSignInButton.setText(working ? R.string.premium_login_signing_in : R.string.premium_server_log_in);
        bind.premiumUsernameEditText.setEnabled(!working);
        bind.premiumPasswordEditText.setEnabled(!working);
        if (working) bind.premiumErrorTextView.setVisibility(View.GONE);
    }

    private void showError(String message) {
        bind.premiumErrorTextView.setText(message);
        bind.premiumErrorTextView.setVisibility(View.VISIBLE);
    }

    private static String text(@Nullable CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
