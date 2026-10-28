package com.company.meetingroom.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first admin (and the demo user, in the demo profile) if they don't exist yet. */
@Component
@Order(1)
public class AccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties props;

    public AccountBootstrap(UserRepository users, PasswordEncoder passwordEncoder, BootstrapProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!users.existsByRole(Role.ADMIN)) {
            create(props.admin(), Role.ADMIN);
        }
        if (props.demoUser() != null && !users.existsByEmail(User.normalizeEmail(props.demoUser().email()))) {
            create(props.demoUser(), Role.USER);
        }
    }

    private void create(BootstrapProperties.Account account, Role role) {
        users.save(new User(account.email(), passwordEncoder.encode(account.password()), account.fullName(), role));
        log.info("Bootstrap: created {} account {}", role, User.normalizeEmail(account.email()));
    }
}
