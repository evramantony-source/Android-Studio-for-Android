package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AiProvider(
    val displayName: String,
    val shortName: String,
    val defaultModelId: String,
    val keyHint: String,
    val usageConsoleUrl: String,
) {
    GEMINI(
        displayName = "Google Gemini",
        shortName = "Gemini",
        defaultModelId = GEMINI_MODEL_ID,
        keyHint = "AIza...",
        usageConsoleUrl = "https://aistudio.google.com/usage",
    ),
    OPENAI(
        displayName = "OpenAI ChatGPT",
        shortName = "ChatGPT",
        defaultModelId = "gpt-5",
        keyHint = "sk-...",
        usageConsoleUrl = "https://platform.openai.com/usage",
    ),
    CLAUDE(
        displayName = "Anthropic Claude",
        shortName = "Claude",
        defaultModelId = "claude-sonnet-4-5",
        keyHint = "sk-ant-...",
        usageConsoleUrl = "https://console.anthropic.com/settings/usage",
    ),
}

enum class AiThinkingMode(
    val label: String,
    val badge: String,
    val description: String,
) {
    FAST(
        label = "Fast",
        badge = "⚡ Fast",
        description = "Minimal thinking delay for instant coding edits",
    ),
    BALANCED(
        label = "Balanced",
        badge = "⚖ Balanced",
        description = "Moderate reasoning for multi-file changes",
    ),
    DEEP(
        label = "Deep",
        badge = "🧠 Deep",
        description = "Extended reasoning for complex bugs & architecture",
    ),
}

data class AiModelOption(
    val id: String,
    val provider: AiProvider,
    val label: String,
    val badge: String,
    val description: String,
    val contextWindow: String = "",
    val isLiveFetched: Boolean = false,
)

data class TokenUsageDelta(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val thinkingTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val latencyMs: Long = 0L,
    val remainingQuotaNote: String = "",
)

data class ProviderUsageStats(
    val provider: AiProvider,
    val requests: Long = 0L,
    val failedRequests: Long = 0L,
    val toolCalls: Long = 0L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val thinkingTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val lastLatencyMs: Long = 0L,
    val lastModelId: String = "",
    val remainingQuotaNote: String = "",
)

