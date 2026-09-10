package com.studyagent.client.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StudyCard(
    val id: String,
    val question: String,
    @SerialName("card_number")
    val cardNumber: Int? = null,
    val remaining: Int? = null,
    @SerialName("deck_name")
    val deckName: String? = null
)
