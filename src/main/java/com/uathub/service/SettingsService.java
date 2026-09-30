package com.uathub.service;

import com.uathub.domain.AppSetting;
import com.uathub.repo.SettingRepository;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {

    public static final String REQUIRE_PIN = "requirePin";

    private final SettingRepository repo;

    public SettingsService(SettingRepository repo) {
        this.repo = repo;
    }

    public boolean requirePin() {
        return repo.findById(REQUIRE_PIN).map(s -> "true".equals(s.getValue())).orElse(false);
    }

    public void setRequirePin(boolean on) {
        repo.save(new AppSetting(REQUIRE_PIN, String.valueOf(on)));
    }
}
