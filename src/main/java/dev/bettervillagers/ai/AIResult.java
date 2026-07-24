package dev.bettervillagers.ai;

/** AI 调用结果（规范 1.3 / 4.5 三级降级链）。 */
public record AIResult(boolean success, boolean degraded, String text) {

    /** 正常成功。 */
    public static AIResult ok(String text) {
        return new AIResult(true, false, text);
    }

    /** 降级命中（缓存或规则），非真实大模型结果。 */
    public static AIResult degraded(String text) {
        return new AIResult(true, true, text);
    }

    /** 全部失败。 */
    public static AIResult failed() {
        return new AIResult(false, true, "");
    }

    /**
     * 是否为真实可用的大模型回复（成功、未降级、文本非空）。
     * <p>
     * 统一封装，避免在各调用处重复 {@code success && !degraded && text != null && !text.isBlank()} 判断。
     */
    public boolean isUsable() {
        return success && !degraded && text != null && !text.isBlank();
    }
}
