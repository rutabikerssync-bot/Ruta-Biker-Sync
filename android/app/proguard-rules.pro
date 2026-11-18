# ==============================================================================
# REGLAS R8 / PROGUARD — RUTA BIKER SYNC (standardRelease)
# ==============================================================================

# 1. Atributos generales requeridos para Compose, Serialización y Criptografía
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# 2. Kotlinx Serialization (CBOR / JSON)
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-dontwarn kotlinx.serialization.**

# 3. Modelos de Dominio y Datos de Red / Mesh
-keep class com.rutabikersync.app.core.protocol.** { *; }
-keep class com.rutabikersync.app.core.crypto.** { *; }
-keep class com.rutabikersync.app.feature.groups.ClubDomain.** { *; }
-keep class com.rutabikersync.app.feature.sos.EmergencyRescuePacket { *; }
-keep class com.rutabikersync.app.feature.sos.SosAccessAudit { *; }
-keep class com.rutabikersync.app.feature.vault.VaultItem { *; }
-keep class com.rutabikersync.app.feature.vault.MedicalSheet { *; }
-keep class com.rutabikersync.app.feature.badges.GamificationDomainKt** { *; }
-keep class com.rutabikersync.app.feature.badges.BadgeItem { *; }
-keep class com.rutabikersync.app.feature.badges.MileageMilestone { *; }

# 4. Servicios Foreground, Receptores y Actividades Críticas
-keep class com.rutabikersync.app.core.mesh.RideService { *; }
-keep class com.rutabikersync.app.feature.sos.EmergencyLockScreenActivity { *; }
-keep class com.rutabikersync.app.MainActivity { *; }

# 5. Criptografía, Tink, JCE y Keystore
-keep class com.google.crypto.tink.** { *; }
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-dontwarn com.google.crypto.tink.**

# 6. MapLibre Native SDK (JNI, MapView, Callbacks y Atribución)
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keepclasseswithmembernames class org.maplibre.android.** {
    native <methods>;
}
-dontwarn org.maplibre.android.**

# 7. Google Play Billing Client & Play Integrity
-keep class com.android.billingclient.** { *; }
-keep interface com.android.billingclient.** { *; }
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.android.billingclient.**
-dontwarn com.google.android.play.core.integrity.**

# 8. Google Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.nearby.**

# 9. ZXing Barcode Scanning
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# 10. Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# 11. Jetpack Compose Runtime y ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
