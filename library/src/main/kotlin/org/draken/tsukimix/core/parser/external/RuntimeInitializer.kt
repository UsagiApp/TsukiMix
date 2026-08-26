@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeInitializer(
	private val extRuntime: () -> ExtRuntime,
) {
	constructor(extRuntime: ExtRuntime) : this({ extRuntime })

	private val isStarted = AtomicBoolean(false)

	suspend fun initialize() {
		if (!isStarted.compareAndSet(false, true)) return
		withContext(Dispatchers.IO) {
			runCatching { extRuntime().ensureReady() }
		}
	}
}
