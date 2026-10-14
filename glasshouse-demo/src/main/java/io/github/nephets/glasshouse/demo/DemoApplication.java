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

package io.github.nephets.glasshouse.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示应用入口：一个进程里同时跑「mock 模型服务」与「被观测的客服 Agent」。
 *
 * <p><b>为什么合成一个进程</b>：README 的「30 秒上手」必须是 {@code docker compose up} 之后 一条 {@code curl} 就能看到 span
 * 树。如果 mock 服务是独立进程，用户要先关心端口、 先起后端、再起应用——大多数人在这一步就放弃了。合成一个进程后， 模型调用走本机 HTTP（真实经过网络栈，span
 * 属性与真实场景一致），但使用者只需面对一个地址。
 *
 * <p><b>零 API Key</b>：{@code MockLlmApplication} 在 {@code /mock/v1/chat/completions} 提供 OpenAI
 * 兼容接口，{@code llm.glasshouse.demo.base-url} 指向它。 整个 demo 不需要任何真实凭据。
 */
@SpringBootApplication
public class DemoApplication {

    /**
     * 启动演示应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
