package com.cappielloantonio.tempo.util

import android.util.Log
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.model.HomeSector
import com.cappielloantonio.tempo.subsonic.models.OpenSubsonicExtension
import com.google.gson.Gson


object Preferences {
    private const val SERVER = "server"
    private const val USER = "user"
    private const val PASSWORD = "password"
    private const val TOKEN = "token"
    private const val SALT = "salt"
    private const val LOW_SECURITY = "low_security"
    private const val BATTERY_OPTIMIZATION = "battery_optimization"
    private const val SERVER_ID = "server_id"
    private const val OPEN_SUBSONIC = "open_subsonic"
    private const val OPEN_SUBSONIC_EXTENSIONS = "open_subsonic_extensions"
    private const val LOCAL_ADDRESS = "local_address"
    private const val IN_USE_SERVER_ADDRESS = "in_use_server_address"
    private const val NEXT_SERVER_SWITCH = "next_server_switch"
    private const val PLAYBACK_SPEED = "playback_speed"
    private const val SKIP_SILENCE = "skip_silence"
    private const val IMAGE_CACHE_SIZE = "image_cache_size"
    private const val STREAMING_CACHE_SIZE = "streaming_cache_size"
    private const val IMAGE_SIZE = "image_size"
    private const val MAX_BITRATE_WIFI = "max_bitrate_wifi"
    private const val MAX_BITRATE_MOBILE = "max_bitrate_mobile"
    private const val AUDIO_TRANSCODE_FORMAT_WIFI = "audio_transcode_format_wifi"
    private const val AUDIO_TRANSCODE_FORMAT_MOBILE = "audio_transcode_format_mobile"
    private const val WIFI_ONLY = "wifi_only"
    private const val DATA_SAVING_MODE = "data_saving_mode"
    private const val SERVER_UNREACHABLE = "server_unreachable"
    private const val SYNC_STARRED_TRACKS_FOR_OFFLINE_USE = "sync_starred_tracks_for_offline_use"
    private const val CACHE_LIKED_TRACKS = "cache_liked_tracks"
    private const val SMART_DOWNLOAD = "smart_download"
    private const val SMART_DOWNLOAD_CACHE_SIZE = "smart_download_cache_size"
    private const val CATALOGUE_INDEX_SYNCED_AT = "catalogue_index_synced_at"
    private const val QUEUE_SYNCING = "queue_syncing"
    private const val QUEUE_SYNCING_COUNTDOWN = "queue_syncing_countdown"
    private const val ROUNDED_CORNER_SIZE = "rounded_corner_size"
    private const val REPLAY_GAIN_MODE = "replay_gain_mode"
    private const val AUDIO_TRANSCODE_PRIORITY = "audio_transcode_priority"
    private const val STREAMING_CACHE_STORAGE = "streaming_cache_storage"
    private const val DOWNLOAD_STORAGE = "download_storage"
    private const val AUDIO_TRANSCODE_DOWNLOAD = "audio_transcode_download"
    private const val AUDIO_TRANSCODE_DOWNLOAD_PRIORITY = "audio_transcode_download_priority"
    private const val MAX_BITRATE_DOWNLOAD = "max_bitrate_download"
    private const val AUDIO_TRANSCODE_FORMAT_DOWNLOAD = "audio_transcode_format_download"
    private const val SHARE = "share"
    private const val SCROBBLING = "scrobbling"
    private const val ESTIMATE_CONTENT_LENGTH = "estimate_content_length"
    private const val BUFFERING_STRATEGY = "buffering_strategy"
    private const val ALWAYS_ON_DISPLAY = "always_on_display"
    private const val AUDIO_QUALITY_PER_ITEM = "audio_quality_per_item"
    private const val HOME_SECTOR_LIST = "home_sector_list"
    private const val NEXT_UPDATE_CHECK = "next_update_check"
    private const val CONTINUOUS_PLAY = "continuous_play"
    private const val LAST_INSTANT_MIX = "last_instant_mix"
    private const val WAVE_URL = "wave_url"
    private const val DEEMIX_URL = "deemix_url"
    private const val DEEMIX_PASSWORD = "deemix_password"


    @JvmStatic
    fun getServer(): String? {
        return App.getInstance().preferences.getString(SERVER, null)
    }

    @JvmStatic
    fun setServer(server: String?) {
        App.getInstance().preferences.edit().putString(SERVER, server).apply()
    }

    @JvmStatic
    fun getUser(): String? {
        return App.getInstance().preferences.getString(USER, null)
    }

