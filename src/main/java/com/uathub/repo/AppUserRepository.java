package com.uathub.repo;

import com.uathub.domain.AppUser;
import com.uathub.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByAccessToken(String accessToken);
    List<AppUser> findAllByOrderByActiveDescNameAsc();
    List<AppUser> findByRoleAndActiveTrue(Role role);
}
