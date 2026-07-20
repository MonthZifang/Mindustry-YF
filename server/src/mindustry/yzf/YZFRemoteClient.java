package mindustry.yzf;

public interface YZFRemoteClient extends YZFServiceClient{
    String request(YZFRemoteRequest request) throws Exception;

    default String get(String path) throws Exception{
        YZFRemoteRequest request = new YZFRemoteRequest();
        request.path = path;
        request.method = "GET";
        return request(request);
    }

    default String postJson(String path, String body) throws Exception{
        YZFRemoteRequest request = new YZFRemoteRequest();
        request.path = path;
        request.method = "POST";
        request.body = body;
        request.headers.put("Content-Type", "application/json; charset=utf-8");
        return request(request);
    }
}
