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

package io.github.nephets.glasshouse.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code glasshouse eval} —— 离线评测与 CI 回归门禁（PRD 仓库③ P0-6 ★）。
 *
 * <p><b>为什么这是本仓最容易带来 star 的功能</b>：README 首屏第 6 段就是它。 「改 Prompt 到底是变好还是变坏」以前只能靠几个人主观感受，于是要么不敢改、要么盲改；
 * 有了门禁之后，答案由 CI 给出，且<b>失败时能指出是哪几条用例变差了</b>。
 *
 * <p><b>三个子命令都是顶层类而不是嵌套类</b>：picocli 需要用反射给 {@code @ParentCommand} 字段赋值，静态嵌套类里拿不到外层实例，会在初始化阶段直接失败。
 */
@Command(
        name = "eval",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "离线评测与 CI 回归门禁。",
        subcommands = {EvalRunCommand.class, EvalCompareCommand.class, EvalListCommand.class})
public final class EvalCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return GlasshouseCli.EXIT_OK;
    }
}
