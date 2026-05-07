package com.ecrharv.frontend.controller;

import com.ecrharv.frontend.service.EcrApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class MainController {

    private final EcrApiClient apiClient;

    @GetMapping("/")
    public String index(Model model) {
        try {
            model.addAttribute("students", apiClient.getStudents());
        } catch (Exception e) {
            log.warn("Failed to fetch students from ecr-api: {}", e.getMessage());
            model.addAttribute("students", List.of());
            model.addAttribute("apiError", "Could not reach ecr-api. Make sure it is running.");
        }
        return "index";
    }

    @GetMapping("/student/{id}")
    public String student(@PathVariable UUID id, Model model, RedirectAttributes redirectAttrs) {
        try {
            model.addAttribute("student",       apiClient.getStudent(id));
            model.addAttribute("grades",        apiClient.getGrades(id));
            model.addAttribute("messages",      apiClient.getMessages(id));
            model.addAttribute("attendance",    apiClient.getAttendance(id));
            model.addAttribute("announcements", apiClient.getAnnouncements(id));
        } catch (Exception e) {
            log.warn("Failed to load student {}: {}", id, e.getMessage());
            redirectAttrs.addFlashAttribute("apiError", "Could not load student data: " + e.getMessage());
            return "redirect:/";
        }
        return "student";
    }
}
