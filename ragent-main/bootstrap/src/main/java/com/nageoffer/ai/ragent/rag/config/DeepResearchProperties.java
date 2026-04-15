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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deep Research 工作流配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.deep-research")
public class DeepResearchProperties {

    /**
     * 是否启用 Deep Research 接口
     */
    private boolean enabled = true;

    /**
     * 拆解阶段 LLM 允许的最大子问题数量（上限）
     */
    private int maxSubQuestions = 8;

    /**
     * 每个子问题多通道检索 TopK
     */
    private int retrievalTopK = 12;

    /**
     * 拼接检索片段用于摘要时的最大字符数（防止单次摘要过长）
     */
    private int maxSourceCharsPerSub = 12000;

    /**
     * 单个子问题摘要最大字符数
     */
    private int summaryMaxChars = 2500;
}
