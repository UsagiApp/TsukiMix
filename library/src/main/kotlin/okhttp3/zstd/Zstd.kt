package okhttp3.zstd

import okhttp3.CompressionInterceptor
import okio.BufferedSource
import okio.Source

object Zstd : CompressionInterceptor.DecompressionAlgorithm {

	override val encoding: String = "identity"

	override fun decompress(compressedSource: BufferedSource): Source = compressedSource
}
