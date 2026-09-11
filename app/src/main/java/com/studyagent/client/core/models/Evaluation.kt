package com.studyagent.client.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Evaluation(
    val score: Int? = null,
    @SerialName("short_feedback")
    val shortFeedback: String = "",
    @SerialName("correct_points")
    val correctPoints: List<String> = emptyList(),
    @SerialName("missing_points")
    val missingPoints: List<String> = emptyList(),
    @SerialName("incorrect_points")
    val incorrectPoints: List<String> = emptyList(),
    @SerialName("suggested_rating")
    val suggestedRating: Rating? = null
    val suggestedRating: Rating? = null,
    /** Evaluator confidence in percent (0..100), used for auto-rating decisions. */
    val confidence: Double? = null
)