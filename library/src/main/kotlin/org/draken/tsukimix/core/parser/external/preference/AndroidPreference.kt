package org.draken.tsukimix.core.parser.external.preference

import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed class AndroidPreference<T>(
	private val preferences: SharedPreferences,
	private val keyFlow: Flow<String?>,
	private val key: String,
	private val defaultValue: T,
) : Preference<T> {
	abstract fun read(preferences: SharedPreferences, key: String, defaultValue: T): T
	abstract fun write(key: String, value: T): Editor.() -> Unit
	override fun key() = key
	override fun get(): T = try {
		read(preferences, key, defaultValue)
	} catch (_: ClassCastException) {
		delete()
		defaultValue
	}
	override fun set(value: T) = preferences.edit(action = write(key, value))
	override fun isSet() = preferences.contains(key)
	override fun delete() = preferences.edit { remove(key) }
	override fun defaultValue() = defaultValue
	override fun changes(): Flow<T> = keyFlow
		.filter { it == key || it == null }
		.onStart { emit("ignition") }
		.map { get() }
		.conflate()
	override fun stateIn(scope: CoroutineScope): StateFlow<T> =
		changes().stateIn(scope, SharingStarted.Eagerly, get())

	class StringPrimitive(p: SharedPreferences, f: Flow<String?>, k: String, d: String) :
		AndroidPreference<String>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: String) =
			preferences.getString(key, defaultValue) ?: defaultValue
		override fun write(key: String, value: String): Editor.() -> Unit = { putString(key, value) }
	}
	class LongPrimitive(p: SharedPreferences, f: Flow<String?>, k: String, d: Long) :
		AndroidPreference<Long>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: Long) =
			preferences.getLong(key, defaultValue)
		override fun write(key: String, value: Long): Editor.() -> Unit = { putLong(key, value) }
	}
	class IntPrimitive(p: SharedPreferences, f: Flow<String?>, k: String, d: Int) :
		AndroidPreference<Int>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: Int) =
			preferences.getInt(key, defaultValue)
		override fun write(key: String, value: Int): Editor.() -> Unit = { putInt(key, value) }
	}
	class FloatPrimitive(p: SharedPreferences, f: Flow<String?>, k: String, d: Float) :
		AndroidPreference<Float>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: Float) =
			preferences.getFloat(key, defaultValue)
		override fun write(key: String, value: Float): Editor.() -> Unit = { putFloat(key, value) }
	}
	class BooleanPrimitive(p: SharedPreferences, f: Flow<String?>, k: String, d: Boolean) :
		AndroidPreference<Boolean>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: Boolean) =
			preferences.getBoolean(key, defaultValue)
		override fun write(key: String, value: Boolean): Editor.() -> Unit = { putBoolean(key, value) }
	}
	class StringSetPrimitive(p: SharedPreferences, f: Flow<String?>, k: String, d: Set<String>) :
		AndroidPreference<Set<String>>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: Set<String>) =
			preferences.getStringSet(key, defaultValue) ?: defaultValue
		override fun write(key: String, value: Set<String>): Editor.() -> Unit = { putStringSet(key, value) }
	}
	class ObjectAsString<T>(
		p: SharedPreferences,
		f: Flow<String?>,
		k: String,
		d: T,
		private val serializer: (T) -> String,
		private val deserializer: (String) -> T,
	) : AndroidPreference<T>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: T): T {
			return try {
				preferences.getString(key, null)?.let(deserializer) ?: defaultValue
			} catch (_: Exception) {
				defaultValue
			}
		}
		override fun write(key: String, value: T): Editor.() -> Unit = { putString(key, serializer(value)) }
	}
	class ObjectAsInt<T>(
		p: SharedPreferences,
		f: Flow<String?>,
		k: String,
		d: T,
		private val serializer: (T) -> Int,
		private val deserializer: (Int) -> T,
	) : AndroidPreference<T>(p, f, k, d) {
		override fun read(preferences: SharedPreferences, key: String, defaultValue: T): T {
			return try {
				if (preferences.contains(key)) preferences.getInt(key, 0).let(deserializer) else defaultValue
			} catch (_: Exception) {
				defaultValue
			}
		}
		override fun write(key: String, value: T): Editor.() -> Unit = { putInt(key, serializer(value)) }
	}
}
