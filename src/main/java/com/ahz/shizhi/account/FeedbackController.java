package com.ahz.shizhi.account;

import com.ahz.shizhi.account.dto.FeedbackRequest;
import com.ahz.shizhi.auth.CurrentUser;
import com.ahz.shizhi.auth.UserPrincipal;
import com.ahz.shizhi.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final JdbcTemplate jdbc;

    public FeedbackController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public ApiResponse<Void> submit(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                    @Valid @RequestBody FeedbackRequest request) {
        jdbc.update("INSERT INTO user_feedback (user_id, content, contact, page) VALUES (?, ?, ?, ?)",
                principal.userId(),
                request.content().strip(),
                blankToNull(request.contact()),
                blankToNull(request.page()));
        return ApiResponse.ok(null);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
