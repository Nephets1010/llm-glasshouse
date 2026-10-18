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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CliConsole} 的行为测试。
 *
 * <p>本类是「命令结果仍然落在用户能看到的地方」这一契约的守门人： 它锁住两件事——输出语义与 PrintStream 一致，以及出口每次调用都重新取流。
 *
 * <p>本类会调用 System.setOut，与 GlasshouseCliTest 的夹具是同一块共享资源。 仓库没有 junit-platform.properties，也没有给
 * surefire 配并行执行， 因此 JUnit 默认串行执行不会互相踩踏；本类与 GlasshouseCliTest 又各自在 finally 里恢复原流，
 * 执行顺序无关。若日后开启并行，需在两个类上加 {@code @ResourceLock(Resources.SYSTEM_OUT)}。
 */
class CliConsoleTest {

    /** 行分隔符。println 与 %n 都用系统的那一个，断言必须跟着它走。 */
    private static final String NL = System.lineSeparator();

    /**
     * 执行一次 CLI 输出并取回捕获到的文本。
     *
     * @param action 要执行的输出动作
     * @return 写到标准输出的全部内容
     */
    private String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("出口类每次调用都重新取流：两次 setOut 之间切换，输出分别落到各自的流")
    void outResolvesStreamOnEachCall() {
        PrintStream original = System.out;
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(first, true, StandardCharsets.UTF_8));
            CliConsole.println("第一段");
            System.setOut(new PrintStream(second, true, StandardCharsets.UTF_8));
            CliConsole.println("第二段");
        } finally {
            System.setOut(original);
        }

        String nl = System.lineSeparator();
        assertThat(first.toString(StandardCharsets.UTF_8)).isEqualTo("第一段" + nl);
        assertThat(second.toString(StandardCharsets.UTF_8)).isEqualTo("第二段" + nl);
    }

    @Test
    @DisplayName("print 原样输出且不追加换行")
    void printWritesTextAsIs() {
        assertThat(capture(() -> CliConsole.print("半行内容"))).isEqualTo("半行内容");
        assertThat(capture(() -> CliConsole.print("甲" + NL + "乙"))).isEqualTo("甲" + NL + "乙");
    }

    @Test
    @DisplayName("println() 输出空行，println(x) 输出一行，语义与 PrintStream 一致")
    void printlnWritesLineBreakOnlyThenOneLine() {
        assertThat(capture(CliConsole::println)).isEqualTo(NL);
        assertThat(capture(() -> CliConsole.println("完整一行"))).isEqualTo("完整一行" + NL);
        assertThat(capture(() -> CliConsole.println(""))).isEqualTo(NL);
    }

    @Test
    @DisplayName("printf 沿用 PrintStream 的排版语义：左对齐补空格、宽度右对齐、%n 换行")
    void printfHonoursWidthAndNewline() {
        assertThat(capture(() -> CliConsole.printf("%-6s|%3d|%s%n", "vip", 42, "ok")))
                .isEqualTo("vip   | 42|ok" + NL);
        assertThat(capture(() -> CliConsole.printf("%s%n%s%n", "第一行", "第二行")))
                .isEqualTo("第一行" + NL + "第二行" + NL);
    }

    @Test
    @DisplayName("out() 返回调用时刻安装的那个流本身")
    void outReturnsCurrentlyInstalledStream() {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buf, true, StandardCharsets.UTF_8);
        try {
            System.setOut(fake);
            assertThat(CliConsole.out()).isSameAs(fake);
        } finally {
            System.setOut(original);
        }
        assertThat(CliConsole.out()).isSameAs(original);
    }
}