    @JvmStatic
    fun setUser(user: String?) {
        App.getInstance().preferences.edit().putString(USER, user).apply()
    }

    @JvmStatic
    fun getPassword(): String? {
        return App.getInstance().preferences.getString(PASSWORD, null)
    }

    @JvmStatic
    fun setPassword(password: String?) {
        App.getInstance().preferences.edit().putString(PASSWORD, password).apply()
    }

    @JvmStatic
    fun getToken(): String? {
        return App.getInstance().preferences.getString(TOKEN, null)
    }

    @JvmStatic
    fun setToken(token: String?) {
        App.getInstance().preferences.edit().putString(TOKEN, token).apply()
    }

    @JvmStatic
    fun getSalt(): String? {
        return App.getInstance().preferences.getString(SALT, null)
    }

    @JvmStatic
    fun setSalt(salt: String?) {
        App.getInstance().preferences.edit().putString(SALT, salt).apply()
    }

    @JvmStatic
    fun isLowScurity(): Boolean {
        return App.getInstance().preferences.getBoolean(LOW_SECURITY, false)
    }

    @JvmStatic
    fun setLowSecurity(isLowSecurity: Boolean) {
        App.getInstance().preferences.edit().putBoolean(LOW_SECURITY, isLowSecurity).apply()
    }

    @JvmStatic
    fun getServerId(): String? {
        return App.getInstance().preferences.getString(SERVER_ID, null)
    }

    @JvmStatic
    fun setServerId(serverId: String?) {
        App.getInstance().preferences.edit().putString(SERVER_ID, serverId).apply()
    }

    @JvmStatic
    fun isOpenSubsonic(): Boolean {
        return App.getInstance().preferences.getBoolean(OPEN_SUBSONIC, false)
    }

    @JvmStatic
    fun setOpenSubsonic(isOpenSubsonic: Boolean) {
        App.getInstance().preferences.edit().putBoolean(OPEN_SUBSONIC, isOpenSubsonic).apply()
    }

    @JvmStatic
    fun getOpenSubsonicExtensions(): String? {
        return App.getInstance().preferences.getString(OPEN_SUBSONIC_EXTENSIONS, null)
    }

    @JvmStatic
    fun setOpenSubsonicExtensions(extension: List<OpenSubsonicExtension>) {
        App.getInstance().preferences.edit().putString(OPEN_SUBSONIC_EXTENSIONS, Gson().toJson(extension)).apply()
    }

    /*
     * What a server can do is a property of that server, but this list is
     * stored once for the whole app. Signing into a different one left the
     * previous server's capabilities in place, so the client would keep calling
     * endpoints the new server has never heard of - and miss the ones it does
     * have. Clear it whenever the active server changes.
     */
    @JvmStatic
    fun clearOpenSubsonicExtensions() {
        App.getInstance().preferences.edit()
            .putBoolean(OPEN_SUBSONIC, false)
            .remove(OPEN_SUBSONIC_EXTENSIONS)
            .apply()
    }

    @JvmStatic
    fun getLocalAddress(): String? {
        return App.getInstance().preferences.getString(LOCAL_ADDRESS, null)
    }

    @JvmStatic
    fun setLocalAddress(address: String?) {
        App.getInstance().preferences.edit().putString(LOCAL_ADDRESS, address).apply()
    }

    @JvmStatic
    fun getInUseServerAddress(): String? {
        return App.getInstance().preferences.getString(IN_USE_SERVER_ADDRESS, null)
            ?.takeIf { it.isNotBlank() }
            ?: getServer()
    }

    /* Which address is in use is ServerAddress's call; null goes back to the main one. */
    @JvmStatic
    fun setInUseServerAddress(address: String?) {
        App.getInstance().preferences.edit().putString(IN_USE_SERVER_ADDRESS, address).apply()
    }

    @JvmStatic
    fun askForOptimization(): Boolean {
        return App.getInstance().preferences.getBoolean(BATTERY_OPTIMIZATION, true)
    }

    @JvmStatic
    fun dontAskForOptimization() {
        App.getInstance().preferences.edit().putBoolean(BATTERY_OPTIMIZATION, false).apply()
    }

    @JvmStatic
    fun getPlaybackSpeed(): Float {
        return App.getInstance().preferences.getFloat(PLAYBACK_SPEED, 1f)
    }

    @JvmStatic
    fun setPlaybackSpeed(playbackSpeed: Float) {
        App.getInstance().preferences.edit().putFloat(PLAYBACK_SPEED, playbackSpeed).apply()
    }

