package dev.bettervillagers.ai;

/**
 * 大模型供应商接口（规范 1.1）。
 * <p>
 * 实现提供 <b>阻塞</b> 调用方法；并发、限流、超时、重试、熔断由 {@code AIService} 统一编排，
 * 供应商内部不得触碰任何游戏世界对象。
 */
public interface AIProvider {

    /** 供应商标识，与配置 {@code ai.provider} 对应。 */
    String id();

    /**
     * 阻塞式补全调用（由 {@code AIService} 在受控线程池上调度）。
     *
     * @param request 不可变请求（含消息、模型、温度、超时）
     * @return 模型回复文本
     * @throws AIException 调用失败（可标记是否可重试）
     */
    String completeBlocking(AIRequest request) throws AIException;
}