data class AiUsageSummary(
    val byProvider: Map<AiProvider, ProviderUsageStats> = emptyMap(),
    val byModel: Map<String, Long> = emptyMap(),
    val lastUpdatedMillis: Long = 0L,
) {
    val totalRequests: Long
        get() = byProvider.values.sumOf { it.requests }
    val totalFailedRequests: Long
        get() = byProvider.values.sumOf { it.failedRequests }
    val totalToolCalls: Long
        get() = byProvider.values.sumOf { it.toolCalls }
    val totalInputTokens: Long
        get() = byProvider.values.sumOf { it.inputTokens }
    val totalOutputTokens: Long
        get() = byProvider.values.sumOf { it.outputTokens }
    val totalThinkingTokens: Long
        get() = byProvider.values.sumOf { it.thinkingTokens }
    val totalTokens: Long
        get() = byProvider.values.sumOf { it.totalTokens }
    val totalEstimatedCostUsd: Double
        get() = byProvider.values.sumOf { it.estimatedCostUsd }

    fun toJson(): String {
        val root = JSONObject()
        val providersObj = JSONObject()
        byProvider.forEach { (provider, stats) ->
            providersObj.put(
                provider.name,
                JSONObject()
                    .put("requests", stats.requests)
                    .put("failedRequests", stats.failedRequests)
                    .put("toolCalls", stats.toolCalls)
                    .put("inputTokens", stats.inputTokens)
                    .put("outputTokens", stats.outputTokens)
                    .put("thinkingTokens", stats.thinkingTokens)
                    .put("totalTokens", stats.totalTokens)
                    .put("estimatedCostUsd", stats.estimatedCostUsd)
                    .put("lastLatencyMs", stats.lastLatencyMs)
                    .put("lastModelId", stats.lastModelId)
                    .put("remainingQuotaNote", stats.remainingQuotaNote),
            )
        }
        val modelsObj = JSONObject()
        byModel.forEach { (model, tokens) -> modelsObj.put(model, tokens) }
        root.put("byProvider", providersObj)
        root.put("byModel", modelsObj)
        root.put("lastUpdatedMillis", lastUpdatedMillis)
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String): AiUsageSummary {
            if (raw.isBlank()) return AiUsageSummary()
            return runCatching {
                val root = JSONObject(raw)
                val providersObj = root.optJSONObject("byProvider") ?: JSONObject()
                val providers = linkedMapOf<AiProvider, ProviderUsageStats>()
                AiProvider.entries.forEach { provider ->
                    val p = providersObj.optJSONObject(provider.name)
                    if (p != null) {
                        providers[provider] = ProviderUsageStats(
                            provider = provider,
                            requests = p.optLong("requests", 0L),
                            failedRequests = p.optLong("failedRequests", 0L),
                            toolCalls = p.optLong("toolCalls", 0L),
                            inputTokens = p.optLong("inputTokens", 0L),
                            outputTokens = p.optLong("outputTokens", 0L),
                            thinkingTokens = p.optLong("thinkingTokens", 0L),
                            totalTokens = p.optLong("totalTokens", 0L),
                            estimatedCostUsd = p.optDouble("estimatedCostUsd", 0.0),
                            lastLatencyMs = p.optLong("lastLatencyMs", 0L),
                            lastModelId = p.optString("lastModelId", ""),
                            remainingQuotaNote = p.optString("remainingQuotaNote", ""),
                        )
                    }
                }
                val modelsObj = root.optJSONObject("byModel") ?: JSONObject()
                val models = linkedMapOf<String, Long>()
                modelsObj.keys().forEach { key ->
                    models[key] = modelsObj.optLong(key, 0L)
                }
                AiUsageSummary(
                    byProvider = providers,
                    byModel = models,
                    lastUpdatedMillis = root.optLong("lastUpdatedMillis", 0L),
                )
            }.getOrDefault(AiUsageSummary())
        }
    }
}

object AiModelsCatalog {

    val builtInGeminiModels: List<AiModelOption> = listOf(
        // Gemini 2.5 Series (GA Fastest & Most Reliable)
        AiModelOption(
            id = "gemini-2.5-flash",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.5 Flash",
            badge = "Default",
            description = "Fast, reliable GA Gemini model for autonomous coding, builds & tool use.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.5-flash-lite",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.5 Flash-Lite",
            badge = "2.5 Lite",
            description = "Ultra-fast responses and minimal token cost for quick edits.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.5-pro",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.5 Pro",
            badge = "2.5 Pro",
            description = "High-accuracy multi-file engineering, deep thinking, and debugging.",
            contextWindow = "1M ctx",
        ),
        // Gemini 2.0 Series (Stable Fallback)
        AiModelOption(
            id = "gemini-2.0-flash",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.0 Flash",
            badge = "2.0",
            description = "Reliable general-purpose model when newer endpoints are busy.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.0-flash-lite",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.0 Flash-Lite",
            badge = "2.0 Lite",
            description = "Lightweight 2.0 variant for quick syntax fixes and Q&A.",
            contextWindow = "1M ctx",
        ),
        // Gemini 3 Preview Series
        AiModelOption(
            id = "gemini-3-flash-preview",
            provider = AiProvider.GEMINI,
            label = "Gemini 3 Flash Preview",
            badge = "Gemini 3",
            description = "Preview Gemini 3 Flash model (auto-falls back to 2.5 Flash if busy).",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3-pro-preview",
            provider = AiProvider.GEMINI,
            label = "Gemini 3 Pro Preview",
            badge = "Gemini 3",
            description = "Preview Gemini 3 Pro model with dynamic thinking levels.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3.1-pro-preview",
            provider = AiProvider.GEMINI,
            label = "Gemini 3.1 Pro Preview",
            badge = "Gemini 3",
            description = "Preview Gemini 3.1 Pro for complex multi-file Android engineering.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3.1-pro-preview-customtools",
            provider = AiProvider.GEMINI,
            label = "Gemini 3.1 Pro CustomTools",
            badge = "Gemini 3",
            description = "Agent-tuned Gemini 3.1 Pro optimized for custom file & build tool loops.",
            contextWindow = "1M ctx",
        ),
        // Gemini 1.5 Legacy Series
        AiModelOption(
            id = "gemini-1.5-pro",
            provider = AiProvider.GEMINI,
            label = "Gemini 1.5 Pro",
            badge = "1.5 Legacy",
            description = "Classic 2M context model for broad compatibility.",
            contextWindow = "2M ctx",
        ),
        AiModelOption(
            id = "gemini-1.5-flash",
            provider = AiProvider.GEMINI,
            label = "Gemini 1.5 Flash",
            badge = "1.5 Legacy",
            description = "Lightweight legacy model for simple code snippets.",
            contextWindow = "1M ctx",
        ),
    )

