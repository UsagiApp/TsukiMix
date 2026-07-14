package org.draken.tsukimix.core.parser.tachiyomi.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

class AndroidPreferenceStore(
	context: Context,
	private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context),
) : PreferenceStore {
	private val keyFlow = callbackFlow {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
		sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
		awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
	}
	override fun getString(key: String, defaultValue: String) =
		AndroidPreference.StringPrimitive(sharedPreferences, keyFlow, key, defaultValue)
	override fun getLong(key: String, defaultValue: Long) =
		AndroidPreference.LongPrimitive(sharedPreferences, keyFlow, key, defaultValue)
	override fun getInt(key: String, defaultValue: Int) =
		AndroidPreference.IntPrimitive(sharedPreferences, keyFlow, key, defaultValue)
	override fun getFloat(key: String, defaultValue: Float) =
		AndroidPreference.FloatPrimitive(sharedPreferences, keyFlow, key, defaultValue)
	override fun getBoolean(key: String, defaultValue: Boolean) =
		AndroidPreference.BooleanPrimitive(sharedPreferences, keyFlow, key, defaultValue)
	override fun getStringSet(key: String, defaultValue: Set<String>) =
		AndroidPreference.StringSetPrimitive(sharedPreferences, keyFlow, key, defaultValue)
	override fun <T> getObjectFromString(
		key: String,
		defaultValue: T,
		serializer: (T) -> String,
		deserializer: (String) -> T,
	) = AndroidPreference.ObjectAsString(sharedPreferences, keyFlow, key, defaultValue, serializer, deserializer)
	override fun <T> getObjectFromInt(
		key: String,
		defaultValue: T,
		serializer: (T) -> Int,
		deserializer: (Int) -> T,
	) = AndroidPreference.ObjectAsInt(sharedPreferences, keyFlow, key, defaultValue, serializer, deserializer)
	override fun getAll(): Map<String, *> = sharedPreferences.all
}
