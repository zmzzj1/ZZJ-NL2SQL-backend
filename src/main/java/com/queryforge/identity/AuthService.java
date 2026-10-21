package com.queryforge.identity;

import com.queryforge.identity.LoginRequest;
import com.queryforge.identity.RegisterRequest;
import com.queryforge.identity.UserResponse;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.identity.UserRepository;
import com.queryforge.identity.AppUserPrincipal;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 最小身份闭环：注册、密码校验、Sa-Token 登录态建立与注销。
 * 密码只保存 BCrypt 摘要，不写日志、不返回客户端。
 *
 * <p>P12 S2：登录成功后由 {@link SaTokenIdentityService#establish} 在 Redis 建立
 * Sa-Token 会话（token 每次登录随机生成，天然防止会话固定）；注销删除 token 与会话。
 */
@Service
public class AuthService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SaTokenIdentityService identityService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            SaTokenIdentityService identityService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.identityService = identityService;
    }

    public UserResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        validatePassword(request.password());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, HttpStatus.CONFLICT, "该邮箱已注册");
        }
        String displayName = resolveDisplayName(request.displayName(), email);
        Long id;
        try {
            id = userRepository.insert(email, displayName, passwordEncoder.encode(request.password()));
        } catch (UserRepository.DuplicateEmailException ex) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, HttpStatus.CONFLICT, "该邮箱已注册");
        }
        return new UserResponse(id, email, displayName);
    }

    public UserResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email().trim().toLowerCase(Locale.ROOT), request.password()));
        } catch (AuthenticationException ex) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        }

        // 密码校验通过后才建立 Sa-Token 会话；token 随机生成并写入 Redis，响应 cookie 由 Sa-Token 负责。
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        identityService.establish(principal);
        return new UserResponse(principal.id(), principal.email(), principal.displayName());
    }

    public UserResponse currentUser(AppUserPrincipal principal) {
        return new UserResponse(principal.id(), principal.email(), principal.displayName());
    }

    /** 注销：删除 Sa-Token token 与账号会话，旧请求持有的 token 立即失效。 */
    public void logout() {
        identityService.invalidate();
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "密码至少 8 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "密码需同时包含字母和数字");
        }
    }

    private static String resolveDisplayName(String displayName, String email) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
