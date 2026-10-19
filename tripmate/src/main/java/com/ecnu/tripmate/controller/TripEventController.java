package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.sse.TripSseService;
import com.ecnu.tripmate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 旅行实时事件订阅接口。
 */
@RestController
@RequestMapping("/trip/{tripId}")
public class TripEventController {
    private final TripSseService events;
    private final UserService users;

    /**
     * 初始化旅行事件控制器。
     *
     * @param events 旅行SSE连接与推送服务
     * @param users 用户服务，用于获取当前登录用户
     */
    public TripEventController(TripSseService events, UserService users) {
        this.events = events;
        this.users = users;
    }

    /**
     * 订阅旅行成员变化事件，建立带登录和成员权限校验的SSE连接。
     *
     * @param tripId 旅行ID
     * @param request 当前HTTP请求，用于获取登录用户
     * @return SSE事件流响应；校验失败时返回对应的HTTP错误状态
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(@PathVariable long tripId, HttpServletRequest request) {
        try {
            User user = users.getLoginUser(request);
            SseEmitter emitter = events.subscribe(tripId, user.getId());
            return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform")
                    .header("X-Accel-Buffering", "no").body(emitter);
        } catch (BusinessException denied) {
            // EventSource通过HTTP状态识别连接错误，不使用普通接口的JSON业务响应结构。
            int status = denied.getCode() == ErrorCode.NOT_LOGIN_ERROR.getCode() ? 401
                    : denied.getCode() == ErrorCode.NO_AUTH_ERROR.getCode() ? 403
                    : denied.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode() ? 404 : 400;
            return ResponseEntity.status(status).build();
        }
    }
}
