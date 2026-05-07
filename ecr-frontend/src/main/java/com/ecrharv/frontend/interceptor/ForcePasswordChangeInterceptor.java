package com.ecrharv.frontend.interceptor;

import com.ecrharv.frontend.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class ForcePasswordChangeInterceptor implements HandlerInterceptor {

    private final AppUserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return true;
        }

        // Let the user reach their profile and logout regardless
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/profile") || path.startsWith("/logout")) {
            return true;
        }

        boolean mustChange = userRepository.findByUsername(auth.getName())
                .map(u -> u.isForcePasswordChange())
                .orElse(false);

        if (mustChange) {
            response.sendRedirect(request.getContextPath() + "/profile?mustChange=true");
            return false;
        }

        return true;
    }
}
