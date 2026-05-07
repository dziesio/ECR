package com.ecrharv.frontend.init;

import com.ecrharv.frontend.entity.AppUser;
import com.ecrharv.frontend.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AppUserRepository userRepository;
    private final PasswordEncoder   passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername("admin")) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode("admin"));
        admin.setRole(AppUser.Role.ADMIN);
        admin.setForcePasswordChange(false);
        userRepository.save(admin);
        log.warn("Default admin user created (admin/admin). Change the password immediately.");
    }
}
