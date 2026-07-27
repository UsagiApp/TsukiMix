package com.squareup.zstd.okio

import okio.Sink
import okio.Source

object OkioZstd {
	@JvmStatic
	fun zstdCompress(sink: Sink): Sink = sink

	@JvmStatic
	fun zstdDecompress(source: Source): Source = source
}
