@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.Color.BLACK
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import android.R.id.button1
import android.R.id.button2
import android.R.id.button3
import android.R.id.message
import android.R.id.title
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.draken.tsukimix.core.parser.external.preference.AndroidPreferenceStore
import org.draken.tsukimix.core.parser.external.preference.PreferenceStore
import tsuki.network.UserAgents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

private const val DEFAULT_USER_AGENT = UserAgents.CHROME_MOBILE

class ExtensionBridge(
	private val context: Context,
	private val httpClient: OkHttpClient,
	private val defaultUserAgentProvider: () -> String,
	private val javaScriptEvaluator: suspend (String) -> String?,
) {
	@Volatile
	private var initialized = false

	private val androidCookieJar = AndroidCookieJar()

	@Synchronized
	fun initialize() {
		if (initialized) return
		QuickJs.setContext(context)
		val networkHelper = ExtensionNetworkHelper(
			context = context,
			baseClient = httpClient,
			androidCookieJar = androidCookieJar,
			userAgentProvider = defaultUserAgentProvider,
		)
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		val preferenceStore = AndroidPreferenceStore(context, sharedPreferences)
		val application = context.applicationContext as Application
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(application)
				addSingletonFactory<Context> { context.applicationContext }
				addSingletonFactory<NetworkHelper> { networkHelper }
				addSingletonFactory<OkHttpClient> { networkHelper.client }
				addSingletonFactory<CookieJar> { networkHelper.client.cookieJar }
				addSingletonFactory<AndroidCookieJar> { androidCookieJar }
				addSingletonFactory<SharedPreferences> { sharedPreferences }
				addSingletonFactory<PreferenceStore> { preferenceStore }
				addSingletonFactory<Json> { json }
				addSingletonFactory<StringFormat> { json }
				addSingletonFactory<SerialFormat> { json }
				addSingletonFactory<ProtoBuf> { ProtoBuf }
				addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(javaScriptEvaluator) }
			}
		})
		hookDialogs()
		initialized = true
	}
}

@SuppressLint("PrivateApi", "DiscouragedApi")
private fun hookDialogs() = runCatching {
	val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
		?: return@runCatching
	val f = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
	@Suppress("UNCHECKED_CAST")
	val views = f.get(wmg) as? ArrayList<View> ?: return@runCatching
	f.set(wmg, object : ArrayList<View>(views) {
		override fun add(element: View) = themeDialog(element).let { super.add(element) }
		override fun add(index: Int, element: View) { themeDialog(element); super.add(index, element) }
	})
}

@SuppressLint("DiscouragedApi")
private fun Context.col(k: String, def: Int): Int {
	val id = resources.getIdentifier(k, "attr", packageName)
	val tv = TypedValue()
	return if (id != 0 && theme.resolveAttribute(id, tv, true)) {
		if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
	} else def
}

@SuppressLint("DiscouragedApi")
private fun themeDialog(v: View) = v.post {
	runCatching {
		val lp = v.layoutParams as? LayoutParams
		if (lp?.type != TYPE_APPLICATION) return@runCatching
		val c = v.context ?: return@runCatching
		val cid = c.resources.getIdentifier("parentPanel", "id", c.packageName)
		if (cid != 0 && v.findViewById<View>(cid) != null) return@runCatching

		val tid = c.resources.getIdentifier("alertTitle", "id", "android")
		if (v.findViewById<View>(message) == null &&
			v.findViewById<View>(button1) == null &&
			(tid == 0 || v.findViewById<View>(tid) == null)
		) return@runCatching

		val night = (c.resources.configuration.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
		val def = if (night) 0xFF201F24.toInt() else 0xFFFFFFFF.toInt()
		val bg = if (night && c.col("colorSurface", def) == BLACK) BLACK
			else c.col("colorSurfaceContainerHigh", c.col("colorSurface", def))
		val fg = c.col("colorOnSurface", if (night) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt())
		val sub = c.col("colorOnSurfaceVariant", if (night) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt())
		val pri = c.col("colorPrimary", if (night) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())

		val dp = c.resources.displayMetrics.density
		v.background = InsetDrawable(
			GradientDrawable().apply { setColor(bg); cornerRadius = 28 * dp },
			(24 * dp).toInt(), 0, (24 * dp).toInt(), 0,
		)
		val pid = c.resources.getIdentifier("parentPanel", "id", "android")
		if (pid != 0) v.findViewById<View>(pid)?.background = null

		v.findViewById<TextView>(message)?.setTextColor(sub)
		v.findViewById<TextView>(title)?.setTextColor(fg)
		if (tid != 0) v.findViewById<TextView>(tid)?.setTextColor(fg)
		listOf(button1, button2, button3).forEach {
			v.findViewById<Button>(it)?.setTextColor(pri)
		}

		fun tint(x: View) {
			if (x is EditText) { x.setTextColor(fg); x.setHintTextColor(sub) }
			else if (x is ViewGroup) for (i in 0 until x.childCount) x.getChildAt(i)?.let(::tint)
		}
		tint(v)
	}
}
