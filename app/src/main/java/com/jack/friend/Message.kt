package com.jack.friend

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName
import java.io.Serializable

@IgnoreExtraProperties
data class Message(
    var id: String = "",
    var senderId: String = "",
    var receiverId: String = "",
    var text: String = "",
    var imageUrl: String? = null,
    var audioUrl: String? = null,
    var stickerUrl: String? = null,
    var timestamp: Long = 0L,
    @get:PropertyName("isRead")
    @set:PropertyName("isRead")
    var isRead: Boolean = false,
    
    // Novas funções estilo WhatsApp
    var replyToId: String? = null,
    var replyToText: String? = null,
    var replyToName: String? = null,
    var isDeleted: Boolean = false,
    var isEdited: Boolean = false,
    var reactions: Map<String, String>? = null, // userId -> emoji
    
    // Adicionais
    var isForwarded: Boolean = false,
    var isStarred: Boolean = false,
    var isViewOnce: Boolean = false,
    @get:PropertyName("audioPlayed")
    @set:PropertyName("audioPlayed")
    var audioPlayed: Boolean = false,
    
    // Suporte para Grupos
    var isGroup: Boolean = false,
    var senderName: String? = null, // Para mostrar quem enviou no grupo
    var senderPhotoUrl: String? = null,
    var isSticker: Boolean = false,

    // Mídia simplificada
    var isMedia: Boolean = false,
    var mediaUrl: String? = null,

    // Chamadas
    var isCall: Boolean = false,
    var callRoomId: String? = null,
    var callType: String? = null,
    var callStatus: String? = null,
    var expiryTime: Long? = null,

    @get:Exclude
    @set:Exclude
    var localAudioPath: String? = null
) : Serializable