    @JvmStatic
    fun isSkipSilenceMode(): Boolean {
        return App.getInstance().preferences.getBoolean(SKIP_SILENCE, false)
    }

    @JvmStatic
    fun setSkipSilenceMode(isSkipSilenceMode: Boolean) {
        App.getInstance().preferences.edit().putBoolean(SKIP_SILENCE, isSkipSilenceMode).apply()
    }

    @JvmStatic
    fun getImageCacheSize(): Int {
        return App.getInstance().preferences.getString(IMAGE_CACHE_SIZE, "500")!!.toInt()
    }

    @JvmStatic
    fun getImageSize(): Int {
        return App.getInstance().preferences.getString(IMAGE_SIZE, "-1")!!.toInt()
    }

    @JvmStatic
    fun getStreamingCacheSize(): Long {
        return App.getInstance().preferences.getString(STREAMING_CACHE_SIZE, "256")!!.toLong()
    }

    @JvmStatic
    fun getMaxBitrateWifi(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_WIFI, "0")!!
    }

    @JvmStatic
    fun getMaxBitrateMobile(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_MOBILE, "0")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatWifi(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_WIFI, "raw")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatMobile(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_MOBILE, "raw")!!
    }

    @JvmStatic
    fun isWifiOnly(): Boolean {
        return App.getInstance().preferences.getBoolean(WIFI_ONLY, false)
    }

    @JvmStatic
    fun isDataSavingMode(): Boolean {
        return App.getInstance().preferences.getBoolean(DATA_SAVING_MODE, false)
    }

    @JvmStatic
    fun setDataSavingMode(isDataSavingModeEnabled: Boolean) {
        App.getInstance().preferences.edit().putBoolean(DATA_SAVING_MODE, isDataSavingModeEnabled)
                .apply()
    }

    /**
     * When the local list of library names was last refreshed, for the spelling
     * corrections in search. Zero means never, which is also what a fresh
     * install and a failed sync both look like - all three want the same thing.
     */
    @JvmStatic
    fun getCatalogueIndexSyncedAt(): Long {
        return App.getInstance().preferences.getLong(CATALOGUE_INDEX_SYNCED_AT, 0)
    }

    @JvmStatic
    fun setCatalogueIndexSyncedAt(timestamp: Long) {
        App.getInstance().preferences.edit().putLong(CATALOGUE_INDEX_SYNCED_AT, timestamp).apply()
    }

    /*
     * Whether liked tracks are kept downloaded. This took over from "sync
     * starred tracks for offline use", which asked for the same thing from the
     * settings screen, so anyone who had that on starts out with this on.
     */
    @JvmStatic
    fun isLikedTracksCacheEnabled(): Boolean {
        val preferences = App.getInstance().preferences
        return preferences.getBoolean(
                CACHE_LIKED_TRACKS,
                preferences.getBoolean(SYNC_STARRED_TRACKS_FOR_OFFLINE_USE, false)
        )
    }

    @JvmStatic
    fun setLikedTracksCacheEnabled(isEnabled: Boolean) {
        App.getInstance().preferences.edit()
                .putBoolean(CACHE_LIKED_TRACKS, isEnabled)
                .remove(SYNC_STARRED_TRACKS_FOR_OFFLINE_USE)
                .apply()
    }

    @JvmStatic
    fun isSmartDownloadEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SMART_DOWNLOAD, false)
    }

    /* How many tracks smart download may keep, before the stalest give way. */
    @JvmStatic
    fun getSmartDownloadCacheSize(): Int {
        return App.getInstance().preferences.getString(SMART_DOWNLOAD_CACHE_SIZE, "100")!!.toInt()
    }

    @JvmStatic
    fun showServerUnreachableDialog(): Boolean {
        return App.getInstance().preferences.getLong(
                SERVER_UNREACHABLE, 0
        ) + 86400000 < System.currentTimeMillis()
    }

    @JvmStatic
    fun setServerUnreachableDatetime() {
        App.getInstance().preferences.edit().putLong(SERVER_UNREACHABLE, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isSyncronizationEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(QUEUE_SYNCING, false)
    }

    @JvmStatic
    fun getSyncCountdownTimer(): Int {
        return App.getInstance().preferences.getString(QUEUE_SYNCING_COUNTDOWN, "5")!!.toInt()
    }

    @JvmStatic
    fun getRoundedCornerSize(): Int {
        return App.getInstance().preferences.getString(ROUNDED_CORNER_SIZE, "6")!!.toInt()
    }

    @JvmStatic
    fun getReplayGainMode(): String? {
        return App.getInstance().preferences.getString(REPLAY_GAIN_MODE, "disabled")
    }

    @JvmStatic
    fun isServerPrioritized(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_PRIORITY, false)
    }

    @JvmStatic
    fun getStreamingCacheStoragePreference(): Int {
        return App.getInstance().preferences.getString(STREAMING_CACHE_STORAGE, "0")!!.toInt()
    }

    @JvmStatic
    fun setStreamingCacheStoragePreference(streamingCachePreference: Int) {
        return App.getInstance().preferences.edit().putString(
                STREAMING_CACHE_STORAGE,
                streamingCachePreference.toString()
        ).apply()
    }

    @JvmStatic
    fun getDownloadStoragePreference(): Int {
        return App.getInstance().preferences.getString(DOWNLOAD_STORAGE, "0")!!.toInt()
    }

    @JvmStatic
    fun setDownloadStoragePreference(storagePreference: Int) {
        return App.getInstance().preferences.edit().putString(
                DOWNLOAD_STORAGE,
                storagePreference.toString()
        ).apply()
    }

    @JvmStatic
    fun preferTranscodedDownload(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_DOWNLOAD, false)
    }

    @JvmStatic
    fun isServerPrioritizedInTranscodedDownload(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_TRANSCODE_DOWNLOAD_PRIORITY, false)
    }

    @JvmStatic
    fun getBitrateTranscodedDownload(): String {
        return App.getInstance().preferences.getString(MAX_BITRATE_DOWNLOAD, "0")!!
    }

    @JvmStatic
    fun getAudioTranscodeFormatTranscodedDownload(): String {
        return App.getInstance().preferences.getString(AUDIO_TRANSCODE_FORMAT_DOWNLOAD, "raw")!!
    }

    @JvmStatic
    fun isSharingEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SHARE, false)
    }

    @JvmStatic
    fun isScrobblingEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(SCROBBLING, true)
    }

    @JvmStatic
    fun askForEstimateContentLength(): Boolean {
        return App.getInstance().preferences.getBoolean(ESTIMATE_CONTENT_LENGTH, false)
    }

    @JvmStatic
    fun getBufferingStrategy(): Double {
        return App.getInstance().preferences.getString(BUFFERING_STRATEGY, "1")!!.toDouble()
    }

    @JvmStatic
    fun isDisplayAlwaysOn(): Boolean {
        return App.getInstance().preferences.getBoolean(ALWAYS_ON_DISPLAY, false)
    }

    @JvmStatic
    fun showAudioQuality(): Boolean {
        return App.getInstance().preferences.getBoolean(AUDIO_QUALITY_PER_ITEM, false)
    }

    @JvmStatic
    fun getHomeSectorList(): String? {
        return App.getInstance().preferences.getString(HOME_SECTOR_LIST, null)
    }

    @JvmStatic
    fun setHomeSectorList(extension: List<HomeSector>?) {
        App.getInstance().preferences.edit().putString(HOME_SECTOR_LIST, Gson().toJson(extension)).apply()
    }

    @JvmStatic
    fun isUpdateReminderDue(): Boolean {
        return App.getInstance().preferences.getLong(
                NEXT_UPDATE_CHECK, 0
        ) + 86400000 < System.currentTimeMillis()
    }

    @JvmStatic
    fun setUpdateReminder() {
        App.getInstance().preferences.edit().putLong(NEXT_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isContinuousPlayEnabled(): Boolean {
        return App.getInstance().preferences.getBoolean(CONTINUOUS_PLAY, true)
    }

    @JvmStatic
    fun setLastInstantMix() {
        App.getInstance().preferences.edit().putLong(LAST_INSTANT_MIX, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun isInstantMixUsable(): Boolean {
        return App.getInstance().preferences.getLong(
                LAST_INSTANT_MIX, 0
        ) + 5000 < System.currentTimeMillis()
    }

    /* Where the wave service is, when it is not at the server's own /wave/. */
    @JvmStatic
    fun getWaveUrl(): String? {
        return App.getInstance().preferences.getString(WAVE_URL, null)
    }

    /* Where Deemix plus is, when it is not on the server's host at its usual port. */
    @JvmStatic
    fun getDeemixUrl(): String? {
        return App.getInstance().preferences.getString(DEEMIX_URL, null)
    }

    /* The Deemix plus password, for when it is not the same as the server's. */
    @JvmStatic
    fun getDeemixPassword(): String? {
        return App.getInstance().preferences.getString(DEEMIX_PASSWORD, null)
    }
}
