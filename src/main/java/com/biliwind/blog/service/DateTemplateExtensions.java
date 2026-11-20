package com.biliwind.blog.service;

import com.biliwind.blog.i18n.I18n;
import io.quarkus.qute.TemplateExtension;
import jakarta.enterprise.inject.spi.CDI;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Qute template extensions under the {@code date} namespace providing localized,
 * human-readable SSR fallback formatting for timestamps and calendar dates.
 */
public final class DateTemplateExtensions {

    private static final DateTimeFormatter ZH_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter ZH_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter EN_DATE_TIME =
            DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter EN_DATE =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.ENGLISH);

    private DateTemplateExtensions() {
    }

    @TemplateExtension(namespace = "date")
    public static String formatDateTime(Object value) {
        return formatDateTime(value, resolveLanguage());
    }

    public static String formatDateTime(Object value, String language) {
        if (isNullOrBlank(value)) {
            return "";
        }
        ZonedDateTime zoned = toZonedDateTime(value);
        if (zoned != null) {
            DateTimeFormatter formatter = isEnglish(language) ? EN_DATE_TIME : ZH_DATE_TIME;
            return zoned.format(formatter);
        }
        LocalDate localDate = toLocalDate(value);
        if (localDate != null) {
            DateTimeFormatter formatter = isEnglish(language) ? EN_DATE : ZH_DATE;
            return localDate.format(formatter);
        }
        return value.toString();
    }

    @TemplateExtension(namespace = "date")
    public static String formatDate(Object value) {
        return formatDate(value, resolveLanguage());
    }

    public static String formatDate(Object value, String language) {
        if (isNullOrBlank(value)) {
            return "";
        }
        LocalDate localDate = toLocalDate(value);
        if (localDate != null) {
            DateTimeFormatter formatter = isEnglish(language) ? EN_DATE : ZH_DATE;
            return localDate.format(formatter);
        }
        ZonedDateTime zoned = toZonedDateTime(value);
        if (zoned != null) {
            DateTimeFormatter formatter = isEnglish(language) ? EN_DATE : ZH_DATE;
            return zoned.toLocalDate().format(formatter);
        }
        return value.toString();
    }

    private static boolean isNullOrBlank(Object value) {
        return value == null || (value instanceof CharSequence cs && cs.toString().isBlank());
    }

    private static ZonedDateTime toZonedDateTime(Object value) {
        ZoneId zone = ZoneId.systemDefault();
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.atZoneSameInstant(zone);
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.withZoneSameInstant(zone);
        }
        if (value instanceof Instant instant) {
            return instant.atZone(zone);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(zone);
        }
        if (value instanceof CharSequence chars) {
            String raw = chars.toString().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(raw).atZoneSameInstant(zone);
            } catch (DateTimeParseException ignored) {
                // Try local date-time or instant forms next.
            }
            try {
                return Instant.parse(raw).atZone(zone);
            } catch (DateTimeParseException ignored) {
                // Ignore.
            }
            try {
                String normalized = raw.contains("T") ? raw : raw.replace(' ', 'T');
                return LocalDateTime.parse(normalized).atZone(zone);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof CharSequence chars) {
            String raw = chars.toString().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return LocalDate.parse(raw);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isEnglish(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }

    private static String resolveLanguage() {
        try {
            I18n i18n = CDI.current().select(I18n.class).get();
            if (i18n != null) {
                return i18n.getLanguage();
            }
        } catch (RuntimeException ignored) {
            // Outside an active CDI request context (e.g. unit tests).
        }
        return "zh-cn";
    }
}
