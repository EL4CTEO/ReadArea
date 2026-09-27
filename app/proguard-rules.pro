-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.jetbrains.annotations.**
-keep class * extends androidx.room3.RoomDatabase { <init>(); }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
