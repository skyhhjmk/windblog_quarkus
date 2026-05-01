package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class AdminWalletDtos {

    public record WalletInfo(
            Long id,
            Long userId,
            Long pointsBalance,
            Integer version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record TransactionItem(
            Long id,
            Long walletId,
            Long userId,
            Long changeAmount,
            Long balanceAfter,
            String bizType,
            Long bizId,
            String description,
            OffsetDateTime createdAt) {
    }

    public record TransactionHistory(
            List<TransactionItem> items,
            long total,
            int page,
            int pageSize) {
    }

    public record AdjustPointsRequest(
            Long newBalance,
            String description) {
    }

    public record UpdateCheckInRewardRequest(
            com.fasterxml.jackson.databind.JsonNode reward) {
    }
}
