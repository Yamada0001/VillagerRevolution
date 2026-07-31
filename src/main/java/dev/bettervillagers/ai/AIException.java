package dev.bettervillagers.ai;

/** AI 调用异常（规范 4.5：异常禁止跨线程传播到区域线程，由 AIService 捕获并降级）。 */
public class AIException extends RuntimeException {

    private final boolean retriable;

    public AIException(String message, Throwable cause, boolean retriable) {
        super(message, cause);
        this.retriable = retriable;
    }

    public boolean isTerminal() {
        return !retriable;
    }
}
