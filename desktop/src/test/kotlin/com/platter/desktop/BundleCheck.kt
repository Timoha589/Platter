package com.platter.desktop

import com.platter.desktop.api.Song
import com.platter.desktop.player.PlayerController
import com.platter.desktop.player.VlcDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.system.exitProcess

/**
 * Plays each file through the bundled libvlc alone, in a JVM of its own (libvlc is found once per JVM, so the test JVM,
 * which has already used an installed VLC, could not show it). Prints `OK` or `FAIL` per file; exit code 0 when all played.
 * Arguments: the folder of the bundle, then the files.
 */
fun main(args: Array<String>) {
    System.setProperty(VlcDiscovery.DIRECTORY_PROPERTY, args[0])
    val files = args.drop(1)
    val player = PlayerController(CoroutineScope(SupervisorJob() + Dispatchers.Default), { it.id }, { _, _, _ -> }, vlcArgs = listOf("--aout=dummy") + System.getenv("BUNDLE_VLC_ARGS").orEmpty().split(" ").filter { it.isNotBlank() })
    var failures = 0
    for (file in files) {
        val song = Song().apply { id = if (file.startsWith("http")) file else File(file).absolutePath; title = File(file).name; artist = "x"; album = "y"; duration = 100 }
        player.play(listOf(song))
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val s = player.state.value
            if (s.error != null || (s.isPlaying && s.positionMs > 300)) break
            Thread.sleep(50)
        }
        val s = player.state.value
        val ok = s.error == null && s.isPlaying && s.positionMs > 300
        if (!ok) failures++
        println((if (ok) "OK   " else "FAIL ") + File(file).name + " (from ${VlcDiscovery.loadedFrom}, error=${s.error}, position=${s.positionMs})")
    }
    player.release()
    exitProcess(if (failures == 0 && files.isNotEmpty()) 0 else 1)
}
