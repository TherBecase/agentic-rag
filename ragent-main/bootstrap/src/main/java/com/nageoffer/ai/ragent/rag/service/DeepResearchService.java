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

package com.nageoffer.ai.ragent.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Deep Research：状态机驱动的「拆解 → 并行检索 → 子问题摘要 → 深度报告」
 */
public interface DeepResearchService {

    /**
     * SSE 流式执行 Deep Research（最终报告流式输出，前置阶段通过 message.type=deep_research 推送）
     */
    void streamDeepResearch(String topic, String conversationId, Boolean deepThinking, SseEmitter emitter);
}
