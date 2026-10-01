package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentDeezerPageBinding
import com.cappielloantonio.tempo.deemix.DeemixQuota
import com.cappielloantonio.tempo.deemix.DeezerPreviewPlayer
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.ui.adapter.DeezerPageActionsAdapter
import com.cappielloantonio.tempo.ui.adapter.DeezerResultAdapter
import com.cappielloantonio.tempo.ui.adapter.DeezerSectionHeaderAdapter
import com.cappielloantonio.tempo.ui.dialog.DeezerDownloadDialog
import com.cappielloantonio.tempo.viewmodel.DeezerHit
import com.cappielloantonio.tempo.viewmodel.DeezerPageViewModel
import com.cappielloantonio.tempo.viewmodel.DeezerSearchViewModel
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * A Deezer artist or album, reached from the Deezer part of search: what the
 * library could have, with a download on everything.
 *
 * An artist shows their most played tracks and every release; a release opens
 * the same page for the album, with its tracklist. Tracks play their preview on
 * a tap, as in search.
 */
class DeezerPageFragment : Fragment() {
    private var bind: FragmentDeezerPageBinding? = null

    private lateinit var pageViewModel: DeezerPageViewModel
    private lateinit var downloads: DeezerSearchViewModel

    private lateinit var kind: DeezerHit.Kind
    private var id = 0L

    /* The page itself, as something to download: the artist's discography or the album. */
    private lateinit var self: DeezerHit

    private lateinit var actions: DeezerPageActionsAdapter
    private lateinit var tracksHeader: DeezerSectionHeaderAdapter
    private lateinit var tracks: DeezerResultAdapter
    private lateinit var albumsHeader: DeezerSectionHeaderAdapter
    private lateinit var albums: DeezerResultAdapter

    private var previewPlayer: DeezerPreviewPlayer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val args = requireArguments()
        kind = DeezerHit.Kind.valueOf(args.getString(KIND, DeezerHit.Kind.ARTIST.name))
        id = args.getLong(ID)
        self = DeezerHit(kind, id, args.getString(TITLE).orEmpty(), "", "", args.getString(IMAGE), null, false)

        pageViewModel = ViewModelProvider(this)[DeezerPageViewModel::class.java]
        downloads = ViewModelProvider(requireActivity())[DeezerSearchViewModel::class.java]

        return FragmentDeezerPageBinding.inflate(inflater, container, false).also { bind = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initHeader()
        initList()
        observe()

        downloads.refreshUsage()
        pageViewModel.load(kind, id)
    }

    override fun onStart() {
        super.onStart()

        previewPlayer = DeezerPreviewPlayer(requireContext()) { key, state -> tracks.setPreview(key, state) }
    }

    /* A preview belongs to this screen; leaving it ends the preview (see SearchFragment). */
    override fun onStop() {
        previewPlayer?.release()
        previewPlayer = null
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
    }

    private fun initHeader() {
        val bind = bind ?: return
        val activity = requireActivity() as MainActivity

        activity.setSupportActionBar(bind.deezerPageToolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        bind.deezerPageToolbar.title = null
        bind.deezerPageToolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // DESIGN.md "Geometry Rhythm": an artist is a circle, a record a square at the image radius.
        if (kind == DeezerHit.Kind.ALBUM) {
            bind.deezerPageCover.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(resources.getDimension(R.dimen.radius_image)).build()
        } else {
            bind.deezerPageCover.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f)).build()
        }

        // What search already knew, so the header is there before the page has loaded.
        showHeader(self.title, self.image, getString(if (kind == DeezerHit.Kind.ALBUM) R.string.label_role_album else R.string.label_role_artist))

