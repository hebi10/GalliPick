# GalliPick app-specific R8 rules (GP-R8-03).
# WorkManager persists fully qualified Worker names across app updates and instantiates
# the (Context, WorkerParameters) constructor reflectively. Keep only that ABI.
# https://developer.android.com/reference/androidx/work/WorkManager

-keep,allowoptimization class com.hebi.gallipick.data.DuplicateScanWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep,allowoptimization class com.hebi.gallipick.data.PhotoAnalysisWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep,allowoptimization class com.hebi.gallipick.data.PhotoOcrWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep,allowoptimization class com.hebi.gallipick.data.cloud.CloudAutoBackupWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room-generated implementations and SDK internals use library consumer rules.
# Do not add blanket keep/dontwarn rules; extend only for a documented runtime contract.
