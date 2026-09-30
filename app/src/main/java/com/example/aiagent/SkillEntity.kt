package com.example.aiagent

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Skill entity storing predefined or user-created macros and action sequences.
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey
    val skillName: String,
    val actionsJson: String,
    val isUserDefined: Boolean = false
)
