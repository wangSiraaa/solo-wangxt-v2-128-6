package com.investclass.ledger.rights;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 配股资格与认购核对（只读视图 DTO）。
 *
 * 每一行把同一配股事件（RIGHTS_OFFER）的四个来源串起来：
 * <ul>
 *   <li>应得：登记日资格（projection_entitlement, kind=RIGHTS）；</li>
 *   <li>已认：认购事件载荷中的 subscribedQty（business_event，事实源）；</li>
 *   <li>已扣款：支付日现金行（projection_cash_entry, RIGHTS_PAYMENT）；</li>
 *   <li>已到账：到账日新成本批次（projection_lot, source=RIGHTS_OFFER）。</li>
 * </ul>
 * 未认购量 = 应得 − 已认。支付日前 payments 为空（现金不减少）；
 * 到账日前 allotments 为空（未到账股份不计入持仓）。
 */
public record RightsReconciliation(
        String accountId,
        List<Row> rows,
        Totals totals) {

    public record Row(
            /** 来源事件：配股事件 id（资格/扣款/到账全部溯源到它）。 */
            long rightsEventId,
            String instrument,
            /** 除权日（配股事件的业务日）。 */
            LocalDate exDate,
            LocalDate recordDate,
            LocalDate paymentDate,
            LocalDate allotmentDate,
            BigDecimal rightsPerShare,
            BigDecimal subscriptionPrice,
            String currency,
            /** 应得：登记日资格权数。 */
            BigDecimal eligibleQty,
            /** 已认：认购事件声明的认购数量。 */
            BigDecimal subscribedQty,
            /** 未认购量 = 应得 − 已认。 */
            BigDecimal unsubscribedQty,
            /** 应扣款 = 已认 × 认购价（现金精度）。 */
            BigDecimal expectedPayment,
            /** 资格状态（CALCULATED/SUBSCRIBED/PARTIALLY_SUBSCRIBED/EXPIRED）。 */
            String status,
            /** 资格行的来源效应键（E{eventId}:ENTITLE）。 */
            String entitlementKey,
            /** 已扣款：支付日现金行（支付日前为空）。 */
            List<PaymentLine> payments,
            BigDecimal paidAmount,
            /** 已到账：到账日新成本批次（到账日前为空）。 */
            List<AllotmentLine> allotments,
            BigDecimal allottedQty,
            BigDecimal allottedCost) {
    }

    /** 支付日现金行（RIGHTS_PAYMENT），含来源效应键与幂等键。 */
    public record PaymentLine(
            long eventId,
            String effectKey,
            LocalDate valueDate,
            String direction,
            BigDecimal amount,
            String idemKey) {
    }

    /** 到账日新增成本批次，溯源到配股事件（openingEventId）。 */
    public record AllotmentLine(
            String lotKey,
            long openingEventId,
            LocalDate acquiredDate,
            BigDecimal openQty,
            BigDecimal remainingQty,
            BigDecimal unitCost,
            BigDecimal totalCost,
            BigDecimal remainingCost,
            boolean fractional) {
    }

    /** 页面合计行；CSV 导出使用同一对象，保证导出合计与页面一致。 */
    public record Totals(
            BigDecimal eligibleQty,
            BigDecimal subscribedQty,
            BigDecimal unsubscribedQty,
            BigDecimal expectedPayment,
            BigDecimal paidAmount,
            BigDecimal allottedQty,
            BigDecimal allottedCost) {
    }
}
