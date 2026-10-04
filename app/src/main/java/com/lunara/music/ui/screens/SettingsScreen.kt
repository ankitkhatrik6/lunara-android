package com.lunara.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.UserPrefEntity
import com.lunara.music.service.download.DownloadManager
import com.lunara.music.ui.theme.BackgroundDark
import com.lunara.music.ui.theme.PrimaryIndigo
import com.lunara.music.ui.theme.SurfaceElevatedDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }
    val downloadManager = remember { DownloadManager(context) }

    var userName by remember { mutableStateOf("Music Lover") }
    var audioQuality by remember { mutableStateOf("Normal (128 kbps)") }
    var downloadQuality by remember { mutableStateOf("High (256 kbps)") }
    var wifiOnly by remember { mutableStateOf(false) }
    var autoplay by remember { mutableStateOf(true) }
    var themeMode by remember { mutableStateOf("Dark (Flagship)") }
    var totalStorageSizeMb by remember { mutableStateOf(0.0) }

    var showEditNameDialog by remember { mutableStateOf(false) }
    var newNameText by remember { mutableStateOf("") }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val storedName = db.userPrefDao().getPref("user_name")
            if (!storedName.isNullOrBlank()) userName = storedName
            val storedQuality = db.userPrefDao().getPref("audio_quality")
            if (!storedQuality.isNullOrBlank()) audioQuality = storedQuality

            val size = downloadManager.getTotalDownloadedSize()
            totalStorageSizeMb = size / (1024.0 * 1024.0)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Account / Profile Section
            item { SettingsSectionHeader("Profile") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Person,
                    title = "Name",
                    subtitle = userName,
                    onClick = {
                        newNameText = userName
                        showEditNameDialog = true
                    }
                )
            }

            // Playback Section
            item { SettingsSectionHeader("Playback") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.GraphicEq,
                    title = "Audio Quality",
                    subtitle = audioQuality,
                    onClick = { showQualityDialog = true }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Outlined.Wifi,
                    title = "Stream over Wi-Fi only",
                    subtitle = "Reduce mobile data usage",
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Outlined.PlayCircle,
                    title = "Autoplay",
                    subtitle = "Keep playing related songs when queue finishes",
                    checked = autoplay,
                    onCheckedChange = { autoplay = it }
                )
            }

            // Downloads Section
            item { SettingsSectionHeader("Downloads & Storage") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Download,
                    title = "Download Quality",
                    subtitle = downloadQuality,
                    onClick = {
                        downloadQuality = if (downloadQuality.startsWith("High")) "Normal (128 kbps)" else "High (256 kbps)"
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Storage,
                    title = "Storage Used",
                    subtitle = "%.1f MB of downloaded audio".format(totalStorageSizeMb),
                    onClick = {
                        scope.launch {
                            downloadManager.clearAllDownloads()
                            totalStorageSizeMb = 0.0
                            Toast.makeText(context, "Cleared downloads", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // Appearance Section
            item { SettingsSectionHeader("Appearance") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    title = "Theme",
                    subtitle = themeMode,
                    onClick = {
                        themeMode = if (themeMode.startsWith("Dark")) "Light" else "Dark (Flagship)"
                    }
                )
            }

            // Library Management Section
            item { SettingsSectionHeader("Library") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.DeleteSweep,
                    title = "Clear Listening History",
                    subtitle = "Reset recently played and Top 20 calculations",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            db.songDao().clearHistory()
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // About Section
            item { SettingsSectionHeader("About Lunara") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "Version",
                    subtitle = "1.0.0 (Production Stable)",
                    onClick = { showAboutDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Description,
                    title = "Open Source Licenses",
                    subtitle = "Third-party libraries and components",
                    onClick = { showLicensesDialog = true }
                )
            }
        }
    }

    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Edit Name", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newNameText.trim()
                        if (trimmed.isNotBlank()) {
                            userName = trimmed
                            scope.launch(Dispatchers.IO) {
                                db.userPrefDao().setPref(UserPrefEntity("user_name", trimmed))
                            }
                            showEditNameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Audio Streaming Quality", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    listOf("High Quality (256 kbps)", "Normal (128 kbps)", "Data Saver (64 kbps)").forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    audioQuality = opt
                                    scope.launch(Dispatchers.IO) {
                                        db.userPrefDao().setPref(UserPrefEntity("audio_quality", opt))
                                    }
                                    showQualityDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = audioQuality == opt,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = PrimaryIndigo)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(opt, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Lunara", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Lunara is a modern, personal music streaming application engineered with Android Media3, Kotlin, and Jetpack Compose.",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Legal Notice: Music metadata and catalog data are sourced through YouTube Music InnerTube and public web endpoints for personal playback. Lunara is an independent client and is not affiliated with or endorsed by YouTube, Google, or Spotify.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("OK") }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("Open Source Licenses", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("• AndroidX Media3 (Apache 2.0)", color = Color.White)
                    Text("• Jetpack Compose & Material 3 (Apache 2.0)", color = Color.White)
                    Text("• AndroidX Room (Apache 2.0)", color = Color.White)
                    Text("• Coil Image Loading (Apache 2.0)", color = Color.White)
                    Text("• OkHttp (Apache 2.0)", color = Color.White)
                    Text("• BlazifyExtractor (GPL 3.0)", color = Color.White)
                    Text("• LRCLIB Lyrics API (MIT)", color = Color.White)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            color = PrimaryIndigo,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BackgroundDark,
                checkedTrackColor = PrimaryIndigo
            )
        )
    }
}
