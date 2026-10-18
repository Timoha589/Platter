package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ItemHorizontalArtistBinding
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.cappielloantonio.tempo.repository.ArtistRepository
import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The artists of a joint credit, to go to one of them.
 *
 * Each row opens that artist's page. One the server has no page for - it only
 * ever appears inside joint credits - opens a page of its collaborations.
 */
class ArtistPickerBottomSheetDialog : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.bottom_sheet_artist_picker, container, false)
        val list = view.findViewById<LinearLayout>(R.id.artist_picker_list)
        val artists = BundleCompat.getParcelableArrayList(requireArguments(), Constants.ARTISTS_OBJECT, ArtistID3::class.java).orEmpty()

        for (artist in artists) {
            val row = ItemHorizontalArtistBinding.inflate(inflater, list, false)
            row.artistNameTextView.text = artist.name
            row.artistNameTextView.isSelected = true
            row.artistMoreButton.visibility = View.GONE

            val info = when {
                artist.id == null -> getString(R.string.artist_picker_collaborations_only)
                artist.albumCount > 0 -> resources.getQuantityString(R.plurals.album_count, artist.albumCount, artist.albumCount)
                else -> null
            }
            row.artistInfoTextView.text = info
            row.artistInfoTextView.visibility = if (info != null) View.VISIBLE else View.GONE

            CustomGlideRequest.Builder
                .from(requireContext(), artist.coverArtId, CustomGlideRequest.ResourceType.Artist)
                .build()
                .into(row.artistCoverImageView)

            row.root.setOnClickListener { open(artist) }
            list.addView(row.root)
        }

        return view
    }

    private fun open(artist: ArtistID3) {
        val nav = NavHostFragment.findNavController(this)
        // Off the back stack before the page goes on: a dismiss() finishing after it would take the page down too.
        nav.popBackStack()
        goToArtist(nav, artist, activity)
    }

    companion object {
        /**
         * Goes to the artist behind [credit]: straight to the page of [artist]
         * when the credit names one artist, to a choice of them when it names
         * several. [beforeLeaving] runs just before either - to close the menu
         * the tap came from. The player is left open under the choice, so that
         * closing it leaves the listener where they were; it gets out of the
         * way once a page opens.
         */
        @JvmStatic
        fun openCredit(from: Fragment, credit: String?, artist: ArtistID3?, beforeLeaving: Runnable?) {
            val nav = NavHostFragment.findNavController(from)
            val activity = from.activity
            ArtistRepository().resolveCredit(credit) { artists ->
                if (!from.isAdded) return@resolveCredit
                if (artists == null && artist == null) return@resolveCredit
                beforeLeaving?.run()
                if (artists != null) {
                    nav.navigate(R.id.artistPickerBottomSheetDialog, bundleOf(Constants.ARTISTS_OBJECT to ArrayList(artists)))
                } else {
                    goToArtist(nav, artist!!, activity)
                }
            }
        }

        private fun goToArtist(nav: NavController, artist: ArtistID3, activity: FragmentActivity?) {
            nav.navigate(R.id.artistPageFragment, bundleOf(Constants.ARTIST_OBJECT to artist))
            (activity as? MainActivity)?.let { if (it.isBottomSheetExpanded) it.collapseBottomSheetDelayed() }
        }
    }
}
