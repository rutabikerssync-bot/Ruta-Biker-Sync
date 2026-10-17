# Reglas R8 / ProGuard de Ruta Biker Sync
-keepattributes *Annotation*,Signature
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
