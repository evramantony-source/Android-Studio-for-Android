package com.example

import com.example.ai.ChatMessage
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiCodeAssistant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiCodeAssistantTest {

    @Test
    fun usesGemini4HydrogenModel() {
        assertEquals("gemini-4-hydrogen", GEMINI_MODEL_ID)
    }

    @Test
    fun parseSsePayload_skipsThoughtPartsAndExtractsText() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {"text": "internal thinking...", "thought": true},
                      {"text": "Here is the updated code:\n"}
                    ]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val parsed = GeminiCodeAssistant.parseSsePayload(json)
        assertNull(parsed.errorMessage)
        assertEquals("STOP", parsed.finishReason)
        assertEquals("Here is the updated code:\n", parsed.text)
    }

    @Test
    fun parseSsePayload_preservesFunctionCallAndThoughtSignature() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "functionCall": {
                          "name": "read_file",
                          "args": {"path": "AndroidManifest.xml"}
                        },
                        "thoughtSignature": "sig-xyz-123"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = GeminiCodeAssistant.parseSsePayload(json)
        val assembled = mutableListOf<JSONObject>()
        parsed.rawParts.forEach { GeminiCodeAssistant.appendRawPart(assembled, it) }

        assertEquals(1, assembled.size)
        assertEquals("sig-xyz-123", assembled[0].getString("thoughtSignature"))
        assertEquals("read_file", assembled[0].getJSONObject("functionCall").getString("name"))
    }

    @Test
    fun extractCodeBlocks_parsesPathHintAndStripsHeader() {
        val markdown = """
            Updated MainActivity:
            ```java
            // file: src/com/example/counter/MainActivity.java
            package com.example.counter;
            public class MainActivity {}
            ```
        """.trimIndent()

        val blocks = GeminiCodeAssistant.extractCodeBlocks(markdown)
        assertEquals(1, blocks.size)
        assertEquals("java", blocks[0].language)
        assertEquals("src/com/example/counter/MainActivity.java", blocks[0].suggestedPath)
        assertEquals("package com.example.counter;\npublic class MainActivity {}", blocks[0].code)
    }

    @Test
    fun buildRequestPayload_includesHistoryToolsAndLatestPrompt() {
        val assistant = GeminiCodeAssistant()
        val contents = assistant.buildInitialContents(
            history = listOf(
                ChatMessage(id = "u1", isUser = true, text = "Hello"),
                ChatMessage(id = "a1", isUser = false, text = "Hi there"),
            ),
            latestPromptWithContext = "Add a reset button",
        )
        val payload = assistant.buildRequestPayload(contents)
        assertTrue(payload.contains("\"role\":\"user\""))
        assertTrue(payload.contains("\"role\":\"model\""))
        assertTrue(payload.contains("Add a reset button"))
        assertTrue(payload.contains("\"read_file\""))
        assertTrue(payload.contains("\"write_file\""))
        assertTrue(payload.contains("\"edit_file\""))
        assertTrue(payload.contains("\"build_apk\""))
        assertTrue(payload.contains("\"build_aab\""))
    }

    @Test
    fun isTransientHttpStatus_retriesOnly5xxServerErrors() {
        assertTrue(GeminiCodeAssistant.isTransientHttpStatus(503))
        assertTrue(GeminiCodeAssistant.isTransientHttpStatus(500))
        assertEquals(false, GeminiCodeAssistant.isTransientHttpStatus(400))
        assertEquals(false, GeminiCodeAssistant.isTransientHttpStatus(404))
    }

    @Test
    fun parseSsePayload_extractsEmbeddedErrorCode() {
        val json = """
            {"error": {"code": 503, "message": "high demand"}}
        """.trimIndent()
        val parsed = GeminiCodeAssistant.parseSsePayload(json)
        assertEquals(503, parsed.errorCode)
        assertEquals("high demand", parsed.errorMessage)
    }

    @Test
    fun catalogIncludesAllProvidersGemini3ClaudeAndOpenAiModels() {
        val gemini = com.example.ai.AiModelsCatalog.modelsFor(com.example.ai.AiProvider.GEMINI)
        val openai = com.example.ai.AiModelsCatalog.modelsFor(com.example.ai.AiProvider.OPENAI)
        val claude = com.example.ai.AiModelsCatalog.modelsFor(com.example.ai.AiProvider.CLAUDE)
        assertTrue(gemini.any { it.id == "gemini-4-hydrogen" })
        assertTrue(gemini.any { it.id == "gemini-3.8-flash" })
        assertTrue(gemini.any { it.id == "gemini-3.1-pro-preview" })
        assertTrue(gemini.any { it.id == "gemini-3-pro-preview" })
        assertTrue(gemini.any { it.id == "gemini-3-flash-preview" })
        assertTrue(gemini.any { it.id == "gemini-2.5-pro" })
        assertTrue(openai.any { it.id == "gpt-6-astra" })
        assertTrue(openai.any { it.id == "gpt-5" })
        assertTrue(openai.any { it.id == "gpt-4.1" })
        assertTrue(openai.any { it.id == "o4-mini" })
        assertTrue(openai.any { it.id == "o3-pro" })
        assertTrue(claude.any { it.id == "claude-opus-5-5" })
        assertTrue(claude.any { it.id == "claude-sonnet-4-5" })
        assertTrue(claude.any { it.id == "claude-3-7-sonnet-latest" })
        assertTrue(claude.any { it.id == "claude-3-haiku-20240307" })
    }

    @Test
    fun aiUsageSummaryRoundTripsJsonWithCostAndQuota() {
        val estCost = com.example.ai.AiModelsCatalog.estimateCostUsd(
            modelId = "claude-sonnet-4-5",
            inputTokens = 10_000L,
            outputTokens = 2_000L,
        )
        assertTrue(estCost > 0.0)
        val summary = com.example.ai.AiUsageSummary(
            byProvider = mapOf(
                com.example.ai.AiProvider.GEMINI to com.example.ai.ProviderUsageStats(
                    provider = com.example.ai.AiProvider.GEMINI,
                    requests = 4,
                    failedRequests = 1,
                    toolCalls = 7,
                    inputTokens = 1200,
                    outputTokens = 450,
                    thinkingTokens = 300,
                    totalTokens = 1950,
                    estimatedCostUsd = estCost,
                    lastLatencyMs = 820,
                    lastModelId = "gemini-3.8-flash",
                    remainingQuotaNote = "Req left: 99 • Tok left: 190000",
                ),
            ),
            byModel = mapOf("gemini-3.8-flash" to 1950L),
            lastUpdatedMillis = 123456L,
        )
        val decoded = com.example.ai.AiUsageSummary.fromJson(summary.toJson())
        assertEquals(1950L, decoded.totalTokens)
        assertEquals(4L, decoded.totalRequests)
        assertEquals(7L, decoded.totalToolCalls)
        assertEquals(300L, decoded.totalThinkingTokens)
        assertEquals(estCost, decoded.totalEstimatedCostUsd, 0.00001)
        assertEquals("Req left: 99 • Tok left: 190000", decoded.byProvider[com.example.ai.AiProvider.GEMINI]?.remainingQuotaNote)
        assertEquals(1950L, decoded.byModel["gemini-3.8-flash"])
    }

    @Test
    fun projectChatStorePersistsMainAndSideThreadsWithStepDetails() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("chat_store_test").toFile()
        try {
            val mainThread = com.example.ai.ProjectChatStore.createMainThread().copy(
                messages = listOf(
                    ChatMessage(
                        id = "u1",
                        isUser = true,
                        text = "Build release AAB",
                    ),
                    ChatMessage(
                        id = "a1",
                        isUser = false,
                        text = "Built cleanly.",
                        providerName = "Gemini",
                        modelId = "gemini-4-hydrogen",
                        totalTokens = 640L,
                        steps = listOf(
                            com.example.ai.AgentStep(
                                id = "s1",
                                title = "Build AAB",
                                detail = "Release AAB built in 140ms",
                                status = com.example.ai.AgentStepStatus.COMPLETED,
                                expandedOutput = "BundleConfig.pb\nbase/dex/classes.dex",
                                durationMs = 140L,
                                toolName = "build_aab",
                            ),
                        ),
                    ),
                ),
            )
            val sideThread = com.example.ai.ProjectChatStore.createSideThread(1, "UI Polish").copy(
                messages = listOf(ChatMessage(id = "su1", isUser = true, text = "Check colors")),
            )

            com.example.ai.ProjectChatStore.saveThreads(dir, listOf(mainThread, sideThread))
            val restored = com.example.ai.ProjectChatStore.loadThreads(dir)
            assertEquals(2, restored.size)
            assertEquals("Main Agent", restored[0].title)
            assertEquals("UI Polish", restored[1].title)
            assertEquals(640L, restored[0].messages[1].totalTokens)
            assertEquals("BundleConfig.pb\nbase/dex/classes.dex", restored[0].messages[1].steps[0].expandedOutput)
            assertEquals(140L, restored[0].messages[1].steps[0].durationMs)
        } finally {
            dir.deleteRecursively()
        }
    }
}
