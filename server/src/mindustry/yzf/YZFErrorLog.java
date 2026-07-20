package mindustry.yzf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;

/** Structured YZF error sink with four severity files and terminal colors. */
public final class YZFErrorLog{
    public enum Level{
        LOW("low", "\u001B[33m"),
        MEDIUM("medium", "\u001B[38;5;214m"),
        HIGH("high", "\u001B[38;5;202m"),
        EMERGENCY("emergency", "\u001B[31;1m");

        final String file;
        final String color;

        Level(String file, String color){
            this.file = file;
            this.color = color;
        }
    }

    private static volatile Path logDirectory;
    private static volatile boolean enabled = true;
    private static volatile boolean terminalColors = true;
    private static final long maxFileBytes = 5L * 1024L * 1024L;

    private YZFErrorLog(){
    }

    public static void configure(YZFPaths paths, boolean enabled, boolean terminalColors){
        YZFErrorLog.logDirectory = paths == null ? null : paths.logsDir.file().toPath();
        YZFErrorLog.enabled = enabled;
        YZFErrorLog.terminalColors = terminalColors;
        YZFLogRetention.prune(YZFErrorLog.logDirectory);
    }

    public static void record(Level level, String source, String message, Throwable error){
        if(level == null) level = Level.HIGH;
        String safeSource = source == null || source.isBlank() ? "yzf" : source;
        String safeMessage = message == null ? "" : message;
        StringBuilder line = new StringBuilder()
            .append('[').append(Instant.now()).append("] [")
            .append(level.name()).append("] [").append(safeSource).append("] ")
            .append(safeMessage);
        if(error != null){
            line.append("\n").append(stack(error));
        }
        line.append(System.lineSeparator());

        if(enabled){
            Path directory = logDirectory;
            if(directory != null){
                try{
                    Path category = directory.resolve(LocalDate.now().toString()).resolve("errors").resolve(level.file).resolve(safePath(safeSource));
                    Files.createDirectories(category);
                    Files.writeString(nextFile(category, "error"), line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }catch(IOException ignored){
                    // Logging must never crash the server; keep the terminal fallback below.
                }
            }
        }

        String output = line.toString().stripTrailing();
        if(terminalColors){
            System.err.println(level.color + output + "\u001B[0m");
        }else{
            System.err.println(output);
        }
    }

    public static void low(String source, String message, Throwable error){ record(Level.LOW, source, message, error); }
    public static void medium(String source, String message, Throwable error){ record(Level.MEDIUM, source, message, error); }
    public static void high(String source, String message, Throwable error){ record(Level.HIGH, source, message, error); }
    public static void emergency(String source, String message, Throwable error){ record(Level.EMERGENCY, source, message, error); }

    private static String stack(Throwable error){
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private static Path nextFile(Path directory, String prefix) throws IOException{
        for(int index = 0; ; index++){
            Path file = directory.resolve(prefix + "-" + index + ".log");
            if(!Files.exists(file) || Files.size(file) < maxFileBytes) return file;
        }
    }

    private static String safePath(String source){
        String value = source.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return value.isEmpty() ? "yzf" : value.substring(0, Math.min(80, value.length()));
    }
}
