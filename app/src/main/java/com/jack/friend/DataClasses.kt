package com.jack.friend

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

data class ChatSummary(
    val friendId: String = "",
    val lastMessage: String = "",
    val timestamp: Long = 0L,
    val hasUnread: Boolean = false,
    val lastSenderId: String = "",
    val lastMessageRead: Boolean = false,
    val friendName: String? = null,
    val friendPhotoUrl: String? = null,
    @get:PropertyName("isOnline")
    @set:PropertyName("isOnline")
    var isOnline: Boolean = false,
    var lastActive: Long = 0L,
    var isPinned: Boolean = false,
    var isMuted: Boolean = false,
    var isTyping: Boolean = false,
    var isEphemeral: Boolean = false,
    var isGroup: Boolean = false,
    var presenceStatus: String = "Online"
)

@IgnoreExtraProperties
data class UserStatus(
    var userId: String = "",
    var isOnline: Boolean = false,
    var lastActive: Long = 0L,
    var username: String = "",
    var imageUrl: String = "",
    var timestamp: Long = 0L,
    var userPhotoUrl: String? = null,
    var id: String = "",
    var viewers: Map<String, Long> = emptyMap() // userId -> timestamp
)

@IgnoreExtraProperties
data class Group(
    var id: String = "",
    var name: String = "",
    var photoUrl: String? = null,
    var description: String = "",
    var members: Map<String, Boolean> = emptyMap(), // username -> true
    var createdBy: String = "",
    var timestamp: Long = 0L
)