    val builtInOpenAiModels: List<AiModelOption> = listOf(
        // GPT-6 & GPT-5 Generations
        AiModelOption(
            id = "gpt-6-astra",
            provider = AiProvider.OPENAI,
            label = "GPT-6 Astra",
            badge = "GPT-6",
            description = "OpenAI's latest frontier model for multimodal & agentic software building.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gpt-6.1-sol",
            provider = AiProvider.OPENAI,
            label = "GPT-6.1 Sol",
            badge = "GPT-6",
            description = "Balanced GPT-6.1 model for rapid full-project code generation.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gpt-6.1-terra",
            provider = AiProvider.OPENAI,
            label = "GPT-6.1 Terra",
            badge = "GPT-6",
            description = "High-efficiency GPT-6.1 variant for everyday engineering tasks.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gpt-5",
            provider = AiProvider.OPENAI,
            label = "GPT-5",
            badge = "GPT-5",
            description = "Unified flagship ChatGPT model with built-in reasoning & strong coding.",
            contextWindow = "400K ctx",
        ),
        AiModelOption(
            id = "gpt-5-mini",
            provider = AiProvider.OPENAI,
            label = "GPT-5 Mini",
            badge = "GPT-5",
            description = "Fast, cost-effective GPT-5 coding assistant.",
            contextWindow = "400K ctx",
        ),
        AiModelOption(
            id = "gpt-5-nano",
            provider = AiProvider.OPENAI,
            label = "GPT-5 Nano",
            badge = "GPT-5",
            description = "Ultra-low-latency GPT-5 variant for quick edits and lookups.",
            contextWindow = "400K ctx",
        ),
        // GPT-4.5 / 4.1 / 4o Series
        AiModelOption(
            id = "gpt-4.5-preview",
            provider = AiProvider.OPENAI,
            label = "GPT-4.5 Preview",
            badge = "4.5",
            description = "High-EQ, broad-knowledge GPT-4.5 model.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "gpt-4.1",
            provider = AiProvider.OPENAI,
            label = "GPT-4.1",
            badge = "4.1",
            description = "Strong coding, instruction following, and tool orchestration.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gpt-4.1-mini",
            provider = AiProvider.OPENAI,
            label = "GPT-4.1 Mini",
            badge = "4.1",
            description = "Quick, economical coding edits with 1M context.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gpt-4.1-nano",
            provider = AiProvider.OPENAI,
            label = "GPT-4.1 Nano",
            badge = "4.1",
            description = "Fastest lightweight GPT-4.1 model for simple edits.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gpt-4o",
            provider = AiProvider.OPENAI,
            label = "GPT-4o",
            badge = "4o",
            description = "Versatile ChatGPT omni model for code & UI design.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "gpt-4o-mini",
            provider = AiProvider.OPENAI,
            label = "GPT-4o Mini",
            badge = "4o",
            description = "Low-cost, rapid iteration assistant.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "chatgpt-4o-latest",
            provider = AiProvider.OPENAI,
            label = "ChatGPT-4o Latest",
            badge = "4o",
            description = "Dynamic alias pointing to the current ChatGPT 4o snapshot.",
            contextWindow = "128K ctx",
        ),
        // OpenAI Reasoning o-Series
        AiModelOption(
            id = "o4-mini",
            provider = AiProvider.OPENAI,
            label = "o4-mini",
            badge = "o-Series",
            description = "Compact reasoning model for complex Java/Android bugs.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "o3-pro",
            provider = AiProvider.OPENAI,
            label = "o3-pro",
            badge = "o-Series",
            description = "Extended-compute o3 reasoning for toughest architecture problems.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "o3",
            provider = AiProvider.OPENAI,
            label = "o3",
            badge = "o-Series",
            description = "Deep multi-step STEM & coding reasoning model.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "o3-mini",
            provider = AiProvider.OPENAI,
            label = "o3-mini",
            badge = "o-Series",
            description = "Efficient STEM & algorithm reasoning model.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "o1",
            provider = AiProvider.OPENAI,
            label = "o1",
            badge = "o-Series",
            description = "First-generation o-series full reasoning model.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "o1-mini",
            provider = AiProvider.OPENAI,
            label = "o1-mini",
            badge = "o-Series",
            description = "Compact first-generation o-series reasoning model.",
            contextWindow = "128K ctx",
        ),
        // Classic GPT-4 & GPT-3.5
        AiModelOption(
            id = "gpt-4-turbo",
            provider = AiProvider.OPENAI,
            label = "GPT-4 Turbo",
            badge = "Legacy",
            description = "Classic GPT-4 Turbo with 128K context.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "gpt-4",
            provider = AiProvider.OPENAI,
            label = "GPT-4",
            badge = "Legacy",
            description = "Original GPT-4 model for baseline compatibility.",
            contextWindow = "8K ctx",
        ),
        AiModelOption(
            id = "gpt-3.5-turbo",
            provider = AiProvider.OPENAI,
            label = "GPT-3.5 Turbo",
            badge = "Legacy",
            description = "Fast legacy ChatGPT 3.5 model.",
            contextWindow = "16K ctx",
        ),
    )

