package eu.kanade.tachiyomi

/**
 * Provides host application info for extensions.
 *
 * @since extension-lib 1.3
 */
object AppInfo {
	private var versionCode: Int = 0
	private var versionName: String = ""

	fun initialize(versionCode: Int, versionName: String) {
		this.versionCode = versionCode
		this.versionName = versionName
	}

	fun getVersionCode(): Int = versionCode
	fun getVersionName(): String = versionName

	/**
	 * A list of image MIME types supported by the reader.
	 *
	 * @since extension-lib 1.5
	 */
	fun getSupportedImageMimeTypes(): List<String> = listOf(
		"image/jpeg",
		"image/png",
		"image/gif",
		"image/webp",
		"image/avif",
		"image/jxl",
		"image/heif",
	)
}
