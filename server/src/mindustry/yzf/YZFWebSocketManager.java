package mindustry.yzf;

import arc.util.Log;
import rhino.Function;
import rhino.Scriptable;

import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 瀹㈡埛绔ˉ鎺ワ紝鏀寔澶氫釜 WebSocket 杩炴帴銆? * 姣忎釜杩炴帴鏈夊敮涓€ ID锛孞S 妯″潡鍙€氳繃 yzf.ws.* 鎿嶄綔銆? */
public final class YZFWebSocketManager{
    private static final ConcurrentHashMap<String, YZFWebSocketConnection> connections = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final int maxMessageChars = 1024 * 1024;
    private static final int maxConnections = 128;
    private static ExecutorService executor;

    /**
     * 鍒涘缓 WebSocket 杩炴帴
     * @param url WebSocket URL (ws:// 鎴?wss://)
     * @param moduleScope 妯″潡浣滅敤鍩燂紙鐢ㄤ簬鍥炶皟锛?     * @param onOpen 杩炴帴寤虹珛鍥炶皟
     * @param onMessage 娑堟伅鎺ユ敹鍥炶皟
     * @param onClose 杩炴帴鍏抽棴鍥炶皟
     * @param onError 閿欒鍥炶皟
     * @return 杩炴帴 ID
     */
    public String connect(String moduleId, String url, Scriptable moduleScope, Function onOpen, Function onMessage, Function onClose, Function onError){
        if(connections.size() >= maxConnections) return null;
        String id = "ws-" + nextId.getAndIncrement();
        try{
            HttpClient client = HttpClient.newBuilder()
                .executor(executor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            WebSocket.Builder builder = client.newWebSocketBuilder();
            URI uri = URI.create(url);
            YZFExternalAccessConfig access = MindustryYZF.externalAccess();
            if(access != null && !access.allowsOutbound(uri)) throw new SecurityException("external WebSocket target is not permitted: " + uri);
            if(access != null && access.attachOutboundToken()) builder.header("Authorization", access.authorization());

            YZFWebSocketListener listener = new YZFWebSocketListener(id, moduleScope, onOpen, onMessage, onClose, onError);
            YZFWebSocketConnection conn = new YZFWebSocketConnection(id, moduleId, url, listener);
            connections.put(id, conn);
            builder.buildAsync(uri, listener).whenComplete((ws, error) -> {
                if(error != null){
                    connections.remove(id, conn);
                    if(onError != null && !listener.suppressCallbacks){
                        postCallback("connect", id, moduleScope, onError, new Object[]{error.getMessage()});
                    }
                    return;
                }
                listener.setWebSocket(ws);
                conn.webSocket = ws;
                if(conn.closed || listener.isClosed()){
                    connections.remove(id, conn);
                    try{ ws.abort(); }catch(Throwable ignored){}
                }
            });

            Log.info("[@] WebSocket 杩炴帴鍒涘缓: @ -> @", MindustryYZF.name, id, url);
            return id;
        }catch(Exception e){
            Log.err("[@] WebSocket 杩炴帴澶辫触: @ -> @", MindustryYZF.name, id, url, e);
            if(onError != null){
                postCallback("connect", id, moduleScope, onError, new Object[]{e.getMessage()});
            }
            return null;
        }
    }

    /**
     * 鍙戦€佹枃鏈秷鎭?     */
    public boolean sendText(String connectionId, String message){
        YZFWebSocketConnection conn = connections.get(connectionId);
        if(conn == null || conn.isClosed() || conn.webSocket == null) return false;
        try{
            conn.webSocket.sendText(message, true);
            return true;
        }catch(Exception e){
            Log.err("[@] WebSocket 鍙戦€佸け璐? @", MindustryYZF.name, connectionId, e);
            return false;
        }
    }

    /**
     * 鍙戦€佷簩杩涘埗娑堟伅锛坆ase64 缂栫爜锛?     */
    public boolean sendBinary(String connectionId, String base64Data){
        YZFWebSocketConnection conn = connections.get(connectionId);
        if(conn == null || conn.isClosed() || conn.webSocket == null) return false;
        try{
            byte[] data = java.util.Base64.getDecoder().decode(base64Data);
            conn.webSocket.sendBinary(ByteBuffer.wrap(data), true);
            return true;
        }catch(Exception e){
            Log.err("[@] WebSocket 鍙戦€佷簩杩涘埗澶辫触: @", MindustryYZF.name, connectionId, e);
            return false;
        }
    }

    /**
     * 鍏抽棴杩炴帴
     */
    public void close(String connectionId){
        YZFWebSocketConnection conn = connections.remove(connectionId);
        if(conn != null && !conn.isClosed()){
            conn.closed = true;
            conn.listener.suppressCallbacks = true;
            conn.listener.closed = true;
            if(conn.webSocket != null) try{
                conn.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
            }catch(Exception ignored){}
        }
    }

    public void closeModule(String moduleId){
        if(moduleId == null) return;
        for(YZFWebSocketConnection connection : connections.values()){
            if(moduleId.equals(connection.moduleId)){
                close(connection.id);
            }
        }
    }

    /**
     * 妫€鏌ヨ繛鎺ユ槸鍚﹀瓨娲?     */
    public boolean isOpen(String connectionId){
        YZFWebSocketConnection conn = connections.get(connectionId);
        return conn != null && conn.webSocket != null && !conn.isClosed();
    }

    /**
     * 鑾峰彇杩炴帴 URL
     */
    public String getUrl(String connectionId){
        YZFWebSocketConnection conn = connections.get(connectionId);
        return conn != null ? conn.url : null;
    }

    /**
     * 鑾峰彇鎵€鏈夋椿璺冭繛鎺?ID
     */
    public String[] listConnections(){
        return connections.keySet().toArray(new String[0]);
    }

    /**
     * 鍏抽棴鎵€鏈夎繛鎺?     */
    public void closeAll(){
        for(YZFWebSocketConnection conn : connections.values()){
            if(!conn.isClosed()){
                if(conn.webSocket != null) try{
                    conn.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "server shutdown");
                }catch(Exception ignored){}
            }
        }
        connections.clear();
        synchronized(YZFWebSocketManager.class){
            if(executor != null){
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private static synchronized ExecutorService executor(){
        if(executor == null || executor.isShutdown()){
            executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "MindustryYZF-WebSocket");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    static class YZFWebSocketConnection{
        final String id;
        final String moduleId;
        final String url;
        volatile WebSocket webSocket;
        final YZFWebSocketListener listener;
        volatile boolean closed;

        YZFWebSocketConnection(String id, String moduleId, String url, YZFWebSocketListener listener){
            this.id = id;
            this.moduleId = moduleId;
            this.url = url;
            this.listener = listener;
        }

        boolean isClosed(){
            return closed || listener.isClosed();
        }
    }

    static class YZFWebSocketListener implements WebSocket.Listener{
        private final String id;
        private final Scriptable moduleScope;
        private final Function onOpen;
        private final Function onMessage;
        private final Function onClose;
        private final Function onError;
        private WebSocket webSocket;
        private volatile boolean closed;
        private volatile boolean suppressCallbacks;
        private final StringBuilder messageBuffer = new StringBuilder();

        YZFWebSocketListener(String id, Scriptable moduleScope, Function onOpen, Function onMessage, Function onClose, Function onError){
            this.id = id;
            this.moduleScope = moduleScope;
            this.onOpen = onOpen;
            this.onMessage = onMessage;
            this.onClose = onClose;
            this.onError = onError;
        }

        void setWebSocket(WebSocket ws){
            this.webSocket = ws;
        }

        boolean isClosed(){
            return closed;
        }

        @Override
        public void onOpen(WebSocket webSocket){
            this.webSocket = webSocket;
            webSocket.request(1);
            Log.info("[@] WebSocket 宸茶繛鎺? @", MindustryYZF.name, id);
            if(onOpen != null && !suppressCallbacks){
                postCallback("onOpen", id, moduleScope, onOpen, new Object[]{id});
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last){
            if(messageBuffer.length() + data.length() > maxMessageChars){
                closed = true;
                connections.remove(id);
                try{ webSocket.abort(); }catch(Throwable ignored){}
                if(onError != null && !suppressCallbacks){
                    postCallback("message-size", id, moduleScope, onError, new Object[]{"WebSocket message exceeds 1 MiB limit"});
                }
                return null;
            }
            messageBuffer.append(data);
            if(last){
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                if(onMessage != null && !suppressCallbacks){
                    postCallback("onMessage", id, moduleScope, onMessage, new Object[]{id, message});
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last){
            if(data.remaining() > maxMessageChars){
                closed = true;
                connections.remove(id);
                try{ webSocket.abort(); }catch(Throwable ignored){}
                if(onError != null && !suppressCallbacks){
                    postCallback("message-size", id, moduleScope, onError, new Object[]{"WebSocket binary message exceeds 1 MiB limit"});
                }
                return null;
            }
            // 灏嗕簩杩涘埗鏁版嵁杞负 base64 浼犵粰 JS
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            if(onMessage != null && !suppressCallbacks){
                postCallback("onBinary", id, moduleScope, onMessage, new Object[]{id, base64, true});
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason){
            closed = true;
            connections.remove(id);
            Log.info("[@] WebSocket 宸插叧闂? @ (code=@, reason=@)", MindustryYZF.name, id, statusCode, reason);
            if(onClose != null && !suppressCallbacks){
                postCallback("onClose", id, moduleScope, onClose, new Object[]{id, statusCode, reason != null ? reason : ""});
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error){
            closed = true;
            connections.remove(id);
            try{ webSocket.abort(); }catch(Throwable closeError){
                YZFErrorLog.low("websocket", "WebSocket abort failed", closeError);
            }
            Log.err("[@] WebSocket 閿欒: @ - @", MindustryYZF.name, id, error.getMessage());
            if(onError != null && !suppressCallbacks){
                postCallback("onError", id, moduleScope, onError, new Object[]{id, error.getMessage()});
            }
        }
    }

    private static void postCallback(String kind, String id, Scriptable scope, Function callback, Object[] args){
        YZFMainThread.post(() -> {
            if(MindustryYZF.isShuttingDown()) return;
            rhino.Context ctx = rhino.Context.enter();
            try{
                callback.call(ctx, scope, scope, args);
            }catch(Throwable t){
                Log.err("[@] WebSocket @ callback failed: @", MindustryYZF.name, kind, id, t);
            }finally{
                rhino.Context.exit();
            }
        });
    }
}

