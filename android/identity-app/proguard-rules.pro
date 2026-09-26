# kotlinx.serialization: las clases @Serializable de la librería (Account).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.dotrino.sdk.**$$serializer { *; }
-keepclassmembers class com.dotrino.sdk.** { *** Companion; }
-keepclasseswithmembers class com.dotrino.sdk.** { kotlinx.serialization.KSerializer serializer(...); }
