package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Monotonic deadline, independent of the business clock used for booking dates. */
public final class TurnBudget {
    private final long deadline;
    private final BooleanSupplier valid;
    private int models, tools;
    public TurnBudget(Duration timeout, BooleanSupplier valid) {
        deadline = System.nanoTime() + timeout.toNanos(); this.valid = valid;
    }
    public Duration remaining() {
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new Api.Failure(409,"ASSISTANT_CANCELLED","会话已清空或失效，请重新发送");
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new Api.Failure(504,"ASSISTANT_TIMEOUT","助手处理超时，请稍后重试");
        return Duration.ofNanos(nanos);
    }
    public void model() { remaining(); if (models >= 4) exceeded(); models++; }
    public void tool() { remaining(); if (tools >= 8) exceeded(); tools++; }
    private void exceeded() { throw new Api.Failure(503,"ASSISTANT_LIMIT_REACHED","本轮查询次数已达上限，请简化条件后重试"); }
    public int models() { return models; }
    public int tools() { return tools; }
}
