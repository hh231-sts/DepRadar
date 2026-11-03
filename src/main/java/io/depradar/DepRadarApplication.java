package io.depradar;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class DepRadarApplication {
    public static void main(String[] args) { SpringApplication.run(DepRadarApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