        /*
         * As on the library's artist page: the header owns the name while it is
         * on screen, and the toolbar takes it only once the header has scrolled
         * away - never both at once.
         */
        bind.appbar.addOnOffsetChangedListener { appBar, offset ->
            val b = this.bind ?: return@addOnOffsetChangedListener
            val range = appBar.totalScrollRange
            val collapsed = range > 0 && Math.abs(offset) >= range - b.deezerPageToolbar.height
            b.deezerPageToolbar.title = if (collapsed) b.deezerPageTitle.text else null
        }
    }

    private fun showHeader(title: String, image: String?, caption: String) {
        val bind = bind ?: return

        bind.deezerPageTitle.text = title
        bind.deezerPageCaption.text = caption

        Glide.with(this)
                .load(image)
                .placeholder(R.color.graphite)
                .error(if (kind == DeezerHit.Kind.ALBUM) R.drawable.ic_placeholder_album else R.drawable.ic_placeholder_artist)
                .into(bind.deezerPageCover)
    }

    private fun initList() {
        val bind = bind ?: return

        actions = DeezerPageActionsAdapter(
                if (kind == DeezerHit.Kind.ALBUM) R.string.deezer_page_download_album else R.string.deezer_page_download_artist
        ) { DeezerDownloadDialog.confirm(requireContext(), downloads, self) }

        tracksHeader = DeezerSectionHeaderAdapter(
                if (kind == DeezerHit.Kind.ALBUM) R.string.search_title_song else R.string.deezer_page_top_tracks)
        tracks = DeezerResultAdapter(
                onPreview = { previewPlayer?.toggle(it.key, it.id) },
                onDownload = { DeezerDownloadDialog.confirm(requireContext(), downloads, it) },
                onOpen = {})

        albumsHeader = DeezerSectionHeaderAdapter(R.string.deezer_page_releases)
        albums = DeezerResultAdapter(
                onPreview = {},
                onDownload = { DeezerDownloadDialog.confirm(requireContext(), downloads, it) },
                onOpen = { open(it) })

        bind.deezerPageList.layoutManager = LinearLayoutManager(requireContext())
        bind.deezerPageList.adapter = ConcatAdapter(actions, tracksHeader, tracks, albumsHeader, albums)
    }

    private fun observe() {
        pageViewModel.getPage().observe(viewLifecycleOwner) { page ->
            val bind = bind ?: return@observe

            bind.deezerPageLoader.visibility = if (page == null && pageViewModel.getFailed().value != true) View.VISIBLE else View.GONE
            if (page == null) return@observe

            self = self.copy(title = page.title.ifEmpty { self.title })
            showHeader(self.title, page.image ?: self.image, page.caption)

            actions.show(true)
            showTracks(page)

            albumsHeader.show(page.albums.isNotEmpty())
            albums.setHits(page.albums)
        }

        pageViewModel.getFailed().observe(viewLifecycleOwner) { failed ->
            val bind = bind ?: return@observe

            bind.deezerPageError.visibility = if (failed) View.VISIBLE else View.GONE
            if (failed) bind.deezerPageLoader.visibility = View.GONE
            bind.deezerPageError.setOnClickListener { pageViewModel.retry(kind, id) }
        }

        downloads.getStates().observe(viewLifecycleOwner) { states ->
            actions.setState(states[self.key] ?: DeezerHit.State.IDLE)
            tracks.setStates(states)
            albums.setStates(states)
        }

        downloads.getUsage().observe(viewLifecycleOwner) { usage ->
            actions.setQuota(DeemixQuota.describe(requireContext(), usage))
        }
    }

    /*
     * An artist's list opens on the first few of their most played tracks,
     * enough to recognise them by; the rest is one tap away. An album shows its
     * whole tracklist - that is what the page is for.
     */
    private fun showTracks(page: DeezerPageViewModel.Page) {
        tracksHeader.show(page.tracks.isNotEmpty())

        val foldable = kind == DeezerHit.Kind.ARTIST && page.tracks.size > FOLDED_TRACKS
        val expanded = pageViewModel.tracksExpanded || !foldable

        tracks.setHits(if (expanded) page.tracks else page.tracks.take(FOLDED_TRACKS))
        tracksHeader.setAction(
                if (pageViewModel.tracksExpanded) R.string.deezer_page_show_less else R.string.artist_page_title_most_streamed_song_see_all_button,
                if (foldable) ({
                    pageViewModel.tracksExpanded = !pageViewModel.tracksExpanded
                    showTracks(page)
                }) else null)
    }

    private fun open(hit: DeezerHit) {
        findNavController().navigate(R.id.deezerPageFragment, args(hit))
    }

    companion object {
        private const val KIND = "deezer_kind"
        private const val ID = "deezer_id"
        private const val TITLE = "deezer_title"
        private const val IMAGE = "deezer_image"

        private const val FOLDED_TRACKS = 5

        /** What opens [hit]'s page: an artist or an album. */
        @JvmStatic
        fun args(hit: DeezerHit) = Bundle().apply {
            putString(KIND, hit.kind.name)
            putLong(ID, hit.id)
            putString(TITLE, hit.title)
            putString(IMAGE, hit.image)
        }
    }
}
