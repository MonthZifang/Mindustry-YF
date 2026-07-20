package mindustry.yzf;

import arc.files.Fi;
import arc.struct.Seq;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YZFAuditLog{
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Fi file;
    private final boolean enabled;
    private static final long maxFileBytes = 5L * 1024L * 1024L;

    public YZFAuditLog(Fi file){
        this(file, true);
    }

    public YZFAuditLog(Fi file, boolean enabled){
        this.file = file;
        this.enabled = enabled;
        YZFLogRetention.prune(file.parent().file().toPath());
    }

    public synchronized void record(String kind, String subject, String detail){
        if(!enabled) return;
        String line = formatter.format(Instant.now()) +
            " [" + safe(kind) + "] " +
            safe(subject) +
            (YZFText.blank(detail) ? "" : " | " + safe(detail)) +
            System.lineSeparator();
        try{
            Path directory = file.parent().file().toPath().resolve(java.time.LocalDate.now().toString()).resolve("audit");
            Files.createDirectories(directory);
            Files.writeString(nextFile(directory), line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }catch(Exception ignored){
        }
    }

    public synchronized Seq<String> tail(int maxLines){
        Seq<String> lines = new Seq<>();
        Path directory = file.parent().file().toPath().resolve(java.time.LocalDate.now().toString()).resolve("audit");
        Path current;
        try{ current = nextFile(directory); }catch(Exception ignored){ return lines; }
        if(!Files.exists(current)) return lines;

        String[] split;
        try{ split = Files.readString(current, StandardCharsets.UTF_8).split("\\R"); }catch(Exception ignored){ return lines; }
        int start = Math.max(0, split.length - Math.max(1, maxLines));
        for(int i = start; i < split.length; i++){
            if(!YZFText.blank(split[i])){
                lines.add(split[i]);
            }
        }
        return lines;
    }

    public String path(){
        return file.parent().child(java.time.LocalDate.now().toString()).child("audit").absolutePath();
    }

    private String safe(String value){
        if(value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private Path nextFile(Path directory) throws Exception{
        for(int index = 0; ; index++){
            Path candidate = directory.resolve("audit-" + index + ".log");
            if(!Files.exists(candidate) || Files.size(candidate) < maxFileBytes) return candidate;
        }
    }
}
