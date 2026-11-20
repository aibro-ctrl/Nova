# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.nova.**$$serializer { *; }
-keepclassmembers class ai.nova.** { *** Companion; }
-keepclasseswithmembers class ai.nova.** { kotlinx.serialization.KSerializer serializer(...); }

# SQLCipher
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# Vosk + JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**

# Shizuku: команды оболочки через newProcess (вызывается рефлексией)
-keepclassmembers class rikka.shizuku.Shizuku { *** newProcess(...); }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

# sherpa-onnx (встроенный голос): нативный код читает поля конфигурации по именам через JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Офлайн-модель: нативный код ищет колбэк по имени «onToken» через JNI. Без этого правила R8
# переименовывает метод, JNI бросает NoSuchMethodError — и release-приложение падает (SIGABRT)
# на первом же слове ответа. Проверено по tombstone: «no non-static method …onToken(Ljava/lang/String;)Z».
-keep interface ai.nova.app.llm.LlamaNative$TokenListener { *; }
-keepclassmembers class * implements ai.nova.app.llm.LlamaNative$TokenListener {
    public boolean onToken(java.lang.String);
}
