package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.ProjectionService;
import com.investclass.ledger.projection.store.ProjectionReadRepository;
import com.investclass.ledger.rightscheck.RightsCheckReport;
import com.investclass.ledger.rightscheck.RightsCheckRow;
import com.investclass.ledger.rightscheck.RightsCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配股资格与认购核对端到端验收（真实 PG，只读视图）：
 *  - 资格 20 股只认 7 股：剩余 13，扣款与新批次都只对应 7 股；
 *  - 支付前现金不减少、未到账批次不计入持仓；
 *  - 无资格账户不出现可认购额度（应得 0）；
 *  - 合计逐笔汇总（导出与页面同源）。
 */
class RightsCheckIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private ProjectionService projection;
    @Autowired private ProjectionReadRepository read;
    @Autowired private RightsCheckService rightsCheck;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String STK = "CCC";

    private Event buy(String acc, long id, String date, String settle, String qty, String price) {
        return new Event(null, EventType.TRADE, acc, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade("BUY", new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "file", acc + "-T" + id, null, false, null);
    }

    /** 10 配 2（rightsPerShare=0.2），认购价 3.00。 */
    private Event rights(String acc, long id, String announce, String record,
                         String pay, String allot, String subscribed) {
        return new Event(null, EventType.RIGHTS_OFFER, acc, STK, LocalDate.parse(announce),
                null, LocalDate.parse(record), LocalDate.parse(pay), LocalDate.parse(allot),
                new EventPayload.RightsOffer(new BigDecimal("0.2"), new BigDecimal("3.00"),
                        new BigDecimal(subscribed), "CNY"),
                "file", acc + "-R" + id, null, false, null);
    }

    private long insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("file|" + e.canonicalFingerprint());
        return events.insertIfAbsent(e, json, idem).event().id();
    }

    private BigDecimal openPositionQty(String acc, String instrument) {
        return read.lots(acc).stream()
                .filter(l -> l.instrument().equals(instrument) && !l.closed())
                .map(l -> l.fractional() ? BigDecimal.ZERO : l.remainingQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void partialSubscriptionShowsRemainingThirteenAndSevenOnlyCashAndLot() throws Exception {
        String acc = "RC1";
        long tradeId = insert(buy(acc, 1, "2026-07-01", "2026-07-02", "100", "5.00"));
        long rightsId = insert(rights(acc, 2, "2026-07-08", "2026-07-10",
                "2026-07-12", "2026-07-15", "7"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        RightsCheckReport report = rightsCheck.report(acc);
        assertThat(report.rows()).hasSize(1);
        RightsCheckRow row = report.rows().get(0);

        // 应得 20、已认 7、剩余 13
        assertThat(row.rightsEventId()).isEqualTo(rightsId);
        assertThat(row.eligibleQty()).isEqualByComparingTo("20");
        assertThat(row.subscribedQty()).isEqualByComparingTo("7");
        assertThat(row.unsubscribedQty()).isEqualByComparingTo("13");
        assertThat(row.status()).isEqualTo("PARTIALLY_SUBSCRIBED");

        // 扣款与新批次都只对应 7 股
        assertThat(row.cashDeducted()).isTrue();
        assertThat(row.paidAmount()).isEqualByComparingTo("21.00");
        assertThat(row.lotArrived()).isTrue();
        assertThat(row.allottedQty()).isEqualByComparingTo("7");
        assertThat(row.allottedCost()).isEqualByComparingTo("21.00");

        // 四个来源事件都可定位
        assertThat(row.entitlement()).isNotNull();
        assertThat(row.entitlement().effectKey()).isEqualTo("E" + rightsId + ":ENTITLE");
        assertThat(row.subscription()).isNotNull();
        assertThat(row.subscription().key()).contains("RC1-R2");
        assertThat(row.payment()).isNotNull();
        assertThat(row.payment().effectKey()).isEqualTo("E" + rightsId + ":PAY");
        assertThat(row.allotment()).isNotNull();
        assertThat(row.allotment().key()).isEqualTo("L" + rightsId + ":ALLOT");

        // 持仓为 100（原批）+ 7（配股新批）
        assertThat(openPositionQty(acc, STK)).isEqualByComparingTo("107");

        // 合计 = 逐笔（页面/CSV 同源）
        assertThat(report.totals().offers()).isEqualTo(1);
        assertThat(report.totals().eligibleQty()).isEqualByComparingTo("20");
        assertThat(report.totals().subscribedQty()).isEqualByComparingTo("7");
        assertThat(report.totals().unsubscribedQty()).isEqualByComparingTo("13");
        assertThat(report.totals().paidAmount()).isEqualByComparingTo("21.00");
        assertThat(report.totals().allottedQty()).isEqualByComparingTo("7");
        assertThat(report.totals().allottedCost()).isEqualByComparingTo("21.00");
        assertThat(tradeId).isPositive();
    }

    @Test
    void beforePaymentCashNotReducedAndUnallottedSharesNotInPosition() throws Exception {
        String acc = "RC2";
        long buyId = insert(buy(acc, 1, "2026-07-01", "2026-07-02", "100", "5.00"));
        long rightsId = insert(rights(acc, 2, "2026-07-08", "2026-07-10",
                "2026-07-12", "2026-07-15", "7"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        // 完整状态作为对照：扣款 21、余额 -521、持仓 107
        assertThat(read.cashBalance(acc)).isEqualByComparingTo("-521.00");

        // 模拟崩溃在“资格已计算、支付尚未提交”：删除支付现金行、到账批次、支付与到账检查点，
        // 资格回到 CALCULATED，游标退回到登记日资格效应。
        jdbcTemplate.update("DELETE FROM projection_cash_entry WHERE account_id = ? "
                + "AND category = 'RIGHTS_PAYMENT'", acc);
        jdbcTemplate.update("DELETE FROM projection_lot WHERE account_id = ? "
                + "AND lot_key = ?", acc, "L" + rightsId + ":ALLOT");
        jdbcTemplate.update("DELETE FROM projection_checkpoint WHERE account_id = ? "
                + "AND effect_key IN (?, ?)", acc,
                "E" + rightsId + ":PAY", "E" + rightsId + ":ALLOT");
        jdbcTemplate.update("UPDATE projection_entitlement SET status = 'CALCULATED', "
                + "subscribed_qty = 0 WHERE account_id = ? AND event_id = ?", acc, rightsId);
        jdbcTemplate.update("UPDATE projection_cursor SET last_event_id = ?, last_stage = 'RIGHTS_ENTITLE', "
                + "last_effect_key = ? WHERE account_id = ?",
                buyId, "E" + rightsId + ":ENTITLE", acc);

        // 支付前：现金未减少（只有买入 -500）
        assertThat(read.cashBalance(acc)).isEqualByComparingTo("-500.00");
        // 未到账的 7 股没有被提前计入持仓
        assertThat(openPositionQty(acc, STK)).isEqualByComparingTo("100");

        RightsCheckReport before = rightsCheck.report(acc);
        RightsCheckRow row = before.rows().get(0);
        assertThat(row.entitlementProjected()).isTrue();
        assertThat(row.eligibleQty()).isEqualByComparingTo("20");
        assertThat(row.subscribedQty()).isEqualByComparingTo("7");
        assertThat(row.unsubscribedQty()).isEqualByComparingTo("13");
        assertThat(row.cashDeducted()).isFalse();
        assertThat(row.paidAmount()).isNull();
        assertThat(row.payment()).isNull();
        assertThat(row.lotArrived()).isFalse();
        assertThat(row.allottedQty()).isNull();
        assertThat(row.allotment()).isNull();

        // 从一致游标续放：只补出一笔扣款与一个到账批次
        projection.projectTo(acc, events.maxEventId(acc), false);
        assertThat(read.cashBalance(acc)).isEqualByComparingTo("-521.00");
        assertThat(openPositionQty(acc, STK)).isEqualByComparingTo("107");
        List<ProjectionReadRepository.CashRow> payRows = read.cash(acc).stream()
                .filter(c -> c.category().equals("RIGHTS_PAYMENT")).toList();
        assertThat(payRows).hasSize(1);
        assertThat(payRows.get(0).amount()).isEqualByComparingTo("21.00");

        RightsCheckReport after = rightsCheck.report(acc);
        RightsCheckRow afterRow = after.rows().get(0);
        assertThat(afterRow.paidAmount()).isEqualByComparingTo("21.00");
        assertThat(afterRow.allottedQty()).isEqualByComparingTo("7");
    }

    @Test
    void accountWithZeroEligibilityShowsNoSubscribableQuota() throws Exception {
        String acc = "RC3";
        // 登记日 07-10 当天才结算（settle > record），登记日在册 0 股 → 无资格
        insert(buy(acc, 1, "2026-07-09", "2026-07-11", "100", "5.00"));
        insert(rights(acc, 2, "2026-07-08", "2026-07-10",
                "2026-07-12", "2026-07-15", "0"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        RightsCheckReport report = rightsCheck.report(acc);
        assertThat(report.rows()).hasSize(1);
        RightsCheckRow row = report.rows().get(0);
        // 无资格：可认购额度 0，没有扣款、没有新批次
        assertThat(row.eligibleQty()).isEqualByComparingTo("0");
        assertThat(row.unsubscribedQty()).isEqualByComparingTo("0");
        assertThat(row.paidAmount()).isNull();
        assertThat(row.allottedQty()).isNull();
        assertThat(row.status()).isEqualTo("EXPIRED");
        assertThat(row.cashDeducted()).isFalse();
        assertThat(row.lotArrived()).isFalse();
    }

    @Test
    void accountWithoutRightsOffersHasNoRows() throws Exception {
        String acc = "RC4";
        insert(buy(acc, 1, "2026-07-01", "2026-07-02", "100", "5.00"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        RightsCheckReport report = rightsCheck.report(acc);
        assertThat(report.rows()).isEmpty();
        assertThat(report.totals()).isEqualTo(RightsCheckReport.Totals.zero());
    }
}
