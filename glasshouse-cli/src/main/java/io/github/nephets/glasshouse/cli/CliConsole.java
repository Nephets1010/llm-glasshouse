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

import java.io.PrintStream;

/**
 * 命令结果的统一出口。
 *
 * <p>CLI 的输出分两类，本类只管第一类：
 *
 * <ul>
 *   <li>命令结果（报表、表格、JSON/CSV、帮助、提示）→ 走本类，落 stdout
 *   <li>错误与告警 → 走 SLF4J，由 logback 落 stderr
 * </ul>
 *
 * <p>两者不能混：命令结果是给用户看的产物，测试用 System.setOut 捕获后逐条断言； 一旦改走 logger，用户看不到报表，测试也抓不到内容。
 *
 * <p>本类是全仓唯一允许出现 System.out 的地方，也是 CLI 产品在标准输出上的唯一 seam： 命令类想往 stdout 写东西，只能经过这里，不允许自己摸 System.out。
 *
 * <p>每次调用都重新读 System.out 字段，不在静态初始化里快照。做成静态快照（{@code static final PrintStream OUT = System.out;}）会让
 * GlasshouseCliTest 的捕获失效： 测试在命令构造之后才调用 System.setOut，而快照早在类加载时就已经被取走。
 */
public final class CliConsole {

    private CliConsole() {}

    /**
     * 取得当前标准输出流（每次调用重新取）。
     *
     * <p>供 {@code CommandLine.usage(this, out())} 这类需要把流交给第三方渲染器的场景使用。
     *
     * @return 调用时刻的 System.out
     */
    public static PrintStream out() {
        return System.out;
    }

    /**
     * 输出一段文本，不追加换行。
     *
     * <p>渲染器自带尾部换行时用这个，避免多出一个空行。
     *
     * @param text 要输出的文本
     */
    public static void print(String text) {
        out().print(text);
    }

    /** 输出一个空行。 */
    public static void println() {
        out().println();
    }

    /**
     * 输出一行文本。
     *
     * @param line 要输出的一行
     */
    public static void println(String line) {
        out().println(line);
    }

    /**
     * 按格式输出一行。
     *
     * <p>保持 PrintStream.printf 的默认语义（不显式指定 Locale），替换前后输出逐字节一致。
     *
     * @param format 格式串
     * @param args 格式参数
     */
    public static void printf(String format, Object... args) {
        out().printf(format, args);
    }
}
