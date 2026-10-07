import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RightsCheckRef, RightsCheckReport, RightsCheckRow } from './models';

/**
 * 配股资格与认购核对（只读）：
 *  - 逐笔把“应得(登记日资格) / 已认(认购事件) / 已扣款(支付日现金行) /
 *    已到账(到账日新成本批次)”与来源事件关联，未认购量单列；
 *  - 支付效应尚未提交时已扣款为空（现金未减少），到账效应尚未提交时已到账为空
 *    （未到账股份不提前计入持仓）；
 *  - 逐笔可展开查看四个来源环节的事件/效应/批次键；
 *  - 导出 CSV 合计与页面合计同一份数据。
 */
@Component({
  selector: 'app-rights-check',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <div class="row" style="justify-content:space-between">
        <h2 style="margin:0">配股资格与认购核对（只读 · 应得 / 已认 / 已扣款 / 已到账）</h2>
        <button class="ghost" [disabled]="!report || !report.rows.length"
          (click)="exportCsv()">导出 CSV</button>
      </div>
      <div class="spacer"></div>
      <table>
        <thead>
          <tr>
            <th></th>
            <th>配股事件</th><th>证券</th><th>登记日</th><th>支付日</th><th>到账日</th>
            <th class="num">应得资格</th><th class="num">已认购</th><th class="num">未认购</th>
            <th class="num">已扣款</th><th class="num">已到账股</th><th class="num">新批成本</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let r of rows">
            <tr>
              <td>
                <button class="ghost" style="padding:0 8px"
                  (click)="toggle(r.rightsEventId)">
                  {{ expanded.has(r.rightsEventId) ? '−' : '+' }}
                </button>
              </td>
              <td class="num mono">#{{ r.rightsEventId }}</td>
              <td class="mono">{{ r.instrument }}</td>
              <td>{{ r.recordDate }}</td>
              <td>{{ r.paymentDate || '—' }}</td>
              <td>{{ r.allotmentDate || '—' }}</td>
              <td class="num">
                <span [class.muted]="!r.entitlementProjected">
                  {{ r.eligibleQty ?? '未投影' }}
                </span>
              </td>
              <td class="num">{{ r.subscribedQty }}</td>
              <td class="num" [class.diff-bad]="isPositive(r.unsubscribedQty)">
                {{ r.unsubscribedQty ?? '—' }}
              </td>
              <td class="num" [class.diff-bad]="r.cashDeducted" [class.muted]="!r.cashDeducted">
                {{ r.cashDeducted ? '−' + r.paidAmount : '未扣款' }}
              </td>
              <td class="num" [class.diff-ok]="r.lotArrived" [class.muted]="!r.lotArrived">
                {{ r.allottedQty ?? '未到账' }}
              </td>
              <td class="num" [class.muted]="!r.lotArrived">
                {{ r.lotArrived ? r.allottedCost : '—' }}
              </td>
              <td><span class="badge" [ngClass]="statusClass(r.status)">
                {{ statusLabel(r.status) }}</span></td>
            </tr>
            <tr *ngIf="expanded.has(r.rightsEventId)" class="detail-row">
              <td></td>
              <td colspan="12">
                <table class="detail-table">
                  <thead>
                    <tr>
                      <th>环节</th><th>发生日</th><th>来源事件</th><th>效应键</th>
                      <th>来源键 / 批次键 / 幂等键</th><th>说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td><span class="badge calc">应得 · 登记日资格</span></td>
                      <ng-container *ngIf="r.entitlement; else noEntitle">
                        <ng-container *ngTemplateOutlet="refCells;
                          context: { $implicit: r.entitlement }"></ng-container>
                      </ng-container>
                      <ng-template #noEntitle>
                        <td colspan="5" class="muted">资格效应尚未投影（无应得额度）。</td>
                      </ng-template>
                    </tr>
                    <tr>
                      <td><span class="badge rights">已认 · 认购指令</span></td>
                      <ng-container *ngTemplateOutlet="refCells;
                        context: { $implicit: r.subscription }"></ng-container>
                    </tr>
                    <tr>
                      <td><span class="badge bad">已扣款 · 支付日</span></td>
                      <ng-container *ngIf="r.payment; else noPay">
                        <ng-container *ngTemplateOutlet="refCells;
                          context: { $implicit: r.payment }"></ng-container>
                      </ng-container>
                      <ng-template #noPay>
                        <td colspan="5" class="muted">
                          支付日现金行尚未入账，现金不减少（支付日前 / 崩溃在支付前）。
                        </td>
                      </ng-template>
                    </tr>
                    <tr>
                      <td><span class="badge ok">已到账 · 新成本批次</span></td>
                      <ng-container *ngIf="r.allotment; else noAllot">
                        <ng-container *ngTemplateOutlet="refCells;
                          context: { $implicit: r.allotment }"></ng-container>
                      </ng-container>
                      <ng-template #noAllot>
                        <td colspan="5" class="muted">
                          到账日新批次尚未建立，未到账股份不计入持仓。
                        </td>
                      </ng-template>
                    </tr>
                  </tbody>
                </table>
                <div class="muted mono" style="margin-top:6px">
                  每股权数 {{ r.rightsPerShare }} · 认购价 {{ r.subscriptionPrice }}
                  {{ r.currency }} · 通知日 {{ r.announceDate }}
                </div>
              </td>
            </tr>
          </ng-container>
          <tr *ngIf="!rows.length">
            <td colspan="13" class="muted">该账户没有配股事件（无资格账户不出现可认购额度）。</td>
          </tr>
        </tbody>
        <tfoot *ngIf="report">
          <tr class="totals-row">
            <td></td>
            <td colspan="6">合计（{{ report.totals.offers }} 笔配股）</td>
            <td class="num">{{ report.totals.eligibleQty }}</td>
            <td class="num">{{ report.totals.subscribedQty }}</td>
            <td class="num">{{ report.totals.unsubscribedQty }}</td>
            <td class="num">−{{ report.totals.paidAmount }}</td>
            <td class="num">{{ report.totals.allottedQty }}</td>
            <td class="num">{{ report.totals.allottedCost }}</td>
            <td></td>
          </tr>
        </tfoot>
      </table>

      <ng-template #refCells let-ref>
        <td>{{ ref.effectiveDate || '—' }}</td>
        <td class="num mono" *ngIf="ref.eventId !== null">#{{ ref.eventId }}</td>
        <td class="muted" *ngIf="ref.eventId === null">—</td>
        <td class="mono">{{ ref.effectKey || '—' }}</td>
        <td class="mono muted">{{ ref.key || '—' }}</td>
        <td>{{ ref.detail }}</td>
      </ng-template>
    </div>
  `,
  styles: [`
    .detail-row td { background: var(--panel-2); }
    .detail-table { margin: 4px 0; }
    .totals-row td { font-weight: 600; border-top: 2px solid var(--border); }
  `]
})
export class RightsCheckComponent {
  @Input() report: RightsCheckReport | null = null;

  expanded = new Set<number>();

  get rows(): RightsCheckRow[] {
    return this.report?.rows ?? [];
  }

  toggle(eventId: number): void {
    if (this.expanded.has(eventId)) {
      this.expanded.delete(eventId);
    } else {
      this.expanded.add(eventId);
    }
  }

  isPositive(v: string | null): boolean {
    return v !== null && Number(v) > 0;
  }

  statusLabel(s: string): string {
    return ({
      CALCULATED: '已计算未入账',
      SUBSCRIBED: '已认购',
      PARTIALLY_SUBSCRIBED: '部分认购',
      EXPIRED: '过期未认购',
      PAID: '已支付',
      NOT_PROJECTED: '尚未投影'
    } as Record<string, string>)[s] || s;
  }

  statusClass(s: string): string {
    if (s === 'SUBSCRIBED' || s === 'PAID') return 'ok';
    if (s === 'EXPIRED') return 'bad';
    return 'calc';
  }

  /** 导出当前页面同一份数据：逐笔行 + 合计行，合计与页面完全一致。 */
  exportCsv(): void {
    if (!this.report) {
      return;
    }
    const t = this.report.totals;
    const header = [
      '配股事件', '账户', '证券', '通知日', '登记日', '支付日', '到账日',
      '每股权数', '认购价', '币种', '状态',
      '应得资格', '已认购', '未认购', '已扣款金额',
      '已到账股数', '新批成本',
      '资格效应键', '认购来源键', '扣款幂等键', '到账批次键'
    ];
    const lines = [header.map(csvCell).join(',')];
    for (const r of this.report.rows) {
      lines.push([
        r.rightsEventId, r.accountId, r.instrument, r.announceDate, r.recordDate,
        r.paymentDate ?? '', r.allotmentDate ?? '',
        r.rightsPerShare, r.subscriptionPrice, r.currency,
        this.statusLabel(r.status),
        r.eligibleQty ?? '', r.subscribedQty, r.unsubscribedQty ?? '',
        r.paidAmount ?? '', r.allottedQty ?? '', r.allottedCost ?? '',
        r.entitlement?.effectKey ?? '', r.subscription?.key ?? '',
        r.payment?.key ?? '', r.allotment?.key ?? ''
      ].map(csvCell).join(','));
    }
    lines.push([
      `合计（${t.offers} 笔配股）`, '', '', '', '', '', '', '', '', '', '',
      t.eligibleQty, t.subscribedQty, t.unsubscribedQty, t.paidAmount,
      t.allottedQty, t.allottedCost, '', '', '', ''
    ].map(csvCell).join(','));

    // UTF-8 BOM，Excel 打开中文不乱码
    const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `rights-check-${this.report.accountId}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}

function csvCell(v: unknown): string {
  const s = v === null || v === undefined ? '' : String(v);
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
