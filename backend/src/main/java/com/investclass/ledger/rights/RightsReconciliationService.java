package com.investclass.ledger.rights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.MoneyMath;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配股资格与认购核对：把同一配股事件的登记日资格、认购事件、支付日现金行、
 * 到账日新成本批次关联成一行，供学生看清“应得、已认、已扣款、已到账”各自的来源。
 *
 * <p>严格只读：只查询事件账本与投影表，不写任何表、不触发重放，
 * 不改变原投影，也不会把未到账的配股提前计入持仓。
 * 行由登记日资格（projection_entitlement, kind=RIGHTS）驱动——
 * 没有资格的账户不会出现任何可认购额度。</p>
 */
@Service
public class RightsReconciliationService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AccountingProperties props;

    public RightsReconciliationService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                                       AccountingProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    public RightsReconciliation reconcile(String accountId) {
        List<EntitlementJoin> ens = loadRightsEntitlements(accountId);
        List<RightsReconciliation.Row> rows = new ArrayList<>();
        if (!ens.isEmpty()) {
            List<Long> eventIds = ens.stream().map(EntitlementJoin::eventId).toList();
            Map<Long, List<RightsReconciliation.PaymentLine>> payments =
                    paymentsByEvent(accountId, eventIds);
            Map<Long, List<RightsReconciliation.AllotmentLine>> allotments =
                    allotmentsByEvent(accountId, eventIds);
            for (EntitlementJoin en : ens) {
                rows.add(toRow(en,
                        payments.getOrDefault(en.eventId(), List.of()),
                        allotments.getOrDefault(en.eventId(), List.of())));
            }
        }
        return new RightsReconciliation(accountId, rows, totals(rows));
    }

    /** 登记日资格（kind=RIGHTS）关联配股事件本体（认购事件，事实源）。 */
    private List<EntitlementJoin> loadRightsEntitlements(String accountId) {
        return jdbc.query("""
                SELECT en.event_id, en.instrument, en.record_date, en.eligible_qty,
                       en.status, en.idem_key,
                       e.business_date AS ex_date, e.payment_date, e.allotment_date, e.payload
                FROM projection_entitlement en
                JOIN business_event e ON e.id = en.event_id
                WHERE en.account_id = :a AND en.kind = 'RIGHTS'
                ORDER BY en.record_date, en.event_id
                """, new MapSqlParameterSource("a", accountId),
                (rs, n) -> {
                    try {
                        EventPayload.RightsOffer rights = mapper
                                .readValue(rs.getString("payload"), EventPayload.class)
                                .asRights();
                        return new EntitlementJoin(
                                rs.getLong("event_id"), rs.getString("instrument"),
                                rs.getObject("ex_date", LocalDate.class),
                                rs.getObject("record_date", LocalDate.class),
                                rs.getObject("payment_date", LocalDate.class),
                                rs.getObject("allotment_date", LocalDate.class),
                                rs.getBigDecimal("eligible_qty"), rs.getString("status"),
                                rs.getString("idem_key"), rights);
                    } catch (Exception ex) {
                        throw new IllegalStateException("failed to map rights entitlement", ex);
                    }
                });
    }

    /** 支付日现金行（RIGHTS_PAYMENT）：只有支付效应真正入账后才存在。 */
    private Map<Long, List<RightsReconciliation.PaymentLine>> paymentsByEvent(
            String accountId, List<Long> eventIds) {
        List<RightsReconciliation.PaymentLine> lines = jdbc.query("""
                SELECT event_id, effect_key, value_date, direction, amount, idem_key
                FROM projection_cash_entry
                WHERE account_id = :a AND category = 'RIGHTS_PAYMENT'
                  AND event_id IN (:ids)
                ORDER BY value_date, id
                """, new MapSqlParameterSource("a", accountId).addValue("ids", eventIds),
                (rs, n) -> new RightsReconciliation.PaymentLine(
                        rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4),
                        rs.getBigDecimal(5), rs.getString(6)));
        Map<Long, List<RightsReconciliation.PaymentLine>> byEvent = new LinkedHashMap<>();
        for (RightsReconciliation.PaymentLine p : lines) {
            byEvent.computeIfAbsent(p.eventId(), k -> new ArrayList<>()).add(p);
        }
        return byEvent;
    }

    /** 到账日新成本批次（source=RIGHTS_OFFER）：只有到账效应生效后才存在。 */
    private Map<Long, List<RightsReconciliation.AllotmentLine>> allotmentsByEvent(
            String accountId, List<Long> eventIds) {
        List<RightsReconciliation.AllotmentLine> lines = jdbc.query("""
                SELECT lot_key, opening_event_id, acquired_date, open_qty, remaining_qty,
                       unit_cost, total_cost, remaining_cost, fractional
                FROM projection_lot
                WHERE account_id = :a AND source_event_type = 'RIGHTS_OFFER'
                  AND opening_event_id IN (:ids)
                ORDER BY acquired_date, id
                """, new MapSqlParameterSource("a", accountId).addValue("ids", eventIds),
                (rs, n) -> new RightsReconciliation.AllotmentLine(
                        rs.getString(1), rs.getLong(2), rs.getObject(3, LocalDate.class),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                        rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBoolean(9)));
        Map<Long, List<RightsReconciliation.AllotmentLine>> byEvent = new LinkedHashMap<>();
        for (RightsReconciliation.AllotmentLine a : lines) {
            byEvent.computeIfAbsent(a.openingEventId(), k -> new ArrayList<>()).add(a);
        }
        return byEvent;
    }

    private RightsReconciliation.Row toRow(
            EntitlementJoin en,
            List<RightsReconciliation.PaymentLine> payments,
            List<RightsReconciliation.AllotmentLine> allotments) {
        EventPayload.RightsOffer r = en.rights();
        // 已认：以认购事件（事实源）声明的 subscribedQty 为准，与投影进度无关
        BigDecimal subscribed = r.subscribedQty() == null ? BigDecimal.ZERO
                : MoneyMath.shares(r.subscribedQty(), props);
        BigDecimal eligible = MoneyMath.shares(en.eligibleQty(), props);
        BigDecimal unsubscribed = MoneyMath.shares(eligible.subtract(subscribed), props);
        BigDecimal expected = MoneyMath.cash(
                subscribed.multiply(r.subscriptionPrice()), props);
        BigDecimal paid = BigDecimal.ZERO;
        for (RightsReconciliation.PaymentLine p : payments) {
            paid = paid.add(p.amount());
        }
        BigDecimal allottedQty = BigDecimal.ZERO;
        BigDecimal allottedCost = BigDecimal.ZERO;
        for (RightsReconciliation.AllotmentLine a : allotments) {
            allottedQty = allottedQty.add(a.openQty());
            allottedCost = allottedCost.add(a.totalCost());
        }
        return new RightsReconciliation.Row(
                en.eventId(), en.instrument(), en.exDate(), en.recordDate(),
                en.paymentDate(), en.allotmentDate(),
                r.rightsPerShare(), r.subscriptionPrice(), r.currency(),
                eligible, subscribed, unsubscribed, expected,
                en.status(), en.entitlementKey(),
                payments, MoneyMath.cash(paid, props),
                allotments, MoneyMath.shares(allottedQty, props),
                MoneyMath.cash(allottedCost, props));
    }

    private RightsReconciliation.Totals totals(List<RightsReconciliation.Row> rows) {
        BigDecimal eligible = BigDecimal.ZERO;
        BigDecimal subscribed = BigDecimal.ZERO;
        BigDecimal unsubscribed = BigDecimal.ZERO;
        BigDecimal expected = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal allottedQty = BigDecimal.ZERO;
        BigDecimal allottedCost = BigDecimal.ZERO;
        for (RightsReconciliation.Row r : rows) {
            eligible = eligible.add(r.eligibleQty());
            subscribed = subscribed.add(r.subscribedQty());
            unsubscribed = unsubscribed.add(r.unsubscribedQty());
            expected = expected.add(r.expectedPayment());
            paid = paid.add(r.paidAmount());
            allottedQty = allottedQty.add(r.allottedQty());
            allottedCost = allottedCost.add(r.allottedCost());
        }
        return new RightsReconciliation.Totals(
                MoneyMath.shares(eligible, props), MoneyMath.shares(subscribed, props),
                MoneyMath.shares(unsubscribed, props), MoneyMath.cash(expected, props),
                MoneyMath.cash(paid, props), MoneyMath.shares(allottedQty, props),
                MoneyMath.cash(allottedCost, props));
    }

    private record EntitlementJoin(
            long eventId, String instrument, LocalDate exDate, LocalDate recordDate,
            LocalDate paymentDate, LocalDate allotmentDate,
            BigDecimal eligibleQty, String status, String entitlementKey,
            EventPayload.RightsOffer rights) {
    }
}
