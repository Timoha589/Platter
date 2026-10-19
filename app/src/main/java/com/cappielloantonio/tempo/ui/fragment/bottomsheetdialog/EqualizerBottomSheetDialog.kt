package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.audio.EqPresets
import com.cappielloantonio.tempo.audio.EqSettings
import com.cappielloantonio.tempo.audio.PlatterEqualizer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The equalizer, as the desktop has it: on or off, a preset to start from, the preamp and ten bands. Every move is
 * heard at once - the players read [PlatterEqualizer] as they play - and kept for the next time.
 */
class EqualizerBottomSheetDialog : BottomSheetDialogFragment() {
    private lateinit var switch: MaterialSwitch
    private lateinit var presets: ChipGroup

    /** The preamp first, then the ten bands, low to high; the same for the dB readouts beside them. */
    private val sliders = ArrayList<Slider>()
    private val readouts = ArrayList<TextView>()

    /** The slider under the finger: what the finger says goes, not an echo of an earlier move arriving late. */
    private var touching: Slider? = null

    /** Set while the views are being brought in line with the settings, so that is not taken for the listener's doing. */
    private var showing = false

    private val listener = PlatterEqualizer.Listener { show(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.bottom_sheet_equalizer, container, false)

        switch = view.findViewById(R.id.eq_switch)
        switch.setOnCheckedChangeListener { _, on -> if (!showing) PlatterEqualizer.setEnabled(on) }

        presets = view.findViewById(R.id.eq_presets)
        for ((name, _) in EqPresets.all) {
            val chip = inflater.inflate(R.layout.item_eq_preset_chip, presets, false) as Chip
            chip.text = getString(presetName(name))
            chip.tag = name
            chip.setOnClickListener { PlatterEqualizer.choosePreset(name) }
            presets.addView(chip)
        }

        val bands = view.findViewById<LinearLayout>(R.id.eq_bands)
        // The preamp's name is too long for the column of frequencies, so it heads its row instead.
        bands.addView(TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = getString(R.string.eq_preamp)
            setPadding(0, dp(8), 0, 0)
        })
        addRow(bands, null, 0)
        EqSettings.LABELS.forEachIndexed { band, label -> addRow(bands, label, band + 1) }

        view.findViewById<View>(R.id.eq_reset).setOnClickListener { PlatterEqualizer.reset() }

        return view
    }

    private fun addRow(parent: LinearLayout, label: String?, index: Int) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The preamp's row has no label of its own but keeps the column, so all eleven sliders line up.
        row.addView(TextView(requireContext(), null, 0, R.style.LabelSmall).apply { text = label }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))

        val slider = Slider(requireContext()).apply {
            valueFrom = -EqSettings.RANGE_DB
            valueTo = EqSettings.RANGE_DB
            stepSize = STEP
            // Forty-eight ticks across a phone are a dotted line, not a scale.
            isTickVisible = false
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            contentDescription = label?.let { "$it Hz" } ?: getString(R.string.eq_preamp)
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser || showing) return@addOnChangeListener
                readouts[index].text = decibels(value)
                change(index, value)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    touching = slider
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    touching = null
                }
            })
        }
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val readout = TextView(requireContext(), null, 0, R.style.LabelSmall).apply { gravity = Gravity.END }
        row.addView(readout, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))

        sliders += slider
        readouts += readout
        parent.addView(row)
    }

    private fun change(index: Int, value: Float) {
        val now = PlatterEqualizer.settings
        PlatterEqualizer.set(
            if (index == 0) now.copy(preamp = value)
            else now.copy(bands = now.gains().toMutableList().also { it[index - 1] = value })
        )
    }

    override fun onStart() {
        super.onStart()
        PlatterEqualizer.addListener(listener)
        show(PlatterEqualizer.settings)

        // Eleven sliders do not fit a half-open sheet; it opens all the way.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onStop() {
        PlatterEqualizer.removeListener(listener)
        super.onStop()
    }

    private fun show(settings: EqSettings) {
        if (view == null) return
        showing = true
        switch.isChecked = settings.enabled

        val values = listOf(settings.preamp) + settings.gains()
        values.forEachIndexed { i, value ->
            val slider = sliders[i]
            // Off, the bands keep their shape but cannot be moved: what they would do is not being heard.
            slider.isEnabled = settings.enabled
            if (slider !== touching) {
                slider.value = ((value / STEP).roundToInt() * STEP).coerceIn(-EqSettings.RANGE_DB, EqSettings.RANGE_DB)
                readouts[i].text = decibels(value)
            }
        }

        val preset = EqPresets.nameOf(settings.gains())
        for (i in 0 until presets.childCount) {
            val chip = presets.getChildAt(i) as Chip
            chip.isChecked = settings.enabled && chip.tag == preset
        }
        showing = false
    }

    private fun decibels(value: Float): String =
        if (abs(value) < 0.05f) "0 dB" else String.format(Locale.getDefault(), "%+.1f dB", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val STEP = 0.5f
        private const val TAG = "EqualizerBottomSheet"

        @JvmStatic
        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) EqualizerBottomSheetDialog().show(fragmentManager, TAG)
        }

        private fun presetName(name: String): Int = when (name) {
            "flat" -> R.string.eq_preset_flat
            "bass_boost" -> R.string.eq_preset_bass_boost
            "treble_boost" -> R.string.eq_preset_treble_boost
            "rock" -> R.string.eq_preset_rock
            "pop" -> R.string.eq_preset_pop
            "jazz" -> R.string.eq_preset_jazz
            "classical" -> R.string.eq_preset_classical
            "vocal" -> R.string.eq_preset_vocal
            else -> R.string.eq_preset_electronic
        }
    }
}
