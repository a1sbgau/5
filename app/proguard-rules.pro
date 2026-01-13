# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Gson classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.wechat.auto.ConfigManager$KeywordItem { *; }

# Keep accessibility service
-keep class com.wechat.auto.WeChatAccessibilityService { *; }

# Keep notification service
-keep class com.wechat.auto.NotificationService { *; }
