package mindustry.yzf;

import arc.files.Fi;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class YZFText{
    private YZFText(){
    }

    public static boolean blank(String value){
        return value == null || value.trim().isEmpty();
    }

    /**
     * Reads text with UTF-8 first and falls back to common legacy Chinese encodings.
     * This helps modules saved in different Windows encodings avoid mojibake.
     */
    public static String readTextSmart(Fi file){
        return readTextSmart(file, StandardCharsets.UTF_8, Charset.forName("GB18030"), Charset.defaultCharset());
    }

    public static String readTextSmart(Fi file, Charset... charsets){
        if(file == null) return "";
        byte[] bytes = file.readBytes();
        if(bytes.length == 0) return "";
        String fallback = null;
        for(Charset charset : charsets){
            if(charset == null) continue;
            try{
                String text = new String(bytes, charset);
                if(fallback == null) fallback = text;
                if(!looksLikeMojibake(text, charset)){
                    return text;
                }
            }catch(Throwable error){
                YZFErrorLog.low("text-decoder", "Failed to decode text with charset " + charset, error);
            }
        }
        return fallback != null ? fallback : new String(bytes, StandardCharsets.UTF_8);
    }

    public static String cleanDisplayText(String value){
        if(value == null) return "";
        String text = value.replace('\uFEFF', ' ').trim();
        if(text.indexOf('\uFFFD') >= 0){
            text = text.replace("\uFFFD", "");
        }
        text = text.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "");
        while(text.contains("??")){
            text = text.replace("??", "?");
        }
        if(looksLikeMojibake(text)){
            return "";
        }
        return text.trim();
    }

    public static boolean looksLikeMojibake(String value){
        return looksLikeMojibake(value, null);
    }

    private static boolean looksLikeMojibake(String value, Charset charset){
        if(value == null || value.isEmpty()) return false;
        int suspicious = 0;
        int replacement = 0;
        int total = value.length();
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c == '\uFFFD'){
                replacement++;
                suspicious += 3;
                continue;
            }
            if(c >= '\uE000' && c <= '\uF8FF'){
                suspicious += 2;
                continue;
            }
            if(isSuspiciousGlyph(c)){
                suspicious += 2;
            }
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if(containsSuspiciousGlyphs(lower)){
            suspicious += 4;
        }

        if(charset != null && StandardCharsets.UTF_8.equals(charset) && replacement > 0){
            suspicious += 2;
        }

        return suspicious >= Math.max(3, total / 8);
    }

    private static boolean containsSuspiciousGlyphs(String value){
        if(value == null || value.isEmpty()) return false;
        for(int i = 0; i < value.length(); i++){
            if(isSuspiciousGlyph(value.charAt(i))){
                return true;
            }
        }
        return false;
    }

    private static boolean isSuspiciousGlyph(char c){
        return c == '锟'
            || c == '閳'
            || c == '閵'
            || c == '閸'
            || c == '瀵'
            || c == '鈺'
            || c == '鍏'
            || c == '鐜'
            || c == '妯'
            || c == '杩';
    }
}
