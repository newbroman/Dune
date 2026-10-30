# Nothing app-specific needed; Compose and AndroidX ship their own rules.

# Vosk talks to its native library through JNA, which finds classes and methods by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
