package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.ProjectionService;
import com.investclass.ledger.projection.store.ProjectionReadRepository;
import com.investclass.ledger.rights.RightsReconciliation;
import com.investclass.ledger.rights.RightsReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配股资格与认购核对端到端验收（真实 PG，样例场景 3 的口径）：
 *  - 资格 20 股只认购 7 股：未认购 13，扣款与新批次都只对应 7 股；
 *  - 支付日现金未入账前：核对页无扣款行、现金不减少、未到账股份不计入持仓；
 *  - 核对只读：调用前后投影表逐行一致；
 *  - 无资格账户不出现可认购额度。
 */
class RightsReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private ProjectionService projection;
    @Autowired private ProjectionReadRepository read;
    @Autowired private RightsReconciliationService rightsRecon;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String STK = "CCC";

    private Event buy(String account, String key, String qty, String price) {
        return new Event(null, EventType.TRADE, account, STK,
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"),
                null, null, null,
                new EventPayload.Trade("BUY", new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "rights-test", key, null, false, null);
    }

    /** 10 配 2（每股 0.2 权），认购价 3.00，只认购 7；登记 07-10、支付 07-12、到账 07-15。 */
    private Event rights(String account, String key, String subscribedQty) {
        return new Event(null, EventType.RIGHTS_OFFER, account, STK,
                LocalDate.parse("2026-07-08"), null,
                LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-12"),
                LocalDate.parse("2026-07-15"),
                new EventPayload.RightsOffer(new BigDecimal("0.2"), new BigDecimal("3.00"),
                        new BigDecimal(subscribedQty), "CNY"),
                "rights-test", key, null, false, null);
    }

    private long insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("rights-test|" + e.canonicalFingerprint());
        return events.insertIfAbsent(e, json, idem).event().id();
    }

    @Test
    void partialSubscriptionReconcilesEntitlementPaymentAndAllotment() throws Exception {
        String acc = "RC1";
        insert(buy(acc, "RC1-T1", "100", "5.00"));
        long rightsId = insert(rights(acc, "RC1-R1", "7"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        RightsReconciliation rc = rightsRecon.reconcile(acc);
        assertThat(rc.accountId()).isEqualTo(acc);
        assertThat(rc.rows()).hasSize(1);

        RightsReconciliation.Row row = rc.rows().get(0);
        assertThat(row.rightsEventId()).isEqualTo(rightsId);
        assertThat(row.instrument()).isEqualTo(STK);
        assertThat(row.recordDate()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(row.paymentDate()).isEqualTo(LocalDate.parse("2026-07-12"));
        assertThat(row.allotmentDate()).isEqualTo(LocalDate.parse("2026-07-15"));
        // 应得 20、已认 7、未认购 13
        assertThat(row.eligibleQty()).isEqualByComparingTo("20");
        assertThat(row.subscribedQty()).isEqualByComparingTo("7");
        assertThat(row.unsubscribedQty()).isEqualByComparingTo("13");
        assertThat(row.status()).isEqualTo("PARTIALLY_SUBSCRIBED");
        assertThat(row.entitlementKey()).isEqualTo("E" + rightsId + ":ENTITLE");

        // 扣款只对应 7 股：21.00 = 7 × 3.00，值日为支付日
        assertThat(row.expectedPayment()).isEqualByComparingTo("21.00");
        assertThat(row.payments()).hasSize(1);
        RightsReconciliation.PaymentLine pay = row.payments().get(0);
        assertThat(pay.eventId()).isEqualTo(rightsId);
        assertThat(pay.effectKey()).isEqualTo("E" + rightsId + ":PAY");
        assertThat(pay.valueDate()).isEqualTo(LocalDate.parse("2026-07-12"));
        assertThat(pay.direction()).isEqualTo("OUT");
        assertThat(pay.amount()).isEqualByComparingTo("21.00");
        assertThat(row.paidAmount()).isEqualByComparingTo("21.00");

        // 到账新批次只对应 7 股：独立成本批次，溯源到配股事件
        assertThat(row.allotments()).hasSize(1);
        RightsReconciliation.AllotmentLine lot = row.allotments().get(0);
        assertThat(lot.lotKey()).isEqualTo("L" + rightsId + ":ALLOT");
        assertThat(lot.openingEventId()).isEqualTo(rightsId);
        assertThat(lot.acquiredDate()).isEqualTo(LocalDate.parse("2026-07-15"));
        assertThat(lot.openQty()).isEqualByComparingTo("7");
        assertThat(lot.unitCost()).isEqualByComparingTo("3.00");
        assertThat(lot.totalCost()).isEqualByComparingTo("21.00");
        assertThat(row.allottedQty()).isEqualByComparingTo("7");
        assertThat(row.allottedCost()).isEqualByComparingTo("21.00");

        // 合计 = 逐行之和（导出合计与页面一致的口径）
        assertThat(rc.totals().eligibleQty()).isEqualByComparingTo("20");
        assertThat(rc.totals().subscribedQty()).isEqualByComparingTo("7");
        assertThat(rc.totals().unsubscribedQty()).isEqualByComparingTo("13");
        assertThat(rc.totals().expectedPayment()).isEqualByComparingTo("21.00");
        assertThat(rc.totals().paidAmount()).isEqualByComparingTo("21.00");
        assertThat(rc.totals().allottedQty()).isEqualByComparingTo("7");
        assertThat(rc.totals().allottedCost()).isEqualByComparingTo("21.00");
    }

    @Test
    void beforePaymentCashNotReducedAndReconcileIsReadOnly() throws Exception {
        String acc = "RC2";
        insert(buy(acc, "RC2-T1", "100", "5.00"));
        long rightsId = insert(rights(acc, "RC2-R1", "7"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        // 模拟“资格已计算、支付日现金尚未入账、批次未到账”：
        // 移除支付现金行与到账批次及对应检查点，资格回到 CALCULATED
        jdbcUpdate(("DELETE FROM projection_cash_entry WHERE account_id='%s' "
                + "AND category='RIGHTS_PAYMENT'").formatted(acc));
        jdbcUpdate(("DELETE FROM projection_lot WHERE account_id='%s' "
                + "AND source_event_type='RIGHTS_OFFER'").formatted(acc));
        jdbcUpdate(("DELETE FROM projection_checkpoint WHERE account_id='%s' "
                + "AND effect_key IN ('E%d:PAY','E%d:ALLOT')").formatted(acc, rightsId, rightsId));
        jdbcUpdate(("UPDATE projection_entitlement SET status='CALCULATED', subscribed_qty=0 "
                + "WHERE account_id='%s' AND event_id=%d").formatted(acc, rightsId));

        // 核对前快照投影表（只读断言用）
        List<ProjectionReadRepository.LotRow> lotsBefore = read.lots(acc);
        List<ProjectionReadRepository.CashRow> cashBefore = read.cash(acc);
        List<ProjectionReadRepository.EntitlementRow> entBefore = read.entitlements(acc);

        RightsReconciliation rc = rightsRecon.reconcile(acc);
        assertThat(rc.rows()).hasSize(1);
        RightsReconciliation.Row row = rc.rows().get(0);
        // 应得/已认/未认购仍完整呈现（已认来自认购事件，不依赖支付进度）
        assertThat(row.eligibleQty()).isEqualByComparingTo("20");
        assertThat(row.subscribedQty()).isEqualByComparingTo("7");
        assertThat(row.unsubscribedQty()).isEqualByComparingTo("13");
        assertThat(row.expectedPayment()).isEqualByComparingTo("21.00");
        // 支付前：无扣款行、无到账批次
        assertThat(row.payments()).isEmpty();
        assertThat(row.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(row.allotments()).isEmpty();
        assertThat(row.allottedQty()).isEqualByComparingTo("0");
        assertThat(row.allottedCost()).isEqualByComparingTo("0.00");

        // 支付前现金不减少：只有买入流出的 500.00
        assertThat(read.cashBalance(acc)).isEqualByComparingTo("-500.00");
        // 未到账股份不计入持仓：仍只有买入批次的 100 股
        BigDecimal totalQty = read.lots(acc).stream()
                .map(ProjectionReadRepository.LotRow::remainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQty).isEqualByComparingTo("100");

        // 只读：核对前后投影表逐行一致
        assertThat(read.lots(acc)).usingRecursiveComparison().isEqualTo(lotsBefore);
        assertThat(read.cash(acc)).usingRecursiveComparison().isEqualTo(cashBefore);
        assertThat(read.entitlements(acc)).usingRecursiveComparison().isEqualTo(entBefore);
    }

    @Test
    void accountWithoutEntitlementSeesNoSubscribableQuota() throws Exception {
        String acc = "RC3";
        // 只有成交、没有任何配股资格的账户
        insert(buy(acc, "RC3-T1", "50", "4.00"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        RightsReconciliation rc = rightsRecon.reconcile(acc);
        assertThat(rc.rows()).isEmpty();
        assertThat(rc.totals().eligibleQty()).isEqualByComparingTo("0");
        assertThat(rc.totals().subscribedQty()).isEqualByComparingTo("0");
        assertThat(rc.totals().unsubscribedQty()).isEqualByComparingTo("0");
        assertThat(rc.totals().expectedPayment()).isEqualByComparingTo("0.00");
        assertThat(rc.totals().paidAmount()).isEqualByComparingTo("0.00");
        assertThat(rc.totals().allottedQty()).isEqualByComparingTo("0");
        assertThat(rc.totals().allottedCost()).isEqualByComparingTo("0.00");
    }

    private void jdbcUpdate(String sql) {
        jdbcTemplate.execute(sql);
    }
}
