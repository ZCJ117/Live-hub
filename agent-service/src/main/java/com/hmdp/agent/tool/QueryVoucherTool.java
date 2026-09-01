package com.hmdp.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.feign.ShopFeignClient;
import com.hmdp.agent.feign.VoucherFeignClient;
import com.hmdp.agent.security.Desensitizer;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 券查询工具（FR-06，T3.8）
 * 规则完整性 rulesComplete=false → LLM 硬约束诚实回答"规则暂未录入"（T3.9/R1，禁止编造）
 * applicableScope 解析店铺ID（≤5 个）附查店名，供"该券还适用于 XX 店"推荐话术
 */
@Component
@AgentToolHost
@Slf4j
@RequiredArgsConstructor
public class QueryVoucherTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VoucherFeignClient voucherFeignClient;
    private final ShopFeignClient shopFeignClient;

    @AgentTool(name = "query_voucher",
            friendlyText = "正在为您查询优惠券信息…",
            description = "查询优惠券详情与使用规则（门槛/适用范围/有效期）。参数：voucherId(必填,券ID)；用户未提供券ID时，先调用 query_my_orders 从其订单中获取 voucherId")
    public ToolResult queryVoucher(ToolContext ctx, Map<String, Object> args) {
        Long voucherId = extractLong(args.get("voucherId"));
        if (voucherId == null) {
            return ToolResult.fail("VOUCHER_ID_REQUIRED",
                    "请先通过 query_my_orders 查询用户订单获取 voucherId，再调用本工具");
        }
        Result result;
        try {
            result = voucherFeignClient.queryVoucherById(voucherId);
        } catch (Exception e) {
            log.warn("voucher-service 调用失败: voucherId={}", voucherId, e);
            return ToolResult.fail("VOUCHER_QUERY_FAIL", "优惠券服务暂时繁忙，请稍后再试");
        }
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
            return ToolResult.fail("VOUCHER_NOT_FOUND", "未找到该优惠券");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) result.getData();

        // 规则完整性（C2：字段缺失=null，禁止默认值编造语义）
        boolean rulesComplete = v.get("threshold") != null
                && v.get("applicableScope") != null
                && v.get("rules") != null && !String.valueOf(v.get("rules")).isBlank();

        Map<String, Object> data = new HashMap<>(v);
        data.put("voucherId", voucherId);
        data.put("rulesComplete", rulesComplete);
        List<Map<String, Object>> applicableShops = parseApplicableShops(v.get("applicableScope"));
        if (!applicableShops.isEmpty()) {
            data.put("applicableShops", applicableShops);
        }

        String summary = Desensitizer.mask("已查到优惠券「" + v.get("title") + "」"
                + (rulesComplete ? "" : "（使用规则暂未录入）"));
        return ToolResult.builder().success(true).data(data).summary(summary).build();
    }

    /** applicableScope 约定为店铺ID列表 JSON（如 "[3,4]"）；解析失败按缺失处理（不编造） */
    private List<Map<String, Object>> parseApplicableShops(Object scope) {
        List<Long> ids = new ArrayList<>();
        try {
            if (scope instanceof List<?> list) {
                for (Object o : list) {
                    Long id = extractLong(o);
                    if (id != null) ids.add(id);
                }
            } else if (scope != null) {
                JsonNode arr = MAPPER.readTree(String.valueOf(scope));
                if (arr.isArray()) {
                    arr.forEach(n -> {
                        Long id = extractLong(n.asText());
                        if (id != null) ids.add(id);
                    });
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        List<Map<String, Object>> shops = new ArrayList<>();
        for (Long id : ids) {
            if (shops.size() >= 5) {
                break;
            }
            try {
                Result r = shopFeignClient.queryShopById(id);
                if (r != null && Boolean.TRUE.equals(r.getSuccess()) && r.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) r.getData();
                    shops.add(Map.of("shopId", id, "shopName", String.valueOf(s.get("name"))));
                }
            } catch (Exception e) {
                log.warn("适用范围店铺名查询失败（忽略）: shopId={}", id);
            }
        }
        return shops;
    }

    private Long extractLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
