# Klipora v10 release rules: R8 shrinks and obfuscates Klipora's own code; the native/reflective libraries are kept
# whole so nothing they look up by name (JNI callbacks, Jackson mapping, ad SDK) can break.

# FFmpegKit: JNI calls back into these classes by name
-keep class com.antonkarpenko.ffmpegkit.** { *; }
-keep class com.arthenica.** { *; }
# youtubedl-android + its JSON mapping (Jackson, by reflection) and archive helpers
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Unity Ads (WebView JS bridges)
-keep class com.unity3d.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# OkHttp / Okio ship their own rules; silence optional platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# Klipora: custom views referenced from XML layouts are kept by aapt; keep their constructors explicitly too
-keep public class * extends android.view.View { public <init>(android.content.Context); public <init>(android.content.Context, android.util.AttributeSet); public <init>(android.content.Context, android.util.AttributeSet, int); }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# Strip debug/verbose logging from release
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }
# Hide source file names in stack traces
-renamesourcefileattribute K
-keepattributes SourceFile,LineNumberTable
-repackageclasses 'k'

# readable names for Klipora's own error types (they appear in error messages)
-keepnames class com.viral.shortsmaker.** extends java.lang.Throwable

# v13 MediaPipe tasks (face tracking): JNI + protobuf-lite reflection
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**

# v14 FIX (2.4 face tracking crash): MediaPipe's Graph.<clinit> calls Flogger's GoogleLogger.forEnclosingClass(), which
# finds its caller by walking the stack and loads its backend by class NAME
# ("com.google.common.flogger.backend.system.DefaultPlatform"). R8 renamed/inlined Flogger (and repackaged it into 'k'),
# so the static init threw (ExceptionInInitializerError, then NoClassDefFoundError ...framework.Graph on every later
# use). Keep Flogger whole: no rename, no inlining, no removal.
-keep class com.google.common.flogger.** { *; }
-keepnames class com.google.common.flogger.**
-dontwarn com.google.common.flogger.**
# MediaPipe's other runtime dependencies that are reached by name from native code / reflection
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.android.odml.**

# v15 FIX (2.5 "Ads init: Field loadTimeoutMs_ for k.bp0 not found"): Unity Ads 4.12 talks to its ad gateway with
# protobuf-lite messages in the packages gateway.v1 / headerbidding.v1 (NOT under com.unity3d, so its own consumer rules
# don't cover them). protobuf-lite builds each message's schema at runtime by looking up the Java fields BY NAME
# ("loadTimeoutMs_"), so R8 must not rename or remove the fields of any GeneratedMessageLite subclass. Unity Ads ships
# no rule for this and the protobuf-javalite runtime on the classpath ships none either.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class gateway.v1.** { *; }
-keep class headerbidding.v1.** { *; }
-dontwarn gateway.v1.**
-dontwarn headerbidding.v1.**

# v21 (4.0) Unity Ads 4.21: its ad gateway messages moved to gatewayprotocol.v1 (4.21 ships a rule; kept here too).
-keep class gatewayprotocol.v1.** { *; }
-dontwarn gatewayprotocol.v1.**
# Unity's documented R8 rules (docs.unity.com ads-android requirements): WorkManager pieces made by reflection. Without
# them R8 full mode can strip them and Unity's operative events (impressions / clicks / completions) are silently dropped.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(); }
-dontwarn com.ironsource.adqualitysdk.**
-dontwarn com.google.android.gms.appset.*
-dontwarn com.google.android.gms.tasks.*
-dontwarn com.android.installreferrer.api.*
# Klipora's ad-link fallback is reached from the Application (kept by name for readable logs)
-keep class com.viral.shortsmaker.core.LinkOpener { *; }
