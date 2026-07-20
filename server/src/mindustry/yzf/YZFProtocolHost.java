package mindustry.yzf;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class YZFProtocolHost implements AutoCloseable{
    private final PrintWriter writer;

    public YZFProtocolHost(Process process){
        this.writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    public synchronized void send(YZFProtocolMessage message){
        writer.println(message.toJsonLine());
        writer.flush();
        if(writer.checkError()){
            throw new IllegalStateException("Process protocol stream is closed.");
        }
    }

    @Override
    public synchronized void close(){
        writer.close();
    }
}
