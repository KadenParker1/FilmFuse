package com.pm.filmfuse.common.config;

import com.pm.filmfuse.user.security.CurrentUserArgumentResolver;
import com.pm.filmfuse.user.service.ProfileService;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProfileService profileService;

    public WebMvcConfig(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(profileService));
    }
}
