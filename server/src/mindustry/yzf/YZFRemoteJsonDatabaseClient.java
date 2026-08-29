package mindustry.yzf;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class YZFRemoteJsonDatabaseClient implements YZFDatabaseClient{
    private static final int maxPayloadBytes = 4 * 1024 * 1024;
    private final YZFDatabaseDefinition definition;

    public YZFRemoteJsonDatabaseClient(YZFDatabaseDefinition definition){
        this.definition = definition;
    }

    @Override
    public YZFDatabaseDefinition definition(){
        return definition;
    }

    @Override
    public String summary(){
        return "Remote JSON -> " + endpoint();
    }

    @Override
    public void start(){
    }

    @Override
    public void stop(){
    }

    @Override
    public boolean healthy(){
        if(YZFText.blank(endpoint())) return false;
        try{ request("GET", "/categories", null); return true; }
        catch(Exception ignored){ return false; }
    }

    @Override
    public String healthDetails(){
        return healthy() ? endpoint() : "unconfigured";
    }

    @Override
    public String listCategories() throws Exception{
        return request("GET", "/categories", null);
    }

    @Override
    public String listKeys(String category) throws Exception{
        return request("GET", "/categories/" + encode(category) + "/keys", null);
    }

    @Override
    public String get(String category, String key) throws Exception{
        return request("GET", "/categories/" + encode(category) + "/keys/" + encode(key), null);
    }

    @Override
    public void set(String category, String key, String valueJson) throws Exception{
        request("POST", "/categories/" + encode(category) + "/keys/" + encode(key), valueJson);
    }

    @Override
    public boolean remove(String category, String key) throws Exception{
        request("DELETE", "/categories/" + encode(category) + "/keys/" + encode(key), null);
        return true;
    }

    @Override
    public String dumpJson() throws Exception{
        return request("GET", "/dump", null);
    }

    @Override
    public void importJson(String json) throws Exception{
        request("POST", "/import", json);
    }

    private String request(String method, String path, String body) throws Exception{
        String target = endpoint();
        if(target.endsWith("/")) target = target.substring(0, target.length() - 1);
        String next = path.startsWith("/") ? path : "/" + path;
        URL url = new URL(target + next);
        YZFExternalAccessConfig access = MindustryYZF.externalAccess();
        if(access != null && !access.allowsOutbound(url.toURI())) throw new SecurityException("external JSON database target is not permitted: " + url);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setDoInput(true);
        if(access != null && access.attachOutboundToken()) connection.setRequestProperty("Authorization", access.authorization());
        if(body != null){
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if(bytes.length > maxPayloadBytes) throw new IllegalArgumentException("Remote JSON request body exceeds 4 MiB limit");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try(OutputStream out = connection.getOutputStream()){
                out.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        if(status < 200 || status >= 300) throw new java.io.IOException("Remote JSON database HTTP " + status);
        InputStream stream = connection.getInputStream();
        if(stream == null) return "";
        try(InputStream input = stream){
            byte[] bytes = input.readNBytes(maxPayloadBytes + 1);
            if(bytes.length > maxPayloadBytes) throw new IllegalStateException("Remote JSON response exceeds 4 MiB limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String endpoint(){
        if(!YZFText.blank(definition.endpoint)) return definition.endpoint;
        if(!YZFText.blank(definition.serviceId) && definition.serviceId.startsWith("http")) return definition.serviceId;
        return "";
    }

    private String encode(String value){
        if(value == null) return "";
        try{return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");}
        catch(Exception e){throw new IllegalArgumentException("invalid database path segment", e);}
    }
}
