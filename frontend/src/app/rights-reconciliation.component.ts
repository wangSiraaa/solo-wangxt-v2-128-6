import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RightsReconciliation, RightsReconciliationRow } from './models';

/**
 * 配股资格与认购核对（只读）：
 *  - 每一行是一笔配股事件：应得（登记日资格）、已认（认购事件）、未认购、
 *    已扣款（支付日现金行）、已到账（到账日新成本批次）；
 *  - 逐笔展开可看到每个数字的来源事件/效应键/批次键；
 *  - 支付日前无扣款行（现金不减少），到账日前无新批次（不计入持仓）；
 *  - CSV 导出使用与页面完全相同的数据与合计行。
 */
@Component({
  selector: 'app-rights-reconciliation',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <h2>配股资格与认购核对
        <span class="muted" style="font-weight:400">
          只读：应得/已认来自登记日资格与认购事件，扣款/到账只认投影中已入账的现金行与新批次
        </span>
      </h2>
      <div class="row" style="margin-bottom:10px; align-items:center">
        <button (click)="exportCsv()" [disabled]="!data || !data.rows.length">导出 CSV</button>
        <span class="muted" style="font-size:12px">
          导出含与页面一致的合计行；核对不改动投影，未到账股份不计入持仓。
        </span>
      </div>
      <table>
        <thead>
          <tr>
            <th></th><th>配股事件</th><th>证券</th><th>登记日</th><th>支付日</th><th>到账日</th>
            <th class="num">应得权数</th><th class="num">已认购</th><th class="num">未认购</th>
            <th class="num">应扣款</th><th class="num">已扣款</th>
            <th class="num">到账股数</th><th class="num">到账成本</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let r of data?.rows || []">
            <tr>
              <td>
                <button class="ghost" style="padding:1px 7px"
                  (click)="toggle(r.rightsEventId)">{{ isOpen(r.rightsEventId) ? '▾' : '▸' }}</button>
              </td>
              <td class="num mono">#{{ r.rightsEventId }}</td>
              <td class="mono">{{ r.instrument }}</td>
              <td>{{ r.recordDate }}</td>
              <td>{{ r.paymentDate || '—' }}</td>
              <td>{{ r.allotmentDate || '—' }}</td>
              <td class="num">{{ r.eligibleQty }}</td>
              <td class="num">{{ r.subscribedQty }}</td>
              <td class="num" [class.diff-bad]="!isZero(r.unsubscribedQty)">{{ r.unsubscribedQty }}</td>
              <td class="num">{{ r.expectedPayment }}</td>
              <td class="num" [class.diff-ok]="!isZero(r.paidAmount)">{{ r.paidAmount }}</td>
              <td class="num">{{ r.allottedQty }}</td>
              <td class="num">{{ r.allottedCost }}</td>
              <td><span class="badge" [ngClass]="statusClass(r.status)">{{ statusLabel(r.status) }}</span></td>
            </tr>
            <tr *ngIf="isOpen(r.rightsEventId)">
              <td></td>
              <td colspan="13">
                <div class="rights-detail">
                  <div>
                    <div class="k">登记日资格 · 认购事件（来源：事件 #{{ r.rightsEventId }} /
                      <span class="mono">{{ r.entitlementKey }}</span>）</div>
                    <table>
                      <tbody>
                        <tr><td class="muted">除权日</td><td>{{ r.exDate }}</td></tr>
                        <tr><td class="muted">每股权数 × 认购价</td>
                          <td class="num">{{ r.rightsPerShare }} × {{ r.subscriptionPrice }} {{ r.currency }}</td></tr>
                        <tr><td class="muted">应得 / 已认 / 未认购</td>
                          <td class="num">{{ r.eligibleQty }} / {{ r.subscribedQty }} / {{ r.unsubscribedQty }}</td></tr>
                      </tbody>
                    </table>
                  </div>
                  <div>
                    <div class="k">支付日现金行（已扣款，来源事件 #{{ r.rightsEventId }}）</div>
                    <table>
                      <thead>
                        <tr><th>效应键</th><th>值日</th><th>方向</th><th class="num">金额</th></tr>
                      </thead>
                      <tbody>
                        <tr *ngFor="let p of r.payments">
                          <td class="mono">{{ p.effectKey }}</td>
                          <td>{{ p.valueDate }}</td>
                          <td><span class="badge" [class.bad]="p.direction==='OUT'"
                            [class.ok]="p.direction==='IN'">{{ p.direction === 'OUT' ? '流出' : '流入' }}</span></td>
                          <td class="num">{{ p.amount }}</td>
                        </tr>
                        <tr *ngIf="!r.payments.length">
                          <td colspan="4" class="muted">支付日前/未入账：现金尚未减少。</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <div>
                    <div class="k">到账日新成本批次（已到账，来源事件 #{{ r.rightsEventId }}）</div>
                    <table>
                      <thead>
                        <tr><th>批次键</th><th>到账日</th><th class="num">数量</th>
                          <th class="num">单位成本</th><th class="num">总成本</th></tr>
                      </thead>
                      <tbody>
                        <tr *ngFor="let a of r.allotments">
                          <td class="mono">{{ a.lotKey }}</td>
                          <td>{{ a.acquiredDate }}</td>
                          <td class="num">{{ a.openQty }}</td>
                          <td class="num">{{ a.unitCost }}</td>
                          <td class="num">{{ a.totalCost }}</td>
                        </tr>
                        <tr *ngIf="!r.allotments.length">
                          <td colspan="5" class="muted">未到账：新批次尚未形成，不计入持仓。</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>
              </td>
            </tr>
          </ng-container>
          <tr *ngIf="!data || !data.rows.length">
            <td colspan="14" class="muted">该账户没有配股资格记录（无资格账户不显示可认购额度）。</td>
          </tr>
        </tbody>
        <tfoot *ngIf="data && data.rows.length">
          <tr>
            <td></td>
            <td colspan="5">合计（{{ data.rows.length }} 笔配股）</td>
            <td class="num">{{ data.totals.eligibleQty }}</td>
            <td class="num">{{ data.totals.subscribedQty }}</td>
            <td class="num">{{ data.totals.unsubscribedQty }}</td>
            <td class="num">{{ data.totals.expectedPayment }}</td>
            <td class="num">{{ data.totals.paidAmount }}</td>
            <td class="num">{{ data.totals.allottedQty }}</td>
            <td class="num">{{ data.totals.allottedCost }}</td>
            <td></td>
          </tr>
        </tfoot>
      </table>
    </div>
  `
})
export class RightsReconciliationComponent {
  @Input() data: RightsReconciliation | null = null;

  private expanded = new Set<number>();

  toggle(eventId: number): void {
    if (this.expanded.has(eventId)) {
      this.expanded.delete(eventId);
    } else {
      this.expanded.add(eventId);
    }
  }

  isOpen(eventId: number): boolean {
    return this.expanded.has(eventId);
  }

  isZero(v: string): boolean {
    return Number(v) === 0;
  }

  statusLabel(s: string): string {
    return ({
      CALCULATED: '已计算未入账', PAID: '已支付', EXPIRED: '过期未认购',
      SUBSCRIBED: '已认购', PARTIALLY_SUBSCRIBED: '部分认购'
    } as Record<string, string>)[s] || s;
  }

  statusClass(s: string): string {
    if (s === 'PAID' || s === 'SUBSCRIBED') return 'ok';
    if (s === 'EXPIRED') return 'bad';
    return 'calc';
  }

  /** 导出与页面完全相同的数据：逐笔行 + 同一合计对象，保证导出合计与页面一致。 */
  exportCsv(): void {
    if (!this.data) return;
    const header = ['配股事件', '证券', '除权日', '登记日', '支付日', '到账日',
      '每股权数', '认购价', '应得权数', '已认购', '未认购',
      '应扣款', '已扣款', '到账股数', '到账成本', '状态'];
    const lines: string[] = [header.map(esc).join(',')];
    for (const r of this.data.rows) {
      lines.push([
        '#' + r.rightsEventId, r.instrument, r.exDate, r.recordDate,
        r.paymentDate ?? '', r.allotmentDate ?? '',
        r.rightsPerShare, r.subscriptionPrice,
        r.eligibleQty, r.subscribedQty, r.unsubscribedQty,
        r.expectedPayment, r.paidAmount, r.allottedQty, r.allottedCost,
        this.statusLabel(r.status)
      ].map(esc).join(','));
    }
    const t = this.data.totals;
    lines.push(['合计', '', '', '', '', '', '', '',
      t.eligibleQty, t.subscribedQty, t.unsubscribedQty,
      t.expectedPayment, t.paidAmount, t.allottedQty, t.allottedCost, ''
    ].map(esc).join(','));

    // BOM 让 Excel 按 UTF-8 打开中文不乱码
    const blob = new Blob(['\uFEFF' + lines.join('\r\n')],
      { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `rights-reconciliation-${this.data.accountId}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}

function esc(v: unknown): string {
  const s = v == null ? '' : String(v);
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
