package com.jack.friend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jack.friend.ui.theme.AppleGreen

@Composable
fun ModernIncomingCallOverlay(callMessage: Message, viewModel: ChatViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Incoming Call", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(callMessage.senderName ?: "Unknown", style = MaterialTheme.typography.titleLarge, color = Color.White)

            Spacer(modifier = Modifier.height(48.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                IconButton(
                    onClick = { /* TODO: Implement reject call */ },
                    modifier = Modifier.size(72.dp).background(Color.Red, CircleShape)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Reject Call", tint = Color.White, modifier = Modifier.size(40.dp))
                }

                IconButton(
                    onClick = { /* TODO: Implement accept call */ },
                    modifier = Modifier.size(72.dp).background(AppleGreen, CircleShape)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Accept Call", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}
