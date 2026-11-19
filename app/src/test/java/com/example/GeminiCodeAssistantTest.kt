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
        assertEquals("gemini-2.5-pro", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "gemini-2.5-pro"))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, ""))
        assertEquals("gemini-3.8-flash", AiModelsCatalog.normalizeModelId(AiProvider.GEMINI, "   "))
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
        assertTrue(geminiChain.contains("gemini-2.5-flash"))
        assertTrue(geminiChain.contains("gemini-2.0-flash"))
        assertEquals(geminiChain.distinct().size, geminiChain.size)

        val hydrogenChain = AiModelsCatalog.fallbackChain(AiProvider.GEMINI, "gemini-4-hydrogen")
        assertEquals("gemini-4-hydrogen", hydrogenChain.first())
        assertTrue(hydrogenChain.contains("gemini-2.5-flash"))
        assertTrue(hydrogenChain.contains("gemini-2.0-flash"))

        val openAiChain = AiModelsCatalog.fallbackChain(AiProvider.OPENAI, "gpt-6-astra")
        assertEquals("gpt-6-astra", openAiChain.first())
        assertTrue(openAiChain.contains("gpt-4o"))

        val claudeChain = AiModelsCatalog.fallbackChain(AiProvider.CLAUDE, "claude-opus-5-5")
        assertEquals("claude-opus-5-5", claudeChain.first())
        assertTrue(claudeChain.contains("claude-sonnet-4-5"))
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
        assertTrue(gemini.any { it.id == "gemini-2.5-flash" })
        assertTrue(gemini.any { it.id == "gemini-2.5-flash-lite" })
        assertTrue(gemini.any { it.id == "gemini-2.0-flash" })
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

        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.5-flash"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.5-flash-lite"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-2.0-flash"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-flash-latest"))
        assertTrue(com.example.ai.AiModelsCatalog.isCodingCapableGeminiModel("gemini-3-flash-preview"))
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
        assertFalse(com.example.ai.FirebaseAiAgentClient.isFirebaseAiLogicModel("gemini-2.5-flash"))

        val defaultChain = com.example.ai.FirebaseAiAgentClient.buildFirebaseModelCandidates("gemini-3.8-flash")
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), defaultChain)

        val argonChain = com.example.ai.FirebaseAiAgentClient.buildFirebaseModelCandidates("gemini-4-argon")
        assertEquals(listOf("gemini-4-argon", "gemini-3.8-flash", "gemini-3.5-flash-lite"), argonChain)
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
}
