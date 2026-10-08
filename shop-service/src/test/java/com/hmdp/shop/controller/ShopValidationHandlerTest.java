package com.hmdp.shop.controller;

import com.hmdp.dto.Result;
import com.hmdp.shop.config.ShopValidationExceptionHandler;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopValidationHandlerTest {

    @Test
    void 约束违例映射为HTTP400() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("页码必须大于等于 1");
        ConstraintViolationException e = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<Result> response = new ShopValidationExceptionHandler().handleConstraintViolation(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().getSuccess());
        assertEquals("页码必须大于等于 1", response.getBody().getErrorMsg());
    }

    @Test
    void 控制器已声明参数校验() throws Exception {
        assertTrue(ShopController.class.isAnnotationPresent(Validated.class),
                "ShopController 必须标注 @Validated 才会触发方法参数校验");
        Method m = ShopController.class.getMethod("queryShopByType",
                Integer.class, Integer.class, Double.class, Double.class);
        Annotation[] onCurrent = m.getParameterAnnotations()[1];
        assertTrue(Arrays.stream(onCurrent).anyMatch(a -> a instanceof Min && ((Min) a).value() == 1),
                "queryShopByType 的 current 参数必须有 @Min(1)");
    }
}