    val builtInClaudeModels: List<AiModelOption> = listOf(
        // Claude 5.x Generation
        AiModelOption(
            id = "claude-opus-5-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 5.5",
            badge = "Claude 5",
            description = "Flagship Claude 5.5 powerhouse for deep autonomous software engineering.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-sonnet-5-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Sonnet 5.5",
            badge = "Claude 5",
            description = "Balanced Claude 5.5 model combining speed with top coding precision.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-haiku-5-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Haiku 5.5",
            badge = "Claude 5",
            description = "Near-instant Claude 5.5 model for rapid edits & tight token budgets.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-fable-5-2",
            provider = AiProvider.CLAUDE,
            label = "Claude Fable 5.2",
            badge = "Claude 5",
            description = "High-capacity long-horizon Claude model for demanding workflows.",
            contextWindow = "200K ctx",
        ),
        // Claude 4.5 / 4.1 / 4.0 Generation
        AiModelOption(
            id = "claude-opus-4-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4.5",
            badge = "Claude 4.5",
            description = "High-intelligence Claude 4.5 Opus for multi-file refactoring.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-sonnet-4-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Sonnet 4.5",
            badge = "Claude 4.5",
            description = "Widely trusted coding workhorse with crisp tool-calling accuracy.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-haiku-4-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Haiku 4.5",
            badge = "Claude 4.5",
            description = "Fast, affordable Claude 4.5 assistant.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-opus-4-1",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4.1",
            badge = "Claude 4.1",
            description = "Refined Claude 4.1 Opus for complex agentic coding tasks.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-opus-4-20250514",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4",
            badge = "Claude 4",
            description = "Original Claude 4 Opus for deep architectural reasoning.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-sonnet-4-20250514",
            provider = AiProvider.CLAUDE,
            label = "Claude Sonnet 4",
            badge = "Claude 4",
            description = "Balanced Claude 4 Sonnet for precise Android Java/XML edits.",
            contextWindow = "200K ctx",
        ),
        // Claude 3.7 / 3.5 / 3.0 Generation
        AiModelOption(
            id = "claude-3-7-sonnet-latest",
            provider = AiProvider.CLAUDE,
            label = "Claude 3.7 Sonnet",
            badge = "Claude 3.7",
            description = "Hybrid-reasoning 3.7 Sonnet engineering model.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-5-sonnet-latest",
            provider = AiProvider.CLAUDE,
            label = "Claude 3.5 Sonnet",
            badge = "Claude 3.5",
            description = "Classic 3.5 Sonnet coding favorite.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-5-haiku-latest",
            provider = AiProvider.CLAUDE,
            label = "Claude 3.5 Haiku",
            badge = "Claude 3.5",
            description = "Fast, compact Claude 3.5 model for lightweight edits.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-opus-20240229",
            provider = AiProvider.CLAUDE,
            label = "Claude 3 Opus",
            badge = "Claude 3",
            description = "Legacy Claude 3 Opus flagship.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-sonnet-20240229",
            provider = AiProvider.CLAUDE,
            label = "Claude 3 Sonnet",
            badge = "Claude 3",
            description = "Legacy balanced Claude 3 model.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-haiku-20240307",
            provider = AiProvider.CLAUDE,
            label = "Claude 3 Haiku",
            badge = "Claude 3",
            description = "Legacy ultra-fast Claude 3 Haiku.",
            contextWindow = "200K ctx",
        ),
    )

