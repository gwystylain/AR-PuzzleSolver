# ARCore uses reflection for its native bridge.
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# Spellinator's wire messages. kotlinx.serialization ships rules for the common case;
# these keep the generated serializers of a sealed hierarchy reachable however R8 inlines.
-keepclassmembers @kotlinx.serialization.Serializable class com.puzzlesolver.spell.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.puzzlesolver.spell.**$$serializer { *; }
