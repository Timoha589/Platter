package com.cappielloantonio.tempo.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.DialogHomeRearrangementBinding;
import com.cappielloantonio.tempo.ui.adapter.HomeSectorHorizontalAdapter;
import com.cappielloantonio.tempo.util.DragHandleTouch;
import com.cappielloantonio.tempo.viewmodel.HomeRearrangementViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Objects;

public class HomeRearrangementDialog extends DialogFragment {
    private DialogHomeRearrangementBinding bind;
    private HomeRearrangementViewModel homeRearrangementViewModel;
    private HomeSectorHorizontalAdapter homeSectorHorizontalAdapter;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        bind = DialogHomeRearrangementBinding.inflate(getLayoutInflater());

        homeRearrangementViewModel = new ViewModelProvider(requireActivity()).get(HomeRearrangementViewModel.class);

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(bind.getRoot())
                .setTitle(R.string.home_rearrangement_dialog_title)
                .setPositiveButton(R.string.home_rearrangement_dialog_positive_button, (dialog, id) -> { })
                .setNeutralButton(R.string.home_rearrangement_dialog_neutral_button, (dialog, id) -> { })
                .setNegativeButton(R.string.home_rearrangement_dialog_negative_button, (dialog, id) -> dialog.cancel())
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();

        setButtonAction();
        initSectorView();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        homeRearrangementViewModel.closeDialog();
        bind = null;
    }

    private void setButtonAction() {
        androidx.appcompat.app.AlertDialog alertDialog = (androidx.appcompat.app.AlertDialog) Objects.requireNonNull(getDialog());

        alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            homeRearrangementViewModel.saveHomeSectorList(homeSectorHorizontalAdapter.getItems());
            dismiss();
        });

        alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            homeRearrangementViewModel.resetHomeSectorList();
            dismiss();
        });
    }

    private void initSectorView() {
        /*
         * Fifteen rows at wrap_content take the whole dialog and push the
         * button bar - which stacks, being three buttons - past the bottom
         * edge: Reset ends up off screen and Cancel cut in half. Capping the
         * list leaves the bar its room, and the rest of the list scrolls.
         */
        ViewGroup.LayoutParams params = bind.homeSectorItemRecyclerView.getLayoutParams();
        params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        bind.homeSectorItemRecyclerView.setLayoutParams(params);

        bind.homeSectorItemRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.homeSectorItemRecyclerView.setHasFixedSize(true);

        homeSectorHorizontalAdapter = new HomeSectorHorizontalAdapter();
        bind.homeSectorItemRecyclerView.setAdapter(homeSectorHorizontalAdapter);
        homeSectorHorizontalAdapter.setItems(homeRearrangementViewModel.getHomeSectorList());

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            int originalPosition = -1;
            int fromPosition = -1;
            int toPosition = -1;

            /*
             * Rows are picked up by their handle the moment it is touched, as
             * in the play queue; a long press anywhere else would be a second,
             * slower way of doing the same thing.
             */
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                if (originalPosition == -1) originalPosition = viewHolder.getBindingAdapterPosition();

                fromPosition = viewHolder.getBindingAdapterPosition();
                toPosition = target.getBindingAdapterPosition();

                int from = fromPosition;
                int to = toPosition;
                DragHandleTouch.moveKeepingScroll(recyclerView, () -> homeSectorHorizontalAdapter.moveItem(from, to));

                return false;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);

                homeRearrangementViewModel.orderSectorLiveListAfterSwap(homeSectorHorizontalAdapter.getItems());

                originalPosition = -1;
                fromPosition = -1;
                toPosition = -1;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {

            }
        });

        itemTouchHelper.attachToRecyclerView(bind.homeSectorItemRecyclerView);
        homeSectorHorizontalAdapter.setOnStartDrag(itemTouchHelper::startDrag);
    }
}
