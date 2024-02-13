package de.telekom.horizon.comet.config.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest.toAnyEndpoint;

/**
 * The {@code WebSecurityConfig} class configures web security for the application.
 * It disables CSRF protection and allows access to all endpoints.
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class WebSecurityConfig {

    /**
     * Creates and configures the {@code SecurityFilterChain} bean for web security.
     *
     * @param http The HttpSecurity object to be configured.
     * @return The configured {@code SecurityFilterChain} bean.
     * @throws Exception If an error occurs while configuring the HttpSecurity object.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests((authorizeRequests ) ->
                        authorizeRequests
                                .requestMatchers(toAnyEndpoint()).permitAll()
                );
        return http.build();
    }

}




