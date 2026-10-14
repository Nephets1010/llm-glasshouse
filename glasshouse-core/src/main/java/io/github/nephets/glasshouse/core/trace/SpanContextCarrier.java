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

package io.github.nephets.glasshouse.core.trace;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 进程内 TraceContext 存取器。
 *
 * <p><b>虚拟线程安全</b>：架构 §8.3 明确要求「任何 {@code ThreadLocal} 必须用 {@code InheritableThreadLocal} 或
 * Micrometer Context Propagation，虚拟线程下裸 ThreadLocal 在 executor 切换处会丢上下文」。 本类使用 {@link
 * InheritableThreadLocal}，并额外提供显式的 {@link #wrap(Runnable)} / {@link #wrap(Callable)}
 * 让业务在线程池切换处手动传播，覆盖 InheritableThreadLocal 不生效的场景 （线程池中的线程在创建时可能已经存在）。
 *
 * <p>本类为无状态单例，通过静态方法访问，避免被误实例化多处造成上下文分裂。
 */
public final class SpanContextCarrier {

    /** 当前线程的上下文。使用 InheritableThreadLocal，虚拟线程下不丢失（架构 §8.3）。 */
    private static final InheritableThreadLocal<TraceContext> CURRENT =
            new InheritableThreadLocal<>();

    private static final SpanContextCarrier INSTANCE = new SpanContextCarrier();

    private SpanContextCarrier() {}

    /**
     * 返回全局单例。
     *
     * @return 单例实例
     */
    public static SpanContextCarrier getInstance() {
        return INSTANCE;
    }

    /**
     * 读取当前线程的上下文。
     *
     * @return 存在返回上下文，否则为空
     */
    public Optional<TraceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 读取当前线程的 traceId。
     *
     * @return 存在返回 traceId，否则为空
     */
    public Optional<String> currentTraceId() {
        return current().map(TraceContext::traceId);
    }

    /**
     * 设置当前线程的上下文。
     *
     * @param context 上下文；传 null 等价于 {@link #clear()}
     */
    public void set(TraceContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /** 清除当前线程的上下文。 */
    public void clear() {
        CURRENT.remove();
    }

    /**
     * 在给定上下文中执行一段逻辑，执行完毕后恢复原上下文。
     *
     * <p>用于把链路上下文绑定到一段可能跨线程池的回调上，保证结束时一定能恢复，避免上下文泄漏。
     *
     * @param context 上下文；为 null 时等价于直接执行
     * @param action 待执行逻辑，不可为 null
     * @param <T> 返回值类型
     * @return action 的返回值
     */
    public <T> T callWith(TraceContext context, Supplier<T> action) {
        TraceContext previous = CURRENT.get();
        set(context);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /**
     * 在给定上下文中执行一段逻辑，执行完毕后恢复原上下文。
     *
     * @param context 上下文；为 null 时等价于直接执行
     * @param action 待执行逻辑，不可为 null
     */
    public void runWith(TraceContext context, Runnable action) {
        callWith(
                context,
                () -> {
                    action.run();
                    return null;
                });
    }

    /**
     * 包装一个任务，使其携带当前上下文，可在另一个线程执行。
     *
     * @param task 原始任务，不可为 null
     * @return 携带当前上下文的包装任务；当前无上下文时原样返回
     */
    public Runnable wrap(Runnable task) {
        TraceContext captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> runWith(captured, task);
    }

    /**
     * 包装一个任务，使其携带当前上下文，可在另一个线程执行。
     *
     * @param task 原始任务，不可为 null
     * @param <T> 返回值类型
     * @return 携带当前上下文的包装任务；当前无上下文时原样返回
     */
    public <T> Callable<T> wrap(Callable<T> task) {
        TraceContext captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            TraceContext previous = CURRENT.get();
            set(captured);
            try {
                return task.call();
            } finally {
                set(previous);
            }
        };
    }
}
