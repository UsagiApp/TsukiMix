package org.draken.tsukimix.core.parser.external

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val DEX_MAGIC = byteArrayOf(
	'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
)
private val DEX_REGEX = Regex("classes(\\d*)?\\.dex")

internal class DirectDexClassLoader(
	dexPath: String, optDir: String, libPath: String?, parent: ClassLoader,
) : DexClassLoader(dexPath, optDir, libPath, parent) {
	private val sys = getSystemClassLoader()

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		val cls = findLoadedClass(name)
			?: runCatching { sys?.loadClass(name) }.getOrNull()
			?: runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
		if (resolve) resolveClass(cls)
		return cls
	}
}

@SuppressLint("SetWorldWritable")
internal fun prepareDex(input: File, output: File): Boolean = runCatching {
	output.setWritable(true, false)
	output.delete()
	ZipFile(input).use { zip ->
		val hasManifest = zip.getEntry("AndroidManifest.xml") != null
		val hasDex = zip.entries().asSequence().any { it.name.matches(DEX_REGEX) }
		if (hasManifest && hasDex) {
			input.copyTo(output, overwrite = true)
			return@use normalizeLegacyDex(output)
		}
		val nested = zip.entries().asSequence()
			.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
		if (nested != null) {
			zip.getInputStream(nested).use { s -> output.outputStream().use { t -> s.copyTo(t) } }
			return@use ZipFile(output).use {
				it.getEntry("AndroidManifest.xml") != null &&
					it.entries().asSequence().any { e -> e.name.matches(DEX_REGEX) }
			} && normalizeLegacyDex(output)
		}
		false
	}
}.getOrDefault(false)

internal fun normalizeLegacyDex(file: File): Boolean {
	if (Build.VERSION.SDK_INT >= 26) return true
	val tmp = File(file.parentFile, "${file.name}.tmp")
	return runCatching {
		var maxDexIndex = 1
		ZipFile(file).use { zip ->
			ZipOutputStream(tmp.outputStream().buffered()).use { out ->
				val buf = ByteArray(8192)
				zip.entries().asSequence().forEach { entry ->
					out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
					if (entry.name.matches(DEX_REGEX)) {
						val match = Regex("classes(\\d*)\\.dex").matchEntire(entry.name)
						val idx = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
						if (idx > maxDexIndex) maxDexIndex = idx
						out.write(downgradeDex035(zip.getInputStream(entry).use { it.readBytes() }))
					} else {
						zip.getInputStream(entry).use { input ->
							var r: Int
							while (input.read(buf).also { r = it } >= 0) out.write(buf, 0, r)
						}
					}
					out.closeEntry()
				}
				getDexBytes()?.let { timeBytes ->
					val nextName = if (maxDexIndex == 1) "classes2.dex" else "classes${maxDexIndex + 1}.dex"
					out.putNextEntry(ZipEntry(nextName).apply { time = System.currentTimeMillis() })
					out.write(timeBytes)
					out.closeEntry()
				}
			}
		}
		tmp.renameTo(file)
	}.getOrElse { tmp.delete(); false }
}

@Volatile
private var cachedTimeDexBytes: ByteArray? = null

internal fun getDexBytes(): ByteArray? {
	cachedTimeDexBytes?.let { return it }
	return runCatching {
		val stream = DirectDexClassLoader::class.java.getResourceAsStream("/tsukimix/utils.dex.gz")
			?: return@runCatching null
		val bytes = stream.use { raw ->
			GZIPInputStream(raw).use { it.readBytes() }
		}
		cachedTimeDexBytes = bytes
		bytes
	}.getOrNull()
}

internal fun getDex(context: Context): File? {
	if (Build.VERSION.SDK_INT >= 26) return null
	val bytes = getDexBytes() ?: return null
	val dir = File(context.cacheDir, "tsukimix").also { it.mkdirs() }
	val dest = File(dir, "utils.dex")
	if (dest.exists() && dest.length() == bytes.size.toLong()) return dest
	return runCatching {
		val tmp = File(dir, "utils.dex.tmp")
		tmp.outputStream().use { it.write(bytes) }
		tmp.renameTo(dest)
		dest
	}.getOrNull()
}

private fun downgradeDex035(bytes: ByteArray): ByteArray {
	if (bytes.size < 32 || !bytes.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) return bytes
	if (bytes[4] != '0'.code.toByte() || bytes[5] != '3'.code.toByte() ||
		bytes[6] != '8'.code.toByte()
	) return bytes
	bytes[6] = '5'.code.toByte()
	MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 12)
	val cs = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
	for (i in 0 until 4) bytes[8 + i] = (cs ushr (i * 8)).toByte()
	return bytes
}

@SuppressLint("SetWorldReadable")
internal fun makeReadOnly(file: File): Boolean {
	if (!file.exists() || !file.isFile) return false
	file.setReadable(true, false)
	file.setWritable(false, false)
	return !file.canWrite()
}
