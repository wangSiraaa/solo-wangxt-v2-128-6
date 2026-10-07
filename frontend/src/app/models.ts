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

/** 配股核对：一笔配股在四个业务日的来源定位（环节 + 事件/效应/批次键 + 发生日）。 */
export interface RightsCheckRef {
  stage: string;
  eventId: number | null;
  effectKey: string | null;
  effectiveDate: string | null;
  key: string;
  detail: string;
}

/** 配股资格与认购核对逐笔行（只读，未到支付/到账阶段时对应字段为 null）。 */
export interface RightsCheckRow {
  rightsEventId: number;
  accountId: string;
  instrument: string;
  announceDate: string;
  recordDate: string;
  paymentDate: string | null;
  allotmentDate: string | null;
  rightsPerShare: string;
  subscriptionPrice: string;
  currency: string;
  status: string;
  eligibleQty: string | null;
  subscribedQty: string;
  unsubscribedQty: string | null;
  paidAmount: string | null;
  allottedQty: string | null;
  allottedCost: string | null;
  entitlementProjected: boolean;
  cashDeducted: boolean;
  lotArrived: boolean;
  entitlement: RightsCheckRef | null;
  subscription: RightsCheckRef | null;
  payment: RightsCheckRef | null;
  allotment: RightsCheckRef | null;
}

export interface RightsCheckTotals {
  offers: number;
  eligibleQty: string;
  subscribedQty: string;
  unsubscribedQty: string;
  paidAmount: string;
  allottedQty: string;
  allottedCost: string;
}

export interface RightsCheckReport {
  accountId: string;
  rows: RightsCheckRow[];
  totals: RightsCheckTotals;
}
