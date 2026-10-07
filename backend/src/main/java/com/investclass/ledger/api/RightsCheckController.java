package com.investclass.ledger.api;

import com.investclass.ledger.rightscheck.RightsCheckReport;
import com.investclass.ledger.rightscheck.RightsCheckService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配股资格与认购核对（只读）。逐笔关联登记日资格、认购事件、支付日现金行与到账日新批次，
 * 返回应得/已认/已扣款/已到账数量金额、未认购量及各自来源事件。不改变任何投影。
 */
@RestController
public class RightsCheckController {

    private final RightsCheckService service;

    public RightsCheckController(RightsCheckService service) {
        this.service = service;
    }

    @GetMapping("/api/accounts/{accountId}/rights-check")
    public RightsCheckReport rightsCheck(@PathVariable String accountId) {
        return service.report(accountId);
    }
}
