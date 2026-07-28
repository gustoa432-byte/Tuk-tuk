# TukTuk ProGuard / R8

# Keep Room entities & DAOs
-keep class com.blink.dtn.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.blink.dtn.**$$serializer { *; }
-keepclassmembers class com.blink.dtn.** {
    *** Companion;
}
-keepclasseswithmembers class com.blink.dtn.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose / ViewModel
-keep class * extends androidx.lifecycle.ViewModel { *; }

# BLE / reflection-light keep for parcelables if any
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
