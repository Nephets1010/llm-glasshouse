/*
 * Copyright 2026 the llm-glasshouse authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.nephets.glasshouse.core;

/**
 * 三仓共用错误码表（契约编号 {@code ERR-001}）。
 *
 * <p>与 {@code llm-switchyard} / {@code agentswitchyard} 逐项一致。字面量以 {@code SW_} 前缀 + 大写下划线组成， {@link
 * #httpStatus()} 与 {@link #retryable()} 的取值也属于契约的一部分，任何一方修改都必须三仓同 PR。
 *
 * <p><b>重试可判定性</b>：只有 {@link #retryable()} 为 {@code true} 的错误码允许重试。4xx / 参数错误 / 业务校验失败一律不重试。
 */
public enum ErrorCode {

    /** 配额不足。对外 fail-soft 返回友好提示，不抛异常（HTTP 429）。 */
    SW_QUOTA_EXCEEDED(429, false, "配额不足"),
    /** 上游 provider 不可用或熔断中（HTTP 503）。 */
    SW_PROVIDER_UNAVAILABLE(503, true, "上游模型服务不可用"),
    /** 上游超时（HTTP 504）。 */
    SW_PROVIDER_TIMEOUT(504, true, "上游模型服务超时"),
    /** 已配置降级链但全部失败（HTTP 503）。 */
    SW_MODEL_UNAVAILABLE(503, true, "所有候选模型均不可用"),
    /** 内容审核拦截（HTTP 422）。 */
    SW_CONTENT_FILTERED(422, false, "内容被安全策略拦截"),
    /** 参数错误。禁止重试（HTTP 400）。 */
    SW_BAD_REQUEST(400, false, "请求参数错误"),
    /** 工具未注册或超出白名单（HTTP 403）。 */
    SW_TOOL_DENIED(403, false, "工具调用被拒绝"),
    /** 需人工审批，返回挂起令牌（HTTP 202）。 */
    SW_APPROVAL_REQUIRED(202, false, "等待人工审批"),
    /** 审批拒绝（HTTP 403）。 */
    SW_APPROVAL_DENIED(403, false, "审批被拒绝"),
    /** 内部错误（HTTP 500）。 */
    SW_INTERNAL(500, false, "内部错误");

    private final int httpStatus;
    private final boolean retryable;
    private final String description;

    ErrorCode(int httpStatus, boolean retryable, String description) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.description = description;
    }

    /**
     * 返回该错误码对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 判断该错误是否允许重试。
     *
     * @return 允许重试返回 {@code true}
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 返回错误码的中文语义描述。
     *
     * @return 不可为 null 的描述文本
     */
    public String description() {
        return description;
    }
}
