package local.longtan.gmtool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AuditLog {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path path;

    AuditLog(Path path) {
        this.path = path;
    }

    synchronized void write(String action, String detail) {
        String safeDetail = detail.replace('\r', ' ').replace('\n', ' ');
        String line = FORMAT.format(LocalDateTime.now()) + "\t" + action + "\t" + safeDetail + System.lineSeparator();
        try {
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Unable to write audit log: " + e.getMessage());
        }
    }

    synchronized List<String> recent(int limit) throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }
        List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
        int start = Math.max(0, all.size() - Math.max(1, Math.min(limit, 200)));
        List<String> result = new ArrayList<>(all.subList(start, all.size()));
        Collections.reverse(result);
        return result;
    }
}