    fun estimateCostUsd(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        thinkingTokens: Long = 0L,
    ): Double {
        val id = modelId.lowercase()
        val (inPerMillion, outPerMillion) = when {
            id.contains("opus-5") || id.contains("opus-4") || id.contains("fable") || id.contains("o3-pro") || id.contains("o1") -> 15.0 to 75.0
            id.contains("gpt-6") -> 5.0 to 20.0
            id.contains("gpt-4") && !id.contains("mini") && !id.contains("nano") && !id.contains("4.1") -> 2.50 to 10.0
            id.contains("sonnet") || id.contains("pro") || id.contains("gpt-5") && !id.contains("mini") && !id.contains("nano") -> 3.0 to 15.0
            id.contains("gpt-4.1") && !id.contains("mini") && !id.contains("nano") -> 2.0 to 8.0
            id.contains("o3") || id.contains("o4-mini") -> 1.10 to 4.40
            id.contains("haiku") || id.contains("mini") -> 0.40 to 1.60
            id.contains("flash-lite") || id.contains("nano") -> 0.08 to 0.30
            id.contains("flash") || id.contains("hydrogen") -> 0.30 to 2.50
            else -> 1.0 to 4.0
        }
        val billableOut = outputTokens + thinkingTokens
        return (inputTokens / 1_000_000.0) * inPerMillion + (billableOut / 1_000_000.0) * outPerMillion
    }

    fun modelsFor(
        provider: AiProvider,
        liveDiscovered: List<AiModelOption> = emptyList(),
    ): List<AiModelOption> {
        val base = when (provider) {
            AiProvider.GEMINI -> builtInGeminiModels
            AiProvider.OPENAI -> builtInOpenAiModels
            AiProvider.CLAUDE -> builtInClaudeModels
        }
        val merged = linkedMapOf<String, AiModelOption>()
        base.forEach { merged[it.id] = it }
        liveDiscovered.filter { it.provider == provider }.forEach { model ->
            merged.putIfAbsent(model.id, model)
        }
        return merged.values.toList()
    }

    fun normalizeModelId(provider: AiProvider, rawModelId: String?): String {
        val cleaned = rawModelId.orEmpty().trim().removePrefix("models/")
        if (cleaned.isEmpty()) return provider.defaultModelId
        return cleaned
    }

    fun fallbackChain(provider: AiProvider, primaryModelId: String): List<String> {
        val cleanPrimary = normalizeModelId(provider, primaryModelId)
        val pool = when (provider) {
            AiProvider.GEMINI -> listOf(
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-2.5-pro",
                "gemini-2.0-flash-lite",
                "gemini-3-flash-preview",
            )
            AiProvider.OPENAI -> listOf(
                "gpt-5-mini",
                "gpt-4.1",
                "gpt-4o",
                "gpt-4o-mini",
            )
            AiProvider.CLAUDE -> listOf(
                "claude-sonnet-4-5",
                "claude-3-7-sonnet-latest",
                "claude-haiku-4-5",
                "claude-3-5-sonnet-latest",
            )
        }
        val ordered = linkedSetOf<String>()
        ordered.add(cleanPrimary)
        pool.forEach { ordered.add(it) }
        return ordered.toList()
    }

