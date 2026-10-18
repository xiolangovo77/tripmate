package com.ecnu.tripmate.ai.guardrail;

import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 保存旅行前校验城市，独立于行程生成的 AI 输入护轨。
 */
@Component
public class TripCityValidator {

    private static final int MAX_CITY_LENGTH = 120;

    @Resource
    private InputGuardrailService inputGuardrailService;

    public void validate(String departureLocation, String destination) {
        validateCityName(departureLocation, "出发地");
        validateCityName(destination, "目的地");
        // 仅发送城市字段，使用 JSON 转义，避免换行被解释为新的业务字段。
        String prompt = JSONUtil.createObj()
                .set("departureLocation", departureLocation.strip())
                .set("destination", destination.strip())
                .toString();

        String result;
        try {
            result = inputGuardrailService.tripCityCheck(prompt);
        } catch (RuntimeException exception) {
            BusinessException failure = new BusinessException(
                    ErrorCode.SYSTEM_ERROR, "城市真实性检查服务暂不可用，请稍后重试");
            failure.initCause(exception);
            throw failure;
        }

        String decision = result == null ? "" : result.strip();
        if ("true".equalsIgnoreCase(decision)) {
            return;
        }
        if ("false".equalsIgnoreCase(decision)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "无法确认出发地或目的地城市，请填写真实、明确的城市名称");
        }
        // 未知格式不能视为通过，也不能误报为用户填写了不存在的城市。
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "城市真实性检查结果格式异常，请稍后重试");
    }

    private void validateCityName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, label + "城市不能为空");
        }
        String city = value.strip();
        if (city.length() > MAX_CITY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, label + "城市名称不能超过120个字符");
        }
        if (city.codePoints().anyMatch(Character::isISOControl)
                || "null".equalsIgnoreCase(city) || "暂无".equals(city) || "待定".equals(city)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, label + "必须填写真实、明确的城市名称");
        }
    }
}
