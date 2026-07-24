package dev.bettervillagers.scheduler;

/**
 * 统一调度抽象层接口（规范 0.1 / 4.1）。
 * <p>
 * 业务代码仅依赖此接口，由实现根据运行期平台（Paper / Folia）透明切换：
 * <ul>
 *   <li><b>异步层</b>：纯计算 / 网络 / JSON 解析，禁止触碰任何游戏世界对象。</li>
 *   <li><b>全局区域层</b>：无具体坐标的全局任务（统计、定时保存）。</li>
 *   <li><b>区域层</b>：已知坐标但无实体绑定的操作（方块读写、位置探查）。</li>
 *   <li><b>实体层</b>：与某实体绑定的操作（移动、装备、攻击）。</li>
 * </ul>
 * 时间单位统一为 tick（1 秒 = 20 tick）。
 */
public interface SchedulerAdapter {

    /** 异步执行（ForkJoinPool / AsyncScheduler，禁止访问游戏世界对象）。 */
    void runAsync(Runnable task);

    /** 异步延迟执行。 */
    void runAsyncDelayed(Runnable task, long delayTicks);

    /** 异步固定速率定时任务，返回可取消句柄。 */
    ScheduledHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

    /** 全局区域执行（无坐标绑定的世界级任务）。 */
    void runGlobal(Runnable task);

    /** 全局区域固定速率定时任务。 */
    ScheduledHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    /** 在指定坐标所属的区域线程执行（方块读写的正确线程）。 */
    void runAtRegion(org.bukkit.Location location, Runnable task);

    /** 在指定坐标所属区域线程延迟执行。 */
    void runAtRegionDelayed(org.bukkit.Location location, Runnable task, long delayTicks);

    /**
     * 在实体所在区域线程执行；实体被卸载/移除时执行 {@code retired} 回调。
     *
     * @param entity  目标实体
     * @param task    安全的世界交互任务
     * @param retired 实体失效时的清理回调（可为 null）
     */
    void runForEntity(org.bukkit.entity.Entity entity, Runnable task, Runnable retired);

    /** 运行期是否为 Folia。 */
    boolean isFolia();

    /** 当前线程是否为主线程（Paper）或区域线程（Folia，无坐标上下文判断）。 */
    boolean isTickThread();
}
