package mindustry.yzf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.Stream;

/** Deletes only dated log directories older than the configured retention window. */
final class YZFLogRetention{
    static final int days = 14;

    private YZFLogRetention(){
    }

    static void prune(Path root){
        if(root == null || !Files.isDirectory(root)) return;
        LocalDate cutoff = LocalDate.now().minusDays(days);
        try(Stream<Path> children = Files.list(root)){
            children.filter(Files::isDirectory).forEach(child -> {
                try{
                    if(!LocalDate.parse(child.getFileName().toString()).isBefore(cutoff)) return;
                    try(Stream<Path> files = Files.walk(child)){
                        files.sorted(Comparator.reverseOrder()).forEach(path -> {
                            try{ Files.deleteIfExists(path); }catch(IOException ignored){}
                        });
                    }
                }catch(Exception ignored){
                    // Ignore non-date directories and failures; logging must remain available.
                }
            });
        }catch(IOException ignored){
            // A failed cleanup must not prevent the server from starting.
        }
    }
}
