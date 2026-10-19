package com.queryforge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.identity.LoginRequest;
import com.queryforge.identity.RegisterRequest;
import com.queryforge.identity.UserResponse;
import com.queryforge.shared.error.ApiException;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.identity.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("身份服务")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private SecurityContextRepository securityContextRepository;

    @InjectMocks
    private AuthService service;

    @Test
    @DisplayName("重复邮箱注册返回 DUPLICATE_EMAIL")
    void register_shouldRejectDuplicateEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("Alice@Example.com", "Alice", "alice12345")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    @DisplayName("注册时哈希密码并使用邮箱前缀作为默认展示名")
    void register_shouldHashPasswordAndDefaultDisplayName() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("alice12345")).thenReturn("bcrypt-hash");
        when(userRepository.insert("alice@example.com", "alice", "bcrypt-hash")).thenReturn(7L);

        UserResponse response = service.register(new RegisterRequest("Alice@Example.com", "", "alice12345"));

        assertThat(response).isEqualTo(new UserResponse(7L, "alice@example.com", "alice"));
        verify(passwordEncoder).encode("alice12345");
    }

    @Test
    @DisplayName("弱密码注册被拒绝")
    void register_shouldRejectWeakPassword() {
        assertThatThrownBy(() -> service.register(new RegisterRequest("alice@example.com", "Alice", "short")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("正确密码创建服务端会话")
    void login_shouldCreateSession_whenCredentialsValid() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "alice@example.com", "Alice", "hash");
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserResponse user = service.login(new LoginRequest("Alice@example.com", "alice12345"), request, response);

        assertThat(user).isEqualTo(new UserResponse(7L, "alice@example.com", "Alice"));
        verify(securityContextRepository).saveContext(any(), any(), any());
    }

    @Test
    @DisplayName("错误密码返回 UNAUTHENTICATED，不区分邮箱是否存在")
    void login_shouldRejectBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(() -> service.login(
                new LoginRequest("alice@example.com", "wrong12345"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
