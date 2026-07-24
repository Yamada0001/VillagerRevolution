package dev.bettervillagers.ai;

/** AI 决策上下文（由战术/战略层组装，规范 3.1）。 */
public record AIContext(
        String villagerUuid,
        String villagerName,
        String profession,
        String scenario,       // 场景类型：combat / patrol / trade / strategic ...
        String systemPrompt,   // 系统人设与规则
        String userPrompt      // 当前态势快照与决策请求
) {
}
