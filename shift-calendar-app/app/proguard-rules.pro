# Room and Compose ship their own consumer rules.
# Keep the core module's model classes readable in crash reports.
-keepattributes SourceFile,LineNumberTable

# [REL-01] review-ktx refers to a build-time-only annotation from play-services-basement; R8 just needs to know it's absent.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
