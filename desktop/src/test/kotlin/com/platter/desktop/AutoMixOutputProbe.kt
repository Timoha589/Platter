package com.platter.desktop

import com.google.gson.Gson
import com.platter.automix.TrackStructure
import com.platter.desktop.api.Song
import com.platter.desktop.log.AppLog
import com.platter.desktop.player.FadeMode
import com.platter.desktop.player.PlayerController
import com.platter.desktop.player.TransitionMode
import com.platter.desktop.player.mediaLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private data class Probe(
    val positionMs: Long,
    val outgoing: TrackStructure,
    val incoming: TrackStructure,
)

/** Plays only the generated inputs through the regular output in a dedicated JVM for PID-scoped capture. */
fun main(args: Array<String>) {
    val run = Path.of(args.single())
    val setup = Gson().fromJson(run.resolve("../fixture.json").readText(), Probe::class.java)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    AppLog.install(run)
    val player = PlayerController(scope, { mediaLocation(run.resolve("../${it.id}.wav").normalize()) },
        { _, _, _ -> }, initialVolume = 70, initialTransitionMode = TransitionMode.AutoMix)
    try {
        run.resolve("player.pid").writeText(ProcessHandle.current().pid().toString())
        val deadline = System.nanoTime() + 90_000_000_000
        while (!run.resolve("start").exists()) {
            check(System.nanoTime() < deadline) { "Capture did not start" }
            Thread.sleep(20)
        }
        val tracks = mapOf("outgoing" to setup.outgoing, "incoming" to setup.incoming)
        player.setStructures(tracks)
        val songs = tracks.map { (name, structure) ->
            Song().apply { id = name; title = name; duration = (structure.durationMs / 1_000).toInt() }
        }
        player.play(songs, positionMs = setup.positionMs)

        val playbackDeadline = System.nanoTime() + 45_000_000_000
        var started = false
        var planIssued = false
        while (System.nanoTime() < playbackDeadline) {
            val state = player.state.value
            check(state.error == null) { "Playback error: ${state.error}" }
            check(state.fadeTransition?.mode != FadeMode.AutoMixFallback) { "Player fell back before the beat mix" }
            val plan = state.upcomingOverlap
            if (!planIssued && plan?.mode == FadeMode.AutoMix) {
                run.resolve("planned-overlap").writeText("${plan.rangeMs.first} ${plan.rangeMs.last}")
                planIssued = true
            }
            started = started || state.autoMixTransition != null
            if (started && state.fadeTransition == null) {
                Thread.sleep(1_500)
                run.resolve("finished").writeText("BeatMix finished")
                break
            }
            Thread.sleep(10)
        }
        check(run.resolve("finished").exists()) { "Did not complete a beat mix" }
    } finally {
        scope.cancel()
        check(player.releaseAndWait()) { "The playback engine did not shut down" }
    }
    exitProcess(0)
}
