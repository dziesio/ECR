package com.ecrharv.frontend.controller;

import com.ecrharv.frontend.service.AppUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final AppUserService userService;

    @GetMapping
    public String profile(Authentication auth, Model model) {
        userService.findByUsername(auth.getName())
                .ifPresent(u -> model.addAttribute("mustChange", u.isForcePasswordChange()));
        return "profile";
    }

    @PostMapping("/password")
    public String changePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            Authentication auth,
            RedirectAttributes redirectAttrs
    ) {
        if (!newPassword.equals(confirmPassword)) {
            redirectAttrs.addFlashAttribute("error", "New passwords do not match.");
            return "redirect:/profile";
        }
        if (newPassword.length() < 8) {
            redirectAttrs.addFlashAttribute("error", "New password must be at least 8 characters.");
            return "redirect:/profile";
        }
        try {
            userService.changePassword(auth.getName(), currentPassword, newPassword);
            return "redirect:/";
        } catch (IllegalArgumentException e) {
            redirectAttrs.addFlashAttribute("error", e.getMessage());
            return "redirect:/profile";
        }
    }
}
