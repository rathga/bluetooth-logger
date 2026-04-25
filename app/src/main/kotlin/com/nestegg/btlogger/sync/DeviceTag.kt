package com.nestegg.btlogger.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Builds a stable, human-readable suffix that distinguishes one phone's CSV
 * uploads from another's, so multiple devices syncing to the same Google
 * account don't race on a single shared file.
 *
 * Format: `<sanitised-model>-<8-char-android-id>` e.g. `sm-g981b-a1b2c3d4`.
 * ANDROID_ID is per-app+signing-key+user on Android 8+, so two devices always
 * differ, and reinstalling the same app on the same device keeps the same tag.
 */
object DeviceTag {

    @SuppressLint("HardwareIds")
    fun forContext(context: Context): String =
        build(
            model = Build.MODEL,
            androidId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            ),
        )

    internal fun build(model: String?, androidId: String?): String {
        val shortId = androidId.orEmpty().take(8).ifBlank { "unknown" }
        val cleanModel = model.orEmpty()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(16)
            .ifBlank { "device" }
        return "$cleanModel-$shortId"
    }
}
