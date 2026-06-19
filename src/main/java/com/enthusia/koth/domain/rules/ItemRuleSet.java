package com.enthusia.koth.domain.rules;

import com.enthusia.koth.domain.MaceRule;

import java.time.Duration;

public record ItemRuleSet(
        boolean elytraAllowed,
        MaceRule maceRule,
        boolean spearAllowed,
        boolean enderPearlAllowed,
        boolean windChargeAllowed,
        Duration maceCooldown,
        Duration spearCooldown,
        Duration enderPearlCooldown,
        Duration windChargeCooldown
) {
    public static ItemRuleSet permissive() {
        return new ItemRuleSet(true, MaceRule.FULLY_ALLOWED, true, true, true,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }
}
