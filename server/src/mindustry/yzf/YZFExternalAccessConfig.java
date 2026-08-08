package mindustry.yzf;

import arc.files.Fi;
import arc.util.serialization.Jval;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Authentication policy for traffic that crosses the server's private network boundary. */
public final class YZFExternalAccessConfig implements mindustry.server.YZFBridge.ExternalAccess{
    private final boolean enabled, requirePublic, requirePrivate, attachOutboundToken, requireTlsPublic, allowInsecurePublicSocket;
    private final String token;

    private YZFExternalAccessConfig(boolean enabled, boolean requirePublic, boolean requirePrivate, boolean attachOutboundToken, boolean requireTlsPublic, boolean allowInsecurePublicSocket, String token){
        this.enabled = enabled;
        this.requirePublic = requirePublic;
        this.requirePrivate = requirePrivate;
        this.attachOutboundToken = attachOutboundToken;
        this.requireTlsPublic = requireTlsPublic;
        this.allowInsecurePublicSocket = allowInsecurePublicSocket;
        this.token = token;
    }

    public static YZFExternalAccessConfig load(YZFPaths paths){
        if(paths == null || !paths.externalAccessFile.exists()) return new YZFExternalAccessConfig(true, true, false, true, true, false, "");
        try{
            Jval root = Jval.read(YZFText.readTextSmart(paths.externalAccessFile));
            String token = root.getString("token", "").trim();
            String tokenFile = root.getString("tokenFile", "").trim();
            String passwordFile = root.getString("passwordFile", "").trim();
            String secretFile = root.getString("secretFile", "").trim();
            if(secretFile.isEmpty()) secretFile = root.getString("keyFile", "").trim();
            if(!tokenFile.isEmpty()) token = readText(paths, tokenFile);
            if(!passwordFile.isEmpty()) token = readText(paths, passwordFile);
            if(!secretFile.isEmpty()) token = "sha512:" + sha512(resolve(paths, secretFile).readBytes());
            if(!token.isEmpty() && token.length() < 128) throw new IllegalArgumentException("token must contain at least 128 characters");
            return new YZFExternalAccessConfig(root.getBool("enabled", true), root.getBool("requireTokenForPublic", true), root.getBool("requireTokenForPrivate", false), root.getBool("attachTokenToOutbound", true), root.getBool("requireTlsForPublic", true), root.getBool("allowInsecurePublicSocket", false), token);
        }catch(Exception error){
            YZFErrorLog.high("external-access", "Invalid external access configuration; public access is denied", error);
            return new YZFExternalAccessConfig(true, true, true, true, true, false, "");
        }
    }

    public boolean requiresToken(InetAddress address){
        if(!enabled) return false;
        return isPrivate(address) ? requirePrivate : requirePublic;
    }

    public boolean allows(InetAddress address, String presented){
        if(!requiresToken(address)) return true;
        return !token.isEmpty() && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), (presented == null ? "" : presented).getBytes(StandardCharsets.UTF_8));
    }

    /** Raw command sockets do not provide TLS. Public binding therefore needs an explicit opt-in. */
    public boolean allowsSocketBind(InetAddress address){
        if(!enabled) return true;
        return address != null && !address.isAnyLocalAddress() && (isPrivate(address) || allowInsecurePublicSocket);
    }

    public boolean allowsOutbound(URI uri){
        if(!enabled || uri == null) return true;
        try{
            InetAddress address = InetAddress.getByName(uri.getHost());
            if(!isPrivate(address) && requireTlsPublic && !"https".equalsIgnoreCase(uri.getScheme()) && !"wss".equalsIgnoreCase(uri.getScheme())) return false;
            return !requiresToken(address) || !token.isEmpty();
        }catch(Exception ignored){ return false; }
    }

    public String authorization(){ return token.isEmpty() ? null : "Bearer " + token; }
    public boolean attachOutboundToken(){ return attachOutboundToken && !token.isEmpty(); }

    private static boolean isPrivate(InetAddress address){
        if(address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
    private static Fi resolve(YZFPaths paths, String value){
        Fi file = new Fi(value);
        return file.file().isAbsolute() ? file : paths.root.child(value);
    }
    private static String readText(YZFPaths paths, String value){ return YZFText.readTextSmart(resolve(paths, value)).trim(); }
    private static String sha512(byte[] bytes) throws Exception{ return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes)); }
}
