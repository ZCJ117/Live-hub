package com.hmdp.agent.flow;

import com.hmdp.agent.confirm.ConfirmTaskService;
import com.hmdp.agent.dto.OrderCardDTO;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTask;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.tool.QueryMyOrdersTool;
import com.hmdp.agent.tool.ToolContext;
import com.hmdp.agent.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 退款流程编排（FR-08 T4.2）
 * REFUND 意图不走通用 ReAct（LLM 文本无法产出卡片参数）：直调 query_my_orders 取结构化订单，
 * 卡片参数只用工具返回的真实数据（P4-R3：LLM 输出的订单号仅作候选）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RefundFlowService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QueryMyOrdersTool queryMyOrdersTool;
    private final ConfirmTaskService confirmTaskService;
    private final TrackEventService trackEventService;
    private final SseSessionManager sseManager;

    /** REFUND 分支主入口（由 ChatOrchestratorService.dispatch 调用；话术经 onDelta 统一流出，勿手动 append answer） */
    public void handle(AgentSession session, ToolContext ctx, Consumer<String> onDelta) {
        Long sessionId = session.getId();
        ToolResult result;
        try {
            result = queryMyOrdersTool.queryMyOrders(ctx, Map.of("size", 5));
        } catch (Exception e) {
            log.warn("退款编排查询订单失败: sessionId={}", sessionId, e);
            result = ToolResult.fail("ORDER_TIMEOUT", "订单服务暂时繁忙");
        }
        if (result == null || !result.isSuccess()) {
            say(onDelta, "订单服务暂时繁忙，请稍后再试；您也可以回复\"转人工\"由客服跟进。");
            return;
        }
        List<OrderCardDTO> cards = result.getData() instanceof List<?> list
                ? list.stream().map(o -> (OrderCardDTO) o).toList()
                : List.of();
        List<OrderCardDTO> eligible = cards.stream()
                .filter(c -> c.getStatusCode() != null && c.getStatusCode() == 2) // 2=已支付（唯一可退状态，FR-08）
                .toList();

        if (eligible.isEmpty()) {
            // PRD 边界：订单已核销/已完成 → 卡片不可生成，说明不可退原因
            say(onDelta, cards.isEmpty()
                    ? "未查询到您的订单记录。仅\"已支付\"状态且未核销的订单支持申请退款。"
                    : "您近期的订单均不是\"已支付\"状态（已核销/已取消/已退款等），不符合退款条件，无法发起退款申请。");
            return;
        }
        if (eligible.size() > 1) {
            // PRD：多单让用户选择 → 复用 ORDER_LIST 卡片（点选后带 context.orderId 下一轮进入）
            say(onDelta, "您有多笔已支付订单，请点选要退款的订单：");
            sseManager.send(sessionId, "card", Map.of(
                    "cardType", "ORDER_LIST",
                    "payload", Map.of("orders", eligible, "total", eligible.size())));
            return;
        }
        OrderCardDTO order = eligible.get(0);
        createOrReuseCard(session, order, onDelta);
    }

    /** 生成或复用该订单的确认卡片（T4.4：同订单重复申请拦截） */
    private void createOrReuseCard(AgentSession session, OrderCardDTO order, Consumer<String> onDelta) {
        Long sessionId = session.getId();
        Optional<AgentTask> existingOpt = confirmTaskService
                .findActiveByOrder(session.getUserId(), order.getOrderId(), null)
                .stream().findFirst();

        if (existingOpt.isPresent()) {
            AgentTask existing = existingOpt.get();
            if (!confirmTaskService.expireIfOverdue(existing)) {
                if ("ADOPTED".equals(existing.getStatus())) {
                    say(onDelta, "该订单已有一笔退款申请，受理编号 RF" + existing.getId()
                            + "，请耐心等待处理，勿重复提交。");
                    return;
                }
                // PENDING_CONFIRM：重发既有卡片（不新建，actionId 不变保证幂等）
                pushConfirmCard(sessionId, existing, order);
                trackEventService.track("m5_refund_card_show", sessionId, session.getUserId(),
                        Map.of("orderId", order.getOrderId(), "actionId", existing.getActionId(), "reused", true));
                say(onDelta, "您有一笔待确认的退款申请（10 分钟内有效），请在卡片上确认提交或取消。");
                return;
            }
            // 已过期 → 落到下方新建分支
        }
        AgentTask task = confirmTaskService.createRefundTask(session, order);
        pushConfirmCard(sessionId, task, order);
        trackEventService.track("m5_refund_card_show", sessionId, session.getUserId(),
                Map.of("orderId", order.getOrderId(), "actionId", task.getActionId()));
        say(onDelta, "已为您生成退款申请（10 分钟内有效）。请在卡片上确认提交或取消，"
                + "预计 1-3 个工作日原路退回。");
    }

    private void pushConfirmCard(Long sessionId, AgentTask task, OrderCardDTO order) {
        sseManager.send(sessionId, "card", Map.of(
                "cardType", "REFUND_CONFIRM",
                "payload", Map.of(
                        "actionId", task.getActionId(),
                        "order", Map.of(
                                "orderId", String.valueOf(order.getOrderId()),
                                "voucherTitle", order.getVoucherTitle() == null ? "" : order.getVoucherTitle(),
                                "payValue", order.getPayValue() == null ? 0 : order.getPayValue(),
                                "createTime", order.getCreateTime() == null ? "" : order.getCreateTime().format(TIME_FMT),
                                "statusText", order.getStatusText()),
                        "reasons", ConfirmTaskService.REFUND_REASONS,
                        "expireMinutes", 10,
                        "notice", "预计 1-3 个工作日原路退回")));
    }

    /** 话术经 onDelta 统一流出（delta 事件 + answer 累积 + 首 token 埋点），勿手动 append answer */
    private void say(Consumer<String> onDelta, String text) {
        onDelta.accept(text);
    }
}
