package com.enthusia.koth.application.config;

import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.rules.ItemRuleSet;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public record PluginSettings(
        int configVersion,
        ZoneId scheduleZone,
        Duration manualBlockBeforeScheduled,
        Duration manualStartDelay,
        int activeRadiusBlocks,
        LockState lockState,
        double basicStartCost,
        double advancedStartCost,
        boolean scheduleEnabled,
        List<LocalTime> scheduleTimes,
        Map<KothFamily, ArenaDefinition> arenas,
        Map<KothFamily, ItemRuleSet> defaultRules,
        Map<KothFamily, Double> soloRewards,
        Map<KothFamily, Double> guildRewards,
        boolean discordEnabled,
        String discordWebhookUrl
) {
    public PluginSettings {
        scheduleTimes = List.copyOf(scheduleTimes);
        arenas = Map.copyOf(arenas);
        defaultRules = Map.copyOf(defaultRules);
        soloRewards = Map.copyOf(soloRewards);
        guildRewards = Map.copyOf(guildRewards);
    }
}
