import arc.net.Client;
import arc.net.Connection;
import arc.net.FrameworkMessage;
import arc.net.NetListener;
import arc.net.NetSerializer;
import arc.net.Server;
import arc.net.ShapedConnection;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShapedConnectionTests{

    public static void main(String[] args) throws Exception{
        new ShapedConnectionTests().reassemblesFiveKilobytePacketWrittenInFiveHundredByteChunks();
        System.out.println("ShapedConnection TCP reassembly probe passed");
    }

    @Test
    void reassemblesFiveKilobytePacketWrittenInFiveHundredByteChunks() throws Exception{
        NetSerializer serializer = new TestSerializer();
        AtomicReference<ShapedConnection> serverConnection = new AtomicReference<>();
        CountDownLatch serverConnected = new CountDownLatch(1);
        CountDownLatch payloadReceived = new CountDownLatch(1);
        AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
        AtomicInteger payloadCount = new AtomicInteger();

        Server server = new Server(32768, 16384, serializer){
            @Override
            protected Connection newConnection(){
                return new ShapedConnection();
            }
        };
        server.addListener(new NetListener(){
            @Override
            public void connected(Connection connection){
                ShapedConnection shaped = (ShapedConnection)connection;
                shaped.configureTrafficShaping(true, 600_000L, 500, 1);
                serverConnection.set(shaped);
                serverConnected.countDown();
            }
        });

        Client client = new Client(32768, 16384, serializer);
        client.addListener(new NetListener(){
            @Override
            public void received(Connection connection, Object object){
                if(object instanceof byte[] bytes){
                    receivedPayload.set(bytes);
                    payloadCount.incrementAndGet();
                    payloadReceived.countDown();
                }
            }
        });

        int port;
        try(ServerSocket socket = new ServerSocket(0)){
            port = socket.getLocalPort();
        }

        try{
            server.bind(port);
            server.start();
            client.start();
            client.connect(5000, "127.0.0.1", port);
            assertTrue(serverConnected.await(5, TimeUnit.SECONDS), "server connection was not established");

            byte[] payload = new byte[5 * 1024];
            for(int i = 0; i < payload.length; i++) payload[i] = (byte)(i * 31);
            serverConnection.get().sendTCP(payload);

            assertTrue(payloadReceived.await(5, TimeUnit.SECONDS), "shaped payload was not received");
            Thread.sleep(150L);

            assertArrayEquals(payload, receivedPayload.get());
            assertEquals(1, payloadCount.get(), "TCP fragments must decode as one packet");
            assertEquals(1L, serverConnection.get().trafficSplitPackets());
            assertTrue(serverConnection.get().trafficChunksSent() >= 10L);
            assertEquals(0L, serverConnection.get().trafficQueuedBytes());
            assertNull(client.getLastProtocolError());
        }finally{
            client.stop();
            server.stop();
        }
    }

    private static final class TestSerializer implements NetSerializer{
        private static final byte registerTcp = 1;
        private static final byte keepAlive = 2;
        private static final byte payload = 3;

        @Override
        public void write(ByteBuffer buffer, Object object){
            if(object instanceof FrameworkMessage.RegisterTCP message){
                buffer.put(registerTcp).putInt(message.connectionID);
            }else if(object instanceof FrameworkMessage.KeepAlive){
                buffer.put(keepAlive);
            }else if(object instanceof byte[] bytes){
                buffer.put(payload).putInt(bytes.length).put(bytes);
            }else{
                throw new IllegalArgumentException("Unsupported test object: " + object);
            }
        }

        @Override
        public Object read(ByteBuffer buffer){
            byte type = buffer.get();
            if(type == registerTcp){
                FrameworkMessage.RegisterTCP message = new FrameworkMessage.RegisterTCP();
                message.connectionID = buffer.getInt();
                return message;
            }
            if(type == keepAlive) return FrameworkMessage.keepAlive;
            if(type == payload){
                byte[] bytes = new byte[buffer.getInt()];
                buffer.get(bytes);
                return bytes;
            }
            throw new IllegalArgumentException("Unsupported test type: " + type + ", remaining=" + buffer.remaining());
        }
    }
}
