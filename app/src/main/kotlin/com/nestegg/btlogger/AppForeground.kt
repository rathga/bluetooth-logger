package com.nestegg.btlogger

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether any activity of this app is currently started.
 *
 * `ActivityManager.getMyMemoryState` is not a substitute: a process running a manifest broadcast
 * reports `IMPORTANCE_FOREGROUND` for the duration of `onReceive`, which is exactly when the sync
 * watchdog asks. Counting started activities is read from a background thread, hence the atomic.
 */
object AppForeground : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)

    val isForeground: Boolean
        get() = startedActivities.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.decrementAndGet()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
