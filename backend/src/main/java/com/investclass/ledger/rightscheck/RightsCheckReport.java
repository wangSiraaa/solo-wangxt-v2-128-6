package com.investclass.ledger.rightscheck;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户配股核对结果。合计直接由逐笔行汇总，前端页面合计与 CSV 导出口径一致。
 */
public record RightsCheckReport(
        String accountId,
        List<RightsCheckRow> rows,
        Totals totals) {

    public record Totals(int offers,
                         BigDecimal eligibleQty,
                         BigDecimal subscribedQty,
                         BigDecimal unsubscribedQty,
                         BigDecimal paidAmount,
                         BigDecimal allottedQty,
                         BigDecimal allottedCost) {

        public static Totals zero() {
            return new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