    suspend fun fetchLiveModels(
        provider: AiProvider,
        apiKey: String,
    ): Result<List<AiModelOption>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Enter your ${provider.shortName} API key first."))
        }
        runCatching {
            when (provider) {
                AiProvider.GEMINI -> fetchGeminiModels(cleanKey)
                AiProvider.OPENAI -> fetchOpenAiModels(cleanKey)
                AiProvider.CLAUDE -> fetchClaudeModels(cleanKey)
            }
        }
    }

    private fun fetchGeminiModels(apiKey: String): List<AiModelOption> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${GeminiCodeAssistant.extractApiErrorMessage(body).ifBlank { conn.responseMessage }}")
            }
            val modelsArr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val results = mutableListOf<AiModelOption>()
            for (i in 0 until modelsArr.length()) {
                val item = modelsArr.optJSONObject(i) ?: continue
                val rawName = item.optString("name").removePrefix("models/")
                val methods = item.optJSONArray("supportedGenerationMethods")
                val supportsGenerate = buildList {
                    if (methods != null) {
                        for (j in 0 until methods.length()) add(methods.optString(j))
                    }
                }.any { it == "generateContent" }
                if (!supportsGenerate || rawName.isBlank()) continue
                val inputLimit = item.optInt("inputTokenLimit", 0)
                val ctxLabel = if (inputLimit >= 1_000_000) "${inputLimit / 1_000_000}M ctx"
                else if (inputLimit >= 1000) "${inputLimit / 1000}K ctx" else ""
                results.add(
                    AiModelOption(
                        id = rawName,
                        provider = AiProvider.GEMINI,
                        label = item.optString("displayName").ifBlank { rawName },
                        badge = if (rawName.contains("gemini-3")) "Gemini 3" else "Live",
                        description = item.optString("description").ifBlank { "Fetched live from Google AI Studio." }.take(120),
                        contextWindow = ctxLabel,
                        isLiveFetched = true,
                    ),
                )
            }
            return results.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchOpenAiModels(apiKey: String): List<AiModelOption> {
        val url = URL("https://api.openai.com/v1/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${GeminiCodeAssistant.extractApiErrorMessage(body).ifBlank { conn.responseMessage }}")
            }
            val dataArr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val results = mutableListOf<AiModelOption>()
            for (i in 0 until dataArr.length()) {
                val id = dataArr.optJSONObject(i)?.optString("id").orEmpty()
                val lower = id.lowercase()
                val isChatCapable = (lower.startsWith("gpt-") ||
                    lower.startsWith("o1") ||
                    lower.startsWith("o3") ||
                    lower.startsWith("o4") ||
                    lower.startsWith("chatgpt-") ||
                    lower.startsWith("codex-")) &&
                    !lower.contains("audio") &&
                    !lower.contains("realtime") &&
                    !lower.contains("transcribe") &&
                    !lower.contains("tts") &&
                    !lower.contains("image") &&
                    !lower.contains("instruct")
                if (isChatCapable) {
                    results.add(
                        AiModelOption(
                            id = id,
                            provider = AiProvider.OPENAI,
                            label = id,
                            badge = "Live",
                            description = "Available on your OpenAI API account.",
                            isLiveFetched = true,
                        ),
                    )
                }
            }
            return results.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchClaudeModels(apiKey: String): List<AiModelOption> {
        val url = URL("https://api.anthropic.com/v1/models?limit=100")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${GeminiCodeAssistant.extractApiErrorMessage(body).ifBlank { conn.responseMessage }}")
            }
            val dataArr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val results = mutableListOf<AiModelOption>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val id = item.optString("id").orEmpty()
                if (id.isBlank()) continue
                results.add(
                    AiModelOption(
                        id = id,
                        provider = AiProvider.CLAUDE,
                        label = item.optString("display_name").ifBlank { id },
                        badge = "Live",
                        description = "Available on your Anthropic API account.",
                        contextWindow = "200K ctx",
                        isLiveFetched = true,
                    ),
                )
            }
            return results.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }
}
