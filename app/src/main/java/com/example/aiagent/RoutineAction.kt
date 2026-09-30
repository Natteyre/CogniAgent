package com.example.aiagent

import com.google.gson.annotations.SerializedName

/**
 * Data model for a single discrete step in a macro routine/skill sequence.
 */
data class RoutineAction(
    @SerializedName("type")
    val type: String, // e.g. "SPEAK", "OPEN_APP", "SET_VOLUME", "SET_BRIGHTNESS", "CLICK_NODE", "SEND_SMS", "DELAY"
    @SerializedName("param1")
    val param1: String? = null,
    @SerializedName("param2")
    val param2: String? = null,
    @SerializedName("delayMs")
    val delayMs: Long = 500L
)
