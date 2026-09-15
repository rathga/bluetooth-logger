package com.nestegg.btlogger.sync

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEditor(private val values: MutableMap<String, Any>) : SharedPreferences.Editor {

    private val staged = mutableMapOf<String, Any?>()

    override fun putString(key: String?, value: String?) = stage(key, value)

    override fun putStringSet(key: String?, values: MutableSet<String>?) = stage(key, values)

    override fun putInt(key: String?, value: Int) = stage(key, value)

    override fun putLong(key: String?, value: Long) = stage(key, value)

    override fun putFloat(key: String?, value: Float) = stage(key, value)

    override fun putBoolean(key: String?, value: Boolean) = stage(key, value)

    override fun remove(key: String?) = stage(key, null)

    override fun clear(): SharedPreferences.Editor {
        values.keys.toList().forEach { staged[it] = null }
        return this
    }

    override fun commit(): Boolean {
        staged.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        staged.clear()
        return true
    }

    override fun apply() {
        commit()
    }

    private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
        staged[key!!] = value
        return this
    }
}

private class FakeSharedPreferences(val values: MutableMap<String, Any>) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.mapTo(mutableSetOf()) { it as String } ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

class SyncStateTest {

    @Test fun `an account carried over from a build with no stamp is signed in as of the upgrade`() {
        val prefs = FakeSharedPreferences(mutableMapOf("account_name" to "driver@example.com"))
        val beforeFirstRead = System.currentTimeMillis()

        val signedInSince = SyncState(prefs).signedInSinceMillis

        assertTrue(
            "expected a stamp at the upgrade, got $signedInSince",
            signedInSince != null && signedInSince >= beforeFirstRead,
        )
    }

    @Test fun `a stamp already recorded is left alone`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf("account_name" to "driver@example.com", "signed_in_since_millis" to 1_000L),
        )

        assertEquals(1_000L, SyncState(prefs).signedInSinceMillis)
    }

    @Test fun `signing in drops sync health left behind by a build that signed out without it`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "last_attempt_millis" to 1_000L,
                "last_attempt_outcome" to "auth-failure",
                "last_success_millis" to 900L,
                "last_forced_reenqueue_millis" to 800L,
            ),
        )
        val state = SyncState(prefs)

        state.recordSignIn("driver@example.com")

        assertEquals(0L, state.lastAttemptMillis)
        assertNull(state.lastAttemptOutcome)
        assertEquals(0L, state.lastSuccessMillis)
        assertEquals(0L, state.lastForcedReenqueueMillis)
    }

    @Test fun `re-signing in the account already recorded drops the auth failure it answers`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "account_name" to "driver@example.com",
                "signed_in_since_millis" to 1_000L,
                "last_attempt_millis" to 5_000L,
                "last_attempt_outcome" to "auth-failure",
                "last_success_millis" to 4_000L,
            ),
        )
        val state = SyncState(prefs)

        state.recordSignIn("driver@example.com")

        assertNull(state.lastAttemptOutcome)
        assertEquals(0L, state.lastAttemptMillis)
        assertEquals(1_000L, state.signedInSinceMillis)
        assertEquals(4_000L, state.lastSuccessMillis)
    }

    @Test fun `a signed-out install stamps nothing`() {
        val prefs = FakeSharedPreferences(mutableMapOf())

        assertNull(SyncState(prefs).signedInSinceMillis)
        assertTrue(prefs.values.isEmpty())
    }
}
