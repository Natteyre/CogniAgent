package com.example.aiagent

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Long-term memory entity storing extracted facts, personal preferences, and knowledge.
 */
@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val factContent: String,
    val timestamp: Long = System.currentTimeMillis()
)
