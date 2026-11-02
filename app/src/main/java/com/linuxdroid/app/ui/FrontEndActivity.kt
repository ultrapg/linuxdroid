package com.linuxdroid.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.linuxdroid.app.gpu.GpuConfigGenerator
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.gpu.GpuInfo
import com.linuxdroid.app.runtime.DesktopCatalog
import com.linuxdroid.app.runtime.DesktopEdition
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.runtime.RuntimeInstallService
import com.linuxdroid.app.session.ResolutionPreset
import com.linuxdroid.app.session.ScreenOrientationPref
import com.linuxdroid.app.session.SessionPhase
import com.linuxdroid.app.session.SessionPrefs
import com.linuxdroid.app.session.SessionService
import com.linuxdroid.app.session.SessionState
import com.linuxdroid.app.session.TouchInputMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FrontEndActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestBasePermissionsIfNeeded()

        val skipAutoBoot = intent?.getBooleanExtra(EXTRA_SKIP_AUTOBOOT, false) == true
        if (savedInstanceState == null && !skipAutoBoot && SessionPrefs.autoBootOnLaunch(this) && LinuxRuntime.isReady(this)) {
            openDesktopSession()
            return
        }

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
                    onShareLogs = { shareSessionLogs() },
                )
            }
        }
    }

    private fun openDesktopSession() {
        startActivity(Intent(this, SessionActivity::class.java))
    }

    private fun requestBasePermissionsIfNeeded() {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 100)
        }
    }

    private fun shareSessionLogs() {
        val logFile = File(LinuxRuntime.logDir(this), "session.log")
        if (!logFile.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share LinuxDroid Session Log"))
    }

    companion object {
        const val EXTRA_SKIP_AUTOBOOT = "skip_autoboot"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardScreen(
    onLaunchDesktop: () -> Unit,
    onOpenTerminal: (Boolean) -> Unit,
    onShareLogs: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionSnap by SessionState.state.collectAsState()
    val installSnap by RuntimeInstallService.state.collectAsState()

    val gpuInfo = remember { GpuInfo.detect() }
    var catalog by remember { mutableStateOf(DesktopCatalog.FALLBACK_EDITIONS) }
    var installedReady by remember(installSnap.active, installSnap.stage) {
        mutableStateOf(LinuxRuntime.isReady(context))
    }
    var installedLabel by remember(installSnap.active, installSnap.stage) {
        mutableStateOf(LinuxRuntime.detectInstalledDesktopLabel(context))
    }

    var autoBoot by remember { mutableStateOf(SessionPrefs.autoBootOnLaunch(context)) }
    var gpuMode by remember { mutableStateOf(SessionPrefs.gpuMode(context)) }
    var resolution by remember { mutableStateOf(SessionPrefs.resolutionPreset(context)) }
    var scalePref by remember { mutableFloatStateOf(SessionPrefs.displayScale(context)) }
    var refreshHzPref by remember { mutableIntStateOf(SessionPrefs.targetRefreshHz(context)) }
    var orientationPref by remember { mutableStateOf(SessionPrefs.orientation(context)) }
    var touchMode by remember { mutableStateOf(SessionPrefs.touchInputMode(context)) }
    var micEnabled by remember { mutableStateOf(SessionPrefs.micEnabled(context)) }
    var fastPathEnabled by remember { mutableStateOf(SessionPrefs.prootFastPath(context)) }
    var username by remember { mutableStateOf(SessionPrefs.username(context)) }
    var customMounts by remember { mutableStateOf(SessionPrefs.customMounts(context)) }
    var showAddMountDialog by remember { mutableStateOf(false) }

    val resolvedGpu = remember(gpuMode, gpuInfo) {
        GpuConfigGenerator.resolve(gpuMode, gpuInfo, LinuxRuntime.VIRGL_SOCKET_GUEST_PATH)
    }

    LaunchedEffect(Unit) {
        val fetched = withContext(Dispatchers.IO) { DesktopCatalog.fetchCatalogOrFallback() }
        catalog = fetched
    }

    LaunchedEffect(installSnap.active) {
        installedReady = LinuxRuntime.isReady(context)
        installedLabel = LinuxRuntime.detectInstalledDesktopLabel(context)
    }

    val importArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "LinuxDroid",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "ARM64 GPU-Accelerated Linux Desktop Workstation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShareLogs) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share Session Log",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Hero Workstation Launch & Session Control Card
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = installedLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (sessionSnap.phase == SessionPhase.STOPPED) {
                                if (installedReady) "Ready to boot • User '$username'" else "Install or import an ARM64 Linux rootfs below"
                            } else {
                                "${sessionSnap.statusText} • ${sessionSnap.resolutionText}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onLaunchDesktop,
                        enabled = installedReady && !installSnap.active,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (sessionSnap.phase) {
                                SessionPhase.RUNNING -> "Open Desktop"
                                SessionPhase.PAUSED -> "Resume Desktop"
                                else -> "Launch Desktop"
                            }
                        )
                    }

                    if (sessionSnap.phase == SessionPhase.RUNNING || sessionSnap.phase == SessionPhase.PAUSED) {
                        OutlinedButton(onClick = { SessionService.togglePause(context) }) {
                            Icon(Icons.Outlined.Pause, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (sessionSnap.phase == SessionPhase.PAUSED) "Unfreeze" else "Pause")
                        }
                        OutlinedButton(
                            onClick = { SessionService.shutdown(context) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                        }
                    }
                }

                if (installedReady) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onOpenTerminal(false) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Terminal ($username)")
                        }
                        OutlinedButton(
                            onClick = { onOpenTerminal(true) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Root Shell")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow(
                    title = "Auto-boot directly into Linux on launch",
                    subtitle = "Skip this dashboard when opening LinuxDroid (accessible anytime via Back -> Quick Menu).",
                    checked = autoBoot,
                    onCheckedChange = {
                        autoBoot = it
                        SessionPrefs.setAutoBootOnLaunch(context, it)
                    },
                )
            }

            // Desktop Rootfs Installer & Custom Archive Import
            SectionCard {
                SectionHeader("Linux Desktop Editions & Rootfs Import")
                Text(
                    text = "Clean Ubuntu 24.04 LTS ARM64 workstation desktops with Firefox, MPV, FEX-Emu (x86_64 emulation), and SoC Vulkan/OpenGL drivers — or import your own rootfs archive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (installSnap.active) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = installSnap.stage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (installSnap.fraction >= 0f) {
                        LinearProgressIndicator(
                            progress = { installSnap.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { RuntimeInstallService.cancel(context) }) {
                        Text("Cancel Installation")
                    }
                } else {
                    if (installSnap.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Error: ${installSnap.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    for (edition in catalog) {
                        EditionRow(
                            edition = edition,
                            onInstall = { RuntimeInstallService.startDownload(context, edition) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = { importArchiveLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import Custom Rootfs (.tar.zst / .tar.xz / .tar.gz)")
                    }
                }
            }

            // GPU Driver & Hardware Acceleration Card
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("SoC GPU Acceleration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Detected: ${gpuInfo.shortSummary()} • Active: ${resolvedGpu.summary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                for (mode in GpuDriverMode.entries) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (gpuMode == mode) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (gpuMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                gpuMode = mode
                                SessionPrefs.setGpuMode(context, mode)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = gpuMode == mode,
                                onClick = {
                                    gpuMode = mode
                                    SessionPrefs.setGpuMode(context, mode)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(mode.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Display, HiDPI, Refresh Rate & Input
            SectionCard {
                SectionHeader("Display, DeX & Input")

                Text("Desktop Resolution", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in ResolutionPreset.entries) {
                        FilterChip(
                            selected = resolution == preset,
                            onClick = {
                                resolution = preset
                                SessionPrefs.setResolutionPreset(context, preset)
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("HiDPI Scaling", style = MaterialTheme.typography.labelLarge)
                val scales = listOf(0f to "Auto HiDPI", 1.0f to "100%", 1.25f to "125%", 1.5f to "150%", 1.75f to "175%", 2.0f to "200%")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in scales) {
                        FilterChip(
                            selected = scalePref == value,
                            onClick = {
                                scalePref = value
                                SessionPrefs.setDisplayScale(context, value)
                            },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Refresh Rate", style = MaterialTheme.typography.labelLarge)
                val rates = listOf(0 to "Auto Max (90/120Hz)", 60 to "60 Hz", 90 to "90 Hz", 120 to "120 Hz")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((hz, label) in rates) {
                        FilterChip(
                            selected = refreshHzPref == hz,
                            onClick = {
                                refreshHzPref = hz
                                SessionPrefs.setTargetRefreshHz(context, hz)
                            },
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
                            onClick = {
                                orientationPref = orient
                                SessionPrefs.setOrientation(context, orient)
                            },
                            label = { Text(orient.label) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Touchscreen Behavior", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in TouchInputMode.entries) {
                        FilterChip(
                            selected = touchMode == mode,
                            onClick = {
                                touchMode = mode
                                SessionPrefs.setTouchInputMode(context, mode)
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }

            // Storage, Audio & User Account
            SectionCard {
                SectionHeader("Storage, Audio & Workstation Account")

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        SessionPrefs.setUsername(context, it)
                    },
                    label = { Text("Linux Non-Root Username (passwordless sudo enabled)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Android shared storage is automatically mounted at /home/$username/Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (customMounts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    for ((name, path) in customMounts) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("/home/$username/Mounts/$name", style = MaterialTheme.typography.bodyMedium)
                                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                SessionPrefs.removeCustomMount(context, name)
                                customMounts = SessionPrefs.customMounts(context)
                            }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove Mount")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Folder")
                    }
                    OutlinedButton(onClick = { showAddMountDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add Path")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SettingToggleRow(
                    title = "Microphone Forwarding (PulseAudio Source)",
                    subtitle = "Expose Android microphone inside Linux for voice/video calls and recording.",
                    checked = micEnabled,
                    onCheckedChange = { want ->
                        if (want && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            micEnabled = want
                            SessionPrefs.setMicEnabled(context, want)
                        }
                    },
                )

                SettingToggleRow(
                    title = "PRoot Fast-Path Syscall Bypass",
                    subtitle = "Accelerates filesystem I/O for Firefox, compilers, Git, and FEX-Emu.",
                    checked = fastPathEnabled,
                    onCheckedChange = {
                        fastPathEnabled = it
                        SessionPrefs.setProotFastPath(context, it)
                    },
                )
            }
        }
    }

    if (showAddMountDialog) {
        var mountName by remember { mutableStateOf("") }
        var mountPath by remember { mutableStateOf("/storage/") }
        AlertDialog(
            onDismissRequest = { showAddMountDialog = false },
            title = { Text("Add Custom Folder Mount") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mountName,
                        onValueChange = { mountName = it },
                        label = { Text("Mount Name (e.g. sdcard2 or projects)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = mountPath,
                        onValueChange = { mountPath = it },
                        label = { Text("Android Host Directory Path") },
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

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun EditionRow(edition: DesktopEdition, onInstall: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(edition.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    edition.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (edition.sizeBytes > 0) {
                    Text(
                        edition.formattedSize(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Button(onClick = onInstall) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Install")
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
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
