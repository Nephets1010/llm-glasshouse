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

package io.github.nephets.glasshouse.eval.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatasetRegistry} 缓存容量回归测试。
 *
 * <p>{@link DatasetRegistry#load(String)} 的入参可以是任意文件路径，键空间由调用方控制，
 * 因此缓存必须有硬上限。这里用「超限后再次加载同一个键会拿到新实例」来锁定淘汰确实发生了 —— 直接读私有缓存字段会让测试与实现细节耦合，断言行为更稳。
 */
class DatasetRegistryCacheCapacityTest {

    @TempDir Path tempDir;

    /**
     * 生成一个可被 {@link DatasetRegistry#load(String)} 直接加载的文件。
     *
     * <p>刻意<b>不带</b> {@code .jsonl} 后缀：{@code load} 会先把结尾的 {@code .jsonl} 削掉，
     * 带后缀的绝对路径在削掉之后就指不到真实文件了。
     */
    private Path datasetFile(int index) throws IOException {
        Path file = tempDir.resolve("ds-" + index);
        String line =
                "{\"id\":\"c"
                        + index
                        + "\",\"input\":{\"question\":\"q\"},\"expected\":{\"contains\":[\"x\"]},"
                        + "\"metadata\":{\"authored\":\"t\"}}";
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("缓存容量上限是正数，且远小于「无限」")
    void capacityIsBounded() {
        assertThat(DatasetRegistry.MAX_CACHE_ENTRIES).isPositive();
    }

    @Test
    @DisplayName("未超限时重复加载命中缓存，返回同一实例")
    void cachedWithinCapacity() throws IOException {
        DatasetRegistry registry = new DatasetRegistry();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            files.add(datasetFile(i));
        }
        JsonlDataset first = registry.load(files.get(0).toString());
        for (int i = 1; i < files.size(); i++) {
            registry.load(files.get(i).toString());
        }
        assertThat(registry.load(files.get(0).toString())).isSameAs(first);
    }

    @Test
    @DisplayName("超过容量上限后最久未使用的条目被淘汰，再次加载会重新解析")
    void evictedBeyondCapacity() throws IOException {
        DatasetRegistry registry = new DatasetRegistry();
        int overload = DatasetRegistry.MAX_CACHE_ENTRIES + 6;
        List<Path> files = new ArrayList<>(overload);
        for (int i = 0; i < overload; i++) {
            files.add(datasetFile(i));
        }

        JsonlDataset first = registry.load(files.get(0).toString());
        for (int i = 1; i < overload; i++) {
            registry.load(files.get(i).toString());
        }

        assertThat(registry.load(files.get(0).toString()))
                .as("超过容量上限后，最早加载的条目必须已被淘汰，不能继续命中旧实例")
                .isNotSameAs(first);
    }
}
