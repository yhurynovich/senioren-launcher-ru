-keep class com.seniorenlauncher.data.model.** { *; }
-keep class com.seniorenlauncher.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# TDLib JNI binding (native symbol names)
-keep class io.github.up9cloud.td.** { *; }
