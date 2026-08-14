package com.codingshuttle.distributed_lovable.account_service.dto.subscription;

import com.codingshuttle.distributed_lovable.common_lib.dto.PlanDto;

import java.time.Instant;

public record SubscriptionResponse(
        PlanDto plan,
        String status,
        Instant currentPeriodEnd,
        Long tokensUsedThisCycle
) {
}
