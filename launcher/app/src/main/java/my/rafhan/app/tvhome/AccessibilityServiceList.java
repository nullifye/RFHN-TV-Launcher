package my.rafhan.app.tvhome;

import java.util.ArrayList;
import java.util.List;

/** Colon-separated secure setting; short and fully qualified component names mean the same thing. */
final class AccessibilityServiceList {
    private AccessibilityServiceList() {}

    private static String canonical(String component) {
        String c = component.trim();
        int slash = c.indexOf('/');
        if (slash > 0 && c.startsWith(".", slash + 1)) {
            return c.substring(0, slash + 1) + c.substring(0, slash) + c.substring(slash + 1);
        }
        return c;
    }

    private static List<String> entries(String current) {
        List<String> out = new ArrayList<>();
        if (current == null || current.equals("null")) return out;
        for (String c : current.split(":")) if (!c.trim().isEmpty()) out.add(c.trim());
        return out;
    }

    static boolean contains(String current, String component) {
        for (String c : entries(current)) if (canonical(c).equals(canonical(component))) return true;
        return false;
    }

    static String add(String current, String component) {
        List<String> out = entries(current);
        if (!contains(current, component)) out.add(component);
        return String.join(":", out);
    }

    static String remove(String current, String component) {
        List<String> out = entries(current);
        out.removeIf(c -> canonical(c).equals(canonical(component)));
        return String.join(":", out);
    }
}
