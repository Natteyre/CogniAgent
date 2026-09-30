package com.example.aiagent

import android.content.Context
import android.util.Log

/**
 * Long-term Cognitive Memory Manager.
 * Handles storage, retrieval, and contextual injection of user facts into LLM system prompts.
 */
class MemoryManager(private val context: Context, private val database: AgentDatabase) {

    private val tag = "MemoryManager"
    private val dao = database.agentDao()

    /**
     * Saves a new fact into long-term memory.
     */
    suspend fun saveFact(category: String, factContent: String): Long {
        val entity = FactEntity(
            category = category.trim(),
            factContent = factContent.trim(),
            timestamp = System.currentTimeMillis()
        )
        val id = dao.insertFact(entity)
        Log.i(tag, "Saved fact [$id] ($category): $factContent")
        return id
    }

    /**
     * Searches stored facts by keyword matching.
     */
    suspend fun searchFacts(keyword: String): List<FactEntity> {
        val cleanKeyword = keyword.trim().lowercase()
        return if (cleanKeyword.isBlank()) {
            dao.getAllFacts()
        } else {
            dao.searchFacts(cleanKeyword)
        }
    }

    /**
     * Deletes stored facts containing the specified keyword.
     */
    suspend fun deleteFactsByKeyword(keyword: String): Int {
        val count = dao.deleteFactsByKeyword(keyword)
        Log.i(tag, "Deleted $count facts matching keyword: '$keyword'")
        return count
    }

    /**
     * Builds a formatted Polish memory context string to be injected into the LLM System Prompt.
     */
    suspend fun buildMemoryContext(userQuery: String): String {
        val words = userQuery.lowercase()
            .replace(Regex("[.,!?;:]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in listOf("kto", "co", "jak", "gdzie", "kiedy", "dlaczego", "jest", "był", "będzie", "mój", "moja", "moje") }

        val foundFacts = mutableSetOf<FactEntity>()

        for (word in words) {
            val results = dao.searchFacts(word)
            foundFacts.addAll(results)
        }

        // If no specific match found, take the top 5 most recent facts
        if (foundFacts.isEmpty()) {
            val recent = dao.getAllFacts().take(5)
            foundFacts.addAll(recent)
        }

        if (foundFacts.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.append("\n[DŁUGOTERMINOWA PAMIĘĆ AGENTA - FAKTY O UŻYTKOWNIKU]:\n")
        foundFacts.take(8).forEach { fact ->
            sb.append("- (${fact.category}) ${fact.factContent}\n")
        }
        sb.append("Wykorzystaj powyższe fakty, jeśli są istotne dla pytania użytkownika.\n")
        return sb.toString()
    }
}
