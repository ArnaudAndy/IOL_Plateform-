package com.iol.etlplatform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.iol.etlplatform.dto.user.UserDto;
import com.iol.etlplatform.entity.User;
import com.iol.etlplatform.entity.enums.UserRole;
import com.iol.etlplatform.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void listedAccountsAreReportedAsActive() {
        User user = new User();
        user.setId(42L);
        user.setName("Alice Nkom");
        user.setEmail("alice@iol.local");
        user.setRole(UserRole.USER);
        when(userRepository.findAll()).thenReturn(List.of(user));

        UserDto result = userService.getAllUsers().get(0);

        assertEquals("alice@iol.local", result.getEmail());
        assertTrue(result.isActive());
    }
}
