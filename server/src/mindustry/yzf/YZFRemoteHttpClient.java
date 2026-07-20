package mindustry.yzf;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class YZFRemoteHttpClient implements YZFRemoteClient{
    private static final int maxPayloadBytes = 4 * 1024 * 1024;
    private final YZFServiceConfig config;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public YZFRemoteHttpClient(YZFServiceConfig config){
        this.config = config;
    }

    @Override
    public YZFServiceConfig config(){
        return config;
    }

    @Override
    public String summary(){
        return "HTTP " + config.clusterMode + " -> " + pickEndpoint();
    }

    @Override
    public void start(){
        // stateless
    }

    @Override
    public void stop(){
    }

    @Override
    public boolean healthy(){
        return config.endpoint != null && !config.endpoint.trim().isEmpty() || !config.nodes.isEmpty();
    }

    @Override
    public String request(YZFRemoteRequest request) throws Exception{
        String method = YZFText.blank(request.method) ? "GET" : request.method.toUpperCase();
        String path = YZFText.blank(request.path) ? "/" : request.path;
        HttpURLConnection connection = open(join(path), method);
        for(var entry : request.headers){
            connection.setRequestProperty(entry.key, entry.value);
        }

        if(!YZFText.blank(request.body) && !"GET".equalsIgnoreCase(method)){
            connection.setDoOutput(true);
            byte[] payload = request.body.getBytes(StandardCharsets.UTF_8);
            if(payload.length > maxPayloadBytes) throw new IllegalArgumentException("HTTP request body exceeds 4 MiB limit");
            connection.setFixedLengthStreamingMode(payload.length);
            try(OutputStream output = connection.getOutputStream()){
                output.write(payload);
                output.flush();
            }
        }
        return readBody(connection);
    }

    @Override
    public String healthDetails(){
        return healthy() ? pickEndpoint() : "未配置端点";
    }

    private HttpURLConnection open(String target, String method) throws Exception{
        URL url = new URL(target);
        YZFExternalAccessConfig access = MindustryYZF.externalAccess();
        if(access != null && !access.allowsOutbound(url.toURI())) throw new SecurityException("external HTTP target is not permitted: " + url);
        HttpURLConnection connection = (HttpURLConnection)(proxy() == null ? url.openConnection() : url.openConnection(proxy()));
        connection.setRequestMethod(method);
        connection.setConnectTimeout(config.connectTimeoutMs);
        connection.setReadTimeout(config.readTimeoutMs);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        if(access != null && access.attachOutboundToken()) connection.setRequestProperty("Authorization", access.authorization());
        return connection;
    }

    private Proxy proxy(){
        String host = config.option("proxyHost", null);
        String port = config.option("proxyPort", null);
        if(host != null && port != null){
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, Integer.parseInt(port)));
        }
        return null;
    }

    private String join(String path){
        String endpoint = pickEndpoint();
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String next = path.startsWith("/") ? path : "/" + path;
        return base + next;
    }

    private String pickEndpoint(){
        if(!config.nodes.isEmpty()){
            int index = roundRobin.getAndIncrement();
            index = index & Integer.MAX_VALUE;
            return config.nodes.get(index % config.nodes.size);
        }
        return config.endpoint;
    }

    private String readBody(HttpURLConnection connection) throws Exception{
        InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if(stream == null) return "";
        try(InputStream input = stream){
            byte[] bytes = input.readNBytes(maxPayloadBytes + 1);
            if(bytes.length > maxPayloadBytes) throw new IllegalStateException("HTTP response exceeds 4 MiB limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
