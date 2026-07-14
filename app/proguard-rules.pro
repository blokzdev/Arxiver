# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Tink (security-crypto) references compile-only annotation libraries.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Hilt multibindings vs R8 (belt-and-braces on top of compat mode, see
# gradle.properties): v1.1.0 shipped with TodayViewModel's BindsModule and
# all @HiltWorker assisted factories stripped — ViewModel crash on first
# navigation, workers uninstantiable. Keep the generated plumbing.
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_HiltModule { *; }
-keep class * implements androidx.hilt.work.WorkerAssistedFactory { *; }
-keep @androidx.hilt.work.HiltWorker class * { *; }

# Jetpack DataStore Preferences embeds protobuf-javalite, which resolves its own
# generated message fields BY NAME via reflection at runtime. R8 renaming breaks
# PreferencesProto serialization ("Field value_ for ... not found" — the v1.1.1
# onboarding-write crash). Inert while minification is off (see build.gradle.kts)
# — kept so re-enabling shrinking is safe by default.
-keep class androidx.datastore.preferences.protobuf.** { *; }
-keep class androidx.datastore.preferences.PreferencesProto** { *; }
-keepclassmembers class androidx.datastore.preferences.** { <fields>; }

# pdfbox-android (P-Reader2 PFT.5): reflection-heavy font/resource loading + its transitive BouncyCastle (used
# for LOCAL PDF crypto only, never network). Inert today (isMinifyEnabled = false) but kept so re-enabling
# minification can't silently strip the font reflection that PDFTextStripper relies on. BouncyCastle references
# javax.naming / desktop-JCE classes that don't exist on Android — dontwarn them.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
