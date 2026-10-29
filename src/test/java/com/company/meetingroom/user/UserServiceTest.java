package com.company.meetingroom.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.user.dto.UpdateUserRequest;

/**
 * Lock-out guards. The integration database always holds the bootstrap admin plus admins from
 * other tests, so "last active admin" is only reachable reliably with a mocked repository.
 */
class UserServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserService service = new UserService(users, mock(PasswordEncoder.class));

    private final AuthUser caller = new AuthUser(1L, "boss@test.local", Role.ADMIN);
    private User otherAdmin;

    @BeforeEach
    void setUp() {
        otherAdmin = withId(new User("other@test.local", "x", "Other Admin", Role.ADMIN), 2L);
        when(users.findById(2L)).thenReturn(Optional.of(otherAdmin));
        when(users.findById(1L)).thenReturn(Optional.of(withId(new User("boss@test.local", "x", "Boss", Role.ADMIN), 1L)));
        when(users.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void lastActiveAdmin_cannotBeDemoted() {
        when(users.lockActiveByRole(Role.ADMIN)).thenReturn(activeAdmins(1));

        assertThatThrownBy(() -> service.update(caller, 2L, new UpdateUserRequest("Other", Role.USER)))
                .satisfies(ex -> assertCode(ex, "last-admin"));
    }

    @Test
    void lastActiveAdmin_cannotBeDeactivated() {
        when(users.lockActiveByRole(Role.ADMIN)).thenReturn(activeAdmins(1));

        assertThatThrownBy(() -> service.changeStatus(caller, 2L, false))
                .satisfies(ex -> assertCode(ex, "last-admin"));
    }

    @Test
    void adminCanBeDemoted_whenAnotherActiveAdminRemains() {
        when(users.lockActiveByRole(Role.ADMIN)).thenReturn(activeAdmins(2));

        assertThat(service.update(caller, 2L, new UpdateUserRequest("Other", Role.USER)).role()).isEqualTo(Role.USER);
    }

    @Test
    void adminCannotDemoteOrDeactivateThemselves_evenWithOtherAdmins() {
        when(users.lockActiveByRole(Role.ADMIN)).thenReturn(activeAdmins(5));

        assertThatThrownBy(() -> service.update(caller, 1L, new UpdateUserRequest("Boss", Role.USER)))
                .satisfies(ex -> assertCode(ex, "self-lockout"));
        assertThatThrownBy(() -> service.changeStatus(caller, 1L, false))
                .satisfies(ex -> assertCode(ex, "self-lockout"));
    }

    private static void assertCode(Throwable ex, String code) {
        assertThat(ex).isInstanceOf(BusinessRuleException.class);
        assertThat(((BusinessRuleException) ex).errorCode()).isEqualTo(code);
    }

    private static List<User> activeAdmins(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> withId(new User("admin" + i + "@test.local", "x", "Admin " + i, Role.ADMIN), i))
                .toList();
    }

    private static User withId(User user, long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
