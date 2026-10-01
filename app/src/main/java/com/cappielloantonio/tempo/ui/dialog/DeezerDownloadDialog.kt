package com.cappielloantonio.tempo.ui.dialog

import android.content.Context
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.deemix.DeemixQuota
import com.cappielloantonio.tempo.viewmodel.DeezerHit
import com.cappielloantonio.tempo.viewmodel.DeezerSearchViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DeezerDownloadDialog {
    /**
     * A track costs one download and goes straight to the queue. An album or a
     * whole artist can spend much of the day's quota in one tap, so those ask
     * first, with what is left in view.
     */
    @JvmStatic
    fun confirm(context: Context, downloads: DeezerSearchViewModel, hit: DeezerHit) {
        if (hit.kind == DeezerHit.Kind.TRACK) {
            downloads.download(hit)
            return
        }

        val title = if (hit.kind == DeezerHit.Kind.ALBUM) {
            context.getString(R.string.deezer_download_album_title, hit.title)
        } else {
            context.getString(R.string.deezer_download_artist_title, hit.title)
        }
        val quota = DeemixQuota.describe(context, downloads.getUsage().value)

        MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(context.getString(R.string.deezer_download_many_message, quota).trim())
                .setNegativeButton(R.string.deezer_download_cancel, null)
                .setPositiveButton(R.string.deezer_download_confirm) { _, _ -> downloads.download(hit) }
                .show()
    }
}
