package com.example

import com.example.ai.AgentStep
import com.example.ai.AgentStepStatus
import com.example.ai.AiModelsCatalog
import com.example.ai.AiProvider
import com.example.ai.AiThinkingMode
import com.example.ai.AiUiState
import com.example.ai.ChatMessage
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiCodeAssistant
import com.example.model.FileLanguage
import com.example.model.ProjectFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun usesFastReliableGeminiDefaultModel() {
        assertEquals("gemini-3.8-flash", GEMINI_MODEL_ID)
    }

    @Test
    fun normalizeModelId_preservesUserSelectedModelsAndDefaultsBlank() {
        assertEquals("gemini-4-hydrogen", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-4-hydrogen"))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-3.8-flash"))
        assertEquals("gemini-3.6-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "models/gemini-3.6-flash"))
        assertEquals("gemini-3.5-flash-lite", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-3.5-flash-lite"))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, ""))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "   "))
    }

    @Test
    fun normalizeModelId_remapsRetiredGeminiModelsToLiveReplacements() {
        // These IDs are shut down or restricted to pre-existing users; calling them 404s.
        assertEquals("gemini-3.5-flash-lite", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-2.5-flash"))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-2.5-pro"))
        assertEquals("gemini-3.6-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-2.0-flash"))
        assertEquals("gemini-3.1-flash-lite", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-2.0-flash-lite"))
        assertEquals("gemini-3.1-pro-preview", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-3-pro-preview"))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "models/gemini-flash-latest"))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "GEMINI-1.5-PRO"))
    }

    @Test
    fun geminiFailoverPoolContainsNoRetiredModels() {
        assertTrue(AiModelsCatalog.geminiFailoverPool.isNotEmpty())
        AiModelsCatalog.geminiFailoverPool.forEach { id ->
            assertFalse(
                "Failover pool must not contain retired model $id",
                AiModelsCatalog.retiredGeminiModelRemap.containsKey(id.lowercase()),
            )
            assertTrue(
                "Failover pool entry $id must be usable for coding",
                AiModelsCatalog.isCodingCapableGeminiModel(id),
            )
        }
        // Every remap target must itself be a live model, or healing just moves the 404.
        AiModelsCatalog.retiredGeminiModelRemap.values.forEach { target ->
            assertFalse(
                "Remap target $target is itself retired",
                AiModelsCatalog.retiredGeminiModelRemap.containsKey(target.lowercase()),
            )
        }
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

        // Verify parameterless tools omit empty "parameters" object
        val rootJson = JSONObject(payload)
        val decls = rootJson.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        var listFilesDecl: JSONObject? = null
        for (i in 0 until decls.length()) {
            val d = decls.getJSONObject(i)
            if (d.getString("name") == "list_files") listFilesDecl = d
        }
        assertTrue("Expected list_files declaration", listFilesDecl != null)
        assertFalse("Parameterless tool list_files should omit parameters", listFilesDecl!!.has("parameters"))
    }

    @Test
    fun isTransientHttpStatus_andFailoverRulesCover503And404() {
        assertTrue(GeminiCodeAssistant.isTransientHttpStatus(503))
        assertTrue(GeminiCodeAssistant.isTransientHttpStatus(529))
        assertTrue(GeminiCodeAssistant.isTransientHttpStatus(429))
        assertTrue(GeminiCodeAssistant.isTransientHttpStatus(500))
        assertFalse(GeminiCodeAssistant.isTransientHttpStatus(400))
        assertFalse(GeminiCodeAssistant.isTransientHttpStatus(404))

        assertTrue(GeminiCodeAssistant.shouldFailoverHttpStatus(503))
        assertTrue(GeminiCodeAssistant.shouldFailoverHttpStatus(529))
        assertTrue(GeminiCodeAssistant.shouldFailoverHttpStatus(404))
        assertFalse(GeminiCodeAssistant.shouldFailoverHttpStatus(401))
    }

    @Test
    fun buildThinkingConfigJson_respectsModelGenerationAndSpeedMode() {
        val g3Fast = GeminiCodeAssistant.buildThinkingConfigJson("gemini-3.8-flash", AiThinkingMode.FAST)
        assertEquals("minimal", g3Fast?.getString("thinkingLevel"))

        val g3ProFast = GeminiCodeAssistant.buildThinkingConfigJson("gemini-3.1-pro-preview", AiThinkingMode.FAST)
        assertEquals("low", g3ProFast?.getString("thinkingLevel"))

        val g25Fast = GeminiCodeAssistant.buildThinkingConfigJson("gemini-2.5-flash", AiThinkingMode.FAST)
        assertEquals(0, g25Fast?.getInt("thinkingBudget"))

        val g25ProFast = GeminiCodeAssistant.buildThinkingConfigJson("gemini-2.5-pro", AiThinkingMode.FAST)
        assertEquals(256, g25ProFast?.getInt("thinkingBudget"))

        val g20 = GeminiCodeAssistant.buildThinkingConfigJson("gemini-2.0-flash", AiThinkingMode.FAST)
        assertNull(g20)

        val g4Custom = GeminiCodeAssistant.buildThinkingConfigJson("gemini-4-hydrogen", AiThinkingMode.FAST)
        assertNull(g4Custom)
    }

    @Test
    fun fallbackChain_startsWithSelectedModelAndIncludesReliableFallbacks() {
        val geminiChain = AiModelsCatalog.fallbackChain(AiProvider.GEMINI, "gemini-3.1-pro-preview")
        assertEquals("gemini-3.1-pro-preview", geminiChain.first())
        assertTrue(geminiChain.contains("gemini-3.8-flash"))
        assertTrue(geminiChain.contains("gemini-3.5-flash-lite"))
        assertEquals(geminiChain.distinct().size, geminiChain.size)
        // A chain containing a retired model is the 404 cascade we are guarding against.
        geminiChain.forEach { assertFalse(AiModelsCatalog.retiredGeminiModelRemap.containsKey(it.lowercase())) }

        val hydrogenChain = AiModelsCatalog.fallbackChain(AiProvider.GEMINI, "gemini-4-hydrogen")
        assertEquals("gemini-4-hydrogen", hydrogenChain.first())
        assertTrue(hydrogenChain.contains("gemini-3.8-flash"))
        assertTrue(hydrogenChain.contains("gemini-3.6-flash"))
        hydrogenChain.forEach { assertFalse(AiModelsCatalog.retiredGeminiModelRemap.containsKey(it.lowercase())) }

        // A retired primary is de-retired rather than tried and 404'd.
        val retiredChain = AiModelsCatalog.fallbackChain(AiProvider.GEMINI, "gemini-2.5-flash")
        assertEquals("gemini-3.5-flash-lite", retiredChain.first())
        retiredChain.forEach { assertFalse(AiModelsCatalog.retiredGeminiModelRemap.containsKey(it.lowercase())) }

        val openAiChain = AiModelsCatalog.fallbackChain(AiProvider.OPENAI, "gpt-5")
        assertEquals("gpt-5", openAiChain.first())
        assertTrue(openAiChain.contains("gpt-4o"))

        val claudeChain = AiModelsCatalog.fallbackChain(AiProvider.CLAUDE, "claude-opus-4-6")
        assertEquals("claude-opus-4-6", claudeChain.first())
        assertTrue(claudeChain.contains("claude-sonnet-4-5"))
        assertEquals("gpt-5", AiModelsCatalog.normalizeModelId(AiProvider.OPENAI, "gpt-6-astra"))
        assertEquals("claude-opus-4-6", AiModelsCatalog.normalizeModelId(AiProvider.CLAUDE, "claude-opus-5-5"))
    }

    @Test
    fun buildPrompt_preloadsActiveFileAndManifestForSingleTurnSpeed() {
        val assistant = GeminiCodeAssistant()
        val mainJava = ProjectFile(
            relativePath = "src/com/example/app/MainActivity.java",
            content = "public class MainActivity { int count = 0; }",
            language = FileLanguage.JAVA,
        )
        val manifest = ProjectFile(
            relativePath = "AndroidManifest.xml",
            content = "<manifest package=\"com.example.app\"/>",
            language = FileLanguage.XML,
        )
        val prompt = assistant.buildPrompt(
            userPrompt = "Increment count by 2",
            projectName = "Demo",
            packageName = "com.example.app",
            activeFile = mainJava,
            allFiles = listOf(mainJava, manifest),
            diagnostics = emptyList(),
        )
        assertTrue(prompt.contains("=== Pre-loaded Project Files ==="))
        assertTrue(prompt.contains("int count = 0;"))
        assertTrue(prompt.contains("<manifest package=\"com.example.app\"/>"))
    }

    @Test
    fun markCancelled_distinguishesUnexpectedInterruptionFromUserStop() {
        val assistant = GeminiCodeAssistant()
        assistant.setMessages(
            listOf(
                ChatMessage(
                    id = "a_1",
                    isUser = false,
                    text = "",
                    isStreaming = true,
                    steps = listOf(AgentStep(id = "t0", title = "Streaming", status = AgentStepStatus.RUNNING)),
                ),
            ),
        )
        assistant.markCancelled(targetMsgId = "a_1", userInitiated = false)
        val msg = assistant.messages.value.single()
        assertFalse(msg.isStreaming)
        assertEquals("Interrupted before completion", msg.steps.single().detail)
        assertFalse(msg.text.contains("Stopped by user"))
        assertTrue(assistant.uiState.value is AiUiState.Error)
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
        assertTrue(gemini.any { it.id == "gemini-4-carbon" })
        assertTrue(gemini.any { it.id == "gemini-3.8-flash" })
        assertTrue(gemini.any { it.id == "gemini-3.6-flash" })
        assertTrue(gemini.any { it.id == "gemini-3.5-flash-lite" })
        assertTrue(gemini.any { it.id == "gemini-3.1-flash-lite" })
        assertTrue(gemini.any { it.id == "gemini-3.1-pro-preview" })
        assertTrue(gemini.any { it.id == "gemini-3-flash-preview" })
        // Retired models must not be offered in the picker at all.
        gemini.forEach {
            assertFalse(
                "Catalog offers retired model ${it.id}",
                AiModelsCatalog.retiredGeminiModelRemap.containsKey(it.id.lowercase()),
            )
        }
        assertTrue(openai.any { it.id == "gpt-5" })
        assertTrue(openai.any { it.id == "gpt-4.1" })
        assertTrue(openai.any { it.id == "o4-mini" })
        assertTrue(openai.any { it.id == "o3-pro" })
        assertTrue(claude.any { it.id == "claude-opus-4-6" })
        assertTrue(claude.any { it.id == "claude-sonnet-4-6" })
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
                    lastModelId = "gemini-2.5-flash",
                    remainingQuotaNote = "Req left: 99 • Tok left: 190000",
                ),
            ),
            byModel = mapOf("gemini-2.5-flash" to 1950L),
            lastUpdatedMillis = 123456L,
        )
        val decoded = com.example.ai.AiUsageSummary.fromJson(summary.toJson())
        assertEquals(1950L, decoded.totalTokens)
        assertEquals(4L, decoded.totalRequests)
        assertEquals(7L, decoded.totalToolCalls)
        assertEquals(300L, decoded.totalThinkingTokens)
        assertEquals(estCost, decoded.totalEstimatedCostUsd, 0.00001)
        assertEquals("Req left: 99 • Tok left: 190000", decoded.byProvider[com.example.ai.AiProvider.GEMINI]?.remainingQuotaNote)
        assertEquals(1950L, decoded.byModel["gemini-2.5-flash"])
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

    @Test
    fun aiProviderToolSpecsIncludeAllFifteenGitHubTools() {
        val toolNames = com.example.ai.ProviderApiClient.buildSharedToolSpecs().map { it.first }.toSet()
        val expectedGitHubTools = listOf(
            "github_status",
            "github_list_repos",
            "github_create_repo",
            "github_link_repo",
            "github_list_branches",
            "github_list_repo_files",
            "github_read_file",
            "github_upload_file",
            "github_download_file",
            "github_pull_changes",
            "github_push_changes",
            "github_trigger_cloud_build",
            "github_list_workflow_runs",
            "github_get_workflow_logs",
            "github_rerun_workflow",
        )
        expectedGitHubTools.forEach { name ->
            assertTrue("Expected AI tool schema '$name'", toolNames.contains(name))
        }
    }

    @Test
    fun geminiModelFilterExcludesImageAudioTtsAndRoboticsModels() {
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3.1-flash-image-preview"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.5-flash-image"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.5-flash-preview-tts"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-robotics-er-1.5-preview"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemma-3-27b-it"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("text-embedding-004"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-1.5-pro"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-1.0-pro-vision"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("lyria-realtime-exp"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3.5-transcribe"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-omni-1.2-flash"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-decision-1"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("antigravity-preview-09-2026"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3.5-live-translate-preview"))
        assertFalse(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3.8-live"))

        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.5-flash"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.5-flash-lite"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.0-flash"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-flash-latest"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3-flash-preview"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3.8-flash"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3.5-flash-lite"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-4-hydrogen"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-4-carbon"))
    }

    @Test
    fun stripThoughtPartsFromContentsRemovesThoughtAndThoughtSignaturePreservingFunctionCalls() {
        val original = org.json.JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", org.json.JSONArray().put(JSONObject().put("text", "Build a game"))),
            )
            put(
                JSONObject()
                    .put("role", "model")
                    .put(
                        "parts",
                        org.json.JSONArray()
                            .put(JSONObject().put("text", "internal reasoning").put("thought", true))
                            .put(
                                JSONObject()
                                    .put("functionCall", JSONObject().put("name", "write_file").put("args", JSONObject()))
                                    .put("thoughtSignature", "sig-g3-123"),
                            ),
                    ),
            )
        }

        val cleaned = GeminiCodeAssistant.stripThoughtPartsFromContents(original)
        assertEquals(2, cleaned.length())
        val modelParts = cleaned.getJSONObject(1).getJSONArray("parts")
        assertEquals(1, modelParts.length())
        assertFalse(modelParts.getJSONObject(0).has("thoughtSignature"))
        assertEquals("write_file", modelParts.getJSONObject(0).getJSONObject("functionCall").getString("name"))
    }

    @Test
    fun buildRequestPayloadOmitsGitHubToolsWhenNotRelevant() {
        val assistant = GeminiCodeAssistant()
        val contents = org.json.JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", org.json.JSONArray().put(JSONObject().put("text", "Build a 3D game"))),
        )
        val compactJson = JSONObject(
            assistant.buildRequestPayload(
                contents = contents,
                modelId = "gemini-2.5-flash",
                includeGitHubTools = false,
            ),
        )
        val compactDecls = compactJson.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val compactNames = (0 until compactDecls.length()).map { compactDecls.getJSONObject(it).getString("name") }
        assertEquals(8, compactNames.size)
        assertTrue(compactNames.contains("write_file"))
        assertTrue(compactNames.contains("build_apk"))
        assertFalse(compactNames.any { it.startsWith("github_") })

        val fullJson = JSONObject(
            assistant.buildRequestPayload(
                contents = contents,
                modelId = "gemini-2.5-flash",
                includeGitHubTools = true,
            ),
        )
        val fullDecls = fullJson.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(23, fullDecls.length())
    }

    @Test
    fun firebaseAiCandidateChainPrioritizesBuiltInGemini38AndFlashLite() {
        assertTrue(com.example.ai.FirebaseAiAgentClient.isFirebaseAiLogicModel("gemini-3.8-flash"))
        assertTrue(com.example.ai.FirebaseAiAgentClient.isFirebaseAiLogicModel("gemini-3.5-flash-lite"))
        assertTrue(com.example.ai.FirebaseAiAgentClient.isFirebaseAiLogicModel("gemini-4-hydrogen"))
        assertFalse(com.example.ai.FirebaseAiAgentClient.isFirebaseAiLogicModel("gemini-2.5-flash"))

        val defaultChain = com.example.ai.FirebaseAiAgentClient.buildFirebaseModelCandidates("gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", defaultChain.first())
        assertTrue(defaultChain.contains("gemini-3.5-flash-lite"))
        assertEquals(defaultChain.distinct().size, defaultChain.size)

        val argonChain = com.example.ai.FirebaseAiAgentClient.buildFirebaseModelCandidates("gemini-4-argon")
        assertEquals("gemini-4-argon", argonChain.first())
        assertTrue(argonChain.contains("gemini-3.8-flash"))
        assertEquals(argonChain.distinct().size, argonChain.size)

        // A retired saved preference must resolve to a live model, never head the chain itself.
        // gemini-2.5-flash remaps to gemini-3.5-flash-lite, which is Firebase-served, so that
        // becomes the head of the chain instead of the retired ID.
        val retiredChain = com.example.ai.FirebaseAiAgentClient.buildFirebaseModelCandidates("gemini-2.5-flash")
        assertEquals("gemini-3.5-flash-lite", retiredChain.first())
        assertTrue(retiredChain.contains("gemini-3.8-flash"))

        // No Firebase candidate may be a model Google has retired.
        (defaultChain + argonChain + retiredChain).forEach {
            assertFalse(AiModelsCatalog.retiredGeminiModelRemap.containsKey(it.lowercase()))
        }
    }

    @Test
    fun appCheckDiagnosticsDetectsErrorsAndFormatsSummary() {
        assertTrue(com.example.ai.AppCheckDiagnostics.isAppCheckMessage("Firebase App Check token is invalid."))
        assertTrue(com.example.ai.AppCheckDiagnostics.isAppCheckMessage("Recaptcha task failed: NETWORK_ERROR"))
        assertTrue(com.example.ai.AppCheckDiagnostics.isAppCheckMessage("HTTP 403: App attestation failed"))
        assertFalse(com.example.ai.AppCheckDiagnostics.isAppCheckMessage("Model quota exceeded (429)"))

        val snap = com.example.ai.AppCheckDiagnosticSnapshot(
            mode = com.example.ai.AppCheckProviderMode.RECAPTCHA_ENTERPRISE,
            hasSiteKeyResource = false,
            isDebugBuild = false,
            projectId = "gen-lang-client-0552974152",
            certSha1 = "4D:95:5B:76:B0:68:1E:11:8D:E4:58:D7:86:2A:01:E5:D0:81:6D:F2",
        )
        assertFalse(snap.hasProvisionedCertMismatch())
        assertTrue(snap.copy(certSha1 = "D2:03:CB:F9:11:22").hasProvisionedCertMismatch())
        val label = snap.summaryLabel()
        assertTrue(label.contains("reCAPTCHA Enterprise"))
        assertTrue(label.contains("release"))
        assertTrue(label.contains("4D:95:5B:76"))
        assertTrue(label.contains("fallback site key"))

        val withAttestationErr = snap.withRecordedError("Error returned from API. code: 403 body: App attestation failed.")
        val withBackoff = withAttestationErr.withRecordedError("Too many attempts.")
        assertTrue(withBackoff.effectiveErrorMessage("Too many attempts.").contains("403 body: App attestation failed"))
    }

    @Test
    fun openRouterProviderIncludesFreeAndPaidModelsZeroCostFreeTierAndFallback() {
        assertEquals("openrouter/auto", AiProvider.OPENROUTER.defaultModelId)
        assertEquals("openrouter/auto", AiModelsCatalog.normalizeModelId(AiProvider.OPENROUTER, ""))

        val openRouterModels = AiModelsCatalog.modelsFor(AiProvider.OPENROUTER)
        assertTrue(openRouterModels.any { it.id == "openrouter/auto" })
        assertTrue(openRouterModels.any { it.id == "qwen/qwen3-coder:free" })
        assertTrue(openRouterModels.any { it.id == "deepseek/deepseek-chat-v3-0324:free" })
        assertTrue(openRouterModels.any { it.id == "google/gemini-2.5-flash" })
        assertTrue(openRouterModels.any { it.id == "anthropic/claude-sonnet-4" })

        // Free tier models must always estimate $0.00 cost
        assertEquals(
            0.0,
            AiModelsCatalog.estimateCostUsd("qwen/qwen3-coder:free", inputTokens = 50_000L, outputTokens = 10_000L),
            0.0,
        )
        assertEquals(
            0.0,
            AiModelsCatalog.estimateCostUsd("deepseek/deepseek-chat-v3-0324:free", inputTokens = 25_000L, outputTokens = 5_000L),
            0.0,
        )
        assertTrue(
            AiModelsCatalog.estimateCostUsd("anthropic/claude-sonnet-4", inputTokens = 10_000L, outputTokens = 2_000L) > 0.0,
        )

        val chain = AiModelsCatalog.fallbackChain(AiProvider.OPENROUTER, "anthropic/claude-sonnet-4")
        assertEquals("anthropic/claude-sonnet-4", chain.first())
        assertTrue(chain.contains("openrouter/auto"))
        assertTrue(chain.contains("qwen/qwen3-coder:free"))

        assertEquals("sk-or-v1-user", GeminiCodeAssistant.resolveOpenRouterApiKey("  sk-or-v1-user  "))

        val usageSummary = com.example.ai.AiUsageSummary(
            byProvider = mapOf(
                AiProvider.OPENROUTER to com.example.ai.ProviderUsageStats(
                    provider = AiProvider.OPENROUTER,
                    requests = 2,
                    toolCalls = 3,
                    inputTokens = 500,
                    outputTokens = 200,
                    totalTokens = 700,
                    estimatedCostUsd = 0.0,
                    lastModelId = "qwen/qwen3-coder:free",
                ),
            ),
            byModel = mapOf("qwen/qwen3-coder:free" to 700L),
        )
        val decoded = com.example.ai.AiUsageSummary.fromJson(usageSummary.toJson())
        assertEquals("qwen/qwen3-coder:free", decoded.byProvider[AiProvider.OPENROUTER]?.lastModelId)
        assertEquals(700L, decoded.totalTokens)
    }

    @Test
    fun openRouterFreeModelsNeverFailoverToPaidModels() {
        assertTrue(AiModelsCatalog.isFreeOpenRouterModel("openrouter/free"))
        assertTrue(AiModelsCatalog.isFreeOpenRouterModel("google/gemma-3-27b-it:free"))
        assertFalse(AiModelsCatalog.isFreeOpenRouterModel("openrouter/auto"))
        assertFalse(AiModelsCatalog.isFreeOpenRouterModel("google/gemini-2.5-flash"))

        val liveModels = listOf(
            com.example.ai.AiModelOption(
                id = "nvidia/nemotron-3-super-120b-a12b:free",
                provider = AiProvider.OPENROUTER,
                label = "Nemotron 120B (Free)",
                badge = "Free",
                description = "Live free model",
                isLiveFetched = true,
                supportsTools = true,
            ),
            com.example.ai.AiModelOption(
                id = "anthropic/claude-opus-4",
                provider = AiProvider.OPENROUTER,
                label = "Claude Opus 4",
                badge = "Claude",
                description = "Paid model",
                isLiveFetched = true,
            ),
        )

        val gemmaFreeChain = AiModelsCatalog.fallbackChain(
            provider = AiProvider.OPENROUTER,
            primaryModelId = "google/gemma-3-27b-it:free",
            liveModels = liveModels,
        )
        assertEquals("google/gemma-3-27b-it:free", gemmaFreeChain.first())
        assertTrue(gemmaFreeChain.contains("openrouter/free"))
        assertTrue(gemmaFreeChain.contains("nvidia/nemotron-3-super-120b-a12b:free"))
        assertTrue(gemmaFreeChain.all { AiModelsCatalog.isFreeOpenRouterModel(it) })
        assertFalse(gemmaFreeChain.contains("google/gemini-2.5-flash"))
        assertFalse(gemmaFreeChain.contains("openai/gpt-4o-mini"))
        assertFalse(gemmaFreeChain.contains("anthropic/claude-opus-4"))
    }

    @Test
    fun providerApiClientAdaptsSystemToolHistoryAndImagesForRestrictedFreeModels() {
        val rawMessages = org.json.JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", "You are an Android coding agent."))
            put(
                JSONObject().put("role", "user").put(
                    "content",
                    org.json.JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "Fix MainActivity"))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,AAA"))),
                ),
            )
            put(
                JSONObject().put("role", "assistant").put("content", "").put(
                    "tool_calls",
                    org.json.JSONArray().put(
                        JSONObject().put("id", "call_1").put("type", "function").put(
                            "function",
                            JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"AndroidManifest.xml\"}"),
                        ),
                    ),
                ),
            )
            put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "{\"content\":\"<manifest/>\"}"))
        }

        val adapted = com.example.ai.ProviderApiClient.buildAdaptedOpenAiMessages(
            messages = rawMessages,
            foldSystemIntoUser = true,
            convertToolsToTextHistory = true,
            stripImages = true,
            textToolPromptSuffix = com.example.ai.ProviderApiClient.buildTextModeToolPrompt(includeGitHubTools = false),
        )

        // System message must be folded into first user message for models like Gemma 3.
        for (i in 0 until adapted.length()) {
            assertFalse("system role should be folded", adapted.getJSONObject(i).getString("role") == "system")
            assertFalse("tool role should be converted", adapted.getJSONObject(i).getString("role") == "tool")
            assertFalse("tool_calls should be stripped in text mode", adapted.getJSONObject(i).has("tool_calls"))
        }
        val firstUserContent = adapted.getJSONObject(0).getString("content")
        assertTrue(firstUserContent.contains("You are an Android coding agent."))
        assertTrue(firstUserContent.contains("<tool_call>"))
        assertTrue(firstUserContent.contains("Fix MainActivity"))
        assertTrue(firstUserContent.contains("Image attachment omitted"))
        assertFalse(firstUserContent.contains("base64,AAA"))
        assertTrue(adapted.getJSONObject(2).getString("content").contains("[Tool Result]"))
    }

    @Test
    fun providerApiClientExtractsTextToolCallsAndStripsThinkBlocks() {
        val raw = """
            <think>Let me think step by step about the fix.</think>
            Applying the change now.
            <tool_call>{"name":"edit_file","arguments":{"path":"MainActivity.java","old_text":"a","new_text":"b"}}</tool_call>
            <tool_call>{"name":"unknown_fake_tool","arguments":{}}</tool_call>
        """.trimIndent()

        val withoutThink = com.example.ai.ProviderApiClient.stripThinkBlocks(raw)
        assertFalse(withoutThink.contains("<think>"))
        assertTrue(withoutThink.contains("Applying the change now."))

        val (cleanedText, extracted) = com.example.ai.ProviderApiClient.extractTextToolCalls(withoutThink)
        assertEquals(1, extracted.size)
        assertEquals("edit_file", extracted[0].name)
        assertTrue(extracted[0].argsJson.contains("MainActivity.java"))
        assertTrue(cleanedText.contains("Applying the change now."))
        assertFalse(cleanedText.contains("edit_file"))

        // Streaming sanitizer should suppress partial unclosed <think> and <tool_call> blocks
        assertEquals("", com.example.ai.ProviderApiClient.sanitizeStreamingVisibleText("<think>partial reasoning", hideToolCalls = true))
        assertEquals(
            "Hello world",
            com.example.ai.ProviderApiClient.sanitizeStreamingVisibleText(
                "<think>reasoning</think>Hello world<tool_call>{\"name\":\"build_",
                hideToolCalls = true,
            ),
        )
    }

    @Test
    fun openRouterLiveCatalogParsingDetectsToolSupportPricingAndPrunesStaleSlugs() {
        val rawJson = """
            {
              "data": [
                {
                  "id": "openrouter/free",
                  "name": "Free Models Router",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "context_length": 200000,
                  "supported_parameters": ["tools", "temperature"]
                },
                {
                  "id": "openrouter/auto",
                  "name": "Auto Router",
                  "pricing": {"prompt": "-1", "completion": "-1"},
                  "context_length": 2000000,
                  "supported_parameters": ["tools"]
                },
                {
                  "id": "arcee-ai/trinity-mini:free",
                  "name": "Trinity Mini (Free)",
                  "pricing": {"prompt": "0.00", "completion": "0.00"},
                  "context_length": 131072,
                  "supported_parameters": []
                },
                {
                  "id": "qwen/qwen3-next-80b-a3b-instruct:free",
                  "name": "Qwen3 Next 80B (Free)",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "context_length": 262144,
                  "supported_parameters": ["tools", "temperature", "stream_options"]
                },
                {
                  "id": "nvidia/nemotron-3-super-120b-a12b:free",
                  "name": "Nemotron 3 Super 120B (Free)",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "context_length": 262144,
                  "supported_parameters": ["tools", "reasoning"]
                },
                {
                  "id": "anthropic/claude-sonnet-4",
                  "name": "Claude Sonnet 4",
                  "pricing": {"prompt": "0.000003", "completion": "0.000015"},
                  "context_length": 200000,
                  "supported_parameters": ["tools"]
                }
              ]
            }
        """.trimIndent()

        val parsed = AiModelsCatalog.parseOpenRouterModelsJson(rawJson)
        val byId = parsed.associateBy { it.id }

        // Empty supported_parameters array means tools are NOT supported (do not treat empty array as true!)
        assertFalse(byId["arcee-ai/trinity-mini:free"]!!.supportsTools)
        assertTrue(byId["qwen/qwen3-next-80b-a3b-instruct:free"]!!.supportsTools)
        assertFalse(AiModelsCatalog.isFreeOpenRouterModel("openrouter/auto"))
        assertEquals("Auto", byId["openrouter/auto"]!!.badge)
        assertEquals("Free", byId["arcee-ai/trinity-mini:free"]!!.badge)

        // When live catalog is loaded, retired built-in slugs must be pruned from modelsFor(...)
        val merged = AiModelsCatalog.modelsFor(AiProvider.OPENROUTER, parsed)
        assertFalse(merged.any { it.id == "deepseek/deepseek-chat-v3-0324:free" })
        assertTrue(merged.any { it.id == "openrouter/auto" })
        assertTrue(merged.any { it.id == "openrouter/free" })
        assertTrue(merged.any { it.id == "qwen/qwen3-next-80b-a3b-instruct:free" })

        // Retired free slug resolves to active live free model in same family or top coding model, never paid!
        val resolvedQwen = AiModelsCatalog.resolveOpenRouterModelId("qwen/qwen3-coder:free", parsed)
        assertEquals("qwen/qwen3-next-80b-a3b-instruct:free", resolvedQwen)
        val resolvedDeepSeek = AiModelsCatalog.resolveOpenRouterModelId("deepseek/deepseek-r1-0528:free", parsed)
        assertTrue(AiModelsCatalog.isFreeOpenRouterModel(resolvedDeepSeek))
        assertTrue(resolvedDeepSeek != "openrouter/free")

        // Fallback chain tries capable live named free models BEFORE openrouter/free router
        val chain = AiModelsCatalog.fallbackChain(
            provider = AiProvider.OPENROUTER,
            primaryModelId = "nvidia/nemotron-3-super-120b-a12b:free",
            liveModels = parsed,
        )
        val qwenIdx = chain.indexOf("qwen/qwen3-next-80b-a3b-instruct:free")
        val routerIdx = chain.indexOf("openrouter/free")
        assertTrue(qwenIdx in 1 until routerIdx)
        assertTrue(chain.all { AiModelsCatalog.isFreeOpenRouterModel(it) })
    }

    @Test
    fun openRouterAdaptationHandlesAll404CausesAndErrorMetadata() {
        val initial = GeminiCodeAssistant.OpenAiModelCapabilities()

        // 1. Policy 404 triggers data_collection fallback override
        val policyNext = GeminiCodeAssistant.nextOpenRouterAdaptation(
            current = initial,
            httpCode = 404,
            errorMessage = "No endpoints found matching your data policy (Free model publication).",
            hasImages = false,
        )
        assertTrue(policyNext?.allowDataCollectionFallback == true)
        assertNull(
            GeminiCodeAssistant.nextOpenRouterAdaptation(
                current = policyNext!!,
                httpCode = 404,
                errorMessage = "No endpoints found matching your data policy (Free model publication).",
                hasImages = false,
            ),
        )

        // 2. Generic 404 "No endpoints found" without "tool" still adapts step-by-step and terminates cleanly
        var state: GeminiCodeAssistant.OpenAiModelCapabilities? = initial
        val history = mutableListOf<GeminiCodeAssistant.OpenAiModelCapabilities>()
        var guard = 0
        while (state != null && guard < 10) {
            guard++
            state = GeminiCodeAssistant.nextOpenRouterAdaptation(
                current = state,
                httpCode = 404,
                errorMessage = "No endpoints found for model.",
                hasImages = true,
            )
            if (state != null) history.add(state)
        }
        assertEquals(7, history.size)
        assertFalse(history[0].useNativeTools)
        assertFalse(history[1].includeStreamOptions)
        assertFalse(history[2].useStreaming)
        assertFalse(history[3].includeTemperature)
        assertTrue(history[4].allowDataCollectionFallback)
        assertTrue(history[5].foldSystem)
        assertTrue(history[6].stripImages)

        // 3. Request builder honors data_collection override and omits temperature when adapted off
        val reqBody = com.example.ai.ProviderApiClient.buildOpenAiCompatibleRequestBody(
            modelId = "qwen/qwen3-next-80b-a3b-instruct:free",
            adaptedMessages = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")),
            tools = org.json.JSONArray(),
            thinkingMode = AiThinkingMode.FAST,
            isOpenRouter = true,
            includeTools = false,
            includeStreamOptions = false,
            includeTemperature = false,
            allowOpenRouterDataCollectionFallback = true,
        )
        assertFalse(reqBody.has("tools"))
        assertFalse(reqBody.has("stream_options"))
        assertFalse(reqBody.has("temperature"))
        assertEquals("allow", reqBody.getJSONObject("provider").getString("data_collection"))

        // 4. Error extractor preserves metadata.raw details
        val extracted = GeminiCodeAssistant.extractApiErrorMessage(
            """{"error":{"message":"Provider returned error","code":404,"metadata":{"raw":"No endpoints found matching ZDR"}}}""",
        )
        assertTrue(extracted.contains("Provider returned error"))
        assertTrue(extracted.contains("No endpoints found matching ZDR"))
    }

    @Test
    fun openAiAndClaudeModelResolutionResponsesRoutingAndAdaptationWorkCleanly() {
        assertTrue(com.example.ai.ProviderApiClient.isResponsesOnlyOpenAiModel("o3-pro"))
        assertTrue(com.example.ai.ProviderApiClient.isResponsesOnlyOpenAiModel("openai/codex-mini-latest"))
        assertTrue(com.example.ai.ProviderApiClient.isResponsesOnlyOpenAiModel("gpt-5-codex"))
        assertFalse(com.example.ai.ProviderApiClient.isResponsesOnlyOpenAiModel("gpt-4o"))
        assertFalse(com.example.ai.ProviderApiClient.isResponsesOnlyOpenAiModel("gpt-5-mini"))

        val liveOpenAi = listOf(
            com.example.ai.AiModelOption("gpt-4o", AiProvider.OPENAI, "GPT-4o", "Live", "", isLiveFetched = true),
            com.example.ai.AiModelOption("gpt-5-mini", AiProvider.OPENAI, "GPT-5 Mini", "Live", "", isLiveFetched = true),
        )
        assertEquals("gpt-4o", AiModelsCatalog.resolveOpenAiModelId("gpt-4o", liveOpenAi))
        assertEquals("gpt-5-mini", AiModelsCatalog.resolveOpenAiModelId("gpt-6-astra", liveOpenAi))

        val liveClaude = listOf(
            com.example.ai.AiModelOption("claude-sonnet-4-20250514", AiProvider.CLAUDE, "Claude Sonnet 4", "Live", "", isLiveFetched = true),
            com.example.ai.AiModelOption("claude-3-7-sonnet-20250219", AiProvider.CLAUDE, "Claude 3.7 Sonnet", "Live", "", isLiveFetched = true),
        )
        assertEquals("claude-sonnet-4-20250514", AiModelsCatalog.resolveClaudeModelId("claude-sonnet-4", liveClaude))
        assertEquals("claude-sonnet-4-20250514", AiModelsCatalog.resolveClaudeModelId("claude-opus-5-5", liveClaude))
        val liveWithOpus = liveClaude + com.example.ai.AiModelOption(
            "claude-opus-4-20250514", AiProvider.CLAUDE, "Claude Opus 4", "Live", "", isLiveFetched = true,
        )
        assertEquals("claude-opus-4-20250514", AiModelsCatalog.resolveClaudeModelId("claude-opus-4", liveWithOpus))

        val reasoningReq = com.example.ai.ProviderApiClient.buildOpenAiCompatibleRequestBody(
            modelId = "o4-mini",
            adaptedMessages = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")),
            tools = org.json.JSONArray(),
            thinkingMode = AiThinkingMode.FAST,
            isOpenRouter = false,
            temperature = 0.9f,
        )
        assertFalse(reasoningReq.has("temperature"))
        assertEquals("low", reasoningReq.getString("reasoning_effort"))

        val claudeInitial = GeminiCodeAssistant.ClaudeModelCapabilities(maxTokens = 8192)
        val claudeMaxCap = GeminiCodeAssistant.nextClaudeAdaptation(
            current = claudeInitial,
            httpCode = 400,
            errorMessage = "max_tokens: 8192 > maximum of 4096",
            hasImages = false,
        )
        assertEquals(4096, claudeMaxCap?.maxTokens)

        val claudeNoTools = GeminiCodeAssistant.nextClaudeAdaptation(
            current = claudeInitial,
            httpCode = 400,
            errorMessage = "tools input_schema invalid for legacy model",
            hasImages = false,
        )
        assertFalse(claudeNoTools?.useNativeTools ?: true)
    }

    @Test
    fun roleAgentThreadsAndCustomSystemTempPersistRoundTrip() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("role_agent_store_test").toFile()
        try {
            val fixer = com.example.ai.ProjectChatStore.createRoleAgentThread(
                role = com.example.ai.AgentRole.ERROR_FIXER,
                providerName = AiProvider.CLAUDE.name,
                modelId = "claude-sonnet-4-5",
            ).copy(
                customSystemInstruction = "Fix all lint and javac errors.",
                temperature = 0.2f,
            )
            val watcher = com.example.ai.ProjectChatStore.createRoleAgentThread(
                role = com.example.ai.AgentRole.GITHUB_WATCHER,
                providerName = AiProvider.OPENAI.name,
                modelId = "gpt-5-mini",
            )
            com.example.ai.ProjectChatStore.saveThreads(dir, listOf(fixer, watcher))
            val loaded = com.example.ai.ProjectChatStore.loadThreads(dir)
            assertEquals(2, loaded.size)
            assertEquals(com.example.ai.AgentRole.ERROR_FIXER, loaded[0].agentRole)
            assertEquals(AiProvider.CLAUDE.name, loaded[0].providerName)
            assertEquals("claude-sonnet-4-5", loaded[0].modelId)
            assertEquals("Fix all lint and javac errors.", loaded[0].customSystemInstruction)
            assertEquals(0.2f, loaded[0].temperature ?: -1f, 0.01f)
            assertTrue(loaded[0].isPinnedAgent)
            assertEquals(com.example.ai.AgentRole.GITHUB_WATCHER, loaded[1].agentRole)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openRouterSlugMappingHandlesOpenAiAndClaudeDateDotVersions() {
        val liveOpenRouter = listOf(
            com.example.ai.AiModelOption("anthropic/claude-sonnet-4.5", AiProvider.OPENROUTER, "Claude Sonnet 4.5", "Claude", "", isLiveFetched = true),
            com.example.ai.AiModelOption("anthropic/claude-opus-4.6", AiProvider.OPENROUTER, "Claude Opus 4.6", "Claude", "", isLiveFetched = true),
            com.example.ai.AiModelOption("openai/gpt-5", AiProvider.OPENROUTER, "GPT-5", "OpenAI", "", isLiveFetched = true),
            com.example.ai.AiModelOption("openai/o4-mini", AiProvider.OPENROUTER, "o4-mini", "OpenAI", "", isLiveFetched = true),
        )

        assertEquals(
            "anthropic/claude-sonnet-4.5",
            AiModelsCatalog.toOpenRouterSlug(AiProvider.CLAUDE, "claude-sonnet-4-5", liveOpenRouter),
        )
        assertEquals(
            "anthropic/claude-opus-4.6",
            AiModelsCatalog.toOpenRouterSlug(AiProvider.CLAUDE, "claude-opus-4-6-latest", liveOpenRouter),
        )
        assertEquals(
            "anthropic/claude-3.7-sonnet",
            AiModelsCatalog.toOpenRouterSlug(AiProvider.CLAUDE, "claude-3-7-sonnet-20250219", liveOpenRouter),
        )
        assertEquals(
            "openai/gpt-5",
            AiModelsCatalog.toOpenRouterSlug(AiProvider.OPENAI, "gpt-5", liveOpenRouter),
        )
        assertEquals(
            "openai/o4-mini",
            AiModelsCatalog.toOpenRouterSlug(AiProvider.OPENAI, "o4-mini", liveOpenRouter),
        )
    }

    @Test
    fun buildAdaptedClaudeMessagesEnforcesAlternationMergesDuplicatesAndFoldsSystem() {
        val raw = org.json.JSONArray().apply {
            // Leading assistant message should become synthetic leading user before it, or merge cleanly
            put(JSONObject().put("role", "assistant").put("content", "")) // blank skipped
            put(JSONObject().put("role", "assistant").put("content", "Previous assistant greeting"))
            put(JSONObject().put("role", "user").put("content", "First question"))
            put(
                JSONObject().put("role", "user").put(
                    "content",
                    org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "Second question part")),
                ),
            )
            put(JSONObject().put("role", "assistant").put("content", "Ack 1"))
            put(JSONObject().put("role", "assistant").put("content", "Ack 2"))
        }

        val adapted = com.example.ai.ProviderApiClient.buildAdaptedClaudeMessages(
            messages = raw,
            stripImages = true,
            convertToolsToTextHistory = false,
            foldedSystemPrefix = "Strict Android IDE rules",
        )

        assertTrue(adapted.length() >= 3)
        assertEquals("user", adapted.getJSONObject(0).getString("role"))
        assertTrue(adapted.getJSONObject(0).get("content").toString().contains("Strict Android IDE rules"))

        // Verify strict alternation (no consecutive user/user or assistant/assistant)
        for (i in 1 until adapted.length()) {
            val prevRole = adapted.getJSONObject(i - 1).getString("role")
            val curRole = adapted.getJSONObject(i).getString("role")
            assertTrue("Expected alternating roles at index $i, found $prevRole -> $curRole", prevRole != curRole)
        }
    }

    @Test
    fun openRouterCredit402AndNonStreamingAdaptationsStepDownSafely() {
        val initial = GeminiCodeAssistant.OpenAiModelCapabilities(maxTokens = 8192, useStreaming = true)

        val step1 = GeminiCodeAssistant.nextOpenRouterAdaptation(
            current = initial,
            httpCode = 402,
            errorMessage = "Insufficient credits: affordable max_tokens is 2048",
            hasImages = false,
        )
        assertEquals(2048, step1?.maxTokens)

        val step2 = GeminiCodeAssistant.nextOpenRouterAdaptation(
            current = step1!!,
            httpCode = 402,
            errorMessage = "Insufficient credits",
            hasImages = false,
        )
        assertEquals(1024, step2?.maxTokens)

        val noStream = GeminiCodeAssistant.nextOpenRouterAdaptation(
            current = initial,
            httpCode = 400,
            errorMessage = "SSE stream error: unsupported parameter stream",
            hasImages = false,
        )
        assertFalse(noStream?.useStreaming ?: true)

        val claudeNoStream = GeminiCodeAssistant.nextClaudeAdaptation(
            current = GeminiCodeAssistant.ClaudeModelCapabilities(useStreaming = true),
            httpCode = 400,
            errorMessage = "streaming disabled for this endpoint",
            hasImages = false,
        )
        assertFalse(claudeNoStream?.useStreaming ?: true)
    }
}
