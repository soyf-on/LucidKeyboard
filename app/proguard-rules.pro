# Keep serializers for the user dictionary / touch model JSON.
-keepclassmembers class dev.lucid.keyboard.core.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.lucid.keyboard.core.**$$serializer { *; }
