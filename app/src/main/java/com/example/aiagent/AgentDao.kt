package com.example.aiagent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Room database operations.
 * Supports asynchronous coroutines and Flow observables.
 */
@Dao
interface AgentDao {

    // --- Facts (Long-term Memory) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: FactEntity): Long

    @Query("SELECT * FROM facts ORDER BY timestamp DESC")
    fun getAllFactsFlow(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts ORDER BY timestamp DESC")
    suspend fun getAllFacts(): List<FactEntity>

    @Query("SELECT * FROM facts WHERE factContent LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchFacts(query: String): List<FactEntity>

    @Query("DELETE FROM facts WHERE factContent LIKE '%' || :keyword || '%' OR category LIKE '%' || :keyword || '%'")
    suspend fun deleteFactsByKeyword(keyword: String): Int

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteFactById(id: Long): Int

    // --- Skills (Automated Macro Sequences) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY skillName ASC")
    fun getAllSkillsFlow(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE skillName = :name LIMIT 1")
    suspend fun getSkillByName(name: String): SkillEntity?

    @Query("DELETE FROM skills WHERE skillName = :name")
    suspend fun deleteSkillByName(name: String): Int

    // --- Routines (Triggers & Schedules) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Query("SELECT * FROM routines ORDER BY id DESC")
    fun getAllRoutinesFlow(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE isEnabled = 1 AND triggerType = :type")
    suspend fun getActiveRoutinesByTrigger(type: String): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun getRoutineById(id: Long): RoutineEntity?

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutineById(id: Long): Int
}
