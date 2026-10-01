package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentLoginBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.ServerAdapter;
import com.cappielloantonio.tempo.ui.dialog.ServerSignupDialog;
import com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog.PremiumLoginBottomSheetDialog;
import com.cappielloantonio.tempo.util.PremiumServer;
import com.cappielloantonio.tempo.util.ServerSignIn;
import com.cappielloantonio.tempo.viewmodel.LoginViewModel;

import java.util.List;

@UnstableApi
public class LoginFragment extends Fragment implements ClickCallback {
    private static final String TAG = "LoginFragment";

    private FragmentLoginBinding bind;
    private MainActivity activity;
    private LoginViewModel loginViewModel;

    private ServerAdapter serverAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        loginViewModel = new ViewModelProvider(requireActivity()).get(LoginViewModel.class);
        bind = FragmentLoginBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        initAppBar();
        initPremium();
        initServerListView();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.toolbar);

        // The page's name moves into the toolbar once the one in the header has scrolled away.
        bind.loginScrollView.setOnScrollChangeListener((View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateToolbarTitle(scrollY));
        updateToolbarTitle(0);
    }

    private void updateToolbarTitle(int scrollY) {
        if (bind == null || activity.getSupportActionBar() == null) return;
        boolean gone = scrollY > 0 && scrollY >= bind.loginTitleTextView.getBottom();
        // Through the action bar, not the toolbar: otherwise it puts the app's name back.
        activity.getSupportActionBar().setTitle(gone ? getString(R.string.login_title) : "");
    }

    private void initPremium() {
        View.OnClickListener open = v -> new PremiumLoginBottomSheetDialog()
                .show(activity.getSupportFragmentManager(), PremiumLoginBottomSheetDialog.TAG);

        bind.loginPremium.getRoot().setOnClickListener(open);
        bind.loginPremium.premiumLoginButton.setOnClickListener(open);
    }

    private void initServerListView() {
        bind.serverListRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        serverAdapter = new ServerAdapter(this);
        bind.serverListRecyclerView.setAdapter(serverAdapter);
        loginViewModel.getServerList().observe(getViewLifecycleOwner(), servers -> {
            if (bind == null) return;

            boolean empty = servers.isEmpty();
            bind.noServerAddedTextView.setVisibility(empty ? View.VISIBLE : View.GONE);
            bind.serverListRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
            serverAdapter.setItems(servers);

            // Once Timoha Premium is among the servers, it is signed in to from the list like the rest.
            bind.loginPremium.getRoot().setVisibility(hasPremium(servers) ? View.GONE : View.VISIBLE);
        });

        bind.addServerButton.setOnClickListener(v -> new ServerSignupDialog().show(activity.getSupportFragmentManager(), null));
    }

    private static boolean hasPremium(List<Server> servers) {
        for (Server server : servers) {
            if (PremiumServer.isPremium(server.getAddress())) return true;
        }
        return false;
    }

    @Override
    public void onServerClick(Bundle bundle) {
        Server server = bundle.getParcelable("server_object");
        if (server == null || serverAdapter.isSigningIn()) return;

        serverAdapter.setSigningIn(server.getServerId());
        ServerSignIn.signIn(server, new ServerSignIn.Callback() {
            @Override
            public void onSignedIn() {
                if (!isAdded()) return;
                activity.goFromLogin();
            }

            @Override
            public void onFailed(@NonNull Exception exception) {
                if (!isAdded()) return;
                serverAdapter.setSigningIn(null);
                Toast.makeText(requireContext(), exception.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onServerLongClick(Bundle bundle) {
        ServerSignupDialog dialog = new ServerSignupDialog();
        dialog.setArguments(bundle);
        dialog.show(activity.getSupportFragmentManager(), null);
    }
}
