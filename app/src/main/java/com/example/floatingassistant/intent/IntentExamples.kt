package com.example.floatingassistant.intent

/**
 * Predefined intents with example phrasings. [IntentClassifier] embeds every
 * example once at startup and matches new queries against them by cosine
 * similarity — add more examples here (or new intents) to improve coverage
 * without touching any classifier code.
 */
object IntentExamples {

    val ALL: List<Intent> = listOf(
        Intent(
            name = "TURN_ON_DARK_MODE",
            examples = listOf(
                "Turn on dark mode",
                "Enable dark mode",
                "Switch to dark theme",
                "Make the screen dark",
                "I want a dark theme",
                "Change my display to dark",
                "I want to use dark mode",
                "I want to change into dark mode",
                "Can you make the display dark",
                "Make my screen dark"
            )
        ),
        Intent(
            name = "TURN_ON_LIGHT_MODE",
            examples = listOf(
                "Turn on light mode",
                "Enable light mode",
                "Switch to light theme",
                "Make the screen light",
                "Change my display to light",
                "I want a light theme",
                "Switch to light mode",
                "Turn off dark mode"
            )
        ),
        Intent(
            name = "OPEN_SETTINGS",
            examples = listOf(
                "Open settings",
                "Take me to settings",
                "Go to the settings page",
                "Show me settings",
                "I want to open settings",
                "Launch the settings app"
            )
        ),
        Intent(
            name = "CLOSE_SETTINGS",
            examples = listOf(
                "Close settings",
                "Exit settings",
                "Go back from settings",
                "Leave the settings page",
                "Close the settings screen"
            )
        ),
        Intent(
            name = "INCREASE_VOLUME",
            examples = listOf(
                "Turn up the volume",
                "Increase the volume",
                "Make it louder",
                "Raise the volume",
                "Volume up please",
                "Can you turn the sound up"
            )
        ),
        Intent(
            name = "DECREASE_VOLUME",
            examples = listOf(
                "Turn down the volume",
                "Decrease the volume",
                "Make it quieter",
                "Lower the volume",
                "Volume down please",
                "Can you turn the sound down"
            )
        ),
        Intent(
            name = "PLAY_MUSIC",
            examples = listOf(
                "Play music",
                "Start playing music",
                "Play some songs",
                "Resume the music",
                "Play my playlist",
                "Start the music"
            )
        ),
        Intent(
            name = "PAUSE_MUSIC",
            examples = listOf(
                "Pause the music",
                "Pause the song",
                "Hold the music for a second",
                "Pause playback"
            )
        ),
        Intent(
            name = "STOP_MUSIC",
            examples = listOf(
                "Stop the music",
                "Stop playing music",
                "End the music",
                "Turn off the music",
                "Stop playback"
            )
        ),

        // ── Mobile Settings ──────────────────────────────────────────────
        Intent(
            name = "TURN_ON_WIFI",
            examples = listOf(
                "Turn on wifi",
                "Enable wifi",
                "Connect to wifi",
                "Switch on the wifi",
                "Turn wifi on please",
                "I want to enable wireless internet"
            )
        ),
        Intent(
            name = "TURN_OFF_WIFI",
            examples = listOf(
                "Turn off wifi",
                "Disable wifi",
                "Disconnect from wifi",
                "Switch off the wifi",
                "Turn wifi off please"
            )
        ),
        Intent(
            name = "TURN_ON_BLUETOOTH",
            examples = listOf(
                "Turn on bluetooth",
                "Enable bluetooth",
                "Switch on bluetooth",
                "I want to connect bluetooth headphones",
                "Turn bluetooth on"
            )
        ),
        Intent(
            name = "TURN_OFF_BLUETOOTH",
            examples = listOf(
                "Turn off bluetooth",
                "Disable bluetooth",
                "Switch off bluetooth",
                "Turn bluetooth off"
            )
        ),
        Intent(
            name = "TURN_ON_AIRPLANE_MODE",
            examples = listOf(
                "Turn on airplane mode",
                "Enable flight mode",
                "Switch on airplane mode",
                "Put my phone in flight mode",
                "Turn on flight mode please"
            )
        ),
        Intent(
            name = "TURN_OFF_AIRPLANE_MODE",
            examples = listOf(
                "Turn off airplane mode",
                "Disable flight mode",
                "Switch off airplane mode",
                "Take my phone out of flight mode"
            )
        ),
        Intent(
            name = "INCREASE_BRIGHTNESS",
            examples = listOf(
                "Increase the brightness",
                "Turn up the brightness",
                "Make the screen brighter",
                "Raise screen brightness",
                "Brightness up please",
                "The screen is too dim, make it brighter"
            )
        ),
        Intent(
            name = "DECREASE_BRIGHTNESS",
            examples = listOf(
                "Decrease the brightness",
                "Turn down the brightness",
                "Make the screen dimmer",
                "Lower screen brightness",
                "Brightness down please",
                "The screen is too bright, dim it"
            )
        ),
        Intent(
            name = "TURN_ON_DO_NOT_DISTURB",
            examples = listOf(
                "Turn on do not disturb",
                "Enable do not disturb mode",
                "Silence all notifications",
                "Turn on DND",
                "I don't want to be disturbed right now"
            )
        ),
        Intent(
            name = "TURN_OFF_DO_NOT_DISTURB",
            examples = listOf(
                "Turn off do not disturb",
                "Disable do not disturb mode",
                "Turn off DND",
                "Let notifications through again",
                "Stop silencing notifications"
            )
        ),
        Intent(
            name = "TURN_ON_FLASHLIGHT",
            examples = listOf(
                "Turn on the flashlight",
                "Turn on the torch",
                "Switch on flashlight",
                "I need some light, turn on the torch",
                "Enable flashlight"
            )
        ),
        Intent(
            name = "TURN_OFF_FLASHLIGHT",
            examples = listOf(
                "Turn off the flashlight",
                "Turn off the torch",
                "Switch off flashlight",
                "Disable flashlight"
            )
        ),
        Intent(
            name = "CHECK_BATTERY_STATUS",
            examples = listOf(
                "How much battery do I have",
                "Check battery percentage",
                "Show battery status",
                "What's my battery level",
                "Open battery settings"
            )
        ),
        Intent(
            name = "CHECK_STORAGE",
            examples = listOf(
                "How much storage do I have left",
                "Check storage space",
                "Show me storage usage",
                "Open storage settings",
                "How much space is free on my phone"
            )
        ),
        Intent(
            name = "LOCK_SCREEN",
            examples = listOf(
                "Lock my screen",
                "Lock the phone",
                "Turn off the display and lock it",
                "Lock my phone now"
            )
        ),
        Intent(
            name = "CHANGE_WALLPAPER",
            examples = listOf(
                "Change my wallpaper",
                "Set a new wallpaper",
                "I want to change my background",
                "Change the home screen background",
                "Pick a new wallpaper"
            )
        ),

        // ── WhatsApp ─────────────────────────────────────────────────────
        Intent(
            name = "OPEN_WHATSAPP",
            examples = listOf(
                "Open WhatsApp",
                "Launch WhatsApp",
                "Take me to WhatsApp",
                "I want to open WhatsApp",
                "Start WhatsApp"
            )
        ),
        Intent(
            name = "CHANGE_WHATSAPP_PROFILE_PICTURE",
            examples = listOf(
                "Change my WhatsApp profile picture",
                "Change my WhatsApp DP",
                "Update my WhatsApp photo",
                "I want to change my WhatsApp display picture",
                "Set a new WhatsApp profile photo",
                "Change my whatsapp dp"
            )
        ),
        Intent(
            name = "SEND_WHATSAPP_MESSAGE",
            examples = listOf(
                "Send a WhatsApp message to Mom",
                "Message someone on WhatsApp",
                "Send a WhatsApp text",
                "I want to send a message on WhatsApp",
                "Text someone through WhatsApp"
            )
        ),
        Intent(
            name = "MAKE_WHATSAPP_CALL",
            examples = listOf(
                "Make a WhatsApp voice call",
                "Call someone on WhatsApp",
                "Start a WhatsApp voice call",
                "Ring someone on WhatsApp"
            )
        ),
        Intent(
            name = "MAKE_WHATSAPP_VIDEO_CALL",
            examples = listOf(
                "Make a WhatsApp video call",
                "Video call someone on WhatsApp",
                "Start a WhatsApp video call",
                "I want to video call on WhatsApp"
            )
        ),
        Intent(
            name = "MUTE_WHATSAPP_CHAT",
            examples = listOf(
                "Mute this WhatsApp chat",
                "Mute notifications for this chat",
                "Silence this WhatsApp conversation",
                "Turn off notifications for this WhatsApp chat"
            )
        ),
        Intent(
            name = "UNMUTE_WHATSAPP_CHAT",
            examples = listOf(
                "Unmute this WhatsApp chat",
                "Turn notifications back on for this chat",
                "Unmute this conversation",
                "Stop silencing this WhatsApp chat"
            )
        ),
        Intent(
            name = "ARCHIVE_WHATSAPP_CHAT",
            examples = listOf(
                "Archive this WhatsApp chat",
                "Archive this conversation",
                "Move this chat to archive",
                "I want to archive this WhatsApp chat"
            )
        ),
        Intent(
            name = "DELETE_WHATSAPP_CHAT",
            examples = listOf(
                "Delete this WhatsApp chat",
                "Delete this conversation",
                "Remove this WhatsApp chat",
                "I want to delete this chat"
            )
        ),
        Intent(
            name = "BLOCK_WHATSAPP_CONTACT",
            examples = listOf(
                "Block this contact on WhatsApp",
                "Block this person on WhatsApp",
                "I want to block them on WhatsApp",
                "Block this WhatsApp number"
            )
        ),
        Intent(
            name = "OPEN_WHATSAPP_STATUS",
            examples = listOf(
                "Open WhatsApp status",
                "Show me WhatsApp statuses",
                "View WhatsApp status updates",
                "Check who posted a status on WhatsApp"
            )
        ),
        Intent(
            name = "CLEAR_WHATSAPP_CHAT_HISTORY",
            examples = listOf(
                "Clear this WhatsApp chat history",
                "Delete all messages in this chat",
                "Clear the conversation history",
                "Wipe this WhatsApp chat"
            )
        ),

        // ── YouTube ──────────────────────────────────────────────────────
        Intent(
            name = "SEARCH_YOUTUBE_VIDEO",
            examples = listOf(
                "Search for a video on YouTube",
                "Find a video about cooking on YouTube",
                "Search YouTube for cat videos",
                "Look up a video on YouTube",
                "I want to search something on YouTube"
            )
        ),
        Intent(
            name = "PLAY_YOUTUBE_VIDEO",
            examples = listOf(
                "Play this video",
                "Play the YouTube video",
                "Resume this YouTube video",
                "Start playing this video"
            )
        ),
        Intent(
            name = "PAUSE_YOUTUBE_VIDEO",
            examples = listOf(
                "Pause this video",
                "Pause the YouTube video",
                "Hold the video for a second",
                "Stop the video for now"
            )
        ),
        Intent(
            name = "SUBSCRIBE_YOUTUBE_CHANNEL",
            examples = listOf(
                "Subscribe to this channel",
                "Subscribe to this YouTube channel",
                "I want to follow this channel",
                "Subscribe to this creator"
            )
        ),
        Intent(
            name = "UNSUBSCRIBE_YOUTUBE_CHANNEL",
            examples = listOf(
                "Unsubscribe from this channel",
                "Unsubscribe from this YouTube channel",
                "Stop following this channel",
                "Remove my subscription to this channel"
            )
        ),
        Intent(
            name = "LIKE_YOUTUBE_VIDEO",
            examples = listOf(
                "Like this video",
                "Give this video a thumbs up",
                "Like this YouTube video",
                "I want to like this video"
            )
        ),
        Intent(
            name = "DISLIKE_YOUTUBE_VIDEO",
            examples = listOf(
                "Dislike this video",
                "Give this video a thumbs down",
                "I don't like this video",
                "Dislike this YouTube video"
            )
        ),
        Intent(
            name = "OPEN_YOUTUBE_HISTORY",
            examples = listOf(
                "Open my YouTube watch history",
                "Show me videos I've watched",
                "Take me to my YouTube history",
                "What have I watched on YouTube recently"
            )
        ),
        Intent(
            name = "CLEAR_YOUTUBE_HISTORY",
            examples = listOf(
                "Clear my YouTube watch history",
                "Delete my YouTube history",
                "Erase my watch history",
                "Wipe my YouTube viewing history"
            )
        ),
        Intent(
            name = "ADD_TO_WATCH_LATER",
            examples = listOf(
                "Add this video to watch later",
                "Save this for watch later",
                "Add to my watch later list",
                "I want to watch this later"
            )
        ),
        Intent(
            name = "TURN_ON_YOUTUBE_CAPTIONS",
            examples = listOf(
                "Turn on captions",
                "Enable subtitles on this video",
                "Show captions for this video",
                "Turn on subtitles please"
            )
        ),
        Intent(
            name = "TURN_OFF_YOUTUBE_CAPTIONS",
            examples = listOf(
                "Turn off captions",
                "Disable subtitles on this video",
                "Hide captions for this video",
                "Turn off subtitles please"
            )
        ),
        Intent(
            name = "CHANGE_YOUTUBE_PLAYBACK_SPEED",
            examples = listOf(
                "Change the playback speed",
                "Play this video faster",
                "Slow down the video playback",
                "Set playback speed to 2x",
                "I want to watch this at 1.5x speed"
            )
        ),

        // ── Email ────────────────────────────────────────────────────────
        Intent(
            name = "COMPOSE_EMAIL",
            examples = listOf(
                "Compose a new email",
                "Write a new email",
                "I want to send an email",
                "Start a new email",
                "Draft an email to someone"
            )
        ),
        Intent(
            name = "SEND_EMAIL",
            examples = listOf(
                "Send this email",
                "Send the email now",
                "Go ahead and send it",
                "I'm done, send the email"
            )
        ),
        Intent(
            name = "DELETE_EMAIL",
            examples = listOf(
                "Delete this email",
                "Remove this email",
                "Move this email to trash",
                "I want to delete this message"
            )
        ),
        Intent(
            name = "ARCHIVE_EMAIL",
            examples = listOf(
                "Archive this email",
                "Move this email to archive",
                "Archive this message",
                "I want to archive this mail"
            )
        ),
        Intent(
            name = "MARK_EMAIL_AS_READ",
            examples = listOf(
                "Mark this email as read",
                "Mark as read",
                "I've already read this email",
                "Mark this message read"
            )
        ),
        Intent(
            name = "MARK_EMAIL_AS_UNREAD",
            examples = listOf(
                "Mark this email as unread",
                "Mark as unread",
                "Keep this email marked unread",
                "Mark this message unread"
            )
        ),
        Intent(
            name = "OPEN_EMAIL_INBOX",
            examples = listOf(
                "Open my inbox",
                "Show me my email inbox",
                "Take me to my inbox",
                "Go to my email inbox"
            )
        ),
        Intent(
            name = "OPEN_SENT_EMAILS",
            examples = listOf(
                "Show me my sent emails",
                "Open sent mail folder",
                "Take me to sent items",
                "What emails have I sent"
            )
        ),
        Intent(
            name = "OPEN_EMAIL_DRAFTS",
            examples = listOf(
                "Open my drafts",
                "Show me my draft emails",
                "Take me to drafts",
                "What emails do I have saved as drafts"
            )
        ),
        Intent(
            name = "SEARCH_EMAIL",
            examples = listOf(
                "Search my emails",
                "Find an email from someone",
                "Search my inbox for an email",
                "Look up an email about something"
            )
        ),
        Intent(
            name = "REPLY_TO_EMAIL",
            examples = listOf(
                "Reply to this email",
                "Reply to this message",
                "I want to respond to this email",
                "Write a reply to this email"
            )
        ),
        Intent(
            name = "FORWARD_EMAIL",
            examples = listOf(
                "Forward this email",
                "Forward this message to someone",
                "I want to forward this email",
                "Send this email to someone else"
            )
        ),
        Intent(
            name = "STAR_EMAIL",
            examples = listOf(
                "Star this email",
                "Mark this email as important",
                "Flag this email",
                "Add a star to this message"
            )
        )
    )
}