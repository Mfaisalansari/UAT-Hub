package com.uathub.repo;

import com.uathub.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<AppSetting, String> {
}
