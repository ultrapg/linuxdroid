package com.linuxdroid.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxdroid.app.gpu.GpuConfigGenerator
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.runtime.CpuCoreTier
import com.linuxdroid.app.runtime.CpuInfo
import com.linuxdroid.app.runtime.DesktopCatalog
import com.linuxdroid.app.runtime.DesktopEdition
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.runtime.RuntimeInstallService
import com.linuxdroid.app.session.ExternalDisplayMode
import com.linuxdroid.app.session.ResolutionPreset
import com.linuxdroid.app.session.ScreenOrientationPref
import com.linuxdroid.app.session.SessionPhase
import com.linuxdroid.app.session.BackgroundMode
import com.linuxdroid.app.session.SessionPrefs
import com.linuxdroid.app.session.SessionService
import com.linuxdroid.app.session.SessionState
import com.linuxdroid.app.session.TouchInputMode
import com.linuxdroid.app.wayland.DeXEnvironmentDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class FrontEndActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val skipAutoBoot = intent?.getBooleanExtra(EXTRA_SKIP_AUTOBOOT, false) == true
        if (savedInstanceState == null && !skipAutoBoot && SessionPrefs.autoBootOnLaunch(this) && LinuxRuntime.isReady(this)) {
            openDesktopSession()
            return
        }

        ThemeState.current.value = SessionPrefs.appTheme(this)
        setContent {
            LinuxDroidTheme {
                DashboardScreen(
                    onLaunchDesktop = { openDesktopSession() },
                    onOpenTerminal = { root ->
                        startActivity(
                            Intent(this, TerminalActivity::class.java)
                                .putExtra(TerminalActivity.EXTRA_AS_ROOT, root)
                        )
                    },
                )
            }
        }
    }

    private fun openDesktopSession() {
        startActivity(Intent(this, SessionActivity::class.java))
    }

    private var permissionFlowRunning = false
    private var askedAppSettings = false

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionFlowRunning = false
        // Continue with the next missing permission (or settings screen) once the dialog closed.
        window.decorView.post { requestBasePermissionsIfNeeded() }
    }

    override fun onResume() {
        super.onResume()
        // Post so the window is attached and focused; this makes the system dialog show reliably on app start.
        window.decorView.postDelayed({ requestBasePermissionsIfNeeded() }, 300L)
    }

    private fun requestBasePermissionsIfNeeded() {
        if (isFinishing || isDestroyed || permissionFlowRunning) return
        val runtimeNeeded = ArrayList<String>()
        // targetSdk 28: the classic system popup is shown for storage on every Android version.
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            runtimeNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            runtimeNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runtimeNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (runtimeNeeded.isNotEmpty()) {
            val canAsk = runtimeNeeded.any { shouldShowRequestPermissionRationale(it) } ||
                !SessionPrefs.permissionAsked(this)
            if (canAsk) {
                SessionPrefs.setPermissionAsked(this)
                permissionFlowRunning = true
                permissionLauncher.launch(runtimeNeeded.toTypedArray())
                return
            }
            // The system no longer shows its dialog (denied twice): send the user to the app settings once.
            if (!askedAppSettings) {
                askedAppSettings = true
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:$packageName")),
                    )
                }
                return
            }
        }
    }

    companion object {
        const val EXTRA_SKIP_AUTOBOOT = "skip_autoboot"
    }
}

