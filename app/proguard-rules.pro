# Music Manager: se reduce el código pero sin renombrar clases (algunas librerías usan reflexión)
-dontobfuscate
-ignorewarnings
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

-keep class org.jaudiotagger.** { *; }
-keep class com.yausername.** { *; }
-keep class io.github.junkfood02.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-keep class com.pandxmax.musicmanager.** { *; }
