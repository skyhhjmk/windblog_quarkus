package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.ConcurrentModificationException;
import com.biliwind.blog.common.exception.ConflictException;
import com.biliwind.blog.controller.api.admin.dto.AdminWalletDtos.AdjustPointsRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminWalletDtos.TransactionHistory;
import com.biliwind.blog.controller.api.admin.dto.AdminWalletDtos.TransactionItem;
import com.biliwind.blog.controller.api.admin.dto.AdminWalletDtos.WalletInfo;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserWallet;
import com.biliwind.blog.model.WalletTransaction;
import com.biliwind.blog.service.WalletService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * 管理员钱包管理接口
 */
@Path("/api/admin/users/{userId}/wallet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminWallet")
public class AdminWalletController {

    @Inject
    WalletService walletService;

    /**
     * 查询用户钱包信息
     */
    @GET
    @Operation(summary = "查询用户钱包余额")
    public WalletInfo getWallet(@PathParam("userId") Long userId) {
        // 验证用户是否存在
        User user = User.findById(userId);
        if (user == null || user.deletedAt != null) {
            throw new NotFoundException("用户不存在");
        }

        UserWallet wallet = walletService.getWalletByUserId(userId);
        if (wallet == null) {
            // 自动创建钱包
            wallet = walletService.createWallet(userId);
        }

        return new WalletInfo(
                wallet.id,
                wallet.userId,
                wallet.pointsBalance,
                wallet.version,
                wallet.createdAt,
                wallet.updatedAt);
    }

    /**
     * 查询钱包交易历史
     */
    @GET
    @Path("/transactions")
    @Operation(summary = "查询用户钱包交易流水")
    public TransactionHistory getTransactions(
            @PathParam("userId") Long userId,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize) {

        // 验证用户是否存在
        User user = User.findById(userId);
        if (user == null || user.deletedAt != null) {
            throw new NotFoundException("用户不存在");
        }

        List<WalletTransaction> transactions = walletService.getTransactionHistory(userId, page, pageSize);
        long total = walletService.getTransactionCount(userId);

        List<TransactionItem> items = transactions.stream()
                .map(t -> new TransactionItem(
                        t.id,
                        t.walletId,
                        t.userId,
                        t.changeAmount,
                        t.balanceAfter,
                        t.bizType,
                        t.bizId,
                        t.description,
                        t.createdAt))
                .toList();

        return new TransactionHistory(items, total, Math.max(page, 1), Math.max(pageSize, 1));
    }

    /**
     * 管理员调整积分
     */
    @POST
    @Path("/adjust")
    @Transactional
    @Operation(summary = "管理员调整用户积分")
    public WalletInfo adjustPoints(@PathParam("userId") Long userId, AdjustPointsRequest request) {
        // 验证用户是否存在
        User user = User.findById(userId);
        if (user == null || user.deletedAt != null) {
            throw new NotFoundException("用户不存在");
        }

        if (request.newBalance() == null) {
            throw new BadRequestException("新余额不能为空");
        }

        if (request.newBalance() < 0) {
            throw new BadRequestException("新余额不能为负数");
        }

        try {
            Long newBalance = walletService.adjustPoints(
                    userId,
                    request.newBalance(),
                    request.description() != null ? request.description() : "管理员手动调整"
            );

            UserWallet wallet = walletService.getWalletByUserId(userId);
            return new WalletInfo(
                    wallet.id,
                    wallet.userId,
                    wallet.pointsBalance,
                    wallet.version,
                    wallet.createdAt,
                    wallet.updatedAt);

        } catch (ConcurrentModificationException e) {
            throw new ConflictException("数据已被修改，请稍后重试");
        }
    }
}
