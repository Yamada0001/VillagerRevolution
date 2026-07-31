package dev.bettervillagers.building;

import java.util.ArrayList;
import java.util.List;

/** 将特色建筑群转换为一个原子施工计划。 */
final class StructureClusterPlanner {

    private StructureClusterPlanner() {
    }

    static Plan plan(StructureCluster cluster, StructureTemplateLibrary templates, int centerX, int baseY, int centerZ) {
        StructureTemplate root = templates.byId(cluster.rootTemplate()).orElse(null);
        if (root == null) {
            return null;
        }
        StructureTemplate.Placement rootPlacement = StructureTemplate.Placement.centered(root);
        List<ConstructionStep> steps = new ArrayList<>(
                BlueprintPlanner.planAt(root, rootPlacement, centerX, baseY, centerZ));
        int minX = centerX - rootPlacement.anchorX();
        int maxX = minX + root.transformedWidth(rootPlacement) - 1;
        int minZ = centerZ - rootPlacement.anchorZ();
        int maxZ = minZ + root.transformedDepth(rootPlacement) - 1;

        for (StructureCluster.Member member : cluster.members()) {
            StructureTemplate template = templates.byId(member.template()).orElse(null);
            if (template == null && member.required()) {
                return null;
            }
            if (template == null) {
                continue;
            }
            int memberX = centerX + member.offsetX();
            int memberZ = centerZ + member.offsetZ();
            StructureTemplate.Placement placement = StructureTemplate.Placement.centered(template);
            steps.addAll(BlueprintPlanner.planAt(template, placement, memberX, baseY, memberZ));
            int memberMinX = memberX - placement.anchorX();
            int memberMinZ = memberZ - placement.anchorZ();
            minX = Math.min(minX, memberMinX);
            maxX = Math.max(maxX, memberMinX + template.transformedWidth(placement) - 1);
            minZ = Math.min(minZ, memberMinZ);
            maxZ = Math.max(maxZ, memberMinZ + template.transformedDepth(placement) - 1);
        }
        return new Plan(cluster.id(), steps, minX, maxX, minZ, maxZ);
    }

    record Plan(String clusterId, List<ConstructionStep> steps, int minX, int maxX, int minZ, int maxZ) {
    }
}
