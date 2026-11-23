package com.hebi.gallipick

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hebi.gallipick.ui.CloudAccountCard
import com.hebi.gallipick.ui.CloudAccountSettings
import com.hebi.gallipick.ui.CloudAdPlanObservation
import com.hebi.gallipick.ui.CloudRoute
import com.hebi.gallipick.ui.SecretCloudSection
import com.hebi.gallipick.ui.CloudAutoBackupSection
import com.hebi.gallipick.ui.CloudBackupSection
import com.hebi.gallipick.ui.CloudBillingSection
import com.hebi.gallipick.ui.CloudLibrarySection
import com.hebi.gallipick.ui.CloudSpaceSection
import com.hebi.gallipick.data.cloud.CloudSpaceBackupProof
import com.hebi.gallipick.ui.GalliHomeBanner
import com.hebi.gallipick.ui.rememberGalliAdsSession
import com.hebi.gallipick.ui.GalliPickApp
import com.hebi.gallipick.ui.GalliPickTheme
import com.hebi.gallipick.ui.TrashReviewDialog

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val mediaPermissionPreferences by lazy { getSharedPreferences("media_permission", MODE_PRIVATE) }
    private var mediaPermissionSettingsRequired by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val cloudAccount = (application as GalliPickApplication).cloudAccount
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
          val uiState by vm.ui.collectAsState()
          var cloudAdPlan by remember { mutableStateOf<CloudAdPlanObservation?>(null) }
          var billingScreenVisible by remember { mutableStateOf(false) }
          val adsSession = rememberGalliAdsSession(this@MainActivity, cloudAccount, cloudAdPlan, billingScreenVisible)
          val useDarkTheme = uiState.themeMode.isDark(isSystemInDarkTheme())
          val view = LocalView.current
          SideEffect {
              WindowCompat.getInsetsController(window, view).apply {
                  isAppearanceLightStatusBars = !useDarkTheme
                  isAppearanceLightNavigationBars = !useDarkTheme
              }
          }
          GalliPickTheme(themeMode = uiState.themeMode) {
            val trashReview = vm.trashReview
            val pending by trashReview.pending.collectAsState()
            val operationResult by trashReview.result.collectAsState()
            val catalogReady by vm.repository.catalogReady.collectAsState()
            val isResumed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            LaunchedEffect(Unit) { trashReview.resumeVerification() }
            LaunchedEffect(catalogReady) {
                if (!catalogReady) trashReview.invalidateReview()
            }
            LaunchedEffect(uiState.selected) { trashReview.onSelectionChanged(uiState.selected) }
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE) trashReview.invalidateReview()
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                if (result.keys.any { it != Manifest.permission.ACCESS_MEDIA_LOCATION }) {
                    mediaPermissionPreferences.edit().putBoolean("requested", true).apply()
                }
                refreshMediaAccess()
            }
            val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                trashReview.onApprovalResult(result.resultCode == Activity.RESULT_OK)
            }

            GalliPickApp(
                vm = vm,
                onRequestPermission = { permissionLauncher.launch(requiredPermissions()) },
                onOpenSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                mediaPermissionSettingsRequired = mediaPermissionSettingsRequired,
                onTrash = { trashReview.review(it, restore = false, isResumed = isResumed) },
                onOpenMedia = { item ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(item.uri), item.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) { vm.showMessage("이 파일을 열 수 있는 앱이 없거나 파일 접근 권한이 없습니다.") }
                },
                onRestore = { trashReview.review(it, restore = true, isResumed = isResumed) },
                result = operationResult,
                onDismissResult = trashReview::dismissResult,
                cloudConnection = cloudAccount,
                cloudContent = if (cloudAccount != null) {
                    { route, goHome ->
                        when (route) {
                            CloudRoute.OVERVIEW ->
                                CloudAccountCard { CloudAccountSettings(cloudAccount, this@MainActivity) }
                            CloudRoute.ACCOUNT_MANAGEMENT -> Unit
                            CloudRoute.BILLING_SIGN_IN ->
                                CloudAccountCard { CloudAccountSettings(cloudAccount, this@MainActivity, forAdRemoval = true) }
                            CloudRoute.LIBRARY ->
                                CloudLibrarySection(cloudAccount, onRestored = { vm.refresh() }) { uri, mimeType ->
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        })
                                    } catch (_: Exception) { vm.showMessage("복원한 파일을 열 수 있는 앱이 없습니다.") }
                                }
                            CloudRoute.BACKUP ->
                                CloudBackupSection(cloudAccount, uiState) {
                                    permissionLauncher.launch(requiredPermissions())
                                }
                            CloudRoute.SECRET -> SecretCloudSection(cloudAccount, uiState) {
                                permissionLauncher.launch(requiredPermissions())
                            }
                            CloudRoute.AUTO_BACKUP ->
                                CloudAutoBackupSection(cloudAccount, uiState) {
                                    permissionLauncher.launch(requiredPermissions())
                                }
                            CloudRoute.SPACE ->
                                cloudAccount.backup?.let { backup ->
                                    CloudSpaceSection(cloudAccount, uiState, vm.repository) { uid ->
                                        backup.listOriginals(uid).map {
                                            CloudSpaceBackupProof(it.sizeBytes, it.sha256)
                                        }.toSet()
                                    }
                                }
                            CloudRoute.BILLING -> CloudBillingSection(cloudAccount, this@MainActivity, onGoHome = goHome) { uid, plan ->
                                cloudAdPlan = CloudAdPlanObservation(uid, plan)
                            }
                        }
                    }
                } else null,
                homeBanner = { GalliHomeBanner(this@MainActivity, adsSession) },
                adsConfigured = adsSession.configured || adsSession.previewOnly,
                adRemoval = adsSession.removal,
                adPreviewEnabled = isDebuggable && adsSession.previewOnly,
                onConfirmExit = { finish() },
                onBillingScreenVisible = { billingScreenVisible = it },
                adsPrivacyOptionsRequired = adsSession.privacyOptionsRequired,
                onAdsPrivacyOptions = adsSession.openPrivacyOptions,
            )

            pending?.let { request ->
                TrashReviewDialog(
                    moving = request.items, kept = request.kept, restore = request.restore, excluded = request.excluded,
                    onDismiss = trashReview::dismissReview,
                    onProceed = {
                        trashReview.proceed(isResumed) { items, restore ->
                            val sender = MediaStore.createTrashRequest(contentResolver, items.map { Uri.parse(it.uri) }, !restore)
                            trashLauncher.launch(IntentSenderRequest.Builder(sender.intentSender).build())
                        }
                    },
                )
            }
          }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMediaAccess()
    }

    private fun refreshMediaAccess() {
        val access = mediaAccess()
        val canReselectPhotos = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        mediaPermissionSettingsRequired = access != MediaAccess.FULL && !canReselectPhotos &&
            mediaPermissionPreferences.getBoolean("requested", false) &&
            requiredPermissions().filterNot {
                it == Manifest.permission.ACCESS_MEDIA_LOCATION ||
                    it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED || granted(it)
            }
                .none { shouldShowRequestPermissionRationale(it) }
        vm.setAccess(access)
        vm.refresh()
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun mediaAccess(): MediaAccess = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
        Build.VERSION.SDK_INT >= 33 && (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) -> MediaAccess.PARTIAL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
        else -> MediaAccess.NONE
    }
}
