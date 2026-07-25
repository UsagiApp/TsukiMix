package com.squareup.zstd.okio

import okio.Source

object OkioZstd {
	@JvmStatic
	fun zstdDecompress(source: Source): Source = source
}
