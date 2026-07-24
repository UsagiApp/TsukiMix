-keep,allowoptimization class eu.kanade.tachiyomi.** { public protected *; }
-keep,allowoptimization class tachiyomi.** { public protected *; }
-keep,allowoptimization class keiyoushi.** { public protected *; }

-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }

-keep class okhttp3.zstd.** { *; }

-keep,allowoptimization class androidx.preference.PreferenceCategory { public protected *; }
-keep,allowoptimization class androidx.preference.Preference { public protected *; }
-keep,allowoptimization class androidx.preference.PreferenceScreen { public protected *; }
-keep,allowoptimization class androidx.preference.PreferenceGroup { public protected *; }
-keep,allowoptimization class androidx.preference.PreferenceManager { public protected *; }
-keep,allowoptimization class androidx.preference.EditTextPreference { public protected *; }
-keep,allowoptimization class androidx.preference.ListPreference { public protected *; }
-keep,allowoptimization class androidx.preference.SwitchPreference { public protected *; }
-keep,allowoptimization class androidx.preference.SwitchPreferenceCompat { public protected *; }
-keep,allowoptimization class androidx.preference.CheckBoxPreference { public protected *; }
-keep,allowoptimization class androidx.preference.MultiSelectListPreference { public protected *; }
-keep,allowoptimization class androidx.preference.TwoStatePreference { public protected *; }

-keep,allowoptimization class rx.Observable { public protected *; }
-keep,allowoptimization class rx.Single { public protected *; }
-keep,allowoptimization class rx.Completable { public protected *; }
-keep,allowoptimization class rx.Subscriber { public protected *; }
-keep,allowoptimization class rx.Observer { public protected *; }
-keep,allowoptimization class rx.Subscription { public protected *; }
-keep,allowoptimization class rx.Scheduler { public protected *; }
-keep,allowoptimization class rx.functions.Action { public protected *; }
-keep,allowoptimization class rx.functions.Action0 { public protected *; }
-keep,allowoptimization class rx.functions.Action1 { public protected *; }
-keep,allowoptimization class rx.functions.Action2 { public protected *; }
-keep,allowoptimization class rx.functions.Func0 { public protected *; }
-keep,allowoptimization class rx.functions.Func1 { public protected *; }
-keep,allowoptimization class rx.functions.Func2 { public protected *; }
-keep,allowoptimization class rx.functions.Func3 { public protected *; }
-keep,allowoptimization class rx.functions.Func4 { public protected *; }
-keep,allowoptimization class rx.functions.Func5 { public protected *; }
-keep,allowoptimization class rx.functions.Func6 { public protected *; }
-keep,allowoptimization class rx.functions.Func7 { public protected *; }
-keep,allowoptimization class rx.functions.Func8 { public protected *; }
-keep,allowoptimization class rx.functions.Func9 { public protected *; }

-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keep,allowoptimization class * extends uy.kohesive.injekt.api.TypeReference { *; }
-keep,allowoptimization class * extends uy.kohesive.injekt.api.FullTypeReference { *; }
