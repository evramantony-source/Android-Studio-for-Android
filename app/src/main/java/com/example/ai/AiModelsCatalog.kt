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
    OPENROUTER(
        displayName = "OpenRouter",
        shortName = "OpenRouter",
        defaultModelId = "openrouter/auto",
        keyHint = "sk-or-v1-...",
        usageConsoleUrl = "https://openrouter.ai/activity",
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
    val supportsTools: Boolean = true,
    val supportsSystemPrompt: Boolean = true,
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
        // Built-in Firebase AI Logic Models (No API Key Required)
        AiModelOption(
            id = "gemini-3.8-flash",
            provider = AiProvider.GEMINI,
            label = "Gemini 3.8 Flash",
            badge = "Built-in",
            description = "Default Firebase AI Logic model — fast autonomous coding with no API key required.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3.5-flash-lite",
            provider = AiProvider.GEMINI,
            label = "Gemini 3.5 Flash-Lite",
            badge = "Built-in Lite",
            description = "Ultra-fast Firebase AI Logic model for rapid edits & Q&A (no API key required).",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-4-argon",
            provider = AiProvider.GEMINI,
            label = "Gemini 4 Argon",
            badge = "Firebase Pro",
            description = "Deep reasoning & complex architecture via Firebase AI Logic (Blaze plan).",
            contextWindow = "1M ctx",
        ),
        // Gemini 2.5 Series (Custom Google AI Studio Key GA Models)
        AiModelOption(
            id = "gemini-2.5-flash",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.5 Flash",
            badge = "2.5 GA",
            description = "Fast, reliable GA Gemini model for autonomous coding, builds & tool use.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.5-flash-lite",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.5 Flash-Lite",
            badge = "2.5 Lite",
            description = "High-quota ultra-fast responses for quick edits and fewer rate limits.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.0-flash",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.0 Flash",
            badge = "2.0 GA",
            description = "Rock-solid general-purpose model with high API availability.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.0-flash-lite",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.0 Flash-Lite",
            badge = "2.0 Lite",
            description = "Lightweight GA 2.0 variant for quick syntax fixes and Q&A.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-flash-latest",
            provider = AiProvider.GEMINI,
            label = "Gemini Flash Latest",
            badge = "GA Alias",
            description = "Google's rolling stable Flash alias for your API key.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-2.5-pro",
            provider = AiProvider.GEMINI,
            label = "Gemini 2.5 Pro",
            badge = "2.5 Pro",
            description = "High-accuracy multi-file engineering and deep thinking (tighter free quota).",
            contextWindow = "1M ctx",
        ),
        // Gemini 3 Preview Series
        AiModelOption(
            id = "gemini-3-flash-preview",
            provider = AiProvider.GEMINI,
            label = "Gemini 3 Flash Preview",
            badge = "Preview",
            description = "Preview Gemini 3 Flash model (auto-falls back to 2.5 Flash if busy/unavailable).",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3-pro-preview",
            provider = AiProvider.GEMINI,
            label = "Gemini 3 Pro Preview",
            badge = "Preview",
            description = "Preview Gemini 3 Pro model with dynamic thinking levels.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3.1-pro-preview",
            provider = AiProvider.GEMINI,
            label = "Gemini 3.1 Pro Preview",
            badge = "Preview",
            description = "Preview Gemini 3.1 Pro for complex multi-file Android engineering.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "gemini-3.1-pro-preview-customtools",
            provider = AiProvider.GEMINI,
            label = "Gemini 3.1 Pro CustomTools",
            badge = "Preview",
            description = "Agent-tuned Gemini 3.1 Pro optimized for custom file & build tool loops.",
            contextWindow = "1M ctx",
        ),
    )

    val builtInOpenAiModels: List<AiModelOption> = listOf(
        // GPT-5 Series
        AiModelOption(
            id = "gpt-5.1",
            provider = AiProvider.OPENAI,
            label = "GPT-5.1",
            badge = "GPT-5.1",
            description = "OpenAI GPT-5.1 flagship coding & agentic model.",
            contextWindow = "400K ctx",
        ),
        AiModelOption(
            id = "gpt-5-codex",
            provider = AiProvider.OPENAI,
            label = "GPT-5 Codex",
            badge = "Codex",
            description = "Agentic software engineering model via OpenAI Responses API.",
            contextWindow = "400K ctx",
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
            description = "Dynamic alias pointing to the current ChatGPT 4o snapshot (text-mode tool support).",
            contextWindow = "128K ctx",
            supportsTools = false,
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
            badge = "Responses",
            description = "Extended-compute o3 reasoning via OpenAI Responses API.",
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
            description = "Compact first-generation o-series reasoning model (text-mode adapted).",
            contextWindow = "128K ctx",
            supportsTools = false,
            supportsSystemPrompt = false,
        ),
        AiModelOption(
            id = "codex-mini-latest",
            provider = AiProvider.OPENAI,
            label = "Codex Mini Latest",
            badge = "Codex",
            description = "Fast coding CLI model routed via OpenAI Responses API.",
            contextWindow = "200K ctx",
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
        // Claude 4.6 / 4.5 / 4.1 / 4.0 Generation (Verified Anthropic IDs)
        AiModelOption(
            id = "claude-sonnet-4-6",
            provider = AiProvider.CLAUDE,
            label = "Claude Sonnet 4.6",
            badge = "Claude 4.6",
            description = "Fast, flagship Claude Sonnet 4.6 coding agent.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-opus-4-6",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4.6",
            badge = "Claude 4.6",
            description = "Flagship Anthropic Claude Opus 4.6 for multi-file Android engineering.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-opus-4-5",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4.5",
            badge = "Claude 4.5",
            description = "High-intelligence Claude 4.5 Opus for multi-file refactoring.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-opus-4-5-20251101",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4.5 (2025-11-01)",
            badge = "Claude 4.5",
            description = "Pinned Claude Opus 4.5 snapshot.",
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
            id = "claude-sonnet-4-5-20250929",
            provider = AiProvider.CLAUDE,
            label = "Claude Sonnet 4.5 (2025-09-29)",
            badge = "Claude 4.5",
            description = "Pinned Claude Sonnet 4.5 snapshot.",
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
            id = "claude-haiku-4-5-20251001",
            provider = AiProvider.CLAUDE,
            label = "Claude Haiku 4.5 (2025-10-01)",
            badge = "Claude 4.5",
            description = "Pinned Claude Haiku 4.5 snapshot.",
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
            id = "claude-opus-4-1-20250805",
            provider = AiProvider.CLAUDE,
            label = "Claude Opus 4.1 (2025-08-05)",
            badge = "Claude 4.1",
            description = "Pinned Claude Opus 4.1 snapshot.",
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
            id = "claude-3-7-sonnet-20250219",
            provider = AiProvider.CLAUDE,
            label = "Claude 3.7 Sonnet (2025-02-19)",
            badge = "Claude 3.7",
            description = "Pinned Claude 3.7 Sonnet engineering model.",
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
            id = "claude-3-5-sonnet-20241022",
            provider = AiProvider.CLAUDE,
            label = "Claude 3.5 Sonnet (2024-10-22)",
            badge = "Claude 3.5",
            description = "Classic Claude 3.5 Sonnet snapshot.",
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
            id = "claude-3-5-haiku-20241022",
            provider = AiProvider.CLAUDE,
            label = "Claude 3.5 Haiku (2024-10-22)",
            badge = "Claude 3.5",
            description = "Pinned Claude 3.5 Haiku snapshot.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-opus-20240229",
            provider = AiProvider.CLAUDE,
            label = "Claude 3 Opus",
            badge = "Claude 3",
            description = "Legacy Claude 3 Opus flagship (4096 max_tokens adapted).",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-sonnet-20240229",
            provider = AiProvider.CLAUDE,
            label = "Claude 3 Sonnet",
            badge = "Claude 3",
            description = "Legacy balanced Claude 3 model (4096 max_tokens adapted).",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "claude-3-haiku-20240307",
            provider = AiProvider.CLAUDE,
            label = "Claude 3 Haiku",
            badge = "Claude 3",
            description = "Legacy ultra-fast Claude 3 Haiku (4096 max_tokens adapted).",
            contextWindow = "200K ctx",
        ),
    )

    val builtInOpenRouterModels: List<AiModelOption> = listOf(
        AiModelOption(
            id = "openrouter/auto",
            provider = AiProvider.OPENROUTER,
            label = "OpenRouter Auto",
            badge = "Auto",
            description = "Smart OpenRouter router that automatically picks the best available model.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "openrouter/free",
            provider = AiProvider.OPENROUTER,
            label = "OpenRouter Free Router",
            badge = "Free",
            description = "Official OpenRouter router that automatically selects a healthy free model ($0).",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "qwen/qwen3-coder:free",
            provider = AiProvider.OPENROUTER,
            label = "Qwen3 Coder (Free)",
            badge = "Free",
            description = "Free coding-specialized Qwen3 model on OpenRouter — no credits or Firebase required.",
            contextWindow = "262K ctx",
        ),
        AiModelOption(
            id = "deepseek/deepseek-r1-0528:free",
            provider = AiProvider.OPENROUTER,
            label = "DeepSeek R1 0528 (Free)",
            badge = "Free",
            description = "Free DeepSeek R1 reasoning model for debugging, architecture & build fixes.",
            contextWindow = "164K ctx",
        ),
        AiModelOption(
            id = "deepseek/deepseek-chat-v3-0324:free",
            provider = AiProvider.OPENROUTER,
            label = "DeepSeek V3 (Free)",
            badge = "Free",
            description = "Free DeepSeek V3 model with strong Java, Kotlin & tool-calling support.",
            contextWindow = "164K ctx",
        ),
        AiModelOption(
            id = "google/gemma-3-27b-it:free",
            provider = AiProvider.OPENROUTER,
            label = "Gemma 3 27B (Free)",
            badge = "Free",
            description = "Free Google Gemma 3 27B instruct model on OpenRouter (auto-adapted for Gemma).",
            contextWindow = "131K ctx",
            supportsTools = false,
            supportsSystemPrompt = false,
        ),
        AiModelOption(
            id = "meta-llama/llama-3.3-70b-instruct:free",
            provider = AiProvider.OPENROUTER,
            label = "Llama 3.3 70B (Free)",
            badge = "Free",
            description = "Free open-weight Llama 3.3 70B instruct model on OpenRouter.",
            contextWindow = "131K ctx",
        ),
        AiModelOption(
            id = "mistralai/mistral-small-3.1-24b-instruct:free",
            provider = AiProvider.OPENROUTER,
            label = "Mistral Small 3.1 (Free)",
            badge = "Free",
            description = "Free Mistral Small 3.1 24B model on OpenRouter for fast Android edits.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "nvidia/nemotron-nano-9b-v2:free",
            provider = AiProvider.OPENROUTER,
            label = "Nemotron Nano 9B v2 (Free)",
            badge = "Free",
            description = "Fast free NVIDIA Nemotron Nano 9B v2 model on OpenRouter.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "qwen/qwen3-235b-a22b:free",
            provider = AiProvider.OPENROUTER,
            label = "Qwen3 235B (Free)",
            badge = "Free",
            description = "Free large Qwen3 235B MoE model on OpenRouter.",
            contextWindow = "131K ctx",
        ),
        AiModelOption(
            id = "google/gemini-2.5-flash",
            provider = AiProvider.OPENROUTER,
            label = "Gemini 2.5 Flash (OR)",
            badge = "Gemini",
            description = "Google Gemini 2.5 Flash routed via OpenRouter (no Firebase App Check needed).",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "google/gemini-2.5-pro",
            provider = AiProvider.OPENROUTER,
            label = "Gemini 2.5 Pro (OR)",
            badge = "Gemini Pro",
            description = "Google Gemini 2.5 Pro routed via OpenRouter for multi-file engineering.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-sonnet-4.6",
            provider = AiProvider.OPENROUTER,
            label = "Claude Sonnet 4.6 (OR)",
            badge = "Claude",
            description = "Anthropic Claude Sonnet 4.6 routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-opus-4.6",
            provider = AiProvider.OPENROUTER,
            label = "Claude Opus 4.6 (OR)",
            badge = "Claude",
            description = "Anthropic Claude Opus 4.6 routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-opus-4.5",
            provider = AiProvider.OPENROUTER,
            label = "Claude Opus 4.5 (OR)",
            badge = "Claude",
            description = "Anthropic Claude Opus 4.5 routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-sonnet-4.5",
            provider = AiProvider.OPENROUTER,
            label = "Claude Sonnet 4.5 (OR)",
            badge = "Claude",
            description = "Anthropic Claude Sonnet 4.5 routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-sonnet-4",
            provider = AiProvider.OPENROUTER,
            label = "Claude Sonnet 4 (OR)",
            badge = "Claude",
            description = "Anthropic Claude Sonnet 4 routed via OpenRouter with accurate tool calling.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-haiku-4.5",
            provider = AiProvider.OPENROUTER,
            label = "Claude Haiku 4.5 (OR)",
            badge = "Claude",
            description = "Anthropic Claude Haiku 4.5 routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-3.7-sonnet",
            provider = AiProvider.OPENROUTER,
            label = "Claude 3.7 Sonnet (OR)",
            badge = "Claude",
            description = "Anthropic Claude 3.7 Sonnet routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "anthropic/claude-3.5-sonnet",
            provider = AiProvider.OPENROUTER,
            label = "Claude 3.5 Sonnet (OR)",
            badge = "Claude",
            description = "Anthropic Claude 3.5 Sonnet routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "openai/gpt-5",
            provider = AiProvider.OPENROUTER,
            label = "GPT-5 (OR)",
            badge = "GPT-5",
            description = "OpenAI GPT-5 routed via OpenRouter.",
            contextWindow = "400K ctx",
        ),
        AiModelOption(
            id = "openai/gpt-5-mini",
            provider = AiProvider.OPENROUTER,
            label = "GPT-5 Mini (OR)",
            badge = "GPT-5",
            description = "OpenAI GPT-5 Mini routed via OpenRouter.",
            contextWindow = "400K ctx",
        ),
        AiModelOption(
            id = "openai/gpt-4.1",
            provider = AiProvider.OPENROUTER,
            label = "GPT-4.1 (OR)",
            badge = "GPT-4.1",
            description = "OpenAI GPT-4.1 routed via OpenRouter with 1M context.",
            contextWindow = "1M ctx",
        ),
        AiModelOption(
            id = "openai/gpt-4o",
            provider = AiProvider.OPENROUTER,
            label = "GPT-4o (OR)",
            badge = "4o",
            description = "OpenAI GPT-4o routed via OpenRouter.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "openai/gpt-4o-mini",
            provider = AiProvider.OPENROUTER,
            label = "GPT-4o Mini (OR)",
            badge = "4o",
            description = "OpenAI GPT-4o Mini routed via OpenRouter.",
            contextWindow = "128K ctx",
        ),
        AiModelOption(
            id = "openai/o4-mini",
            provider = AiProvider.OPENROUTER,
            label = "o4-mini (OR)",
            badge = "o-Series",
            description = "OpenAI o4-mini reasoning model routed via OpenRouter.",
            contextWindow = "200K ctx",
        ),
        AiModelOption(
            id = "qwen/qwen3-coder",
            provider = AiProvider.OPENROUTER,
            label = "Qwen3 Coder",
            badge = "Coder",
            description = "High-throughput paid Qwen3 Coder model on OpenRouter.",
            contextWindow = "262K ctx",
        ),
    )

    fun isFreeOpenRouterModel(modelId: String): Boolean {
        val id = modelId.trim().lowercase()
        return id == "openrouter/free" || id.endsWith(":free") || id.contains(":free")
    }

    fun estimateCostUsd(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        thinkingTokens: Long = 0L,
    ): Double {
        val id = modelId.lowercase()
        if (isFreeOpenRouterModel(id)) {
            return 0.0
        }
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
            id.contains("deepseek") || id.contains("qwen") || id.contains("llama") -> 0.30 to 1.00
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
            AiProvider.OPENROUTER -> builtInOpenRouterModels
        }
        val liveForProvider = liveDiscovered.filter { it.provider == provider }
        if ((provider == AiProvider.OPENROUTER || provider == AiProvider.OPENAI || provider == AiProvider.CLAUDE) && liveForProvider.isNotEmpty()) {
            val builtInById = base.associateBy { it.id.lowercase() }
            val mergedLive = linkedMapOf<String, AiModelOption>()
            liveForProvider.forEach { live ->
                val builtInMeta = builtInById[live.id.lowercase()]
                val refined = if (builtInMeta != null) {
                    live.copy(
                        label = builtInMeta.label.ifBlank { live.label },
                        badge = builtInMeta.badge.ifBlank { live.badge },
                        description = builtInMeta.description.ifBlank { live.description },
                        contextWindow = builtInMeta.contextWindow.ifBlank { live.contextWindow },
                        supportsTools = if (provider == AiProvider.OPENROUTER) live.supportsTools && builtInMeta.supportsTools else builtInMeta.supportsTools,
                        supportsSystemPrompt = live.supportsSystemPrompt && builtInMeta.supportsSystemPrompt,
                    )
                } else {
                    live
                }
                mergedLive[live.id] = refined
            }
            return mergedLive.values.toList()
        }
        val merged = linkedMapOf<String, AiModelOption>()
        base.forEach { merged[it.id] = it }
        liveForProvider.forEach { model ->
            merged.putIfAbsent(model.id, model)
        }
        return merged.values.toList()
    }

    fun normalizeModelId(provider: AiProvider, rawModelId: String?): String {
        val cleaned = rawModelId.orEmpty().trim().removePrefix("models/")
        if (cleaned.isEmpty()) return provider.defaultModelId
        val lower = cleaned.lowercase()
        return when (provider) {
            AiProvider.OPENAI -> when (lower) {
                "gpt-6-astra" -> "gpt-5"
                "gpt-6.1-sol", "gpt-6.1-terra" -> "gpt-5.1"
                else -> cleaned
            }
            AiProvider.CLAUDE -> when (lower) {
                "claude-opus-5-5" -> "claude-opus-4-6"
                "claude-sonnet-5-5" -> "claude-sonnet-4-6"
                "claude-haiku-5-5" -> "claude-haiku-4-5"
                "claude-fable-5-2" -> "claude-sonnet-4-5"
                else -> cleaned
            }
            else -> cleaned
        }
    }

    fun toOpenRouterSlug(
        provider: AiProvider,
        rawModelId: String,
        liveOpenRouterModels: List<AiModelOption> = emptyList(),
    ): String {
        val normalized = normalizeModelId(provider, rawModelId)
        if (normalized.contains('/')) return normalized
        val liveOr = liveOpenRouterModels.filter { it.provider == AiProvider.OPENROUTER }
        return when (provider) {
            AiProvider.OPENAI -> {
                val candidate = "openai/$normalized"
                liveOr.firstOrNull { it.id.equals(candidate, ignoreCase = true) }?.id
                    ?: liveOr.firstOrNull {
                        val id = it.id.lowercase()
                        id.startsWith("openai/") && id.removePrefix("openai/").startsWith(normalized.lowercase())
                    }?.id
                    ?: candidate
            }
            AiProvider.CLAUDE -> {
                val stripped = normalized
                    .lowercase()
                    .removeSuffix("-latest")
                    .replace(Regex("-20\\d{6}$"), "")
                val dotSlug = stripped
                    .replace(Regex("-(\\d+)-(\\d+)(-|$)")) { m -> "-${m.groupValues[1]}.${m.groupValues[2]}${m.groupValues[3]}" }
                val candidates = listOf(
                    "anthropic/$dotSlug",
                    "anthropic/$stripped",
                    "anthropic/$normalized",
                ).distinct()
                candidates.firstNotNullOfOrNull { cand ->
                    liveOr.firstOrNull { it.id.equals(cand, ignoreCase = true) }?.id
                } ?: liveOr.firstOrNull {
                    val id = it.id.lowercase()
                    id.startsWith("anthropic/") && (id.contains(dotSlug) || id.contains(stripped))
                }?.id ?: candidates.first()
            }
            AiProvider.GEMINI -> {
                val candidate = "google/$normalized"
                liveOr.firstOrNull { it.id.equals(candidate, ignoreCase = true) }?.id ?: candidate
            }
            AiProvider.OPENROUTER -> normalized
        }
    }

    fun isOpenRouterMetaRouter(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        return lower == "openrouter/free" || lower == "openrouter/auto"
    }

    internal fun openRouterCodingRank(option: AiModelOption): Int {
        val id = option.id.lowercase()
        var score = 100
        if (option.supportsTools) score -= 45
        if (!option.supportsSystemPrompt) score += 25
        if (isOpenRouterMetaRouter(id)) score += 60
        if (id.contains("coder") || id.contains("codestral") || id.contains("devstral")) score -= 25
        if (id.contains("qwen") || id.contains("deepseek") || id.contains("claude") || id.contains("sonnet") || id.contains("gpt-5") || id.contains("gpt-4.1") || id.contains("gemini-2.5")) score -= 18
        if (id.contains("r1") || id.contains("reasoning") || id.contains("nemotron") || id.contains("llama-3.3") || id.contains("mistral-small") || id.contains("glm") || id.contains("kimi")) score -= 10
        if (id.contains("nano") || id.contains("1b") || id.contains("3b") || id.contains("4b") || id.contains("7b") || id.contains("8b") || id.contains("9b")) score += 20
        if (id.contains("gemma")) score += 25
        return score
    }

    fun resolveOpenRouterModelId(
        requestedModelId: String,
        liveModels: List<AiModelOption>,
    ): String {
        val clean = normalizeModelId(AiProvider.OPENROUTER, requestedModelId)
        val liveOr = liveModels.filter { it.provider == AiProvider.OPENROUTER }
        if (liveOr.size < 2) return clean

        liveOr.firstOrNull { it.id.equals(clean, ignoreCase = true) }?.let { return it.id }
        if (isOpenRouterMetaRouter(clean)) return clean

        val wantsFree = isFreeOpenRouterModel(clean)
        val candidates = liveOr.filter { option ->
            !isOpenRouterMetaRouter(option.id) && (!wantsFree || isFreeOpenRouterModel(option.id))
        }
        if (candidates.isEmpty()) {
            return if (wantsFree) {
                liveOr.firstOrNull { it.id.equals("openrouter/free", ignoreCase = true) }?.id ?: clean
            } else {
                liveOr.firstOrNull { it.id.equals("openrouter/auto", ignoreCase = true) }?.id ?: clean
            }
        }

        val cleanLower = clean.lowercase().removeSuffix(":free")
        val requestedVendor = cleanLower.substringBefore('/', "")
        val requestedTokens = cleanLower.substringAfter('/').split('-', '_', '.', ':')
            .filter { it.length >= 2 }
            .toSet()

        return candidates.minWithOrNull(
            compareBy<AiModelOption>(
                { option ->
                    val optVendor = option.id.lowercase().substringBefore('/', "")
                    if (requestedVendor.isNotBlank() && optVendor == requestedVendor) 0 else 1
                },
                { option ->
                    val optTokens = option.id.lowercase().removeSuffix(":free")
                        .substringAfter('/').split('-', '_', '.', ':').toSet()
                    -requestedTokens.intersect(optTokens).size
                },
                { !it.supportsTools },
                { openRouterCodingRank(it) },
                { it.id },
            ),
        )?.id ?: clean
    }

    fun resolveOpenAiModelId(
        requestedModelId: String,
        liveModels: List<AiModelOption>,
    ): String {
        val clean = normalizeModelId(AiProvider.OPENAI, requestedModelId)
        val liveOpenAi = liveModels.filter { it.provider == AiProvider.OPENAI }
        if (liveOpenAi.isEmpty()) return clean
        liveOpenAi.firstOrNull { it.id.equals(clean, ignoreCase = true) }?.let { return it.id }

        val cleanTokens = clean.lowercase().split('-', '_', '.').filter { it.length >= 2 }.toSet()
        return liveOpenAi.minWithOrNull(
            compareBy<AiModelOption>(
                { opt ->
                    val optTokens = opt.id.lowercase().split('-', '_', '.').toSet()
                    -cleanTokens.intersect(optTokens).size
                },
                { opt ->
                    val id = opt.id.lowercase()
                    when {
                        id.startsWith("gpt-5") -> 0
                        id.startsWith("gpt-4.1") -> 1
                        id.startsWith("gpt-4o") -> 2
                        id.startsWith("o4") || id.startsWith("o3") -> 3
                        else -> 4
                    }
                },
                { it.id.length },
                { it.id },
            ),
        )?.id ?: clean
    }

    fun resolveClaudeModelId(
        requestedModelId: String,
        liveModels: List<AiModelOption>,
    ): String {
        val clean = normalizeModelId(AiProvider.CLAUDE, requestedModelId)
        val liveClaude = liveModels.filter { it.provider == AiProvider.CLAUDE }
        if (liveClaude.isEmpty()) return clean
        liveClaude.firstOrNull { it.id.equals(clean, ignoreCase = true) }?.let { return it.id }

        val cleanLower = clean.lowercase().removeSuffix("-latest")
        liveClaude.firstOrNull {
            val id = it.id.lowercase()
            id.startsWith(cleanLower) || cleanLower.startsWith(id.removeSuffix("-latest"))
        }?.let { return it.id }

        val cleanTokens = cleanLower.split('-', '.').filter { it.length >= 2 }.toSet()
        return liveClaude.minWithOrNull(
            compareBy<AiModelOption>(
                { opt ->
                    val optTokens = opt.id.lowercase().split('-', '.').toSet()
                    -cleanTokens.intersect(optTokens).size
                },
                { opt ->
                    val id = opt.id.lowercase()
                    when {
                        id.contains("sonnet-4") -> 0
                        id.contains("opus-4") -> 1
                        id.contains("haiku-4") -> 2
                        id.contains("3-7-sonnet") || id.contains("3-5-sonnet") -> 3
                        else -> 4
                    }
                },
                { it.id },
            ),
        )?.id ?: clean
    }

    fun isCodingCapableGeminiModel(rawModelId: String): Boolean {
        val id = rawModelId.lowercase().trim().removePrefix("models/")
        if (id.isBlank()) return false
        val excludedTokens = listOf(
            "embedding",
            "aqa",
            "imagen",
            "veo",
            "tts",
            "robotics",
            "-image",
            "image-",
            "image_generation",
            "audio",
            "native-audio",
            "live",
            "dialog",
            "computer-use",
            "deep-research",
            "gemma",
            "banana",
            "learnlm",
            "-thinking",
            "1.0-",
            "1.5-",
            "-vision",
            "lyria",
        )
        return excludedTokens.none { id.contains(it) }
    }

    fun fallbackChain(
        provider: AiProvider,
        primaryModelId: String,
        liveModels: List<AiModelOption> = emptyList(),
    ): List<String> {
        val cleanPrimary = normalizeModelId(provider, primaryModelId)
        val pool = when (provider) {
            AiProvider.GEMINI -> listOf(
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-flash-latest",
                "gemini-2.0-flash-lite",
                "gemini-2.5-pro",
            )
            AiProvider.OPENAI -> {
                val liveIds = liveModels.filter { it.provider == AiProvider.OPENAI }.map { it.id }
                val prioritizedLive = liveIds.sortedWith(
                    compareBy<String>(
                        { !it.equals("gpt-5", ignoreCase = true) && !it.startsWith("gpt-5-mini", ignoreCase = true) },
                        { !it.startsWith("gpt-4.1", ignoreCase = true) },
                        { !it.startsWith("gpt-4o", ignoreCase = true) },
                        { it },
                    ),
                ).take(6)
                prioritizedLive + listOf(
                    "gpt-5-mini",
                    "gpt-4.1",
                    "gpt-4o",
                    "gpt-4o-mini",
                )
            }
            AiProvider.CLAUDE -> {
                val liveIds = liveModels.filter { it.provider == AiProvider.CLAUDE }.map { it.id }
                val prioritizedLive = liveIds.sortedWith(
                    compareBy<String>(
                        { !it.contains("sonnet-4", ignoreCase = true) },
                        { !it.contains("haiku-4", ignoreCase = true) },
                        { !it.contains("opus-4", ignoreCase = true) },
                        { it },
                    ),
                ).take(6)
                prioritizedLive + listOf(
                    "claude-sonnet-4-6",
                    "claude-sonnet-4-5",
                    "claude-haiku-4-5",
                    "claude-sonnet-4-20250514",
                    "claude-3-7-sonnet-latest",
                    "claude-3-5-sonnet-latest",
                    "claude-3-haiku-20240307",
                )
            }
            AiProvider.OPENROUTER -> {
                val liveOr = liveModels.filter { it.provider == AiProvider.OPENROUTER }
                val liveFree = liveOr
                    .filter { isFreeOpenRouterModel(it.id) && !isOpenRouterMetaRouter(it.id) }
                    .sortedWith(
                        compareBy<AiModelOption>(
                            { !it.supportsTools },
                            { openRouterCodingRank(it) },
                            { it.id },
                        ),
                    )
                    .map { it.id }
                    .take(8)
                val livePaid = liveOr
                    .filter { !isFreeOpenRouterModel(it.id) && !isOpenRouterMetaRouter(it.id) }
                    .sortedWith(
                        compareBy<AiModelOption>(
                            { !it.supportsTools },
                            { openRouterCodingRank(it) },
                            { it.id },
                        ),
                    )
                    .map { it.id }
                    .take(6)

                if (isFreeOpenRouterModel(cleanPrimary)) {
                    // Try capable named free models first; keep openrouter/free as last-resort free fallback and NEVER enter paid models.
                    val fallbackFree = if (liveFree.isNotEmpty()) {
                        liveFree
                    } else {
                        builtInOpenRouterModels
                            .map { it.id }
                            .filter { isFreeOpenRouterModel(it) && !isOpenRouterMetaRouter(it) }
                    }
                    fallbackFree + listOf("openrouter/free")
                } else {
                    val primaryVendor = cleanPrimary.lowercase().substringBefore('/', "")
                    val vendorSamePaid = if (primaryVendor == "anthropic" || primaryVendor == "openai") {
                        livePaid.filter { it.lowercase().startsWith("$primaryVendor/") } +
                            when (primaryVendor) {
                                "anthropic" -> listOf(
                                    "anthropic/claude-sonnet-4.5",
                                    "anthropic/claude-sonnet-4",
                                    "anthropic/claude-haiku-4.5",
                                    "anthropic/claude-3.7-sonnet",
                                    "anthropic/claude-3.5-sonnet",
                                )
                                "openai" -> listOf(
                                    "openai/gpt-5-mini",
                                    "openai/gpt-4.1",
                                    "openai/gpt-4o",
                                    "openai/gpt-4o-mini",
                                )
                                else -> emptyList()
                            }
                    } else {
                        emptyList()
                    }
                    val paidPool = (vendorSamePaid + if (livePaid.isNotEmpty()) {
                        livePaid
                    } else {
                        listOf(
                            "google/gemini-2.5-flash",
                            "openai/gpt-4o-mini",
                            "anthropic/claude-3.7-sonnet",
                        )
                    }).distinct()
                    val freePool = if (liveFree.isNotEmpty()) {
                        liveFree.take(4)
                    } else {
                        builtInOpenRouterModels
                            .map { it.id }
                            .filter { isFreeOpenRouterModel(it) && !isOpenRouterMetaRouter(it) }
                            .take(4)
                    }
                    paidPool + listOf("openrouter/auto") + freePool + listOf("openrouter/free")
                }
            }
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
                AiProvider.OPENROUTER -> fetchOpenRouterModels(cleanKey)
            }
        }
    }

    suspend fun fetchOpenRouterPublicModels(apiKey: String = ""): Result<List<AiModelOption>> =
        withContext(Dispatchers.IO) {
            runCatching { fetchOpenRouterModelsList(apiKey.trim()) }
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
                if (!supportsGenerate || rawName.isBlank() || !isCodingCapableGeminiModel(rawName)) continue
                val inputLimit = item.optInt("inputTokenLimit", 0)
                val ctxLabel = if (inputLimit >= 1_000_000) "${inputLimit / 1_000_000}M ctx"
                else if (inputLimit >= 1000) "${inputLimit / 1000}K ctx" else ""
                results.add(
                    AiModelOption(
                        id = rawName,
                        provider = AiProvider.GEMINI,
                        label = item.optString("displayName").ifBlank { rawName },
                        badge = when {
                            rawName.contains("2.5-flash") -> "2.5 GA"
                            rawName.contains("2.0-flash") -> "2.0 GA"
                            rawName.contains("gemini-3") -> "Gemini 3"
                            else -> "Live"
                        },
                        description = item.optString("description").ifBlank { "Verified live on your Google AI Studio key." }.take(120),
                        contextWindow = ctxLabel,
                        isLiveFetched = true,
                    ),
                )
            }
            return results.sortedWith(
                compareBy<AiModelOption>(
                    { !it.id.contains("2.5-flash", ignoreCase = true) },
                    { !it.id.contains("2.0-flash", ignoreCase = true) },
                    { !it.id.contains("flash", ignoreCase = true) },
                    { it.id.contains("preview", ignoreCase = true) || it.id.contains("exp", ignoreCase = true) },
                ).thenBy { it.id },
            )
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

    private fun fetchOpenRouterModels(apiKey: String): List<AiModelOption> {
        // Verify the API key explicitly first because /api/v1/models is public on OpenRouter.
        val keyConn = (URL("https://openrouter.ai/api/v1/key").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/android-studio-for-android")
            setRequestProperty("X-Title", "Android Studio: For Android")
        }
        try {
            val keyCode = keyConn.responseCode
            val keyBody = (if (keyCode in 200..299) keyConn.inputStream else keyConn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (keyCode !in 200..299) {
                error("HTTP $keyCode: ${GeminiCodeAssistant.extractApiErrorMessage(keyBody).ifBlank { keyConn.responseMessage ?: "Invalid OpenRouter key" }}")
            }
        } finally {
            keyConn.disconnect()
        }

        return fetchOpenRouterModelsList(apiKey)
    }

    private fun fetchOpenRouterModelsList(apiKey: String): List<AiModelOption> {
        val conn = (URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            setRequestProperty("HTTP-Referer", "https://github.com/android-studio-for-android")
            setRequestProperty("X-Title", "Android Studio: For Android")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${GeminiCodeAssistant.extractApiErrorMessage(body).ifBlank { conn.responseMessage }}")
            }
            return parseOpenRouterModelsJson(body)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseOpenRouterModelsJson(rawJson: String): List<AiModelOption> {
        val dataArr = JSONObject(rawJson).optJSONArray("data") ?: JSONArray()
        val results = mutableListOf<AiModelOption>()
        for (i in 0 until dataArr.length()) {
            val item = dataArr.optJSONObject(i) ?: continue
            val id = item.optString("id").orEmpty().trim()
            if (id.isBlank()) continue
            val lower = id.lowercase()
            if (lower.contains("embed") || lower.contains("tts") || lower.contains("whisper") || lower.contains("moderation")) {
                continue
            }
            val pricing = item.optJSONObject("pricing")
            val promptNum = pricing?.optString("prompt", "")?.trim()?.toDoubleOrNull()
            val completionNum = pricing?.optString("completion", "")?.trim()?.toDoubleOrNull()
            val isZeroPriced = promptNum != null && completionNum != null && promptNum == 0.0 && completionNum == 0.0
            val isFree = isFreeOpenRouterModel(id) || (lower != "openrouter/auto" && isZeroPriced)
            val ctxLen = item.optInt("context_length", 0)
            val ctxLabel = when {
                ctxLen >= 1_000_000 -> "${ctxLen / 1_000_000}M ctx"
                ctxLen >= 1000 -> "${ctxLen / 1000}K ctx"
                else -> ""
            }
            val supportedParamsArr = item.optJSONArray("supported_parameters")
            val supportedParams = buildSet {
                if (supportedParamsArr != null) {
                    for (pIdx in 0 until supportedParamsArr.length()) {
                        add(supportedParamsArr.optString(pIdx).lowercase())
                    }
                }
            }
            val supportsTools = "tools" in supportedParams
            val supportsSystem = !lower.contains("gemma") && !lower.contains("olmo")
            val badge = when {
                lower == "openrouter/auto" -> "Auto"
                isFree -> "Free"
                lower.startsWith("google/") -> "Gemini"
                lower.startsWith("anthropic/") -> "Claude"
                lower.startsWith("openai/") -> "GPT"
                lower.contains("coder") -> "Coder"
                else -> "Live"
            }
            val tempOption = AiModelOption(
                id = id,
                provider = AiProvider.OPENROUTER,
                label = item.optString("name").ifBlank { id },
                badge = badge,
                description = item.optString("description").ifBlank { "Available via OpenRouter API." }.take(120),
                contextWindow = ctxLabel,
                isLiveFetched = true,
                supportsTools = supportsTools,
                supportsSystemPrompt = supportsSystem,
            )
            results.add(tempOption)
        }
        return results.sortedWith(
            compareBy<AiModelOption>(
                { it.id != "openrouter/auto" && it.id != "openrouter/free" },
                { !(isFreeOpenRouterModel(it.id) || it.badge == "Free") },
                { !it.supportsTools },
                { openRouterCodingRank(it) },
                { it.id },
            ),
        )
    }
}
