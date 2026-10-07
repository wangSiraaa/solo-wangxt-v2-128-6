/** 不可变业务事件（账本唯一事实源）。 */
export interface BusinessEvent {
  id: number;
  eventType: 'TRADE' | 'STOCK_SPLIT' | 'CASH_DIVIDEND' | 'RIGHTS_OFFER';
  accountId: string;
  instrument: string;
  businessDate: string;
  settlementDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
  allotmentDate: string | null;
  payload: Record<string, unknown>;
  sourceSystem: string;
  sourceKey: string;
  idempotencyKey: string;
  late: boolean;
  ingestedAt: string;
}

/** 成本批次（FIFO），含来源链与零碎股标记。 */
export interface Lot {
  id: number;
  lotKey: string;
  instrument: string;
  openingEventId: number;
  sourceEventType: string;
  acquiredDate: string;
  openQty: string;
  remainingQty: string;
  unitCost: string;
  totalCost: string;
  remainingCost: string;
  fractional: boolean;
  closed: boolean;
  derivedFromLotKey: string | null;
  adjustedByEventId: number | null;
}

export interface CashEntry {
  eventId: number;
  businessDate: string;
  valueDate: string;
  effectKey: string;
  direction: 'IN' | 'OUT';
  amount: string;
  category: string;
  idemKey: string;
}

export interface Entitlement {
  eventId: number;
  instrument: string;
  kind: string;
  recordDate: string;
  paymentDate: string | null;
  eligibleQty: string;
  amountPerShare: string | null;
  grossAmount: string | null;
  status: string;
  subscribedQty: string;
}

/** 配股核对：支付日现金行（已扣款）。 */
export interface RightsPaymentLine {
  eventId: number;
  effectKey: string;
  valueDate: string;
  direction: string;
  amount: string;
  idemKey: string;
}

/** 配股核对：到账日新成本批次（已到账）。 */
export interface RightsAllotmentLine {
  lotKey: string;
  openingEventId: number;
  acquiredDate: string;
  openQty: string;
  remainingQty: string;
  unitCost: string;
  totalCost: string;
  remainingCost: string;
  fractional: boolean;
}

/** 配股核对行：同一配股事件的 应得/已认/未认购/已扣款/已到账 及来源事件。 */
export interface RightsReconciliationRow {
  rightsEventId: number;
  instrument: string;
  exDate: string;
  recordDate: string;
  paymentDate: string | null;
  allotmentDate: string | null;
  rightsPerShare: string;
  subscriptionPrice: string;
  currency: string;
  eligibleQty: string;
  subscribedQty: string;
  unsubscribedQty: string;
  expectedPayment: string;
  status: string;
  entitlementKey: string;
  payments: RightsPaymentLine[];
  paidAmount: string;
  allotments: RightsAllotmentLine[];
  allottedQty: string;
  allottedCost: string;
}

export interface RightsReconciliationTotals {
  eligibleQty: string;
  subscribedQty: string;
  unsubscribedQty: string;
  expectedPayment: string;
  paidAmount: string;
  allottedQty: string;
  allottedCost: string;
}

export interface RightsReconciliation {
  accountId: string;
  rows: RightsReconciliationRow[];
  totals: RightsReconciliationTotals;
}

export interface Checkpoint {
  eventId: number;
  effectKey: string;
  businessDate: string;
  stage: string;
  sharesHash: string;
  cashBalance: string;
  openCost: string;
  realizedPnl: string;
}

export interface Cursor {
  lastEventId: number;
  lastBusinessDate: string | null;
  lastStage: string | null;
  lastEffectKey: string | null;
}

export interface ReconciliationLine {
  instrument: string;
  projectedQty: string;
  projectedFractionalQty: string;
  projectedOpenCost: string;
  externalQty: string;
  externalFractionalQty: string;
  externalCost: string;
  externalCash: string | null;
  qtyMatch: boolean;
  costMatch: boolean;
}

export interface ReconciliationReport {
  accountId: string;
  businessDate: string;
  watermarkEventId: number;
  qtyBalanced: boolean;
  cashBalanced: boolean;
  costBalanced: boolean;
  bookVsExternalBalanced: boolean;
  earliestMismatchEventId: number | null;
  mismatchDetail: string | null;
  allBalanced: boolean;
  positions: ReconciliationLine[];
  projectedCash: string;
  externalCash: string | null;
}
