package com.hmdp.agent.confirm;

import com.hmdp.agent.dto.ConfirmRequest;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTask;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.feign.OrderFeignClient;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.ticket.TicketPriorityRules;
import com.hmdp.agent.ticket.TicketService;
import com.hmdp.dto.RefundMessages;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 退款确认编排（FR-08 T4.3/T4.4/T4.5）
 * 双闸门第一道：归属/跨会话/懒过期/幂等抢确认；第二道在 order-service（原子 UPDATE 最终裁决）
 * 不引入 Seata（D-4）：退款受理为事实源，建单失败重试 1 次 + 对账脚本补偿
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConfirmService {

    private final AgentSessionService sessionService;
    private final ConfirmTaskService confirmTaskService;
    private final OrderFeignClient orderFeignClient;
    private final TicketService ticketService;
    private final FlowStateService flowStateService;
    private final TicketPriorityRules priorityRules;
    private final TrackEventService trackEventService;

    /** 同步结果（ConfirmController 转 Result.ok/fail 输出） */
    public record ConfirmOutcome(boolean success, String message,
                                 String refundNo, String ticketNo, String expectedSla) {
    }

    /** m5_refund_confirm 埋点（T5.5：通过/失败原因）统一出口，业务逻辑在 doConfirm */
    public ConfirmOutcome confirm(Long userId, Long sessionId, ConfirmRequest req) {
        ConfirmOutcome o = doConfirm(userId, sessionId, req);
        trackEventService.track("m5_refund_confirm", sessionId, userId,
                Map.of("passed", o.success(), "reason", String.valueOf(o.message())));
        return o;
    }

    private ConfirmOutcome doConfirm(Long userId, Long sessionId, ConfirmRequest req) {
        sessionService.getOwned(sessionId, userId); // 1. 会话归属（4.2 强绑定）

        // 2. actionId 查任务（userId 强制过滤 → 篡改/越权即拦截）
        Optional<AgentTask> found = confirmTaskService.findByActionId(userId, req.getActionId());
        if (found.isEmpty()) {
            return new ConfirmOutcome(false, "确认请求无效或已失效", null, null, null);
        }
        AgentTask task = found.get();
        if (!sessionId.equals(task.getSessionId())) {
            return new ConfirmOutcome(false, "确认请求无效或已失效", null, null, null);
        }

        // 3. 懒过期（10 分钟，CONFIRM/CANCEL 一视同仁）
        if (confirmTaskService.expireIfOverdue(task)) {
            return new ConfirmOutcome(false, "确认卡片已过期（10 分钟有效），请重新发起退款申请", null, null, null);
        }

        // 4. CANCEL：卡片收起，对话继续（不建单不留待办）
        if ("CANCEL".equalsIgnoreCase(req.getDecision())) {
            if ("ADOPTED".equals(task.getStatus())) {
                // 已受理不可取消：不能返回虚假成功让用户误以为已取消
                return new ConfirmOutcome(false, "退款已受理，不可取消，受理编号 RF" + task.getId(), null, null, null);
            }
            if ("PENDING_CONFIRM".equals(task.getStatus())) {
                confirmTaskService.reject(task);
                flowStateService.resetRefundingIfNeeded(sessionId);
                return new ConfirmOutcome(true, "已取消退款申请", null, null, null);
            }
            return new ConfirmOutcome(false, "该卡片已作废，请重新发起", null, null, null);
        }
        if (!"CONFIRM".equalsIgnoreCase(req.getDecision())) {
            return new ConfirmOutcome(false, "decision 仅支持 CONFIRM/CANCEL", null, null, null);
        }

        // 5. 已 ADOPTED → 幂等返回相同受理编号
        if ("ADOPTED".equals(task.getStatus())) {
            return adoptedOutcome(task, "该退款申请已受理，请勿重复提交");
        }
        if (!"PENDING_CONFIRM".equals(task.getStatus())) {
            return new ConfirmOutcome(false, "该卡片已作废（" + task.getStatus() + "），请重新发起", null, null, null);
        }

        // 6. 同订单其他进行中申请 → 提示已有编号（T4.4）
        var others = confirmTaskService.findActiveByOrder(userId, task.getBizOrderId(), task.getId());
        if (!others.isEmpty()) {
            AgentTask other = others.get(0);
            if ("ADOPTED".equals(other.getStatus())) {
                return adoptedOutcome(other, "该订单已有一笔退款申请，受理编号 RF" + other.getId());
            }
            return new ConfirmOutcome(false, "该订单已有一笔待确认的退款申请，请先在原卡片上确认或取消", null, null, null);
        }

        // 7. 条件更新抢确认（幂等核心：并发仅 1 胜出）
        if (!confirmTaskService.tryAdopt(task.getActionId())) {
            Optional<AgentTask> latest = confirmTaskService.findByActionId(userId, req.getActionId());
            if (latest.isPresent() && "ADOPTED".equals(latest.get().getStatus())) {
                return adoptedOutcome(latest.get(), "该退款申请已受理，请勿重复提交");
            }
            return new ConfirmOutcome(false, "确认冲突，请重试", null, null, null);
        }

        // 8. 第二道闸门：order-service 原子退款（最终裁决）
        String confirmReason = buildReason(req);
        Result refundResult;
        try {
            refundResult = orderFeignClient.refund(Map.of(
                    "orderId", task.getBizOrderId(),
                    "reason", confirmReason == null ? "" : confirmReason));
        } catch (Exception e) {
            log.error("退款 Feign 调用失败: taskId={}", task.getId(), e);
            // 不确定态：order 可能已受理（对账兜底），T4.15 用 sql/phase4-reconciliation.sql 核对
            refundResult = Result.fail("退款服务暂时繁忙，请稍后重试或转人工");
        }
        if (refundResult == null || !Boolean.TRUE.equals(refundResult.getSuccess())) {
            String reason = refundResult == null ? "退款服务无响应" : refundResult.getErrorMsg();
            // "已在退款中" → 定位既有受理（对账兜底场景）
            if (reason != null && reason.contains(RefundMessages.ALREADY_PENDING)) {
                Optional<AgentTask> adopted = confirmTaskService.findAdoptedByOrder(userId, task.getBizOrderId());
                if (adopted.isPresent()) {
                    return adoptedOutcome(adopted.get(), "该订单已有一笔退款申请");
                }
            }
            // 不确定态：order 可能已受理（对账兜底），T4.15 用 sql/phase4-reconciliation.sql 核对
            confirmTaskService.rejectAfterAdopt(task); // 卡片作废
            return new ConfirmOutcome(false, reason, null, null, null);
        }

        // 9. 退款受理成功 → 受理编号 + 联动复核工单（T4.5）
        String refundNo = "RF" + task.getId();
        String ticketNo = null;
        String sla = null;
        String linkNote = "";
        try {
            AgentTicket ticket = createTicketWithRetry(task, confirmReason);
            confirmTaskService.bindTicket(task.getId(), ticket.getId());
            ticketNo = ticket.getTicketNo();
            sla = ticket.getExpectedSla();
        } catch (Exception e) {
            // D-4：退款已受理是事实源；建单失败重试后仍失败 → 对账脚本补偿
            log.error("复核工单创建失败（已重试）: taskId={}", task.getId(), e);
            linkNote = "；复核工单登记失败，将由人工补录（受理编号已生效）";
        }
        flowStateService.resetRefundingIfNeeded(sessionId);
        String msg = "退款申请已受理，受理编号 " + refundNo + "，预计 1-3 个工作日原路退回"
                + (ticketNo == null ? linkNote : "，复核工单 " + ticketNo + "（预计 " + sla + " 内处理）");
        return new ConfirmOutcome(true, msg, refundNo, ticketNo, sla);
    }

    private ConfirmOutcome adoptedOutcome(AgentTask adopted, String prefix) {
        String ticketNo = null;
        String sla = null;
        if (adopted.getTicketId() != null) {
            try {
                AgentTicket t = ticketService.getById(adopted.getTicketId());
                if (t != null) {
                    ticketNo = t.getTicketNo();
                    sla = t.getExpectedSla();
                }
            } catch (Exception ignored) {
            }
        }
        return new ConfirmOutcome(true, prefix, "RF" + adopted.getId(), ticketNo, sla);
    }

    private AgentTicket createTicketWithRetry(AgentTask task, String confirmReason) {
        RuntimeException last = null;
        for (int i = 0; i < 2; i++) {
            try {
                Map<String, Object> refs = new HashMap<>();
                refs.put("orderId", task.getBizOrderId());
                String demand = confirmReason == null ? "退款申请" : confirmReason;
                String summary = "用户通过客服Agent申请退款：订单" + task.getBizOrderId()
                        + "，原因：" + demand + "。系统已受理（编号RF" + task.getId() + "），请人工复核执行退款。";
                if (summary.length() > 200) {
                    summary = summary.substring(0, 200);
                }
                return ticketService.create(task.getUserId(), task.getSessionId(),
                        TicketRequest.of("ORDER", priorityRules.fundRelated(demand) ? "HIGH" : "MEDIUM", summary, refs));
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private String buildReason(ConfirmRequest req) {
        String reason = req.getReason();
        String text = req.getReasonText();
        if (reason == null && text == null) {
            return null;
        }
        if (text != null && !text.isBlank()) {
            return (reason == null ? "其他" : reason) + "：" + text;
        }
        return reason;
    }
}