private enum class DashboardTab(val label: String, val icon: ImageVector) {
    WORKSTATION("Home", Icons.Outlined.Computer),
    HARDWARE("Hardware", Icons.Outlined.Memory),
    DISPLAY("Display", Icons.Outlined.Monitor),
    SYSTEM("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    onLaunchDesktop: () -> Unit,
    onOpenTerminal: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionSnap by SessionState.state.collectAsState()
    val installSnap by RuntimeInstallService.state.collectAsState()

    val gpuInfo = remember { GpuInfo.detect() }
    val cpuTopology = remember { CpuInfo.detect() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf(DesktopCatalog.FALLBACK_EDITIONS) }
    var installedReady by remember { mutableStateOf(LinuxRuntime.isReady(context)) }
    var installedLabel by remember { mutableStateOf(LinuxRuntime.detectInstalledDesktopLabel(context)) }
    var installedEditionId by remember { mutableStateOf(LinuxRuntime.detectInstalledEditionId(context)) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var autoBoot by remember { mutableStateOf(SessionPrefs.autoBootOnLaunch(context)) }
    var gpuMode by remember { mutableStateOf(SessionPrefs.gpuMode(context)) }
    var cpuCoreLimit by remember { mutableIntStateOf(SessionPrefs.cpuCoreLimit(context)) }
    var selectedCpuSpec by remember { mutableStateOf(SessionPrefs.selectedCpuCoresSpec(context)) }
    var externalMode by remember { mutableStateOf(SessionPrefs.externalDisplayMode(context)) }
    var connectedExternalSummary by remember {
        mutableStateOf(DeXEnvironmentDetector.findExternalDisplay(context)?.let { DeXEnvironmentDetector.describeDisplay(it) })
    }
    var resolution by remember { mutableStateOf(SessionPrefs.resolutionPreset(context)) }
    var scalePref by remember { mutableFloatStateOf(SessionPrefs.displayScale(context)) }
    var refreshHzPref by remember { mutableIntStateOf(SessionPrefs.targetRefreshHz(context)) }
    var orientationPref by remember { mutableStateOf(SessionPrefs.orientation(context)) }
    var touchMode by remember { mutableStateOf(SessionPrefs.touchInputMode(context)) }
    var showFloatingControls by remember { mutableStateOf(SessionPrefs.showFloatingControls(context)) }
    var micEnabled by remember { mutableStateOf(SessionPrefs.micEnabled(context)) }
    var gpuDesktopEnabled by remember { mutableStateOf(SessionPrefs.gpuDesktop(context)) }
    var gpuAppsEnabled by remember { mutableStateOf(SessionPrefs.gpuApps(context)) }
    var fastPathEnabled by remember { mutableStateOf(SessionPrefs.prootFastPath(context)) }
    var noSeccompEnabled by remember { mutableStateOf(SessionPrefs.prootNoSeccomp(context)) }
    var username by remember { mutableStateOf(SessionPrefs.username(context)) }
    var customMounts by remember { mutableStateOf(SessionPrefs.customMounts(context)) }
    var showAddMountDialog by remember { mutableStateOf(false) }
    var logRefreshTick by remember { mutableIntStateOf(0) }
    var diagnosticLogPreview by remember { mutableStateOf("") }
    var sawStartingPhase by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sessionSnap.phase, sessionSnap.firstFrameReady) {
        when {
            sessionSnap.phase == SessionPhase.STARTING -> {
                sawStartingPhase = true
            }
            sawStartingPhase && sessionSnap.phase == SessionPhase.RUNNING && sessionSnap.firstFrameReady -> {
                sawStartingPhase = false
                onLaunchDesktop()
            }
            sessionSnap.phase == SessionPhase.STOPPED || sessionSnap.phase == SessionPhase.FAILED -> {
                sawStartingPhase = false
            }
        }
    }

    val currentConfigSig = remember(
        installedEditionId,
        resolution,
        scalePref,
        refreshHzPref,
        cpuCoreLimit,
        selectedCpuSpec,
        gpuMode,
        fastPathEnabled,
        gpuAppsEnabled,
        gpuDesktopEnabled,
        noSeccompEnabled,
        username,
        micEnabled,
        customMounts,
    ) {
        SessionPrefs.currentConfigSignature(context)
    }
    val hasPendingRestartChanges = (sessionSnap.phase == SessionPhase.RUNNING || sessionSnap.phase == SessionPhase.PAUSED) &&
        sessionSnap.activeConfigSignature != null &&
        sessionSnap.activeConfigSignature != currentConfigSig

    val resolvedGpu = remember(gpuMode, gpuInfo) {
        GpuConfigGenerator.resolve(gpuMode, gpuInfo, LinuxRuntime.VIRGL_SOCKET_GUEST_PATH)
    }

    val activeCoreSet = remember(selectedCpuSpec, cpuCoreLimit, cpuTopology) {
        cpuTopology.effectiveSelectedSet(selectedCpuSpec, cpuCoreLimit)
    }

    val selectedCoreIndices = remember(activeCoreSet) {
        activeCoreSet.toList().sorted()
    }

    val effectiveCpuCores = remember(selectedCoreIndices, cpuTopology.totalCores) {
        selectedCoreIndices.size.coerceIn(1, cpuTopology.totalCores)
    }

    // Live External Display detection (updates immediately when plugging/unplugging USB-C HDMI or Wireless Cast)
    DisposableEffect(context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                connectedExternalSummary = DeXEnvironmentDetector.findExternalDisplay(context)
                    ?.let { DeXEnvironmentDetector.describeDisplay(it) }
            }

            override fun onDisplayRemoved(displayId: Int) {
                connectedExternalSummary = DeXEnvironmentDetector.findExternalDisplay(context)
                    ?.let { DeXEnvironmentDetector.describeDisplay(it) }
            }

            override fun onDisplayChanged(displayId: Int) {
                connectedExternalSummary = DeXEnvironmentDetector.findExternalDisplay(context)
                    ?.let { DeXEnvironmentDetector.describeDisplay(it) }
            }
        }
        dm?.registerDisplayListener(listener, null)
        onDispose { dm?.unregisterDisplayListener(listener) }
    }

    LaunchedEffect(Unit) {
        val fetched = withContext(Dispatchers.IO) { DesktopCatalog.fetchCatalogOrFallback() }
        catalog = fetched
    }

    LaunchedEffect(installSnap.active, installSnap.stage) {
        installedReady = LinuxRuntime.isReady(context)
        installedLabel = LinuxRuntime.detectInstalledDesktopLabel(context)
        installedEditionId = LinuxRuntime.detectInstalledEditionId(context)
    }

    LaunchedEffect(selectedTab, logRefreshTick, sessionSnap.phase) {
        if (selectedTab == DashboardTab.SYSTEM.ordinal) {
            diagnosticLogPreview = withContext(Dispatchers.IO) {
                val tail = SessionService.collectDiagnosticLogTail(LinuxRuntime.logDir(context), maxLines = 48)
                if (tail.isEmpty()) "No session logs recorded yet." else tail.joinToString("\n")
            }
        }
    }

    val importArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && installedEditionId == null) {
            RuntimeInstallService.startImport(context, uri)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micEnabled = granted
        SessionPrefs.setMicEnabled(context, granted)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            val resolvedPath = resolveDocumentTreeToPosixPath(treeUri)
            if (resolvedPath != null) {
                val folderName = File(resolvedPath).name.ifBlank { "mount" }
                SessionPrefs.addCustomMount(context, folderName, resolvedPath)
                customMounts = SessionPrefs.customMounts(context)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LinuxDroid",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${cpuTopology.totalCores} CPU Cores • ${gpuInfo.shortSummary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (installedReady && !installSnap.active && selectedTab != DashboardTab.WORKSTATION.ordinal) {
                        FilledTonalButton(
                            onClick = onLaunchDesktop,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    hasPendingRestartChanges -> "Restart"
                                    sessionSnap.phase == SessionPhase.RUNNING -> "Open"
                                    else -> "Launch"
                                },
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                DashboardTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 880.dp),
            ) {
                // Tab Viewport (Independent scrollable container per tab)
                val tabScrollState = rememberScrollState()
                LaunchedEffect(selectedTab) {
                    tabScrollState.scrollTo(0)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(tabScrollState)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (hasPendingRestartChanges) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Restart required",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Restart the desktop session to apply your updated settings.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = onLaunchDesktop,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp),
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restart", softWrap = false, maxLines = 1)
                                }
                            }
                        }
                    }

                    when (DashboardTab.entries[selectedTab]) {
                        DashboardTab.WORKSTATION -> WorkstationTabContent(
                            sessionSnap = sessionSnap,
                            installSnap = installSnap,
                            installedReady = installedReady,
                            installedLabel = installedLabel,
                            installedEditionId = installedEditionId,
                            hasPendingRestartChanges = hasPendingRestartChanges,
                            username = username,
                            effectiveCpuCores = effectiveCpuCores,
                            totalCpuCores = cpuTopology.totalCores,
                            gpuSummary = resolvedGpu.summary,
                            externalMode = externalMode,
                            connectedExternalSummary = connectedExternalSummary,
                            catalog = catalog,
                            onLaunchDesktop = onLaunchDesktop,
                            onTogglePause = { SessionService.togglePause(context) },
                            onShutdown = { SessionService.shutdown(context) },
                            onOpenTerminal = onOpenTerminal,
                            onInstallEdition = { edition -> RuntimeInstallService.startDownload(context, edition) },
                            onRequestDeleteEdition = { showDeleteConfirmDialog = true },
                            onCancelInstall = { RuntimeInstallService.cancel(context) },
                            onImportCustomRootfs = { importArchiveLauncher.launch(arrayOf("*/*")) },
                        )

                        DashboardTab.HARDWARE -> HardwareTabContent(
                            cpuTopology = cpuTopology,
                            effectiveCpuCores = effectiveCpuCores,
                            selectedCoreIndices = selectedCoreIndices,
                            onSelectCpuCores = { cores ->
                                val normalized = cores.filter { it in 0 until cpuTopology.totalCores }.distinct().sorted()
                                val allSelected = normalized.isEmpty() || normalized.size >= cpuTopology.totalCores
                                if (allSelected) {
                                    selectedCpuSpec = ""
                                    cpuCoreLimit = 0
                                    SessionPrefs.setSelectedCpuCores(context, emptyList(), cpuTopology.totalCores)
                                    SessionPrefs.setCpuCoreLimit(context, 0)
                                } else {
                                    selectedCpuSpec = normalized.joinToString(",")
                                    cpuCoreLimit = normalized.size
                                    SessionPrefs.setSelectedCpuCores(context, normalized, cpuTopology.totalCores)
                                }
                            },
                            gpuInfo = gpuInfo,
                            gpuMode = gpuMode,
                            resolvedGpuSummary = resolvedGpu.summary,
                            onSelectGpuMode = { mode ->
                                gpuMode = mode
                                SessionPrefs.setGpuMode(context, mode)
                            },
                            gpuDesktopEnabled = gpuDesktopEnabled,
                            onToggleGpuDesktop = {
                                gpuDesktopEnabled = it
                                SessionPrefs.setGpuDesktop(context, it)
                            },
                            gpuAppsEnabled = gpuAppsEnabled,
                            onToggleGpuApps = {
                                gpuAppsEnabled = it
                                SessionPrefs.setGpuApps(context, it)
                            },
                            fastPathEnabled = fastPathEnabled,
                            onToggleFastPath = {
                                fastPathEnabled = it
                                SessionPrefs.setProotFastPath(context, it)
                            },
                            noSeccompEnabled = noSeccompEnabled,
                            onToggleNoSeccomp = {
                                noSeccompEnabled = it
                                SessionPrefs.setProotNoSeccomp(context, it)
                            },
                        )

                        DashboardTab.DISPLAY -> DisplayTabContent(
                            externalMode = externalMode,
                            connectedExternalSummary = connectedExternalSummary,
                            onSelectExternalMode = { mode ->
                                externalMode = mode
                                SessionPrefs.setExternalDisplayMode(context, mode)
                            },
                            resolution = resolution,
                            onSelectResolution = { preset ->
                                resolution = preset
                                SessionPrefs.setResolutionPreset(context, preset)
                            },
                            scalePref = scalePref,
                            onSelectScale = { value ->
                                scalePref = value
                                SessionPrefs.setDisplayScale(context, value)
                            },
                            refreshHzPref = refreshHzPref,
                            onSelectRefreshHz = { hz ->
                                refreshHzPref = hz
                                SessionPrefs.setTargetRefreshHz(context, hz)
                            },
                            orientationPref = orientationPref,
                            onSelectOrientation = { orient ->
                                orientationPref = orient
                                SessionPrefs.setOrientation(context, orient)
                            },
                            touchMode = touchMode,
                            onSelectTouchMode = { mode ->
                                touchMode = mode
                                SessionPrefs.setTouchInputMode(context, mode)
                            },
                            showFloatingControls = showFloatingControls,
                            onToggleFloatingControls = { enabled ->
                                showFloatingControls = enabled
                                SessionPrefs.setShowFloatingControls(context, enabled)
                            },
                        )

                        DashboardTab.SYSTEM -> SystemTabContent(
                            autoBoot = autoBoot,
                            onToggleAutoBoot = {
                                autoBoot = it
                                SessionPrefs.setAutoBootOnLaunch(context, it)
                            },
                            username = username,
                            onUsernameChange = {
                                if (it.isNotBlank()) {
                                    SessionPrefs.setUsername(context, it)
                                    username = SessionPrefs.username(context)
                                }
                            },
                            customMounts = customMounts,
                            onPickFolder = { folderPickerLauncher.launch(null) },
                            onAddPathClick = { showAddMountDialog = true },
                            onRemoveMount = { name ->
                                SessionPrefs.removeCustomMount(context, name)
                                customMounts = SessionPrefs.customMounts(context)
                            },
                            micEnabled = micEnabled,
                            onToggleMic = { want ->
                                if (want && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    micEnabled = want
                                    SessionPrefs.setMicEnabled(context, want)
                                }
                            },
                            diagnosticLogPreview = diagnosticLogPreview,
                            onRefreshLogs = { logRefreshTick++ },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Installed Edition?") },
            text = {
                Text(
                    "Delete \"$installedLabel\"?\n\n" +
                        "This will stop any running session and permanently remove the Linux root filesystem " +
                        "and /home/$username. Your Android storage is not affected."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        RuntimeInstallService.startDelete(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showAddMountDialog) {
        var mountName by remember { mutableStateOf("") }
        var mountPath by remember { mutableStateOf("/storage/") }
        AlertDialog(
            onDismissRequest = { showAddMountDialog = false },
            title = { Text("Add Shared Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mountName,
                        onValueChange = { mountName = it },
                        label = { Text("Folder Name (e.g. projects)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = mountPath,
                        onValueChange = { mountPath = it },
                        label = { Text("Android Directory Path") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (mountPath.isNotBlank()) {
                        val name = mountName.ifBlank { File(mountPath).name.ifBlank { "shared" } }
                        SessionPrefs.addCustomMount(context, name, mountPath.trim())
                        customMounts = SessionPrefs.customMounts(context)
                    }
                    showAddMountDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMountDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkstationTabContent(
    sessionSnap: com.linuxdroid.app.session.SessionSnapshot,
    installSnap: com.linuxdroid.app.runtime.InstallProgress,
    installedReady: Boolean,
    installedLabel: String,
    installedEditionId: String?,
    hasPendingRestartChanges: Boolean,
    username: String,
    effectiveCpuCores: Int,
    totalCpuCores: Int,
    gpuSummary: String,
    externalMode: ExternalDisplayMode,
    connectedExternalSummary: String?,
    catalog: List<DesktopEdition>,
    onLaunchDesktop: () -> Unit,
    onTogglePause: () -> Unit,
    onShutdown: () -> Unit,
    onOpenTerminal: (Boolean) -> Unit,
    onInstallEdition: (DesktopEdition) -> Unit,
    onRequestDeleteEdition: () -> Unit,
    onCancelInstall: () -> Unit,
    onImportCustomRootfs: () -> Unit,
) {
    // 1. Workstation Launch & Live Session Status Card (Fixed-line text so height never jitters)
    SectionCard {
        val statusColor = when (sessionSnap.phase) {
            SessionPhase.RUNNING -> MaterialTheme.colorScheme.secondary
            SessionPhase.STARTING, SessionPhase.PAUSED -> MaterialTheme.colorScheme.tertiary
            SessionPhase.FAILED -> MaterialTheme.colorScheme.error
            else -> if (installedReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = installedLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (sessionSnap.phase) {
                        SessionPhase.STOPPED -> {
                            if (installedReady) "Ready to launch • User: $username"
                            else "Install a desktop edition below to begin"
                        }
                        SessionPhase.FAILED -> {
                            sessionSnap.errorMessage ?: "Startup failed — check logs in the System tab."
                        }
                        SessionPhase.STARTING -> {
                            "Step ${sessionSnap.bootStep.coerceAtLeast(1)} of ${sessionSnap.bootTotalSteps} • ${sessionSnap.statusText}"
                        }
                        else -> "${sessionSnap.statusText} • ${sessionSnap.resolutionText}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sessionSnap.phase == SessionPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (sessionSnap.phase == SessionPhase.STARTING) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Hardware & Output Summary Badges
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InfoPill(
                text = if (effectiveCpuCores >= totalCpuCores) {
                    "$totalCpuCores CPU Cores"
                } else {
                    "$effectiveCpuCores / $totalCpuCores CPU Cores"
                }
            )
            InfoPill(text = "GPU: $gpuSummary")
            InfoPill(
                text = when {
                    externalMode == ExternalDisplayMode.EXTERNAL_ONLY && connectedExternalSummary != null ->
                        "External Display Ready"
                    externalMode == ExternalDisplayMode.EXTERNAL_ONLY ->
                        "Waiting for External Display"
                    connectedExternalSummary != null ->
                        "External Display Connected"
                    else -> externalMode.title
                },
                accent = connectedExternalSummary != null,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onLaunchDesktop,
                enabled = installedReady && !installSnap.active,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(
                    imageVector = if (hasPendingRestartChanges) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        hasPendingRestartChanges -> "Restart Desktop"
                        sessionSnap.phase == SessionPhase.STARTING -> "View Startup"
                        sessionSnap.phase == SessionPhase.RUNNING -> "Open Desktop"
                        sessionSnap.phase == SessionPhase.PAUSED -> "Resume Desktop"
                        sessionSnap.phase == SessionPhase.FAILED -> "Retry Launch"
                        else -> "Launch Desktop"
                    },
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (sessionSnap.phase == SessionPhase.RUNNING || sessionSnap.phase == SessionPhase.PAUSED) {
                OutlinedButton(
                    onClick = onTogglePause,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.height(44.dp),
                ) {
                    Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (sessionSnap.phase == SessionPhase.PAUSED) "Resume" else "Pause",
                        softWrap = false,
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onShutdown,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Shut Down")
                }
            }
        }

        if (installedReady && !installSnap.active) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenTerminal(false) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Terminal ($username)", softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { onOpenTerminal(true) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Root Shell", softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

        // 2. Linux Desktop Editions Manager (Fixed-height scrollable containers + Single-Edition Delete Confirmation)
    SectionCard {
        SectionHeader("Desktop Editions")
        Text(
            text = if (installedEditionId != null) {
                "One desktop edition can be installed at a time. Delete the current edition to switch."
            } else {
                "Select an Ubuntu 24.04 LTS desktop environment to download and install."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        if (installSnap.active) {
            // Fixed-height 184.dp progress box so downloading/extracting/deleting NEVER flickers or shifts layout
            val animatedFraction by animateFloatAsState(
                targetValue = installSnap.fraction.coerceIn(0f, 1f),
                label = "installProgress",
            )
            val installLogScroll = rememberScrollState()
            val logContent = remember(installSnap.recentEntries) {
                if (installSnap.recentEntries.isEmpty()) "Preparing..."
                else installSnap.recentEntries.joinToString("\n")
            }
            LaunchedEffect(logContent) {
                installLogScroll.scrollTo(installLogScroll.maxValue)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(184.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = installSnap.stage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (installSnap.fraction >= 0f) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${(installSnap.fraction * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium.merge(
                                TextStyle(fontFeatureSettings = "tnum")
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (installSnap.fraction >= 0f) {
                    LinearProgressIndicator(
                        progress = { animatedFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // Fixed-height scrollable console box
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(installLogScroll)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = logContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!installSnap.deleting) {
                        OutlinedButton(
                            onClick = onCancelInstall,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Cancel")
                        }
                    } else {
                        Text(
                            text = "Cleaning up...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            if (installSnap.error != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Text(
                        text = "Error: ${installSnap.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // Fixed-height 284.dp scrollable editions container (zero dynamic resizing)
            val editionsScroll = rememberScrollState()
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(284.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(editionsScroll)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (installedEditionId == "custom-rootfs") {
                        EditionRow(
                            title = installedLabel,
                            description = "Imported ARM64 Linux root filesystem.",
                            sizeLabel = "Custom Archive",
                            isInstalled = true,
                            anotherInstalled = false,
                            onInstall = {},
                            onDelete = onRequestDeleteEdition,
                        )
                    }

                    for (edition in catalog) {
                        val isThisInstalled = installedEditionId == edition.id
                        val isAnotherInstalled = installedEditionId != null && !isThisInstalled
                        EditionRow(
                            title = edition.title,
                            description = edition.description,
                            sizeLabel = edition.formattedSize(),
                            isInstalled = isThisInstalled,
                            anotherInstalled = isAnotherInstalled,
                            onInstall = { onInstallEdition(edition) },
                            onDelete = onRequestDeleteEdition,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onImportCustomRootfs,
                enabled = installedEditionId == null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (installedEditionId == null) {
                        "Import Custom Rootfs"
                    } else {
                        "Delete current edition to import custom archive"
                    },
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HardwareTabContent(
    cpuTopology: com.linuxdroid.app.runtime.CpuTopology,
    effectiveCpuCores: Int,
    selectedCoreIndices: List<Int>,
    onSelectCpuCores: (List<Int>) -> Unit,
    gpuInfo: GpuInfo,
    gpuMode: GpuDriverMode,
    resolvedGpuSummary: String,
    onSelectGpuMode: (GpuDriverMode) -> Unit,
    gpuDesktopEnabled: Boolean,
    onToggleGpuDesktop: (Boolean) -> Unit,
    gpuAppsEnabled: Boolean,
    onToggleGpuApps: (Boolean) -> Unit,
    fastPathEnabled: Boolean,
    onToggleFastPath: (Boolean) -> Unit,
    noSeccompEnabled: Boolean,
    onToggleNoSeccomp: (Boolean) -> Unit,
) {
    val totalCores = cpuTopology.totalCores
    val selectedSet = remember(selectedCoreIndices) { selectedCoreIndices.toSet() }
    val allCoreIndices = remember(cpuTopology) { cpuTopology.allCoreIndices() }
    val perfIndices = remember(cpuTopology) { cpuTopology.performanceCoreIndices() }
    val primeIndices = remember(cpuTopology) { cpuTopology.primeCoreIndices() }
    val effIndices = remember(cpuTopology) { cpuTopology.efficiencyCoreIndices() }

    // 1. CPU Core Allocation & Per-Core / Cluster Selection Card
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CPU Cores & Affinity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = cpuTopology.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                val usingAll = selectedSet.size >= totalCores
                Text(
                    text = if (usingAll) {
                        "Using All $totalCores CPU Cores"
                    } else {
                        "Pinned to $effectiveCpuCores of $totalCores CPU Cores (CPU ${selectedCoreIndices.joinToString(", ")})"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (usingAll) {
                        "Linux schedules tasks across all performance and efficiency cores for maximum speed."
                    } else {
                        "Linux processes are restricted to ${selectedCoreIndices.joinToString(", ") { "CPU $it" }}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                if (totalCores > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Cluster Presets",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = usingAll,
                            onClick = { onSelectCpuCores(allCoreIndices) },
                            label = { Text("All Cores ($totalCores)") },
                        )
                        if (perfIndices.size in 1 until totalCores) {
                            val isPerfSelected = selectedSet == perfIndices.toSet()
                            FilterChip(
                                selected = isPerfSelected,
                                onClick = { onSelectCpuCores(perfIndices) },
                                label = { Text("Performance Cores (${perfIndices.size})") },
                            )
                        }
                        if (primeIndices.isNotEmpty() && primeIndices.size < perfIndices.size) {
                            val isPrimeSelected = selectedSet == primeIndices.toSet()
                            FilterChip(
                                selected = isPrimeSelected,
                                onClick = { onSelectCpuCores(primeIndices) },
                                label = { Text("Prime Cores (${primeIndices.size})") },
                            )
                        }
                        if (effIndices.size in 1 until totalCores) {
                            val isEffSelected = selectedSet == effIndices.toSet()
                            FilterChip(
                                selected = isEffSelected,
                                onClick = { onSelectCpuCores(effIndices) },
                                label = { Text("Efficiency Cores (${effIndices.size})") },
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Individual Cores",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))

                    // Fixed-height scrollable individual core selector (never resizes card)
                    val coresScroll = rememberScrollState()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(188.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(coresScroll)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (core in cpuTopology.cores) {
                                val isChecked = core.index in selectedSet
                                val tierColor = when (core.tier) {
                                    CpuCoreTier.PRIME -> MaterialTheme.colorScheme.secondary
                                    CpuCoreTier.PERFORMANCE -> MaterialTheme.colorScheme.primary
                                    CpuCoreTier.EFFICIENCY -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isChecked) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = if (isChecked) {
                                                if (selectedSet.size > 1) (selectedSet - core.index).toList().sorted() else selectedCoreIndices
                                            } else {
                                                (selectedSet + core.index).toList().sorted()
                                            }
                                            onSelectCpuCores(next)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = null,
                                            )
                                            Column {
                                                Text(
                                                    text = "CPU ${core.index} • ${core.formattedGhz()}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = when (core.tier) {
                                                        CpuCoreTier.PRIME -> "Prime Core"
                                                        CpuCoreTier.PERFORMANCE -> "Performance Core"
                                                        CpuCoreTier.EFFICIENCY -> "Efficiency Core"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = tierColor.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = core.tier.shortBadge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = tierColor,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Core Count Slider",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = effectiveCpuCores.toFloat(),
                        onValueChange = { raw ->
                            val count = raw.roundToInt().coerceIn(1, totalCores)
                            onSelectCpuCores(cpuTopology.selectPreferredCores(count))
                        },
                        valueRange = 1f..totalCores.toFloat(),
                        steps = (totalCores - 2).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // 2. Full SoC GPU Acceleration Card (Fixed-height scrollable driver list)
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GPU Hardware Acceleration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${gpuInfo.shortSummary()} • $resolvedGpuSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val gpuListScroll = rememberScrollState()
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .height(268.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(gpuListScroll)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mode in GpuDriverMode.entries) {
                    SelectableOptionCard(
                        selected = gpuMode == mode,
                        title = mode.title,
                        subtitle = mode.subtitle,
                        onClick = { onSelectGpuMode(mode) },
                    )
                }
            }
        }
    }

    // 3. Execution Engine Tuning Card
    SectionCard {
        SectionHeader("Execution Engine")
        SettingToggleRow(
            title = "GPU Desktop Compositing",
            subtitle = "Draw the desktop, panels and window UIs on the GPU for smoother scrolling and animations. If it fails to start, LinuxDroid falls back to software automatically.",
            checked = gpuDesktopEnabled,
            onCheckedChange = onToggleGpuDesktop,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingToggleRow(
            title = "GPU Acceleration for Apps",
            subtitle = "Run Firefox, video players, Blender, Krita, GIMP, Kdenlive, OBS and games on the GPU. Apps that fail to start fall back to software automatically. The desktop shell itself stays software-composited.",
            checked = gpuAppsEnabled,
            onCheckedChange = onToggleGpuApps,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingToggleRow(
            title = "Fast-Path Syscall Acceleration",
            subtitle = "Accelerates ARM64 filesystem syscalls in-process for faster app startup, browsing, and compilation.",
            checked = fastPathEnabled,
            onCheckedChange = onToggleFastPath,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingToggleRow(
            title = "Seccomp Compatibility Mode",
            subtitle = "Disables PRoot seccomp filters for devices with strict vendor kernels. Enable only if startup fails.",
            checked = noSeccompEnabled,
            onCheckedChange = onToggleNoSeccomp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayTabContent(
    externalMode: ExternalDisplayMode,
    connectedExternalSummary: String?,
    onSelectExternalMode: (ExternalDisplayMode) -> Unit,
    resolution: ResolutionPreset,
    onSelectResolution: (ResolutionPreset) -> Unit,
    scalePref: Float,
    onSelectScale: (Float) -> Unit,
    refreshHzPref: Int,
    onSelectRefreshHz: (Int) -> Unit,
    orientationPref: ScreenOrientationPref,
    onSelectOrientation: (ScreenOrientationPref) -> Unit,
    touchMode: TouchInputMode,
    onSelectTouchMode: (TouchInputMode) -> Unit,
    showFloatingControls: Boolean,
    onToggleFloatingControls: (Boolean) -> Unit,
) {
    // 1. External Monitor Output Card (Non-DeX Presentation + DeX)
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Monitor,
                contentDescription = null,
                tint = if (connectedExternalSummary != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "External Display Output",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (connectedExternalSummary != null) {
                        "Connected: $connectedExternalSummary"
                    } else {
                        "No external display detected • Supports USB-C / HDMI monitors and wireless cast"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connectedExternalSummary != null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            title = "External Display Only",
            subtitle = "Render the Linux desktop exclusively on a connected monitor or TV while using your phone as a trackpad and keyboard.",
            checked = externalMode == ExternalDisplayMode.EXTERNAL_ONLY,
            onCheckedChange = { onlyExt ->
                onSelectExternalMode(
                    if (onlyExt) ExternalDisplayMode.EXTERNAL_ONLY else ExternalDisplayMode.AUTO_EXTERNAL
                )
            },
        )

        Spacer(Modifier.height(8.dp))

        // Fixed-height scrollable mode list
        val extScroll = rememberScrollState()
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .height(228.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(extScroll)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mode in ExternalDisplayMode.entries) {
                    SelectableOptionCard(
                        selected = externalMode == mode,
                        title = mode.title,
                        subtitle = mode.subtitle,
                        onClick = { onSelectExternalMode(mode) },
                    )
                }
            }
        }
    }

    // 2. Resolution, Scaling, Refresh Rate & Touch Input
    SectionCard {
        SectionHeader("Display & Input")

        Text("Desktop Resolution", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in ResolutionPreset.entries) {
                FilterChip(
                    selected = resolution == preset,
                    onClick = { onSelectResolution(preset) },
                    label = { Text(preset.label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Display Scaling", style = MaterialTheme.typography.labelLarge)
        val scales = listOf(0f to "Auto", 1.0f to "100%", 1.25f to "125%", 1.5f to "150%", 1.75f to "175%", 2.0f to "200%")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in scales) {
                FilterChip(
                    selected = scalePref == value,
                    onClick = { onSelectScale(value) },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Refresh Rate", style = MaterialTheme.typography.labelLarge)
        val rates = listOf(0 to "Auto (Max)", 60 to "60 Hz", 90 to "90 Hz", 120 to "120 Hz")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((hz, label) in rates) {
                FilterChip(
                    selected = refreshHzPref == hz,
                    onClick = { onSelectRefreshHz(hz) },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Screen Orientation", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (orient in ScreenOrientationPref.entries) {
                FilterChip(
                    selected = orientationPref == orient,
                    onClick = { onSelectOrientation(orient) },
                    label = { Text(orient.label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Touch Input Mode", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in TouchInputMode.entries) {
                FilterChip(
                    selected = touchMode == mode,
                    onClick = { onSelectTouchMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        SettingToggleRow(
            title = "Floating Quick Controls",
            subtitle = "Show a compact keyboard and menu button in the top-right corner of the desktop.",
            checked = showFloatingControls,
            onCheckedChange = onToggleFloatingControls,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemTabContent(
    autoBoot: Boolean,
    onToggleAutoBoot: (Boolean) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    customMounts: Map<String, String>,
    onPickFolder: () -> Unit,
    onAddPathClick: () -> Unit,
    onRemoveMount: (String) -> Unit,
    micEnabled: Boolean,
    onToggleMic: (Boolean) -> Unit,
    diagnosticLogPreview: String,
    onRefreshLogs: () -> Unit,
) {
    // 0. Appearance & Background behavior
    val settingsContext = androidx.compose.ui.platform.LocalContext.current
    var appTheme by remember { mutableStateOf(SessionPrefs.appTheme(settingsContext)) }
    var bgMode by remember { mutableStateOf(SessionPrefs.backgroundMode(settingsContext)) }
    var bgDelay by remember { mutableStateOf(SessionPrefs.backgroundDelaySec(settingsContext)) }
    SectionCard {
        SectionHeader("Appearance")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (theme in AppTheme.entries) {
                SelectableOptionCard(
                    selected = appTheme == theme,
                    title = theme.label,
                    subtitle = theme.description,
                    onClick = {
                        appTheme = theme
                        SessionPrefs.setAppTheme(settingsContext, theme)
                        ThemeState.current.value = theme
                    },
                )
            }
        }
    }
    SectionCard {
        SectionHeader("When App Is in Background")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in BackgroundMode.entries) {
                SelectableOptionCard(
                    selected = bgMode == mode,
                    title = mode.label,
                    subtitle = mode.description,
                    onClick = {
                        bgMode = mode
                        SessionPrefs.setBackgroundMode(settingsContext, mode)
                    },
                )
            }
        }
        if (bgMode != BackgroundMode.KEEP_RUNNING) {
            Spacer(Modifier.height(10.dp))
            Text("Delay", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((sec, label) in listOf(0 to "Immediately", 30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min")) {
                    FilterChip(
                        selected = bgDelay == sec,
                        onClick = {
                            bgDelay = sec
                            SessionPrefs.setBackgroundDelaySec(settingsContext, sec)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    // 1. Startup & Workstation Account
    SectionCard {
        SectionHeader("Startup & User Account")
        SettingToggleRow(
            title = "Open Desktop on App Launch",
            subtitle = "Boot directly into the Linux desktop when opening LinuxDroid. Press Back anytime for the Quick Menu.",
            checked = autoBoot,
            onCheckedChange = onToggleAutoBoot,
        )
        Spacer(Modifier.height(10.dp))
        var usernameInput by remember { mutableStateOf(username) }
        OutlinedTextField(
            value = usernameInput,
            onValueChange = {
                usernameInput = it
                onUsernameChange(it)
            },
            label = { Text("Linux Username", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingText = {
                Text(
                    if (usernameInput.trim() != username) "Normalized to \"$username\"" else "Default account with passwordless sudo",
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 2. Shared Storage & Fixed-Height Custom Mounts List
    SectionCard {
        SectionHeader("Storage & Shared Folders")
        Text(
            text = "Android shared storage is mounted automatically at /home/$username/Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val mountsScroll = rememberScrollState()
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
        ) {
            if (customMounts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "No additional folders mounted. Add a folder below to access it at /home/$username/Mounts/.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(mountsScroll)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    for ((name, path) in customMounts) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "/home/$username/Mounts/$name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onRemoveMount(name) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove Mount")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFolder) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick Folder")
            }
            OutlinedButton(onClick = onAddPathClick) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Path")
            }
        }
    }

    // 3. Audio & Fixed-Height Scrollable Diagnostics Console
    SectionCard {
        SectionHeader("Audio & Diagnostics")
        SettingToggleRow(
            title = "Microphone Input",
            subtitle = "Forward Android microphone audio into Linux via PulseAudio.",
            checked = micEnabled,
            onCheckedChange = onToggleMic,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session & GPU Diagnostics",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    val cm = settingsContext.getSystemService(ClipboardManager::class.java)
                    val text = diagnosticLogPreview.ifBlank { "No session logs recorded yet." }
                    cm?.setPrimaryClip(ClipData.newPlainText("LinuxDroid Diagnostics", text))
                    Toast.makeText(settingsContext, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("Copy", softWrap = false, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    val count = SessionService.clearGpuFallbacks(settingsContext)
                    onRefreshLogs()
                    val msg = if (count > 0) "Cleared $count GPU fallback marker(s)" else "No GPU fallback markers present"
                    Toast.makeText(settingsContext, msg, Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("Reset GPU", softWrap = false, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onRefreshLogs,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh", softWrap = false, maxLines = 1)
            }
        }

        val diagScroll = rememberScrollState()
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(diagScroll)
                    .padding(10.dp),
            ) {
                Text(
                    text = diagnosticLogPreview.ifBlank { "No session logs recorded yet." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 4. About & Open-Source Credits
    SectionCard {
        SectionHeader("About LinuxDroid")
        Text(
            text = "LinuxDroid v${com.linuxdroid.app.BuildConfig.VERSION_NAME} (GPL-3.0)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Built on open-source technologies including Mesa 3D (Turnip, Zink, VirGL & Venus), PRoot, FEX-Emu, PulseAudio, Termux, and DroidDeck runtime components.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 5. Optional: battery optimization exemption for reliable background usage
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(settingsContext)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            batteryUnrestricted = isIgnoringBatteryOptimizations(settingsContext)
            kotlinx.coroutines.delay(1000L)
        }
    }
    SectionCard {
        SectionHeader("Battery (Optional)")
        SettingToggleRow(
            title = "Disable Battery Optimization",
            subtitle = "Optional. Lets Linux keep running reliably in the background without Android stopping it. Only needed if you use Keep Running while the app is in the background.",
            checked = batteryUnrestricted,
            onCheckedChange = { want ->
                if (want) requestIgnoreBatteryOptimizations(settingsContext) else openBatteryOptimizationSettings(settingsContext)
            },
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openBatteryOptimizationSettings(context) }
}

private fun openBatteryOptimizationSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun InfoPill(text: String, accent: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (accent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SelectableOptionCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EditionRow(
    title: String,
    description: String,
    sizeLabel: String,
    isInstalled: Boolean,
    anotherInstalled: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isInstalled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isInstalled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (anotherInstalled) "$sizeLabel • Delete current edition to install" else sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (anotherInstalled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )

                if (isInstalled) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete", softWrap = false, maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !anotherInstalled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Install", softWrap = false, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun resolveDocumentTreeToPosixPath(treeUri: Uri): String? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val parts = docId.split(":", limit = 2)
    if (parts.size == 2) {
        val vol = parts[0]
        val rel = parts[1]
        val base = if (vol.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$vol"
        }
        if (rel.isEmpty()) base else "$base/$rel"
    } else null
}.getOrNull()
