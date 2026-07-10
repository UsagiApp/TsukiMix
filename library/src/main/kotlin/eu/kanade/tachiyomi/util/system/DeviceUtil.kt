package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

object DeviceUtil {

	val isMiui: Boolean by lazy {
		getSystemProperty("ro.miui.ui.version.name")?.isNotEmpty() ?: false
	}

	val miuiMajorVersion: Int? by lazy {
		if (!isMiui) return@lazy null
		Build.VERSION.INCREMENTAL
			.substringBefore('.')
			.trimStart('V')
			.toIntOrNull()
	}

	val isSamsung: Boolean by lazy {
		Build.MANUFACTURER.equals("samsung", ignoreCase = true)
	}

	val oneUiVersion: Double? by lazy {
		runCatching {
			val version = Build.VERSION::class.java
				.getDeclaredField("SEM_PLATFORM_INT")
				.getInt(null) - 90000
			if (version < 0) 1.0 else "${version / 10000}.${version % 10000 / 100}".toDouble()
		}.getOrNull()
	}

	val invalidDefaultBrowsers = listOf(
		"android",
		"com.hihonor.android.internal.app",
		"com.huawei.android.internal.app",
		"com.zui.resolver",
		"com.transsion.resolver",
		"com.android.intentresolver",
	)

	@SuppressLint("PrivateApi")
	fun isMiuiOptimizationDisabled(): Boolean {
		val sysProp = getSystemProperty("persist.sys.miui_optimization")
		if (sysProp == "0" || sysProp == "false") return true
		return runCatching {
			Class.forName("android.miui.AppOpsUtils")
				.getDeclaredMethod("isXOptMode")
				.invoke(null) as Boolean
		}.getOrDefault(false)
	}

	fun isLowRamDevice(context: Context): Boolean {
		val memInfo = ActivityManager.MemoryInfo()
		context.getSystemService<ActivityManager>()?.getMemoryInfo(memInfo)
		return memInfo.totalMem < 3L * 1024 * 1024 * 1024
	}

	@SuppressLint("PrivateApi")
	private fun getSystemProperty(key: String): String? = runCatching {
		Class.forName("android.os.SystemProperties")
			.getDeclaredMethod("get", String::class.java)
			.invoke(null, key) as String
	}.getOrNull()
}
