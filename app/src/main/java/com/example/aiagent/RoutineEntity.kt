package com.example.aiagent

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Routine entity configuring scheduled alarms, sensor triggers, and automated behaviors.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val triggerType: String, // e.g. "TIME", "BATTERY_LOW", "POWER_CONNECTED"
    val triggerValue: String, // e.g. "07:30", "15", "CHARGING"
    val targetSkillName: String,
    val isEnabled: Boolean = true
)
