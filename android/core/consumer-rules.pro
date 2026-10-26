# JNA and the UniFFI bindings are reached by reflection and from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.qwantarc.messenger.core.** { *; }
-dontwarn java.awt.**
