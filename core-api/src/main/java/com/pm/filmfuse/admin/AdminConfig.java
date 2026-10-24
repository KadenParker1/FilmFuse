package com.pm.filmfuse.admin;

import com.pm.filmfuse.common.http.RestClients;
import com.pm.filmfuse.reco.ml.MlHealthProbe;
import com.pm.filmfuse.reco.ml.MlProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig {

    /** The admin health panel's ML check: 1 s to connect, {@code filmfuse.admin.ml-health-timeout} to answer. */
    @Bean
    public MlHealthProbe mlHealthProbe(MlProperties mlProperties, AdminProperties adminProperties, Clock clock) {
        return new MlHealthProbe(RestClients.withTimeouts(Duration.ofSeconds(1), adminProperties.mlHealthTimeout()),
                mlProperties, clock);
    }

    /**
     * Run now's thread: one at a time and no queue, so a second Run now while one is executing is
     * refused (409) rather than piling up behind it. Catalog jobs can take tens of minutes.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService adminJobExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "admin-job-run");
            thread.setDaemon(true);
            return thread;
        });
    }
}
