# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/android-sdk-linux/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# ECH (Conscrypt) — MANDATORY, even though the release build does not
# minify today. Conscrypt reaches
# ConscryptEch$PolicyTrustManager.getNetworkSecurityPolicy() by
# reflection; if R8 strips or renames it, setEchConfigList silently
# becomes a no-op (no error, no ECH extension on the wire) and the app
# would believe ECH works while it does not.
-keep class org.conscrypt.** { *; }
-keep class com.example.javbrowser.ConscryptEch** { *; }
