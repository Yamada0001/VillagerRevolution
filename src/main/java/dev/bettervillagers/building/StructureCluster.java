package dev.bettervillagers.building;

import java.util.List;

/** 由一个核心模板和若干附属模板组成的特色建筑群。 */
record StructureCluster(String id, String rootTemplate, List<Member> members, int minimumPopulation) {

    record Member(String template, int offsetX, int offsetZ, boolean required) {
    }
}
