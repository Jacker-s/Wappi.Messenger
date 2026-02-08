package com.jack.friend

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jack.friend.ui.theme.*

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val uiPrefs = remember { context.getSharedPreferences("ui_prefs", MODE_PRIVATE) }
            var isDarkMode by remember { mutableStateOf(uiPrefs.getBoolean("dark_mode", false)) }

            FriendTheme(isDarkModeOverride = isDarkMode) {
                val viewModel: ChatViewModel = viewModel()
                val myName by viewModel.myName.collectAsStateWithLifecycle("")
                val myPhotoUrl by viewModel.myPhotoUrl.collectAsStateWithLifecycle(null)

                val securityPrefs = remember { context.getSharedPreferences("security_prefs", MODE_PRIVATE) }
                val privacyPrefs = remember { context.getSharedPreferences("privacy_prefs", MODE_PRIVATE) }

                var nameInput by remember { mutableStateOf("") }
                var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

                var isPinEnabled by remember { mutableStateOf(securityPrefs.getBoolean("pin_enabled", false)) }
                var isBiometricEnabled by remember { mutableStateOf(securityPrefs.getBoolean("biometric_enabled", false)) }
                var readReceiptsEnabled by remember { mutableStateOf(privacyPrefs.getBoolean("read_receipts_enabled", true)) }

                var showPinDialog by remember { mutableStateOf(false) }
                var pinInput by remember { mutableStateOf("") }
                var showDeleteAccountDialog by remember { mutableStateOf(false) }

                LaunchedEffect(myName) { if (nameInput.isEmpty()) nameInput = myName }

                val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    selectedImageUri = uri
                }

                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Ajustes", style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)) },
                            navigationIcon = {
                                TextButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, modifier = Modifier.size(18.dp), tint = AppleBlue)
                                    Text("Voltar", color = AppleBlue, style = MaterialTheme.typography.bodyLarge)
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    viewModel.updateProfile(nameInput, selectedImageUri)
                                    uiPrefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                                    Toast.makeText(context, "Salvo", Toast.LENGTH_SHORT).show()
                                    finish()
                                }) {
                                    Text("OK", color = AppleBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Profile Header
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Box(modifier = Modifier.size(90.dp).clip(CircleShape).background(SystemGray5).clickable { photoLauncher.launch("image/*") }) {
                                AsyncImage(
                                    model = selectedImageUri ?: myPhotoUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.1f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CameraAlt, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(24.dp))
                                }
                            }
                        }

                        SwiftSettingsSection {
                            SwiftSettingsTextField(label = "Nome", value = nameInput, onValueChange = { nameInput = it })
                        }

                        Text("CONFIGURAÇÕES", modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = SystemGray)

                        SwiftSettingsSection {
                            SwiftSettingsSwitchItem(icon = Icons.Default.DarkMode, iconColor = Color.Black, title = "Modo Escuro", checked = isDarkMode, onCheckedChange = { isDarkMode = it })
                            SwiftSettingsDivider()
                            SwiftSettingsSwitchItem(icon = Icons.Default.Visibility, iconColor = AppleBlue, title = "Visto por último", checked = readReceiptsEnabled, onCheckedChange = {
                                readReceiptsEnabled = it
                                privacyPrefs.edit().putBoolean("read_receipts_enabled", it).apply()
                            })
                        }

                        SwiftSettingsSection {
                            SwiftSettingsSwitchItem(icon = Icons.Default.Lock, iconColor = SystemGray, title = "Bloqueio com PIN", checked = isPinEnabled, onCheckedChange = {
                                if (it) showPinDialog = true else {
                                    isPinEnabled = false
                                    securityPrefs.edit().putBoolean("pin_enabled", false).apply()
                                }
                            })
                            if (isPinEnabled) {
                                SwiftSettingsDivider()
                                SwiftSettingsItem(title = "Usar Biometria", icon = Icons.Default.Fingerprint, iconColor = ApplePink, trailing = {
                                    Switch(checked = isBiometricEnabled, onCheckedChange = {
                                        isBiometricEnabled = it
                                        securityPrefs.edit().putBoolean("biometric_enabled", it).apply()
                                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleGreen))
                                })
                            }
                        }

                        SwiftSettingsSection {
                            SwiftSettingsItem(title = "Sair da conta", icon = Icons.AutoMirrored.Filled.Logout, iconColor = SystemGray, onClick = {
                                viewModel.logout()
                                finish()
                            })
                            SwiftSettingsDivider()
                            SwiftSettingsItem(title = "Excluir Conta", icon = Icons.Default.DeleteForever, iconColor = AppleRed, textColor = AppleRed, onClick = { showDeleteAccountDialog = true })
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }

                if (showDeleteAccountDialog) {
                    AlertDialog(onDismissRequest = { showDeleteAccountDialog = false }, title = { Text("Excluir Conta") }, text = { Text("Esta ação é permanente.") },
                        confirmButton = { TextButton(onClick = { viewModel.deleteAccount { _, _ -> finish() } }) { Text("Excluir", color = AppleRed) } },
                        dismissButton = { TextButton(onClick = { showDeleteAccountDialog = false }) { Text("Cancelar") } }
                    )
                }

                if (showPinDialog) {
                    AlertDialog(onDismissRequest = { showPinDialog = false }, title = { Text("Definir PIN") },
                        text = { TextField(value = pinInput, onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pinInput = it }, placeholder = { Text("0000") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) },
                        confirmButton = { TextButton(onClick = { if (pinInput.length == 4) {
                            securityPrefs.edit().putBoolean("pin_enabled", true).putString("security_pin", pinInput).apply()
                            isPinEnabled = true
                            showPinDialog = false
                            pinInput = ""
                        } }) { Text("Definir") } }
                    )
                }
            }
        }
    }
}

@Composable
fun SwiftSettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun SwiftSettingsItem(title: String, icon: ImageVector? = null, iconColor: Color = AppleBlue, textColor: Color = Color.Unspecified, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(iconColor), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, color = textColor, modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = SystemGray3, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SwiftSettingsSwitchItem(icon: ImageVector, iconColor: Color, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwiftSettingsItem(title = title, icon = icon, iconColor = iconColor, trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleGreen)) })
}

@Composable
fun SwiftSettingsTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(80.dp))
        TextField(value = value, onValueChange = onValueChange, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), singleLine = true)
    }
}

@Composable
fun SwiftSettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = SystemGray5)
}
