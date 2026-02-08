package com.jack.friend

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jack.friend.ui.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FriendTheme {
                val viewModel: ChatViewModel = viewModel()
                val isUserLoggedIn by viewModel.isUserLoggedIn.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("security_prefs", MODE_PRIVATE) }
                val isPinEnabled = remember { prefs.getBoolean("pin_enabled", false) }
                val isBiometricEnabled = remember { prefs.getBoolean("biometric_enabled", false) }
                val correctPin = remember { prefs.getString("security_pin", "") ?: "" }
                var isUnlocked by remember { mutableStateOf(!(isPinEnabled || isBiometricEnabled)) }

                val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                    } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                LaunchedEffect(Unit) { launcher.launch(permissions.toTypedArray()) }

                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (!isUserLoggedIn) LoginScreen(viewModel)
                    else if (!isUnlocked) PinLockScreen(correctPin, isBiometricEnabled) { isUnlocked = true }
                    else ChatScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: ChatViewModel) {
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }; var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isSignUp by remember { mutableStateOf(false) }; var loading by remember { mutableStateOf(false) }
    val context = LocalContext.current; val googleSignInClient = remember { getGoogleSignInClient(context) }
    val chatColors = LocalChatColors.current
    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.idToken?.let { token ->
                loading = true
                viewModel.signInWithGoogle(token) { s, e -> loading = false; if (!s) Toast.makeText(context, e ?: "Erro Google", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) { }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> selectedImageUri = uri }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = if (isSignUp) "Nova Conta" else "Bem-vindo", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(48.dp))
        if (isSignUp) {
            Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(SystemGray5).clickable { photoLauncher.launch("image/*") }, contentAlignment = Alignment.Center) {
                if (selectedImageUri != null) AsyncImage(model = selectedImageUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.AddAPhoto, null, modifier = Modifier.size(32.dp), tint = SystemGray)
            }
            Spacer(modifier = Modifier.height(24.dp))
            TextField(value = username, onValueChange = { username = it }, placeholder = { Text("Usuário") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(12.dp))
        }
        TextField(value = email, onValueChange = { email = it }, placeholder = { Text("E-mail") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        TextField(value = password, onValueChange = { password = it }, placeholder = { Text("Senha") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(32.dp))
        if (loading) CircularProgressIndicator()
        else {
            Button(onClick = {
                loading = true
                if (isSignUp) viewModel.signUp(email, password, username, selectedImageUri) { s, e -> loading = false; if (!s) Toast.makeText(context, e ?: "Erro", Toast.LENGTH_SHORT).show() }
                else viewModel.login(email, password) { s, e -> loading = false; if (!s) Toast.makeText(context, e ?: "Erro", Toast.LENGTH_SHORT).show() }
            }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = chatColors.primary)) {
                Text(if (isSignUp) "Criar Conta" else "Entrar", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = { googleLauncher.launch(googleSignInClient.signInIntent) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.AccountCircle, null, tint = chatColors.primary); Spacer(Modifier.width(8.dp)); Text("Continuar com Google")
            }
            TextButton(onClick = { isSignUp = !isSignUp }) { Text(if (isSignUp) "Já tem conta? Entre" else "Criar nova conta") }
        }
    }
}

@Composable
fun PinLockScreen(correctPin: String, isBiometricEnabled: Boolean, onUnlock: () -> Unit) {
    var pinInput by remember { mutableStateOf("") }; val chatColors = LocalChatColors.current; val context = LocalContext.current as? FragmentActivity ?: return
    fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = androidx.biometric.BiometricPrompt(context, executor, object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(result); onUnlock() }
        })
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Bloqueio").setSubtitle("Use biometria para acessar").setNegativeButtonText("Usar PIN").build()
        biometricPrompt.authenticate(promptInfo)
    }
    LaunchedEffect(Unit) { if (isBiometricEnabled) showBiometricPrompt() }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = chatColors.primary)
        Spacer(modifier = Modifier.height(24.dp)); Text("App Bloqueado", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { repeat(4) { index -> Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(if (pinInput.length > index) chatColors.primary else SystemGray4)) } }
        Spacer(modifier = Modifier.height(48.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "DEL")
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(keys) { key ->
                if (key.isNotEmpty()) Box(modifier = Modifier.size(72.dp).clip(CircleShape).background(SystemGray6).clickable { if (key == "DEL") { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) } else if (pinInput.length < 4) { pinInput += key; if (pinInput.length == 4) { if (pinInput == correctPin) onUnlock() else pinInput = "" } } }, contentAlignment = Alignment.Center) {
                    if (key == "DEL") Icon(Icons.AutoMirrored.Filled.Backspace, null) else Text(key, style = MaterialTheme.typography.titleMedium)
                } else Spacer(Modifier.size(72.dp))
            }
        }
        if (isBiometricEnabled) IconButton(onClick = { showBiometricPrompt() }, modifier = Modifier.padding(top = 24.dp)) { Icon(Icons.Default.Fingerprint, null, modifier = Modifier.size(40.dp), tint = chatColors.primary) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val myId by viewModel.myId.collectAsStateWithLifecycle(""); val myPhotoUrl by viewModel.myPhotoUrl.collectAsStateWithLifecycle(null)
    val targetId by viewModel.targetId.collectAsStateWithLifecycle(""); val targetProfile by viewModel.targetProfile.collectAsStateWithLifecycle(null)
    val targetGroup by viewModel.targetGroup.collectAsStateWithLifecycle(null); val messages by viewModel.messages.collectAsStateWithLifecycle(emptyList())
    val activeChats by viewModel.activeChats.collectAsStateWithLifecycle(emptyList()); val searchResults by viewModel.searchResults.collectAsStateWithLifecycle(emptyList())
    val statuses by viewModel.statuses.collectAsStateWithLifecycle(emptyList()); val isTargetTyping by viewModel.isTargetTyping.collectAsStateWithLifecycle(false)

    val context = LocalContext.current; val listState = rememberLazyListState(); val chatColors = LocalChatColors.current
    var textState by remember { mutableStateOf("") }; var searchInput by remember { mutableStateOf("") }; var isSearching by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) viewModel.updatePresence(true) else if (event == Lifecycle.Event.ON_STOP) viewModel.updatePresence(false) }
        lifecycleOwner.lifecycle.addObserver(observer); onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(targetId) { if (targetId.isNotEmpty()) viewModel.markAsRead() }
    BackHandler(enabled = targetId.isNotEmpty() || isSearching) { if (isSearching) { isSearching = false; searchInput = ""; viewModel.searchUsers("") } else viewModel.setTargetId("") }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.uploadImage(it, false) } }
    val statusLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.uploadStatus(it) } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (targetId.isNotEmpty()) {
                        val currentChat = activeChats.find { it.friendId == targetId }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (targetGroup != null) (targetGroup?.name ?: "Grupo") else (targetProfile?.name ?: currentChat?.friendName ?: targetId), style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isTargetTyping) Text("digitando...", style = MaterialTheme.typography.labelSmall, color = AppleBlue)
                            else if (targetProfile?.isOnline == true) Text("online", style = MaterialTheme.typography.labelSmall, color = AppleGreen)
                        }
                    } else if (isSearching) {
                        TextField(value = searchInput, onValueChange = { searchInput = it; viewModel.searchUsers(it) }, placeholder = { Text("Buscar") }, modifier = Modifier.fillMaxWidth().height(44.dp), singleLine = true, shape = RoundedCornerShape(10.dp))
                    } else Text("Conversas", style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = { if (targetId.isNotEmpty()) IconButton(onClick = { viewModel.setTargetId("") }) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = chatColors.primary, modifier = Modifier.size(20.dp)) } else if (isSearching) IconButton(onClick = { isSearching = false; searchInput = ""; viewModel.searchUsers("") }) { Icon(Icons.Default.Close, null, tint = chatColors.primary) } },
                actions = { if (targetId.isEmpty() && !isSearching) { IconButton(onClick = { isSearching = true }) { Icon(Icons.Default.Search, null, tint = chatColors.primary) }; IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) { AsyncImage(model = myPhotoUrl, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape).background(SystemGray4), contentScale = ContentScale.Crop) } } else if (targetId.isNotEmpty()) { 
                    IconButton(onClick = { 
                        val myUsername = viewModel.myUsername.value
                        val callKey = "WapiCall_Private_${if (myUsername < targetId) "${myUsername.lowercase()}_${targetId.lowercase()}" else "${targetId.lowercase()}_${myUsername.lowercase()}"}"
                        viewModel.startCall(false, false)
                        val intent = Intent(context, CallActivity::class.java).apply {
                            putExtra("roomId", callKey)
                            putExtra("targetId", targetId)
                            putExtra("isOutgoing", true)
                        }
                        context.startActivity(intent)
                    }) { Icon(Icons.Default.Call, null, tint = chatColors.primary) } 
                } }
            )
        },
        bottomBar = { if (targetId.isNotBlank()) SwiftInput(text = textState, onTextChange = { textState = it; viewModel.setTyping(it.isNotEmpty()) }, onImageClick = { imageLauncher.launch("image/*") }, onSend = { if (textState.isNotBlank()) { viewModel.sendMessage(textState, false); textState = "" } }, onAudioStart = { viewModel.startRecording(context.cacheDir) }, onAudioStop = { viewModel.stopRecording(false) }) }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (targetId.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isSearching) { SwiftStatusRow(statuses, myPhotoUrl) { statusLauncher.launch("image/*") }; HorizontalDivider(color = SystemGray5, thickness = 0.5.dp) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (isSearching && searchInput.isNotEmpty()) items(searchResults) { user -> SwiftUserItem(user) { isSearching = false; viewModel.setTargetId(user.id, false) } }
                        else items(activeChats, key = { it.friendId }) { summary -> SwiftChatItem(summary, myId) { viewModel.setTargetId(summary.friendId, summary.isGroup) } }
                    }
                }
            } else {
                val myUsername by viewModel.myUsername.collectAsStateWithLifecycle("")
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(messages, key = { it.id }) { message -> SwiftMessageBubble(message, message.senderId == myUsername) }
                }
            }
        }
    }
}

