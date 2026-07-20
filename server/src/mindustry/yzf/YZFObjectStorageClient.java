package mindustry.yzf;

import java.io.InputStream;

public interface YZFObjectStorageClient extends YZFServiceClient{
    void putObject(String objectName, InputStream stream, long size, String contentType) throws Exception;

    boolean bucketExists() throws Exception;
}
