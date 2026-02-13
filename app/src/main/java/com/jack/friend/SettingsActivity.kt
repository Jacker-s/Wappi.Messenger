package com.jack.friend

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
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
                val myPresenceStatus by viewModel.myPresenceStatus.collectAsStateWithLifecycle("Online")
                val blockedProfiles by viewModel.blockedProfiles.collectAsStateWithLifecycle(emptyList())

                val securityPrefs = remember { context.getSharedPreferences("security_prefs", MODE_PRIVATE) }

                var nameInput by remember { mutableStateOf("") }
                var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
                var selectedPresence by remember { mutableStateOf(myPresenceStatus) }

                var isPinEnabled by remember { mutableStateOf(securityPrefs.getBoolean("pin_enabled", false)) }
                var isBiometricEnabled by remember { mutableStateOf(securityPrefs.getBoolean("biometric_enabled", false)) }

                var showPinDialog by remember { mutableStateOf(false) }
                var pinInput by remember { mutableStateOf("") }
                var showDeleteAccountDialog by remember { mutableStateOf(false) }
                var showPresenceMenu by remember { mutableStateOf(false) }
                var showBlockedDialog by remember { mutableStateOf(false) }

                LaunchedEffect(myName) { if (nameInput.isEmpty()) nameInput = myName }
                LaunchedEffect(myPresenceStatus) { selectedPresence = myPresenceStatus }

                val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    selectedImageUri = uri
                }

                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Eu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface)
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    viewModel.updateProfile(nameInput, selectedImageUri, presenceStatus = selectedPresence)
                                    uiPrefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                                    Toast.makeText(context, "Salvo", Toast.LENGTH_SHORT).show()
                                    finish()
                                }) {
                                    Text("Concluir", color = MessengerBlue, fontWeight = FontWeight.Bold)
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalChatColors.current.topBar)
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
                        // Profile Header (Messenger Style)
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(modifier = Modifier.size(100.dp)) {
                                AsyncImage(
                                    model = selectedImageUri ?: myPhotoUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(LocalChatColors.current.separator),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { photoLauncher.launch("image/*") },
                                    modifier = Modifier.align(Alignment.BottomEnd).size(32.dp).background(LocalChatColors.current.tertiaryBackground, CircleShape).padding(4.dp)
                                ) {
                                    Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(text = nameInput, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            
                            // Presence Selector
                            Box(modifier = Modifier.padding(top = 8.dp)) {
                                Surface(
                                    onClick = { showPresenceMenu = true },
                                    shape = RoundedCornerShape(16.dp),
                                    color = LocalChatColors.current.tertiaryBackground,
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                                        val color = when(selectedPresence) {
                                            "Online" -> Color(0xFF31A24C)
                                            "Ocupado" -> Color(0xFFFA3E3E)
                                            "Ausente" -> Color(0xFFFFB02E)
                                            else -> Color.Gray
                                        }
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                                        Spacer(Modifier.width(8.dp))
                                        Text(selectedPresence, style = MaterialTheme.typography.labelMedium)
                                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                                DropdownMenu(expanded = showPresenceMenu, onDismissRequest = { showPresenceMenu = false }) {
                                    listOf("Online", "Ocupado", "Ausente", "Invisível").forEach { status ->
                                        DropdownMenuItem(
                                            text = { Text(status) },
                                            onClick = { selectedPresence = status; showPresenceMenu = false },
                                            leadingIcon = {
                                                val color = when(status) {
                                                    "Online" -> Color(0xFF31A24C)
                                                    "Ocupado" -> Color(0xFFFA3E3E)
                                                    "Ausente" -> Color(0xFFFFB02E)
                                                    else -> Color.Gray
                                                }
                                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        MetaSettingsSection {
                            MetaSettingsTextField(label = "Nome", value = nameInput, onValueChange = { nameInput = it })
                        }

                        Text("PREFERÊNCIAS", modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = MetaGray4, fontWeight = FontWeight.Bold)

                        MetaSettingsSection {
                            MetaSettingsSwitchItem(icon = Icons.Default.DarkMode, iconColor = Color.Black, title = "Modo escuro", checked = isDarkMode, onCheckedChange = { isDarkMode = it })
                            MetaSettingsDivider()
                            MetaSettingsItem(title = "Usuários Bloqueados", icon = Icons.Default.Block, iconColor = iOSRed, onClick = { showBlockedDialog = true })
                        }

                        Text("SEGURANÇA", modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = MetaGray4, fontWeight = FontWeight.Bold)

                        MetaSettingsSection {
                            MetaSettingsSwitchItem(icon = Icons.Default.Lock, iconColor = MetaGray4, title = "Bloqueio com PIN", checked = isPinEnabled, onCheckedChange = {
                                if (it) showPinDialog = true else {
                                    isPinEnabled = false
                                    securityPrefs.edit().putBoolean("pin_enabled", false).apply()
                                }
                            })
                            if (isPinEnabled) {
                                MetaSettingsDivider()
                                MetaSettingsItem(title = "Usar Biometria", icon = Icons.Default.Fingerprint, iconColor = InstagramPink, trailing = {
                                    Switch(checked = isBiometricEnabled, onCheckedChange = {
                                        isBiometricEnabled = it
                                        securityPrefs.edit().putBoolean("biometric_enabled", it).apply()
                                    }, colors = SwitchDefaults.colors(checkedTrackColor = MessengerBlue))
                                })
                            }
                        }

                        MetaSettingsSection {
                            MetaSettingsItem(title = "Sair da conta", icon = Icons.AutoMirrored.Filled.Logout, iconColor = MetaGray4, onClick = {
                                viewModel.logout()
                                val intent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                context.startActivity(intent)
                                finish()
                            })
                            MetaSettingsDivider()
                            MetaSettingsItem(title = "Excluir conta", icon = Icons.Default.DeleteForever, iconColor = Color(0xFFFA3E3E), textColor = Color(0xFFFA3E3E), onClick = { showDeleteAccountDialog = true })
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }

                if (showBlockedDialog) {
                    AlertDialog(
                        onDismissRequest = { showBlockedDialog = false },
                        title = { Text("Usuários Bloqueados") },
                        text = {
                            if (blockedProfiles.isEmpty()) {
                                Text("Nenhum usuário bloqueado.")
                            } else {
                                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                    items(blockedProfiles) { profile ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                AsyncImage(model = profile.photoUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(CircleShape).background(LocalChatColors.current.separator), contentScale = ContentScale.Crop)
                                                Spacer(Modifier.width(12.dp))
                                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            TextButton(onClick = { viewModel.unblockUser(profile.id) }) {
                                                Text("Desbloquear", color = MessengerBlue)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showBlockedDialog = false }) { Text("Fechar") } }
                    )
                }

                if (showDeleteAccountDialog) {
                    AlertDialog(onDismissRequest = { showDeleteAccountDialog = false }, title = { Text("Excluir conta?") }, text = { Text("Esta ação não pode ser desfeita. Para sua segurança, você precisa ter feito login recentemente para excluir a conta.") },
                        confirmButton = { 
                            TextButton(onClick = { 
                                viewModel.deleteAccount { success, error -> 
                                    if (success) {
                                        val intent = Intent(context, MainActivity::class.java).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        }
                                        context.startActivity(intent)
                                        finish()
                                    } else {
                                        Toast.makeText(context, error ?: "Erro ao excluir conta. Tente sair e entrar novamente.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                showDeleteAccountDialog = false 
                            }) { 
                                Text("Excluir", color = Color(0xFFFA3E3E)) 
                            } 
                        },
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
fun MetaSettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun MetaSettingsItem(title: String, icon: ImageVector? = null, iconColor: Color = MessengerBlue, textColor: Color = Color.Unspecified, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(iconColor), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (textColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else textColor, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = MetaGray3, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun MetaSettingsSwitchItem(icon: ImageVector, iconColor: Color, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    MetaSettingsItem(title = title, icon = icon, iconColor = iconColor, trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = MessengerBlue)) })
}

@Composable
fun MetaSettingsTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(80.dp), fontWeight = FontWeight.Medium)
        TextField(value = value, onValueChange = onValueChange, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun MetaSettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 60.dp), thickness = 0.5.dp, color = LocalChatColors.current.separator)
}
