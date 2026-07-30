package systems.hugmesh.wikibff.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;

/**
 * R2DBC type-conversion shims. Copied from the forum-bff / user-bff / federation-api
 * {@code R2dbcConvertersConfig}: the Postgres driver returns {@code OffsetDateTime}
 * / {@code LocalDateTime} for {@code timestamptz} / {@code timestamp} columns while
 * the entities expect {@code Instant}. Spring Data's reflective JSR-310 fallbacks
 * are skipped under GraalVM native-image, so registering the two reading converters
 * explicitly makes the conversion deterministic in both JVM and native modes (and
 * on the H2 slice-test DB, which also hands back {@code LocalDateTime} for
 * {@code timestamp}).
 */
@Configuration(proxyBeanMethods = false)
public class R2dbcConvertersConfig {

    /**
     * Replaces the auto-configured {@link R2dbcCustomConversions} bean
     * (see {@link DataR2dbcAutoConfiguration}) with the two timestamp converters
     * added on top of the dialect defaults.
     *
     * @param connectionFactory the active R2DBC {@link ConnectionFactory} used to resolve
     *                          the dialect and its built-in converter list
     * @return an {@link R2dbcCustomConversions} containing the dialect's default converters
     *         plus {@link OffsetDateTimeToInstantConverter} and
     *         {@link LocalDateTimeToInstantConverter}
     */
    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory connectionFactory) {
        R2dbcDialect dialect = DialectResolver.getDialect(connectionFactory);
        var converters = new ArrayList<>(dialect.getConverters());
        converters.add(new OffsetDateTimeToInstantConverter());
        converters.add(new LocalDateTimeToInstantConverter());
        return R2dbcCustomConversions.of(dialect, converters);
    }

    /**
     * Reading converter that maps an {@link OffsetDateTime} value (as returned by the
     * Postgres R2DBC driver for {@code timestamptz} columns) to a UTC {@link Instant}.
     */
    @ReadingConverter
    static class OffsetDateTimeToInstantConverter implements Converter<OffsetDateTime, Instant> {

        /**
         * Converts an {@link OffsetDateTime} to an {@link Instant} by discarding the
         * offset and normalising to the UTC epoch.
         *
         * @param source the {@link OffsetDateTime} value from the database column
         * @return the equivalent {@link Instant} on the UTC timeline
         */
        @Override
        public Instant convert(OffsetDateTime source) {
            return source.toInstant();
        }
    }

    /**
     * Reading converter that maps a {@link LocalDateTime} value (as returned by the
     * Postgres R2DBC driver for bare {@code timestamp} columns, and by H2 in slice tests)
     * to a UTC {@link Instant} by assuming the value is in UTC.
     */
    @ReadingConverter
    static class LocalDateTimeToInstantConverter implements Converter<LocalDateTime, Instant> {

        /**
         * Converts a {@link LocalDateTime} to an {@link Instant} by treating it as UTC
         * ({@link ZoneOffset#UTC}).
         *
         * @param source the {@link LocalDateTime} value from the database column,
         *               assumed to be in UTC
         * @return the equivalent {@link Instant} on the UTC timeline
         */
        @Override
        public Instant convert(LocalDateTime source) {
            return source.toInstant(ZoneOffset.UTC);
        }
    }
}
