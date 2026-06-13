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
