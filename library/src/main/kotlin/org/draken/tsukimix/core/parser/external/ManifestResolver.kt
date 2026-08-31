package org.draken.tsukimix.core.parser.external

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

@Suppress("DEPRECATION")
internal object ManifestResolver {

	fun parse(file: File): PackageInfo? = runCatching {
		val bytes = ZipFile(file).use { zip ->
			val entry = zip.getEntry("AndroidManifest.xml") ?: return null
			zip.getInputStream(entry).use { it.readBytes() }
		}
		if (bytes.size < 8) return null
		val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		buf.position(8)

		val strings = ArrayList<String?>()
		var resourceIds: IntArray? = null
		var pkgName: String? = null
		var vCode = 0
		var vName: String? = null
		var labelRes = 0
		var labelStr: String? = null
		var iconRes = 0
		val meta = Bundle()
		var currentElem: String?

		while (buf.remaining() >= 8) {
			val start = buf.position()
			val type = buf.short.toInt() and 0xFFFF
			buf.short // headerSize
			val size = buf.int
			if (size < 8 || start + size > bytes.size) break

			when (type) {
				0x0001 -> { // STRING_POOL
					buf.position(start + 8)
					val strCount = buf.int
					buf.int // styleCount
					val flags = buf.int
					val strStart = buf.int
					buf.int // stylesStart
					val isUtf8 = flags and 0x100 != 0
					val offsets = IntArray(strCount) { buf.int }
					val base = start + strStart
					strings.ensureCapacity(strCount)
					for (off in offsets) {
						buf.position(base + off)
						val str = if (isUtf8) {
							val l = buf.get().toInt() and 0xFF
							if (l and 0x80 != 0) ((l and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
							var bl = buf.get().toInt() and 0xFF
							if (bl and 0x80 != 0) bl = ((bl and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
							if (bl in 0..buf.remaining()) {
								val b = ByteArray(bl)
								buf.get(b)
								String(b, Charsets.UTF_8)
							} else null
						} else {
							var cl = buf.short.toInt() and 0xFFFF
							if (cl and 0x8000 != 0) cl = ((cl and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
							if (cl in 0..(buf.remaining() / 2)) {
								val sb = StringBuilder(cl)
								repeat(cl) { sb.append(buf.char) }
								sb.toString()
							} else null
						}
						strings.add(str)
					}
				}

				0x0180 -> { // RESOURCE_MAP
					resourceIds = IntArray((size - 8) / 4) { buf.int }
				}

				0x0102 -> { // START_TAG
					buf.position(start + 16)
					buf.int // ns
					val nameIdx = buf.int
					val attrStart = buf.short.toInt() and 0xFFFF
					val attrSize = buf.short.toInt() and 0xFFFF
					val attrCount = buf.short.toInt() and 0xFFFF
					currentElem = strings.getOrNull(nameIdx)

					var attrKey: String? = null
					var attrVal: Any? = null
					for (i in 0 until attrCount) {
						val aPos = start + 16 + attrStart + i * attrSize
						if (aPos + 20 > bytes.size) break
						buf.position(aPos + 4)
						val aNameIdx = buf.int
						val aRawIdx = buf.int
						val aType = bytes[aPos + 15].toInt() and 0xFF
						buf.position(aPos + 16)
						val aVal = buf.int
						val aResId = resourceIds?.getOrNull(aNameIdx) ?: 0
						val aName = strings.getOrNull(aNameIdx)
						val valObj: Any? = when (aType) {
							0x03 -> strings.getOrNull(aVal) ?: strings.getOrNull(aRawIdx)
							0x04 -> java.lang.Float.intBitsToFloat(aVal)
							0x12 -> aVal != 0
							else -> if (aRawIdx >= 0) strings.getOrNull(aRawIdx) ?: aVal else aVal
						}

						when (currentElem) {
							"manifest" -> when {
								aResId == 0x0101021b || aName == "versionCode" -> vCode = (valObj as? Number)?.toInt() ?: 0
								aResId == 0x0101021c || aName == "versionName" -> vName = valObj?.toString()
								aName == "package" -> pkgName = valObj?.toString()
							}

							"application" -> when {
								aResId == 0x01010001 || aName == "label" -> when (valObj) {
									is Int -> labelRes = valObj
									is String -> labelStr = valObj
								}
								aResId == 0x01010002 || aName == "icon" -> iconRes = (valObj as? Number)?.toInt() ?: 0
							}

							"meta-data" -> when {
								aResId == 0x01010003 || aName == "name" -> attrKey = valObj?.toString()
								aResId == 0x01010024 || aResId == 0x01010025 || aName == "value" ||
									aName == "resource" -> attrVal = valObj
							}
						}
					}
					if (currentElem == "meta-data" && !attrKey.isNullOrBlank() && attrVal != null) {
						when (val v = attrVal) {
							is Int -> meta.putInt(attrKey, v)
							is Float -> meta.putFloat(attrKey, v)
							is Double -> meta.putDouble(attrKey, v)
							is Boolean -> meta.putBoolean(attrKey, v)
							else -> meta.putString(attrKey, v.toString())
						}
					}
				}
			}
			buf.position(start + size)
		}

		val pkg = pkgName ?: return null
		PackageInfo().apply {
			packageName = pkg
			versionCode = vCode
			versionName = vName
			applicationInfo = ApplicationInfo().apply {
				packageName = pkg
				sourceDir = file.absolutePath
				publicSourceDir = file.absolutePath
				this.labelRes = labelRes
				this.nonLocalizedLabel = labelStr
				this.icon = iconRes
				this.metaData = meta
			}
		}
	}.getOrNull()
}
