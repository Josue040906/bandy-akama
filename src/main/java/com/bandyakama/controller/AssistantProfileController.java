package com.bandyakama.controller;

import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.assistant.AssistantProfileRepository;
import com.bandyakama.security.ToolRegistrationGuard;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant/profile")
public class AssistantProfileController {
    private final AssistantProfileRepository profileRepository;
    private final ToolRegistrationGuard registrationGuard;

    public AssistantProfileController(
            AssistantProfileRepository profileRepository,
            ToolRegistrationGuard registrationGuard
    ) {
        this.profileRepository = profileRepository;
        this.registrationGuard = registrationGuard;
    }

    @GetMapping
    public AssistantProfile getProfile(
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey
    ) {
        registrationGuard.requireAuthorized(suppliedKey);
        return profileRepository.get();
    }

    @PutMapping
    public AssistantProfile updateProfile(
            @RequestHeader(name = "X-Bandy-Registration-Key", required = false)
            String suppliedKey,
            @Valid @RequestBody AssistantProfile profile
    ) {
        registrationGuard.requireAuthorized(suppliedKey);
        return profileRepository.update(profile);
    }
}
