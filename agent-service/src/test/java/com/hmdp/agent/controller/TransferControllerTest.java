package com.hmdp.agent.controller;

import com.hmdp.agent.transfer.TransferService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.9：转人工确认接口（登录态 + 归属校验 + 委托；逻辑在 TransferService 已覆盖） */
@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    @Mock private TransferService transferService;
    @InjectMocks private TransferController controller;

    @BeforeEach
    void login() {
        UserDTO user = new UserDTO();
        user.setId(100L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void 未登录_拒绝() {
        UserHolder.removeUser();
        Result r = controller.confirm(1L);
        assertTrue(!r.getSuccess());
        verify(transferService, never()).confirmTransfer(any(), any());
    }

    @Test
    void 确认成功_返回工单号与SLA() {
        when(transferService.confirmTransfer(100L, 1L)).thenReturn(
                new TransferService.TransferOutcome(true, "已创建工单 TK88", "TK88", "24h"));

        Result r = controller.confirm(1L);

        assertTrue(r.getSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("TK88", data.get("ticketNo"));
        assertEquals("24h", data.get("expectedSla"));
    }

    @Test
    void 服务层失败_返回fail() {
        when(transferService.confirmTransfer(100L, 1L)).thenReturn(
                new TransferService.TransferOutcome(false, "会话未处于转人工状态", null, null));

        Result r = controller.confirm(1L);

        assertTrue(!r.getSuccess());
        assertEquals("会话未处于转人工状态", r.getErrorMsg());
    }
}
