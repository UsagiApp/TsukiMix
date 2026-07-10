package eu.kanade.tachiyomi.util.system

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import kotlin.math.max

object GLUtil {

	val DEVICE_TEXTURE_LIMIT: Int by lazy {
		val egl = EGLContext.getEGL() as EGL10
		val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
		val version = IntArray(2)
		egl.eglInitialize(display, version)
		val totalConfigurations = IntArray(1)
		egl.eglGetConfigs(display, null, 0, totalConfigurations)
		val configurations = arrayOfNulls<EGLConfig>(totalConfigurations[0])
		egl.eglGetConfigs(display, configurations, totalConfigurations[0], totalConfigurations)
		val textureSize = IntArray(1)
		var maximumTextureSize = 0
		for (i in 0..<totalConfigurations[0]) {
			egl.eglGetConfigAttrib(display, configurations[i], EGL10.EGL_MAX_PBUFFER_WIDTH, textureSize)
			if (maximumTextureSize < textureSize[0]) maximumTextureSize = textureSize[0]
		}
		egl.eglTerminate(display)
		max(maximumTextureSize, SAFE_TEXTURE_LIMIT)
	}

	const val SAFE_TEXTURE_LIMIT: Int = 2048

	val CUSTOM_TEXTURE_LIMIT_OPTIONS: List<Int> by lazy {
		val steps = DEVICE_TEXTURE_LIMIT / MULTIPLIER
		buildList(steps) {
			add(DEVICE_TEXTURE_LIMIT)
			for (step in steps downTo 2) {
				val value = step * MULTIPLIER
				if (value < DEVICE_TEXTURE_LIMIT) add(value)
			}
		}
	}
}

private const val MULTIPLIER: Int = 1024
