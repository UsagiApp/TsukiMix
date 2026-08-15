@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeInitializer(
	private val tachiyomiRuntime: () -> TachiyomiRuntime,
) {
	constructor(tachiyomiRuntime: TachiyomiRuntime) : this({ tachiyomiRuntime })

	private val isStarted = AtomicBoolean(false)

	suspend fun initialize() {
		if (!isStarted.compareAndSet(false, true)) return
		withContext(Dispatchers.IO) {
			runCatching { tachiyomiRuntime().ensureReady() }
		}
	}
}