@Composable
fun SwiftStatusRow(statuses: List<UserStatus>, myPhotoUrl: String?, onAdd: () -> Unit) {
    LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onAdd() }) { Box { AsyncImage(model = myPhotoUrl, contentDescription = null, modifier = Modifier.size(60.dp).clip(CircleShape).background(SystemGray5), contentScale = ContentScale.Crop); Icon(Icons.Default.Add, null, modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).background(AppleBlue, CircleShape).padding(2.dp), tint = Color.White) }; Text("Seu status", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) } }
        items(statuses) { status -> Column(horizontalAlignment = Alignment.CenterHorizontally) { AsyncImage(model = status.userPhotoUrl, contentDescription = null, modifier = Modifier.size(60.dp).clip(CircleShape).border(2.dp, AppleBlue, CircleShape).padding(3.dp).clip(CircleShape), contentScale = ContentScale.Crop); Text(status.username, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) } }
    }
}

@Composable
fun SwiftChatItem(summary: ChatSummary, myId: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            AsyncImage(model = summary.friendPhotoUrl, contentDescription = null, modifier = Modifier.size(60.dp).clip(CircleShape).background(SystemGray5), contentScale = ContentScale.Crop)
            if (summary.isOnline) Box(modifier = Modifier.align(Alignment.BottomEnd).size(14.dp).background(Color.White, CircleShape).padding(2.dp).background(AppleGreen, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(summary.friendName ?: summary.friendId, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(summary.timestamp)); Text(time, style = MaterialTheme.typography.labelSmall, color = if (summary.hasUnread) AppleBlue else SystemGray)
            }
            Text(text = summary.lastMessage, style = MaterialTheme.typography.bodyLarge, color = SystemGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (summary.hasUnread) Box(Modifier.padding(start = 8.dp).size(12.dp).background(AppleBlue, CircleShape))
        Icon(Icons.Default.ChevronRight, null, tint = SystemGray3, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SwiftUserItem(user: UserProfile, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = user.photoUrl, contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape).background(SystemGray5), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(16.dp)); Text(user.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SwiftMessageBubble(message: Message, isMe: Boolean) {
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart; val bubbleColor = if (isMe) AppleBlue else SystemGray5; val textColor = if (isMe) Color.White else Color.Black
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(color = bubbleColor, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (isMe) 18.dp else 4.dp, bottomEnd = if (isMe) 4.dp else 18.dp), modifier = Modifier.widthIn(max = 280.dp)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.imageUrl != null) AsyncImage(model = message.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                if (message.audioUrl != null) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, null, tint = textColor); Text("Mensagem de voz", color = textColor, modifier = Modifier.padding(start = 8.dp)) }
                if (message.text.isNotEmpty()) Text(message.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
                Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                    Text(time, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        Icon(if (message.isRead) Icons.Default.DoneAll else Icons.Default.Check, null, tint = if (message.isRead) Color.Cyan else textColor.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SwiftInput(text: String, onTextChange: (String) -> Unit, onImageClick: () -> Unit, onSend: () -> Unit, onAudioStart: () -> Unit, onAudioStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onImageClick) { Icon(Icons.Default.Add, null, tint = AppleBlue, modifier = Modifier.size(28.dp)) }
            TextField(value = text, onValueChange = onTextChange, placeholder = { Text("Mensagem") }, modifier = Modifier.weight(1f).heightIn(min = 36.dp, max = 120.dp), shape = RoundedCornerShape(20.dp), colors = TextFieldDefaults.colors(focusedContainerColor = SystemGray6, unfocusedContainerColor = SystemGray6, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            if (text.isNotEmpty()) IconButton(onClick = onSend) { Icon(Icons.Default.ArrowUpward, null, tint = Color.White, modifier = Modifier.background(AppleBlue, CircleShape).padding(4.dp).size(20.dp)) }
            else {
                IconButton(onClick = {}, modifier = Modifier.pointerInteropFilter { 
                    when (it.action) {
                        MotionEvent.ACTION_DOWN -> { onAudioStart(); true }
                        MotionEvent.ACTION_UP -> { onAudioStop(); true }
                        else -> false
                    }
                }) { Icon(Icons.Default.Mic, null, tint = AppleBlue, modifier = Modifier.size(24.dp)) }
            }
        }
    }
}
