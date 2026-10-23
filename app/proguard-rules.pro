# Settings are passed to the service as a Serializable extra.
-keepclassmembers class com.sempers.brownie.NoiseEngineSettings {
    static final long serialVersionUID;
    <fields>;
}

# Readable crash stack traces in Play Console (mapping.txt is uploaded with the AAB)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
