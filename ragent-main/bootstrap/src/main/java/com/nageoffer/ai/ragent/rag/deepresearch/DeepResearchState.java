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

package com.nageoffer.ai.ragent.rag.deepresearch;

/**
 * Deep Research 工作流状态（状态机节点）
 */
public enum DeepResearchState {

    /**
     * 宏观问题拆解为子问题（LLM）
     */
    DECOMPOSE,

    /**
     * 子问题并行召回（多通道检索引擎）
     */
    PARALLEL_RETRIEVE,

    /**
     * 每个子问题独立 LLM 会话：阅读检索结果并压缩摘要
     */
    SUBQUESTION_SUMMARIZE,

    /**
     * 基于摘要笔记流式生成深度报告
     */
    SYNTHESIZE_REPORT,

    /**
     * 正常结束
     */
    DONE,

    /**
     * 失败或中止
     */
    FAILED
}
