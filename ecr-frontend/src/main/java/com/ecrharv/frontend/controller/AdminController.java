package com.ecrharv.frontend.controller;

import com.ecrharv.frontend.entity.AppUser;
import com.ecrharv.frontend.service.AppUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AppUserService userService;

    @GetMapping("/users")
    public String users(Model model) {
        model.addAttribute("users", userService.listAll());
        return "admin/users";
    }

    @PostMapping("/users")
    public String createUser(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String role,
            RedirectAttributes redirectAttrs
    ) {
        try {
            AppUser.Role userRole = AppUser.Role.valueOf(role.toUpperCase());
            userService.createUser(username, password, userRole);
            redirectAttrs.addFlashAttribute("success",
                    "User '" + username + "' created. They will be required to change their password on first login.");
        } catch (IllegalArgumentException e) {
            redirectAttrs.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(
            @PathVariable UUID id,
            Authentication auth,
            RedirectAttributes redirectAttrs
    ) {
        try {
            userService.deleteUser(id, auth.getName());
            redirectAttrs.addFlashAttribute("success", "User deleted.");
        } catch (IllegalArgumentException e) {
            redirectAttrs.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
