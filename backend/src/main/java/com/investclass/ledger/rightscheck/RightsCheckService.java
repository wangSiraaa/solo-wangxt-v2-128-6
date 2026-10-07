package com.investclass.ledger.rightscheck;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.store.ProjectionReadRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配股资格与认购核对装配（只读）。
 *
 * <p>不改变任何投影：资格读 {@code projection_entitlement}，扣款读
 * {@code projection_cash_entry}（支付日 RIGHTS_PAYMENT），到账读
 * {@code projection_lot}（到账日 RIGHTS_OFFER 新批次，{@code L{事件}:ALLOT}）。
 * 因此：
 * <ul>
 *   <li>支付效应尚未提交（支付日前/崩溃在支付前）→ 现金行不存在，扣款为 null，现金未减少；</li>
 *   <li>到账效应尚未提交 → 新批次不存在，到账为 null，未到账股份不在持仓里；</li>
 *   <li>登记日资格股数为 0 → 可认购额度为 0（无资格账户不出现可认购额度）。</li>
 * </ul>
 */
@Service
public class RightsCheckService {

    private final EventRepository events;
    private final ProjectionReadRepository read;

    public RightsCheckService(EventRepository events, ProjectionReadRepository read) {
        this.events = events;
        this.read = read;
    }

    public RightsCheckReport report(String accountId) {
        List<Event> offers = events.rightsOffers(accountId);

        Map<Long, ProjectionReadRepository.EntitlementRow> entitlements = read.entitlements(accountId)
                .stream()
                .filter(e -> "RIGHTS".equals(e.kind()))
                .collect(Collectors.toMap(
                        ProjectionReadRepository.EntitlementRow::eventId, Function.identity(),
                        (a, b) -> a));
        Map<Long, ProjectionReadRepository.CashRow> rightsCash = read.cash(accountId).stream()
                .filter(c -> "RIGHTS_PAYMENT".equals(c.category()))
                .collect(Collectors.toMap(
                        ProjectionReadRepository.CashRow::eventId, Function.identity(),
                        (a, b) -> a));
        // 到账批次：配股事件开出的独立成本批次，lot_key = L{事件}:ALLOT
        Map<Long, ProjectionReadRepository.LotRow> allotLots = read.lots(accountId).stream()
                .filter(l -> "RIGHTS_OFFER".equals(l.sourceEventType()))
                .filter(l -> l.lotKey().equals("L" + l.openingEventId() + ":ALLOT"))
                .collect(Collectors.toMap(
                        ProjectionReadRepository.LotRow::openingEventId, Function.identity(),
                        (a, b) -> a));

        List<RightsCheckRow> rows = offers.stream()
                .map(e -> toRow(e, entitlements, rightsCash, allotLots))
                .toList();
        return new RightsCheckReport(accountId, rows, totals(rows));
    }

    private RightsCheckRow toRow(Event e,
                                 Map<Long, ProjectionReadRepository.EntitlementRow> entitlements,
                                 Map<Long, ProjectionReadRepository.CashRow> rightsCash,
                                 Map<Long, ProjectionReadRepository.LotRow> allotLots) {
        EventPayload.RightsOffer r = e.payload().asRights();
        BigDecimal subscribed = r.subscribedQty() == null ? BigDecimal.ZERO : r.subscribedQty();
        String entitleEffect = "E" + e.id() + ":ENTITLE";
        String payEffect = "E" + e.id() + ":PAY";
        String allotEffect = "E" + e.id() + ":ALLOT";

        ProjectionReadRepository.EntitlementRow en = entitlements.get(e.id());
        boolean entitlementProjected = en != null;
        BigDecimal eligible = en == null ? null : en.eligibleQty();
        String status = en == null ? "NOT_PROJECTED" : en.status();
        // 未认购 = 应得(登记日资格) - 已认(认购指令，来自不可变事件，不依赖投影阶段)。
        // 支付效应未提交时权益行 subscribed_qty 仍为 0，但认购事实不变，剩余仍为 13。
        BigDecimal unsubscribed = eligible == null ? null
                : eligible.subtract(subscribed);

        ProjectionReadRepository.CashRow cash = rightsCash.get(e.id());
        BigDecimal paidAmount = cash == null ? null : cash.amount();

        ProjectionReadRepository.LotRow lot = allotLots.get(e.id());
        BigDecimal allottedQty = lot == null ? null : lot.openQty();
        BigDecimal allottedCost = lot == null ? null : lot.totalCost();

        RightsCheckRow.Ref entitlementRef = en == null ? null
                : RightsCheckRow.Ref.event("REGISTRY_ENTITLE", e.id(), entitleEffect,
                        en.recordDate(), e.sourceSystem(), e.sourceKey(),
                        "登记日在册资格 " + nz(en.eligibleQty()) + " 股，状态 " + en.status());
        RightsCheckRow.Ref subscriptionRef = RightsCheckRow.Ref.event(
                "SUBSCRIPTION", e.id(), null, e.businessDate(),
                e.sourceSystem(), e.sourceKey(),
                "认购指令：认购 " + nz(subscribed) + " 股 @ " + nz(r.subscriptionPrice()));
        RightsCheckRow.Ref paymentRef = cash == null ? null
                : RightsCheckRow.Ref.cash("PAYMENT", cash.eventId(), cash.effectKey(),
                        cash.valueDate(), cash.idemKey(),
                        "支付日扣款 " + nz(cash.amount()) + "（现金行，类别 RIGHTS_PAYMENT）");
        RightsCheckRow.Ref allotmentRef = lot == null ? null
                : RightsCheckRow.Ref.lot("ALLOTMENT", lot.openingEventId(), allotEffect,
                        lot.acquiredDate(), lot.lotKey(),
                        "到账日新成本批次 " + nz(lot.openQty()) + " 股，成本 " + nz(lot.totalCost()));

        return new RightsCheckRow(e.id(), e.accountId(), e.instrument(), e.businessDate(),
                e.recordDate(), e.paymentDate(), e.allotmentDate(),
                r.rightsPerShare(), r.subscriptionPrice(), r.currency(),
                status, eligible, subscribed, unsubscribed, paidAmount,
                allottedQty, allottedCost,
                entitlementProjected, cash != null, lot != null,
                entitlementRef, subscriptionRef, paymentRef, allotmentRef);
    }

    private RightsCheckReport.Totals totals(List<RightsCheckRow> rows) {
        BigDecimal eligible = BigDecimal.ZERO;
        BigDecimal subscribed = BigDecimal.ZERO;
        BigDecimal unsubscribed = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal allottedQty = BigDecimal.ZERO;
        BigDecimal allottedCost = BigDecimal.ZERO;
        for (RightsCheckRow row : rows) {
            if (row.eligibleQty() != null) {
                eligible = eligible.add(row.eligibleQty());
            }
            subscribed = subscribed.add(nz(row.subscribedQty()));
            if (row.unsubscribedQty() != null) {
                unsubscribed = unsubscribed.add(row.unsubscribedQty());
            }
            if (row.paidAmount() != null) {
                paid = paid.add(row.paidAmount());
            }
            if (row.allottedQty() != null) {
                allottedQty = allottedQty.add(row.allottedQty());
            }
            if (row.allottedCost() != null) {
                allottedCost = allottedCost.add(row.allottedCost());
            }
        }
        return new RightsCheckReport.Totals(rows.size(), eligible, subscribed, unsubscribed,
                paid, allottedQty, allottedCost);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
