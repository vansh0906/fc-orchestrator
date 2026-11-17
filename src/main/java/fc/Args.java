package fc;

import java.util.HashMap;
import java.util.Map;

/** --key value command-line arguments. */
final class Args {
    private Args() { }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("expected --key value, got " + args[i]);
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }
}
