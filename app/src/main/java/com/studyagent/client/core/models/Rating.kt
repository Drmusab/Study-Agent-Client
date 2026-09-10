package com.studyagent.client.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Rating(val wireValue: String, val displayName: String) {
    @SerialName("again")
    AGAIN("again", "Again"),

    @SerialName("hard")
    HARD("hard", "Hard"),

    @SerialName("good")
    GOOD("good", "Good"),

    @SerialName("easy")
    EASY("easy", "Easy");

    companion object {
        fun fromString(value: String?): Rating? {
            if (value == null) return null
            val normalized = value.trim().lowercase()
            return when {
                normalized.contains("again") || normalized.contains("مرة") || normalized.contains("اعد") -> AGAIN
                normalized.contains("hard") || normalized.contains("صعب") -> HARD
                normalized.contains("good") || normalized.contains("جيد") || normalized.contains("تمام") -> GOOD
                normalized.contains("easy") || normalized.contains("سهل") || normalized.contains("بسيط") -> EASY
                else -> entries.firstOrNull { it.wireValue.equals(normalized, ignoreCase = true) }
            }
        }
    }
}
