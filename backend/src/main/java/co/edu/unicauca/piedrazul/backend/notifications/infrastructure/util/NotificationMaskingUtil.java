package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.util;

public final class NotificationMaskingUtil {

    private NotificationMaskingUtil() {
    }

    public static String maskRecipient(
            String phoneE164,
            String email,
            String displayName
    ) {
        if (phoneE164 != null && !phoneE164.isBlank()) {
            return maskPhone(phoneE164);
        }

        if (email != null && !email.isBlank()) {
            return maskEmail(email);
        }

        return displayName != null && !displayName.isBlank()
                ? displayName
                : "unknown";
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }

        return "*".repeat(phone.length() - 4)
                + phone.substring(phone.length() - 4);
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }

        int atIndex = email.indexOf("@");

        if (atIndex <= 1) {
            return "***";
        }

        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}