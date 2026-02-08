package com.jack.friend.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class ChatColors(
    val bubbleMe: Color,
    val bubbleOther: Color,
    val background: Color,
    val topBar: Color,
    val onTopBar: Color,
    val primary: Color,
    val fab: Color
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        bubbleMe = FriendBubbleMe,
        bubbleOther = FriendBubbleOther,
        background = FriendBackground,
        topBar = FriendSurface,
        onTopBar = FriendOnSurface,
        primary = FriendPrimary,
        fab = FriendSecondary
    )
}

// SwiftUI-like Typography
val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.37.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.38.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.41).sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

@Composable
fun FriendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isDarkModeOverride: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val uiPrefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    var isDarkModePref by remember { mutableStateOf(uiPrefs.getBoolean("dark_mode", false)) }

    DisposableEffect(uiPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "dark_mode") {
                isDarkModePref = prefs.getBoolean("dark_mode", false)
            }
        }
        uiPrefs.registerOnSharedPreferenceChangeListener(listener)
        isDarkModePref = uiPrefs.getBoolean("dark_mode", false)
        onDispose {
            uiPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val actualDark = when {
        isDarkModeOverride != null -> isDarkModeOverride
        uiPrefs.contains("dark_mode") -> isDarkModePref
        else -> darkTheme
    }

    val primaryColor = FriendPrimary

    val colorScheme = if (actualDark) {
        darkColorScheme(
            primary = primaryColor,
            background = Color(0xFF000000), // Pure black for OLED
            surface = Color(0xFF1C1C1E),    // iOS Dark Gray
            onSurface = Color.White,
            onSurfaceVariant = SystemGray
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            background = FriendBackground,
            surface = FriendSurface,
            onSurface = FriendOnSurface,
            onSurfaceVariant = FriendOnSurfaceVariant
        )
    }

    val chatColors = ChatColors(
        bubbleMe = primaryColor,
        bubbleOther = if (actualDark) Color(0xFF262629) else FriendBubbleOther,
        background = if (actualDark) Color(0xFF000000) else FriendBackground,
        topBar = if (actualDark) Color(0xFF1C1C1E) else FriendSurface,
        onTopBar = if (actualDark) Color.White else FriendOnSurface,
        primary = primaryColor,
        fab = FriendSecondary
    )

    CompositionLocalProvider(LocalChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
