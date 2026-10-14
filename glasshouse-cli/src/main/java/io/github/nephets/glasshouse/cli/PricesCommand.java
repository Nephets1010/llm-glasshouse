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
 * {@code glasshouse prices} —— 价格表查看、校验与热更新验证。
 *
 * <p>对应 PRD 仓库③ P0-7「价格表支持外部配置文件热更新」：改价格不需要改代码、 不需要重启。这组命令让「改完之后价格对不对」这件事<b>可以被验证</b>，
 * 而不是靠「重启一下看看成本报表变没变」。
 *
 * <p>四个子命令都是顶层类：picocli 需要反射给 {@code @ParentCommand} 赋值， 静态嵌套类拿不到外层实例。
 */
@Command(
        name = "prices",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "查看 / 校验 / 重新加载价格表。",
        subcommands = {
            PricesShowCommand.class,
            PricesValidateCommand.class,
            PricesReloadCommand.class,
            PricesSupportCommand.class
        })
public final class PricesCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return GlasshouseCli.EXIT_OK;
    }
}
