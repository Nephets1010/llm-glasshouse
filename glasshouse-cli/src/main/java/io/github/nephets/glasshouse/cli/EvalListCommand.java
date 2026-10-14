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

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;
import io.github.nephets.glasshouse.eval.dataset.DatasetRegistry;

/**
 * {@code glasshouse eval list} —— 列出可用评测集。
 *
 * <p>顺带打印每套数据集的<b>自撰条数与版本</b>：首版的评测集必须可复现且有出处， 这两个数字就是「可复现」与「有出处」的证据。
 */
@Command(
        name = "list",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "列出所有可用的评测集。")
public final class EvalListCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        DatasetRegistry registry = new DatasetRegistry();
        List<String> names = registry.list();
        if (names.isEmpty()) {
            System.out.println("未找到评测集。请检查 eval-datasets/ 目录或 classpath。");
            return GlasshouseCli.EXIT_OK;
        }
        System.out.println("可用评测集（" + names.size() + " 个）：");
        for (String name : names) {
            try {
                JsonlDataset dataset = registry.load(name);
                long authored = dataset.cases().stream().filter(EvalCase::authored).count();
                System.out.printf(
                        "  - %-28s %3d 条用例（自撰 %d）  版本=%s%n",
                        name, dataset.size(), authored, dataset.version());
            } catch (RuntimeException e) {
                System.out.printf("  - %-28s 解析失败: %s%n", name, e.getMessage());
            }
        }
        return GlasshouseCli.EXIT_OK;
    }
}
