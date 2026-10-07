package com.investclass.ledger.rightscheck;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 配股资格与认购核对（只读视图）。
 *
 * <p>把一笔配股（RIGHTS_OFFER 事件）与它在各业务日派生出来的事实逐行关联：
 * <ul>
 *   <li>登记日资格：投影权益行（应得 eligibleQty）；</li>
 *   <li>认购：配股事件自身 payload（已认 subscribedQty，来自认购指令）；</li>
 *   <li>已扣款：支付日 RIGHTS_PAYMENT 现金行；</li>
 *   <li>已到账：到账日 RIGHTS_OFFER 新成本批次。</li>
 * </ul>
 *
 * <p>本视图不做任何投影写入；支付效应尚未提交时现金行为空（支付前现金不减少），
 * 到账效应尚未提交时批次为空（未到账股份不提前计入持仓），均以 {@code null} 表示。
 *
 * @param unsubscribedQty 未认购量 = 应得 - 已认
 * @param entitlement     登记日资格来源（应得来自投影权益行；资格效应未投影时为 null）
 * @param payment         支付日现金扣款来源（未到支付阶段为 null）
 * @param allotment       到账日新成本批次来源（未到到账阶段为 null）
 */
public record RightsCheckRow(
        long rightsEventId,
        String accountId,
        String instrument,
        LocalDate announceDate,
        LocalDate recordDate,
        LocalDate paymentDate,
        LocalDate allotmentDate,
        BigDecimal rightsPerShare,
        BigDecimal subscriptionPrice,
        String currency,
        String status,
        BigDecimal eligibleQty,
        BigDecimal subscribedQty,
        BigDecimal unsubscribedQty,
        BigDecimal paidAmount,
        BigDecimal allottedQty,
        BigDecimal allottedCost,
        boolean entitlementProjected,
        boolean cashDeducted,
        boolean lotArrived,
        Ref entitlement,
        Ref subscription,
        Ref payment,
        Ref allotment) {

    /** 来源事件定位：业务环节 + 事件/效应/批次键 + 发生日，回答“来自哪个事件”。 */
    public record Ref(String stage, Long eventId, String effectKey,
                      LocalDate effectiveDate, String key, String detail) {

        public static Ref event(String stage, long eventId, String effectKey,
                                LocalDate effectiveDate, String sourceSystem,
                                String sourceKey, String detail) {
            return new Ref(stage, eventId, effectKey, effectiveDate,
                    sourceSystem + "/" + sourceKey, detail);
        }

        public static Ref cash(String stage, long eventId, String effectKey,
                               LocalDate valueDate, String idemKey, String detail) {
            return new Ref(stage, eventId, effectKey, valueDate, idemKey, detail);
        }

        public static Ref lot(String stage, long eventId, String effectKey,
                              LocalDate acquiredDate, String lotKey, String detail) {
            return new Ref(stage, eventId, effectKey, acquiredDate, lotKey, detail);
        }
    }
}
