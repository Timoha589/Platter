package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * What the full player's bottom right button opens: add the track to a playlist, download it, dislike it, shape the
 * sound, or read about the track. The player it is opened from does the work - its snackbars and its track - so the
 * sheet only shows the rows and says which was chosen. Rows that only music has are left out for an episode or a
 * station.
 */
class PlayerMoreBottomSheetDialog : BottomSheetDialogFragment() {
    enum class Action { ADD_TO_PLAYLIST, DOWNLOAD, DISLIKE, EQUALIZER, TRACK_INFO }

    /** The player, as the sheet needs it. */
    interface Host {
        fun moreTitle(): CharSequence?
        fun moreArtist(): CharSequence?

        /** Music can be filed, downloaded and disliked; an episode or a station cannot. */
        fun moreIsMusic(): Boolean
        fun moreIsDownloaded(): Boolean
        fun moreIsDisliked(): Boolean
        fun onMoreAction(action: Action)
    }

    private val host: Host?
        get() = parentFragment as? Host

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.bottom_sheet_player_more, container, false)
        val host = host ?: return view.also { dismissAllowingStateLoss() }

        view.findViewById<TextView>(R.id.player_more_title).apply {
            text = host.moreTitle()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.player_more_artist).apply {
            text = host.moreArtist()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        val music = host.moreIsMusic()
        row(view, R.id.player_more_add_to_playlist, Action.ADD_TO_PLAYLIST, music)
        row(view, R.id.player_more_download, Action.DOWNLOAD, music).setText(
            if (host.moreIsDownloaded()) R.string.player_more_remove_download else R.string.player_more_download
        )
        row(view, R.id.player_more_dislike, Action.DISLIKE, music).setText(
            if (host.moreIsDisliked()) R.string.player_more_remove_dislike else R.string.player_more_dislike
        )
        row(view, R.id.player_more_equalizer, Action.EQUALIZER, true)
        row(view, R.id.player_more_track_info, Action.TRACK_INFO, true)

        return view
    }

    private fun row(view: View, id: Int, action: Action, shown: Boolean): TextView =
        view.findViewById<TextView>(id).apply {
            visibility = if (shown) View.VISIBLE else View.GONE
            setOnClickListener {
                dismiss()
                host?.onMoreAction(action)
            }
        }

    companion object {
        private const val TAG = "PlayerMoreBottomSheet"

        /** Opened as a child of the player, which is then its [Host]. */
        @JvmStatic
        fun <T> show(player: T) where T : Fragment, T : Host {
            val manager = player.childFragmentManager
            if (manager.findFragmentByTag(TAG) == null) PlayerMoreBottomSheetDialog().show(manager, TAG)
        }
    }
}
