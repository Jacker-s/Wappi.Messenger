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
    val secondaryBackground: Color,
    val tertiaryBackground: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        bubbleMe = iOSBlue,
        bubbleOther = MessengerBubbleOther,
        background = Color.White,
        topBar = Color.White,
        onTopBar = Color.Black,
        primary = iOSBlue,
        secondaryBackground = iOSSystemBackgroundLight,
        tertiaryBackground = Color.White,
        separator = Color(0xFFC6C6C8),
        textPrimary = Color.Black,
        textSecondary = iOSGray
    )
}

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        color = iOSGray
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
        onDispose {
            uiPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val actualDark = when {
        isDarkModeOverride != null -> isDarkModeOverride
        uiPrefs.contains("dark_mode") -> isDarkModePref
        else -> darkTheme
    }

    val colorScheme = if (actualDark) {
        darkColorScheme(
            primary = iOSBlue,
            background = iOSSystemBackgroundDark,
            surface = iOSSystemGroupedBackgroundDark,
            onSurface = Color.White,
            onSurfaceVariant = iOSGray,
            outline = Color(0xFF38383A)
        )
    } else {
        lightColorScheme(
            primary = iOSBlue,
            background = iOSSystemBackgroundLight,
            surface = iOSSystemGroupedBackgroundLight,
            onSurface = Color.Black,
            onSurfaceVariant = iOSGray,
            outline = Color(0xFFC6C6C8)
        )
    }

    val chatColors = ChatColors(
        bubbleMe = iOSBlue,
        bubbleOther = if (actualDark) MessengerBubbleOtherDark else MessengerBubbleOther,
        background = if (actualDark) iOSSystemBackgroundDark else iOSSystemBackgroundLight,
        topBar = if (actualDark) Color.Black else Color.White,
        onTopBar = if (actualDark) Color.White else Color.Black,
        primary = iOSBlue,
        secondaryBackground = if (actualDark) iOSSystemGroupedBackgroundDark else iOSSystemGroupedBackgroundLight,
        tertiaryBackground = if (actualDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA),
        separator = if (actualDark) Color(0xFF38383A) else Color(0xFFC6C6C8),
        textPrimary = if (actualDark) Color.White else Color.Black,
        textSecondary = iOSGray
    )

    CompositionLocalProvider(LocalChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
