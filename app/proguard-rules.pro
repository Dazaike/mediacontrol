# R8 is on for release. Everything reached from the manifest (MainActivity,
# MediaRemoteApp, the Tile/Complication/Wearable services) is kept automatically;
# the rules below cover what R8 cannot see.

# Wearable DataLayer callbacks are invoked reflectively by Google Play services.
-keep class * implements com.google.android.gms.wearable.DataClient$OnDataChangedListener { *; }
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }

# ProtoLayout/Tiles builders are resolved by name from the protolayout runtime.
-keep class androidx.wear.protolayout.** { *; }
-keep class androidx.wear.tiles.** { *; }

# Coroutine internals R8 cannot trace, plus the debug agent probe hook.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
