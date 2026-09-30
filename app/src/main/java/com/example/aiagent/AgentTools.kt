package com.example.aiagent

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Executes cognitive tools invoked by LLM Function Calling:
 * - Real-time Web Search (Ktor + DuckDuckGo HTML engine)
 * - Android 10+ Scoped Storage file read/write operations
 * - System and Application control triggers
 */
class AgentTools(private val context: Context) {

    private val tag = "AgentTools"

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Executes web search via DuckDuckGo HTML parser without external dependencies.
     */
    suspend fun executeWebSearch(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val response = httpClient.get(url) {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/110.0 Mobile Safari/537.36")
                header("Accept-Language", "pl,en-US;q=0.7,en;q=0.3")
            }

            val html = response.bodyAsText()
            val snippets = parseDuckDuckGoHtml(html)

            if (snippets.isEmpty()) {
                return@withContext "Nie znaleziono wyników w internecie dla zapytania: \"$query\"."
            }

            val sb = StringBuilder("Wyniki wyszukiwania dla \"$query\":\n\n")
            snippets.take(4).forEachIndexed { index, (title, snippet) ->
                sb.append("${index + 1}. $title\n   $snippet\n\n")
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(tag, "Web search failed: ${e.message}", e)
            "Błąd podczas wyszukiwania w internecie: ${e.message}"
        }
    }

    private fun parseDuckDuckGoHtml(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            // Regex patterns to extract title links and snippet texts from DuckDuckGo HTML
            val snippetPattern = Regex("<a class=\"result__snippet[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val titlePattern = Regex("<a class=\"result__url[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)

            val snippetMatches = snippetPattern.findAll(html).toList()
            val titleMatches = titlePattern.findAll(html).toList()

            for (i in snippetMatches.indices) {
                val rawSnippet = snippetMatches[i].groupValues[1]
                val cleanSnippet = rawSnippet.replace(Regex("<[^>]*>"), "")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&#x27;", "'")
                    .trim()

                val rawTitle = if (i < titleMatches.size) titleMatches[i].groupValues[1] else "Wynik ${i + 1}"
                val cleanTitle = rawTitle.replace(Regex("<[^>]*>"), "").trim()

                if (cleanSnippet.isNotBlank()) {
                    results.add(cleanTitle to cleanSnippet)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse search HTML: ${e.message}")
        }
        return results
    }

    /**
     * Reads a text file from Android 10+ Scoped Storage (app external files or internal storage).
     */
    suspend fun executeReadFile(fileName: String): String = withContext(Dispatchers.IO) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val targetFile = File(baseDir, fileName)

            if (!targetFile.exists()) {
                return@withContext "Błąd: Plik '$fileName' nie istnieje w katalogu aplikacji (${baseDir.name})."
            }

            val content = targetFile.readText(Charsets.UTF_8)
            "Zawartość pliku '$fileName':\n$content"
        } catch (e: Exception) {
            Log.e(tag, "File read error: ${e.message}", e)
            "Nie udało się odczytać pliku '$fileName': ${e.message}"
        }
    }

    /**
     * Writes text content into a file in Android 10+ Scoped Storage.
     */
    suspend fun executeWriteFile(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val targetFile = File(baseDir, fileName)
            targetFile.writeText(content, Charsets.UTF_8)
            Log.i(tag, "File written successfully: ${targetFile.absolutePath}")
            "Plik '$fileName' został pomyślnie zapisany (${targetFile.length()} bajtów) w pamięci aplikacji."
        } catch (e: Exception) {
            Log.e(tag, "File write error: ${e.message}", e)
            "Nie udało się zapisać pliku '$fileName': ${e.message}"
        }
    }

    /**
     * Returns OpenAI-compatible Tools JSON definition array for Function Calling.
     */
    fun getToolsJsonDefinition(): String {
        return """
        [
          {
            "type": "function",
            "function": {
              "name": "web_search",
              "description": "Wyszukuje aktualne informacje w internecie przy użyciu wyszukiwarki internetowej.",
              "parameters": {
                "type": "object",
                "properties": {
                  "query": {
                    "type": "string",
                    "description": "Hasło lub fraza do wyszukania w wyszukiwarce"
                  }
                },
                "required": ["query"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "read_file",
              "description": "Odczytuje zawartość pliku tekstowego z pamięci masowej urządzenia.",
              "parameters": {
                "type": "object",
                "properties": {
                  "fileName": {
                    "type": "string",
                    "description": "Nazwa pliku z rozszerzeniem np. notatka.txt"
                  }
                },
                "required": ["fileName"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "write_file",
              "description": "Zapisuje dane lub tekst do pliku w pamięci masowej urządzenia.",
              "parameters": {
                "type": "object",
                "properties": {
                  "fileName": {
                    "type": "string",
                    "description": "Nazwa pliku np. zadania.txt"
                  },
                  "content": {
                    "type": "string",
                    "description": "Tekst lub treść do zapisania"
                  }
                },
                "required": ["fileName", "content"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "save_user_fact",
              "description": "Zapisuje fakt lub informację o użytkowniku do długoterminowej pamięci podręcznej.",
              "parameters": {
                "type": "object",
                "properties": {
                  "category": { "type": "string", "description": "Kategoria faktu np. preferencje, praca, rodzina" },
                  "fact": { "type": "string", "description": "Treść faktu do trwałego zapamiętania" }
                },
                "required": ["category", "fact"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "open_application",
              "description": "Uruchamia wskazaną aplikację na smartfonie.",
              "parameters": {
                "type": "object",
                "properties": {
                  "appName": { "type": "string", "description": "Nazwa aplikacji np. YouTube, Chrome, Kalkulator" }
                },
                "required": ["appName"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "adjust_device_setting",
              "description": "Zmienia głośność lub jasność ekranu w urządzeniu.",
              "parameters": {
                "type": "object",
                "properties": {
                  "settingName": { "type": "string", "enum": ["VOLUME", "BRIGHTNESS"], "description": "Nazwa ustawienia" },
                  "valuePercent": { "type": "integer", "description": "Wartość w procentach od 0 do 100" }
                },
                "required": ["settingName", "valuePercent"]
              }
            }
          }
        ]
        """.trimIndent()
    }
}
