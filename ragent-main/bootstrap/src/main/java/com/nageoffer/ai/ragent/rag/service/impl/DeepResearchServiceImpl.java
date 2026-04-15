/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.config.DeepResearchProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.deepresearch.DeepResearchState;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.DeepResearchService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamChatEventHandler;
import com.nageoffer.ai.ragent.rag.service.handler.StreamChatHandlerParams;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEEP_RESEARCH_DECOMPOSE_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEEP_RESEARCH_REPORT_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEEP_RESEARCH_SUB_SUMMARY_PROMPT_PATH;

/**
 * Deep Research：状态机驱动的拆解 → 并行检索 → 子问题摘要 → 深度报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepResearchServiceImpl implements DeepResearchService {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final DeepResearchProperties deepResearchProperties;
    private final AIModelProperties modelProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final StreamTaskManager taskManager;

    @Qualifier("deepResearchThreadPoolExecutor")
    private final Executor deepResearchExecutor;

    @Override
    @ChatRateLimit
    public void streamDeepResearch(String topic, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        if (StrUtil.isBlank(topic)) {
            throw new ClientException("研究主题不能为空");
        }
        if (!deepResearchProperties.isEnabled()) {
            throw new ClientException("Deep Research 功能未启用（rag.deep-research.enabled=false）");
        }

        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();

        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId(conversationId)
                .taskId(taskId)
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .build();
        StreamChatEventHandler handler = new StreamChatEventHandler(params);

        try {
            runWorkflow(handler, taskId, topic, Boolean.TRUE.equals(deepThinking));
        } catch (Exception e) {
            log.error("Deep Research 执行失败", e);
            handler.onError(e);
        }
    }

    private void runWorkflow(StreamChatEventHandler handler, String taskId, String topic, boolean deepThinking) {
        DeepResearchState state = DeepResearchState.DECOMPOSE;
        emitPhase(handler, state, "开始拆解研究主题");

        List<String> subQuestions = decomposeSubQuestions(handler, topic);
        if (taskManager.isCancelled(taskId)) {
            transitionFailed(handler, "任务已取消");
            return;
        }
        if (CollUtil.isEmpty(subQuestions)) {
            subQuestions = List.of(topic);
        }

        state = DeepResearchState.PARALLEL_RETRIEVE;
        emitPhase(handler, state, "并行检索子问题，共 " + subQuestions.size() + " 个");

        List<SubRetrievalPack> packs = parallelRetrieve(taskId, subQuestions);
        if (taskManager.isCancelled(taskId)) {
            transitionFailed(handler, "任务已取消");
            return;
        }

        state = DeepResearchState.SUBQUESTION_SUMMARIZE;
        emitPhase(handler, state, "子问题独立会话摘要压缩");

        List<String> notes = summarizePerSubQuestion(taskId, packs);

        if (taskManager.isCancelled(taskId)) {
            transitionFailed(handler, "任务已取消");
            return;
        }

        state = DeepResearchState.SYNTHESIZE_REPORT;
        emitPhase(handler, state, "撰写深度报告（流式输出）");

        String reportPrompt = buildReportPrompt(topic, notes);
        ChatRequest reportReq = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(reportPrompt)))
                .thinking(deepThinking)
                .temperature(0.35D)
                .topP(0.9D)
                .build();

        llmService.streamChat(reportReq, handler);
    }

    private void transitionFailed(StreamChatEventHandler handler, String reason) {
        emitPhase(handler, DeepResearchState.FAILED, reason);
        handler.onError(new ClientException(reason));
    }

    private void emitPhase(StreamChatEventHandler handler, DeepResearchState state, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("state", state.name());
        o.addProperty("detail", detail);
        handler.sendDeepResearchPhase(o.toString());
    }

    private List<String> decomposeSubQuestions(StreamChatEventHandler handler, String topic) {
        String rendered = promptTemplateLoader.render(
                DEEP_RESEARCH_DECOMPOSE_PROMPT_PATH,
                Map.of(
                        "topic", topic,
                        "max_sub_questions", String.valueOf(deepResearchProperties.getMaxSubQuestions())));
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(rendered)))
                .temperature(0.15D)
                .topP(0.3D)
                .thinking(false)
                .build();
        try {
            String raw = llmService.chat(req);
            List<String> subs = parseSubQuestionsJson(raw);
            if (CollUtil.isEmpty(subs)) {
                return List.of(topic);
            }
            int cap = Math.max(1, deepResearchProperties.getMaxSubQuestions());
            return subs.stream().limit(cap).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Deep Research 拆解失败，退化为单主题", e);
            emitPhase(handler, DeepResearchState.DECOMPOSE, "拆解异常，使用单主题检索：" + e.getMessage());
            return List.of(topic);
        }
    }

    private List<String> parseSubQuestionsJson(String raw) {
        try {
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return List.of();
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("sub_questions") || !obj.get("sub_questions").isJsonArray()) {
                return List.of();
            }
            JsonArray arr = obj.getAsJsonArray("sub_questions");
            List<String> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    String s = el.getAsString().trim();
                    if (StrUtil.isNotBlank(s)) {
                        out.add(s);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("解析子问题 JSON 失败 raw={}", raw, e);
            return List.of();
        }
    }

    private List<SubRetrievalPack> parallelRetrieve(String taskId, List<String> subQuestions) {
        int topK = deepResearchProperties.getRetrievalTopK();
        List<CompletableFuture<SubRetrievalPack>> futures = subQuestions.stream()
                .map(sub -> CompletableFuture.supplyAsync(
                        () -> {
                            List<SubQuestionIntent> one = List.of(new SubQuestionIntent(sub, List.of()));
                            List<RetrievedChunk> chunks =
                                    multiChannelRetrievalEngine.retrieveKnowledgeChannels(one, topK);
                            return new SubRetrievalPack(sub, chunks == null ? List.of() : chunks);
                        },
                        deepResearchExecutor))
                .toList();

        List<SubRetrievalPack> packs = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            if (taskManager.isCancelled(taskId)) {
                break;
            }
            try {
                packs.add(futures.get(i).join());
            } catch (Exception e) {
                log.warn("子问题检索失败 idx={} sub={}", i, subQuestions.get(i), e);
                packs.add(new SubRetrievalPack(subQuestions.get(i), List.of()));
            }
        }
        return packs;
    }

    private List<String> summarizePerSubQuestion(String taskId, List<SubRetrievalPack> packs) {
        List<CompletableFuture<String>> futures = packs.stream()
                .map(pack -> CompletableFuture.supplyAsync(() -> summarizeOneSubQuestion(pack), deepResearchExecutor))
                .toList();

        List<String> notes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            if (taskManager.isCancelled(taskId)) {
                break;
            }
            try {
                notes.add(futures.get(i).join());
            } catch (Exception e) {
                log.warn("子问题摘要失败 idx={}", i, e);
                notes.add("（摘要失败）" + packs.get(i).subQuestion());
            }
        }
        return notes;
    }

    private String summarizeOneSubQuestion(SubRetrievalPack pack) {
        String sources = buildSourcesText(pack.chunks());
        String rendered = promptTemplateLoader.render(
                DEEP_RESEARCH_SUB_SUMMARY_PROMPT_PATH,
                Map.of(
                        "sub_question", pack.subQuestion(),
                        "sources", sources,
                        "summary_max_chars", String.valueOf(deepResearchProperties.getSummaryMaxChars())));
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(rendered)))
                .temperature(0.2D)
                .thinking(false)
                .build();
        return llmService.chat(req);
    }

    private String buildSourcesText(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return "（无检索命中）";
        }
        int maxChars = deepResearchProperties.getMaxSourceCharsPerSub();
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (RetrievedChunk c : chunks) {
            if (c == null || StrUtil.isBlank(c.getText())) {
                continue;
            }
            String block = "\n--- 片段 " + idx++ + " ---\n" + c.getText().trim() + "\n";
            if (sb.length() + block.length() > maxChars) {
                sb.append("\n（后续片段因长度上限省略）\n");
                break;
            }
            sb.append(block);
        }
        if (sb.isEmpty()) {
            return "（无有效文本）";
        }
        return sb.toString();
    }

    private String buildReportPrompt(String topic, List<String> notes) {
        StringBuilder nb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            nb.append("## 子问题 ").append(i + 1).append("\n\n").append(notes.get(i)).append("\n\n");
        }
        return promptTemplateLoader.render(
                DEEP_RESEARCH_REPORT_PROMPT_PATH,
                Map.of("topic", topic, "notes", nb.toString()));
    }

    private record SubRetrievalPack(String subQuestion, List<RetrievedChunk> chunks) {
    }
}
