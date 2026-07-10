package eu.kanade.tachiyomi.util.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.Hash
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

object DiskUtil {

	/**
	 * Returns the root folders of all the available external storages.
	 */
	fun getExternalStorages(context: Context): List<File> {
		return context.getExternalFilesDirs(null)
			.filterNotNull()
			.mapNotNull {
				val file = File(it.absolutePath.substringBefore("/Android/"))
				val state = Environment.getExternalStorageState(file)
				if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
					file
				} else {
					null
				}
			}
	}

	fun hashKeyForDisk(key: String): String {
		return Hash.md5(key)
	}

	fun getDirectorySize(f: File): Long {
		var size: Long = 0
		if (f.isDirectory) {
			for (file in f.listFiles().orEmpty()) {
				size += getDirectorySize(file)
			}
		} else {
			size = f.length()
		}
		return size
	}

	/**
	 * Gets the total space for the disk that a file path points to, in bytes.
	 */
	fun getTotalStorageSpace(file: File): Long {
		return try {
			val stat = StatFs(file.absolutePath)
			stat.blockCountLong * stat.blockSizeLong
		} catch (_: Exception) {
			-1L
		}
	}

	/**
	 * Gets the available space for the disk that a file path points to, in bytes.
	 */
	fun getAvailableStorageSpace(file: File): Long {
		return try {
			val stat = StatFs(file.absolutePath)
			stat.availableBlocksLong * stat.blockSizeLong
		} catch (_: Exception) {
			-1L
		}
	}

	/**
	 * Gets the available space for the disk that a file path points to, in bytes.
	 */
	fun getAvailableStorageSpace(f: UniFile): Long {
		return try {
			val stat = StatFs(f.uri.path)
			stat.availableBlocksLong * stat.blockSizeLong
		} catch (_: Exception) {
			-1L
		}
	}

	/**
	 * Don't display downloaded chapters in gallery apps creating `.nomedia`.
	 */
	fun createNoMediaFile(dir: UniFile?, context: Context?) {
		if (dir != null && dir.exists()) {
			val nomedia = dir.findFile(NOMEDIA_FILE)
			if (nomedia == null) {
				dir.createFile(NOMEDIA_FILE)
				context?.let { scanMedia(it, dir.uri) }
			}
		}
	}

	/**
	 * Scans the given file so that it can be shown in gallery apps, for example.
	 */
	fun scanMedia(context: Context, uri: Uri) {
		MediaScannerConnection.scanFile(context, arrayOf(uri.path), null, null)
	}

	/**
	 * Transform a filename fragment to make it safe to use on almost
	 * all commonly used filesystems. You can pass an entire filename,
	 * or just part of one, in case you want a specific part of a long
	 * filename to be truncated, rather than the end of it.
	 */
	fun buildValidFilename(
		origName: String,
		maxBytes: Int = MAX_FILE_NAME_BYTES,
		disallowNonAscii: Boolean = false,
	): String {
		val name = origName.trim('.', ' ')
		if (name.isEmpty()) {
			return "(invalid)"
		}
		val sb = StringBuilder(name.length)
		name.forEach { c ->
			if (disallowNonAscii && c >= 0x80.toChar()) {
				sb.append(
					c.toString().toByteArray(Charsets.UTF_8).toHexString(
						HexFormat {
							upperCase = false
						},
					),
				)
			} else if (isValidFatFilenameChar(c)) {
				sb.append(c)
			} else {
				sb.append('_')
			}
		}
		return truncateToLength(sb.toString(), maxBytes)
	}

	/**
	 * Truncate a string to a maximum length, while maintaining valid Unicode encoding.
	 */
	fun truncateToLength(s: String, maxBytes: Int): String {
		val charset = Charsets.UTF_8
		val decoder = charset.newDecoder()
		val sba = s.toByteArray(charset)
		if (sba.size <= maxBytes) {
			return s
		}
		val bb = ByteBuffer.wrap(sba, 0, maxBytes)
		val cb = CharBuffer.allocate(maxBytes)
		decoder.onMalformedInput(CodingErrorAction.IGNORE)
		decoder.decode(bb, cb, true)
		decoder.flush(cb)
		return String(cb.array(), 0, cb.position())
	}

	/**
	 * Returns true if the given character is a valid filename character, false otherwise.
	 */
	private fun isValidFatFilenameChar(c: Char): Boolean {
		if (0x00.toChar() <= c && c <= 0x1f.toChar()) {
			return false
		}
		return when (c) {
			'"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
			else -> true
		}
	}

	const val NOMEDIA_FILE = ".nomedia"

	// Safe theoretical max filename size is 255 bytes and 1 char = 2-4 bytes (UTF-8).
	// To allow for writing to ext4 through a FUSE layer in the future, also subtract 15
	// reserved characters.
	const val MAX_FILE_NAME_BYTES = 240
}
