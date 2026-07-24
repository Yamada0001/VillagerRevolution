package dev.bettervillagers.behavior.threat;

import org.bukkit.entity.Entity;

/** 单个威胁（规范 3.1）。 */
public record Threat(ThreatType type, Entity source, double distance) {
}
